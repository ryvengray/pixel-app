#include <Arduino.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <Wire.h>
#include <esp_system.h>

namespace {
constexpr int kSda = 32;
constexpr int kScl = 25;
constexpr uint8_t kSensorAddress = 0x38;
constexpr uint8_t kCalibrated = 0x08;
constexpr uint8_t kBusy = 0x80;
constexpr uint32_t kSampleInterval = 3600000;
constexpr uint32_t kRetryInterval = 30000;
constexpr size_t kQueueSize = 24;

struct Reading {
  char id[33];
  float temperature;
  float humidity;
};

Preferences prefs;
TFT_eSPI screen;
String serverUrl;
String token;
bool wifiConfigured = false;
bool wireReady = false;
Reading latest{};
Reading pending[kQueueSize];
size_t queueStart = 0;
size_t queueCount = 0;
uint32_t nextSample = 0;
uint32_t nextUpload = 0;
const char *sensorState = "Starting";
String uploadState = "Waiting for reading";

bool due(uint32_t now, uint32_t deadline) {
  return static_cast<int32_t>(now - deadline) >= 0;
}

bool sendCommand(const uint8_t *command, size_t size) {
  Wire.beginTransmission(kSensorAddress);
  Wire.write(command, size);
  return Wire.endTransmission() == 0;
}

bool readSensor(Reading &reading) {
  if (!wireReady) {
    wireReady = Wire.begin(kSda, kScl, 100000);
    Wire.setTimeOut(50);
    if (!wireReady) return false;
  }

  const uint8_t status = 0x71;
  if (!sendCommand(&status, 1) || Wire.requestFrom(kSensorAddress, uint8_t{1}) != 1) {
    return false;
  }
  if (!(Wire.read() & kCalibrated)) {
    const uint8_t initialize[] = {0xBE, 0x08, 0x00};
    if (!sendCommand(initialize, sizeof(initialize))) return false;
    delay(10);
  }

  const uint8_t measure[] = {0xAC, 0x33, 0x00};
  if (!sendCommand(measure, sizeof(measure))) return false;
  delay(80);
  uint8_t data[7];
  const uint32_t started = millis();
  do {
    if (Wire.requestFrom(kSensorAddress, sizeof(data), true) != sizeof(data)) return false;
    for (uint8_t &byte : data) byte = Wire.read();
    if (!(data[0] & kBusy)) break;
    delay(10);
  } while (millis() - started < 150);
  if ((data[0] & kBusy) || !(data[0] & kCalibrated)) return false;

  uint8_t crc = 0xFF;
  for (size_t i = 0; i < sizeof(data) - 1; ++i) {
    crc ^= data[i];
    for (int bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x80) ? (crc << 1) ^ 0x31 : crc << 1;
    }
  }
  if (crc != data[6]) return false;

  const uint32_t humidity = (uint32_t(data[1]) << 12) | (uint32_t(data[2]) << 4) | (data[3] >> 4);
  const uint32_t temperature = (uint32_t(data[3] & 0x0F) << 16) | (uint32_t(data[4]) << 8) | data[5];
  reading.temperature = temperature * (200.0f / 1048576.0f) - 50.0f;
  reading.humidity = humidity * (100.0f / 1048576.0f);
  return reading.temperature >= -40.0f && reading.temperature <= 85.0f;
}

bool sample() {
  Reading reading{};
  if (!readSensor(reading)) {
    sensorState = "Read failed";
    Serial.println("AHT20 read failed (I2C, calibration, or CRC)");
    return false;
  }
  uint32_t id[4];
  esp_fill_random(id, sizeof(id));
  snprintf(reading.id, sizeof(reading.id), "%08lx%08lx%08lx%08lx",
           static_cast<unsigned long>(id[0]), static_cast<unsigned long>(id[1]),
           static_cast<unsigned long>(id[2]), static_cast<unsigned long>(id[3]));
  latest = reading;
  sensorState = "OK";
  Serial.printf("AHT20: %.2f C, %.2f %%RH\n", reading.temperature, reading.humidity);

  if (queueCount == kQueueSize) {
    queueStart = (queueStart + 1) % kQueueSize;
    --queueCount;
    Serial.println("Upload queue full; oldest reading dropped");
  }
  if (queueCount == 0) nextUpload = millis();
  pending[(queueStart + queueCount) % kQueueSize] = reading;
  ++queueCount;
  return true;
}

void upload() {
  const Reading &reading = pending[queueStart];
  StaticJsonDocument<256> body;
  body["sample_id"] = reading.id;
  body["temperature_c"] = reading.temperature;
  body["humidity_pct"] = reading.humidity;
  String payload;
  serializeJson(body, payload);

  WiFiClient client;
  HTTPClient http;
  http.setConnectTimeout(2500);
  http.setTimeout(2500);
  int status = -1;
  if (http.begin(client, serverUrl + "/v1/sensors/aht20/readings")) {
    http.addHeader("Content-Type", "application/json");
    http.addHeader("Authorization", "Bearer " + token);
    status = http.POST(payload);
    http.end();
  }

  if (status == 200 || status == 201) {
    queueStart = (queueStart + 1) % kQueueSize;
    --queueCount;
    uploadState = "OK";
    nextUpload = millis();
  } else {
    uploadState = status > 0 ? "HTTP " + String(status) : "Connection error";
    nextUpload = millis() + kRetryInterval;
  }
  Serial.printf("Upload: %s (%u pending)\n", uploadState.c_str(), unsigned(queueCount));
}

void drawStatus() {
  screen.setTextFont(2);
  screen.setTextColor(TFT_WHITE, TFT_BLACK);
  screen.fillRect(8, 36, 224, 160, TFT_BLACK);
  if (latest.id[0]) {
    screen.drawString("Temperature: " + String(latest.temperature, 1) + " C", 8, 36);
    screen.drawString("Humidity: " + String(latest.humidity, 1) + " %RH", 8, 58);
  }
  screen.drawString("Sensor: " + String(sensorState), 8, 80);
  screen.drawString(WiFi.isConnected() ? "Wi-Fi: " + WiFi.localIP().toString() : "Wi-Fi: offline", 8, 102);
  screen.drawString("Upload: " + uploadState, 8, 124);
  screen.drawString("Pending: " + String(queueCount), 8, 146);
}

void handleSerial(const String &line) {
  StaticJsonDocument<1536> config;
  if (deserializeJson(config, line)) {
    Serial.println("Invalid JSON");
    return;
  }
  if (config["op"] == "status") {
    Serial.printf("Sensor: %s | Wi-Fi: %s | IP: %s | MAC: %s | Upload: %s | Pending: %u\n",
                  sensorState, WiFi.isConnected() ? "online" : "offline",
                  WiFi.localIP().toString().c_str(), WiFi.macAddress().c_str(),
                  uploadState.c_str(), unsigned(queueCount));
    if (latest.id[0]) {
      Serial.printf("Latest: %.2f C, %.2f %%RH\n", latest.temperature, latest.humidity);
    }
    return;
  }
  if (config["op"] == "network" && config["ip"].is<const char *>() &&
      config["ip"] == "") {
    if (prefs.isKey("static_ip") && !prefs.remove("static_ip")) {
      Serial.println("Could not reset static IP");
      return;
    }
    Serial.println("DHCP enabled. Restarting.");
    Serial.flush();
    ESP.restart();
    return;
  }
  if (config["op"] != "config") {
    Serial.println("Expected config or status");
    return;
  }
  String url = config["server"] | "";
  const String key = config["token"] | "";
  const String ssid = config["ssid"] | "";
  const String password = config["password"] | "";
  while (url.endsWith("/")) url.remove(url.length() - 1);
  if (!url.startsWith("http://") || url.length() <= 7 || url.length() >= 200 ||
      ssid.isEmpty() || ssid.length() > 32 || password.length() > 63 ||
      key.length() < 24 || key.length() > 128) {
    Serial.println("Invalid Wi-Fi, LAN HTTP server URL, or token");
    return;
  }
  const bool passwordSaved = password.isEmpty()
      ? (!prefs.isKey("password") || prefs.remove("password"))
      : prefs.putString("password", password) > 0;
  if (!passwordSaved || !prefs.putString("ssid", ssid) ||
      !prefs.putString("server", url) || !prefs.putString("aht20_token", key)) {
    Serial.println("Configuration write failed");
    return;
  }
  Serial.println("Configuration saved. Restarting.");
  Serial.flush();
  ESP.restart();
}

void readSerial() {
  static String line;
  static bool tooLong = false;
  while (Serial.available()) {
    const char c = Serial.read();
    if (c == '\n') {
      if (!tooLong && line.length()) handleSerial(line);
      line = "";
      tooLong = false;
    } else if (c != '\r' && !tooLong) {
      if (line.length() < 1536) line += c;
      else tooLong = true;
    }
  }
}

void startWiFi() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  if (prefs.isKey("static_ip")) {
    IPAddress ip, gateway, mask;
    if (ip.fromString(prefs.getString("static_ip")) &&
        gateway.fromString(prefs.getString("gateway")) &&
        mask.fromString(prefs.getString("mask", "255.255.255.0"))) {
      WiFi.config(ip, gateway, mask, gateway);
    }
  }

  const String ssid = prefs.getString("ssid", "");
  wifiConfigured = !ssid.isEmpty();
  if (wifiConfigured) WiFi.begin(ssid.c_str(), prefs.getString("password", "").c_str());
}
}  // namespace

void setup() {
  Serial.begin(115200);
  pinMode(33, OUTPUT);
  digitalWrite(33, HIGH);  // Deselect touch controller.
  pinMode(5, OUTPUT);
  digitalWrite(5, HIGH);   // Deselect SD card.
  pinMode(4, OUTPUT);
  digitalWrite(4, HIGH);   // Disable audio amplifier.
  screen.init();
  screen.setRotation(0);
  pinMode(27, OUTPUT);
  digitalWrite(27, HIGH);
  screen.fillScreen(TFT_BLACK);
  screen.setTextColor(TFT_WHITE, TFT_BLACK);
  screen.drawString("AHT20 Room Sensor", 8, 8, 2);
  prefs.begin("phone-control", false);  // Reuse existing Wi-Fi and static IP.
  serverUrl = prefs.getString("server", "");
  token = prefs.getString("aht20_token", "");
  if (serverUrl.isEmpty() || token.isEmpty()) uploadState = "USB setup required";
  startWiFi();
  delay(40);
  nextSample = millis();
}

void loop() {
  readSerial();
  const uint32_t now = millis();
  if (due(now, nextSample)) {
    nextSample = now + (sample() ? kSampleInterval : kRetryInterval);
    drawStatus();
  }

  static bool wasOnline = false;
  static uint32_t lastReconnect = 0;
  const bool online = WiFi.isConnected();
  if (online != wasOnline) {
    wasOnline = online;
    drawStatus();
  }
  if (!online && wifiConfigured && now - lastReconnect >= kRetryInterval) {
    lastReconnect = now;
    WiFi.reconnect();
  }
  if (online && queueCount && !serverUrl.isEmpty() && !token.isEmpty() && due(millis(), nextUpload)) {
    upload();
    drawStatus();
  }
  delay(20);
}
