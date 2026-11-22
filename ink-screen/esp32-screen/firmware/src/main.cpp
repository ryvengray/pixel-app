#include <Arduino.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <time.h>
#include "trusted_roots.h"
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

WebServer settings(80);
DNSServer dns;
String setupPassword;
String setupSsid;
bool setupActive = false;
uint32_t offlineSince = 0;
uint32_t setupStartedAt = 0;
uint32_t restartAt = 0;

String escapeHtml(String value) {
  value.replace("&", "&amp;"); value.replace("<", "&lt;");
  value.replace(">", "&gt;"); value.replace("\"", "&quot;");
  value.replace("'", "&#39;");
  return value;
}

bool validServer(const String &url) {
  const int prefix = url.startsWith("https://") ? 8 : url.startsWith("http://") ? 7 : 0;
  if (!prefix || url.length() <= prefix || url.length() >= 200) return false;
  for (size_t i = prefix; i < url.length(); ++i) {
    if (url[i] <= ' ' || url[i] == '@' || url[i] == '?' || url[i] == '#') return false;
  }
  return url[prefix] != '/';
}

void startSetup() {
  if (setupActive) return;
  // Stop failed station scans while provisioning: scans can change the AP channel.
  const bool online = WiFi.isConnected();
  WiFi.setAutoReconnect(false);
  if (!online) WiFi.disconnect(false);
  WiFi.mode(online ? WIFI_AP_STA : WIFI_AP);
  const IPAddress apIp(192, 168, 4, 1), mask(255, 255, 255, 0);
  if (!WiFi.softAPConfig(apIp, apIp, mask) ||
      !WiFi.softAP(setupSsid.c_str(), setupPassword.c_str(), 1)) {
    Serial.println("Setup hotspot failed");
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    if (wifiConfigured) WiFi.reconnect();
    return;
  }
  setupActive = true;
  setupStartedAt = millis();
  dns.start(53, "*", apIp);
  // Bind HTTP after the AP interface exists (also refresh after mode changes).
  settings.begin();
  Serial.printf("Wi-Fi setup hotspot: %s | IP: %s | HTTP ready\n", setupSsid.c_str(), WiFi.softAPIP().toString().c_str());
}

bool authenticateSettings() {
  settings.sendHeader("Cache-Control", "no-store");
  // The setup Wi-Fi already requires its password; captive browsers may hide
  // HTTP Basic login dialogs. Keep authentication on the home LAN interface.
  if (setupActive && settings.client().localIP() == WiFi.softAPIP()) return true;
  if (settings.authenticate("admin", setupPassword.c_str())) return true;
  settings.requestAuthentication(BASIC_AUTH, "Pixel Settings", "Open http://192.168.4.1 while connected to Pixel-Setup, or log in as admin.");
  return false;
}

void startSettings() {
  setupPassword = "12345678";
  setupSsid = "Pixel-Setup-" + WiFi.macAddress().substring(12);
  setupSsid.replace(":", "");
  settings.on("/", HTTP_GET, []() {
    if (!authenticateSettings()) return;
    String html = R"HTML(<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pixel 网络设置</title>
<style>body{font:16px system-ui;background:#f1f4f8;color:#182330;margin:0;padding:24px}main{max-width:440px;margin:auto;background:white;padding:24px;border-radius:20px}h1{font-size:24px}label{display:block;margin-top:20px}input,button{box-sizing:border-box;width:100%;font:inherit;padding:12px;border:1px solid #bbc5d0;border-radius:8px;margin-top:8px}button{background:#2457d6;color:white;border:0;margin-top:24px}p,small{line-height:1.6;color:#526172}</style><main><h1>连接 Wi-Fi</h1><p>使用 2.4 GHz 网络。保存后设备会重启并自动连接；连接失败约 30 秒后会重新开放设置热点。</p><form method="post" action="/save"><input type="hidden" name="csrf" value=")HTML";
    html += escapeHtml(setupPassword) + "\"><label>Wi-Fi 名称<input name=\"ssid\" required maxlength=\"32\" autocomplete=\"off\" value=\"" + escapeHtml(prefs.getString("ssid", ""));
    html += R"HTML("></label><label>Wi-Fi 密码<input name="password" type="password" maxlength="63" autocomplete="new-password" placeholder="留空保留原密码"></label><label><input style="width:auto" type="checkbox" name="open" value="1"> 这是无密码网络</label><label>服务器地址<input name="server" type="url" required value=")HTML";
    html += escapeHtml(serverUrl.isEmpty() ? "https://ryven.xin/gray" : serverUrl);
    html += R"HTML("></label><label>INK_TOKEN<input name="token" type="password" maxlength="128" autocomplete="new-password" placeholder="留空保留已保存的令牌"></label><small>复用水墨屏令牌；可以先只设置 Wi-Fi，稍后再填写。</small><button>保存并连接</button></form><p>)HTML";
    html += WiFi.isConnected() ? "已连接 · " + WiFi.localIP().toString() : "尚未连接 Wi-Fi";
    html += "</p></main></html>";
    settings.send(200, "text/html; charset=utf-8", html);
  });
  settings.on("/save", HTTP_POST, []() {
    if (!authenticateSettings()) return;
    if (settings.arg("csrf") != setupPassword) { settings.send(403, "text/plain", "Refresh the settings page"); return; }
    String ssid = settings.arg("ssid"), password = settings.arg("password");
    String url = settings.arg("server"), key = settings.arg("token");
    while (url.endsWith("/")) url.remove(url.length() - 1);
    if (password.isEmpty() && !settings.hasArg("open")) password = prefs.getString("password", "");
    if (settings.hasArg("open")) password = "";
    if (key.isEmpty()) key = token;
    if (ssid.isEmpty() || ssid.length() > 32 || password.length() > 63 ||
        (!password.isEmpty() && password.length() < 8) || !validServer(url) || key.length() > 128) {
      settings.send(400, "text/plain; charset=utf-8", "配置无效：检查 Wi-Fi 名称、密码（至少 8 位）和服务器地址。"); return;
    }
    const bool passwordSaved = password.isEmpty() ? (!prefs.isKey("password") || prefs.remove("password")) : prefs.putString("password", password) > 0;
    const bool keySaved = key.isEmpty() ? true : prefs.putString("aht20_token", key) > 0;
    if (!passwordSaved || !keySaved || !prefs.putString("ssid", ssid) || !prefs.putString("server", url) ||
        (prefs.isKey("static_ip") && !prefs.remove("static_ip"))) {
      settings.send(500, "text/plain; charset=utf-8", "保存失败，请重试。"); return;
    }
    settings.send(200, "text/html; charset=utf-8", "<meta name=viewport content='width=device-width,initial-scale=1'><h2>已保存，正在重启连接</h2><p>请查看设备屏幕。连接失败时，重新连接 Pixel-Setup 热点修改设置。</p>");
    restartAt = millis() + 1500;
  });
  settings.on("/health", HTTP_GET, []() {
    settings.sendHeader("Cache-Control", "no-store");
    settings.send(200, "application/json", "{\"status\":\"ok\"}");
  });
  settings.onNotFound([]() { settings.sendHeader("Location", "http://192.168.4.1/"); settings.send(302, "text/plain", "Open http://192.168.4.1/"); });
  settings.begin();
  if (!wifiConfigured || token.isEmpty() || serverUrl.isEmpty()) startSetup();
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

  const bool secure = serverUrl.startsWith("https://");
  if (secure && time(nullptr) < 1700000000) {
    uploadState = "Waiting for clock";
    nextUpload = millis() + kRetryInterval;
    return;
  }
  WiFiClient plainClient;
  WiFiClientSecure tlsClient;
  tlsClient.setCACert(kTrustedRoots);
  tlsClient.setHandshakeTimeout(8);
  WiFiClient &client = secure ? static_cast<WiFiClient &>(tlsClient) : plainClient;
  HTTPClient http;
  http.setConnectTimeout(5000);
  http.setTimeout(5000);
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
  screen.fillRect(8, 178, 224, 142, TFT_BLACK);
  screen.drawString(setupActive ? setupSsid : "Settings: http://", 8, 180);
  screen.drawString(setupActive ? "192.168.4.1" : WiFi.localIP().toString(), 8, 202);
  screen.drawString("User: admin", 8, 224);
  screen.drawString("Pass: " + setupPassword, 8, 246);
  screen.drawString("Hold BOOT 3s for setup", 8, 280);
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
    Serial.printf("Setup: %s | AP IP: %s | Clients: %u\n", setupActive ? "active" : "off",
                  WiFi.softAPIP().toString().c_str(), unsigned(WiFi.softAPgetStationNum()));
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
  if (config["op"] == "webtest") {
    if (!setupActive) { Serial.println("Web test needs setup hotspot"); return; }
    xTaskCreate([](void *) {
      WiFiClient client;
      HTTPClient http;
      http.setTimeout(3000);
      http.setConnectTimeout(3000);
      http.begin(client, "http://192.168.4.1/");
      const int code = http.GET();
      const String page = http.getString();
      Serial.printf("Web test GET: %d | form: %s\n", code,
                    page.indexOf("action=\"/save\"") >= 0 ? "OK" : "missing");
      http.end();
      http.begin(client, "http://192.168.4.1/save");
      http.addHeader("Content-Type", "application/x-www-form-urlencoded");
      const int denied = http.POST("csrf=wrong");
      http.end();
      http.begin(client, "http://192.168.4.1/save");
      http.addHeader("Content-Type", "application/x-www-form-urlencoded");
      const int invalid = http.POST("csrf=12345678&ssid=&server=invalid");
      http.end();
      Serial.printf("Web test POST: bad CSRF=%d (expected 403), invalid config=%d (expected 400)\n", denied, invalid);
      vTaskDelete(nullptr);
    }, "webtest", 8192, nullptr, 1, nullptr);
    return;
  }
  if (config["op"] == "setup") { startSetup(); drawStatus(); return; }
  if (config["op"] != "config") {
    Serial.println("Expected config or status");
    return;
  }
  String url = config["server"] | "";
  const String key = config["token"] | "";
  const String ssid = config["ssid"] | "";
  const String password = config["password"] | "";
  while (url.endsWith("/")) url.remove(url.length() - 1);
  if (!validServer(url) ||
      ssid.isEmpty() || ssid.length() > 32 || password.length() > 63 ||
      key.isEmpty() || key.length() > 128) {
    Serial.println("Invalid Wi-Fi, server URL, or token");
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
  if (serverUrl.isEmpty() || token.isEmpty()) uploadState = "Setup required";
  startWiFi();
  startSettings();
  pinMode(0, INPUT_PULLUP);
  delay(40);
  nextSample = millis();
}

void loop() {
  readSerial();
  settings.handleClient();
  if (setupActive) dns.processNextRequest();
  const uint32_t now = millis();
  if (restartAt && due(now, restartAt)) ESP.restart();
  static uint32_t buttonSince = 0;
  if (digitalRead(0) == LOW) {
    if (!buttonSince) buttonSince = now;
    if (now - buttonSince >= 3000) { startSetup(); drawStatus(); }
  } else buttonSince = 0;
  if (due(now, nextSample)) {
    nextSample = now + (sample() ? kSampleInterval : kRetryInterval);
    drawStatus();
  }

  static bool wasOnline = false;
  static uint32_t lastReconnect = 0;
  const bool online = WiFi.isConnected();
  if (online != wasOnline) {
    wasOnline = online;
    if (online) configTime(0, 0, "ntp.aliyun.com", "pool.ntp.org", "time.cloudflare.com");
    drawStatus();
  }
  if (online) offlineSince = now;
  if (!online && now - offlineSince >= kRetryInterval && !setupActive) { startSetup(); drawStatus(); }
  if (online && !token.isEmpty() && !serverUrl.isEmpty() && setupActive && WiFi.softAPgetStationNum() == 0 && now - setupStartedAt > 120000) {
    dns.stop(); WiFi.softAPdisconnect(true); WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(true); setupActive = false; drawStatus();
  }
  if (!online && !setupActive && wifiConfigured && now - lastReconnect >= kRetryInterval) {
    lastReconnect = now;
    WiFi.reconnect();
  }
  if (online && !(setupActive && WiFi.softAPgetStationNum()) && queueCount && !serverUrl.isEmpty() && !token.isEmpty() && due(millis(), nextUpload)) {
    upload();
    drawStatus();
  }
  delay(20);
}
