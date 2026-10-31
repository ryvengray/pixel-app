#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <SPI.h>
#include <GxEPD2_BW.h>
#include <mbedtls/sha256.h>
#include "config.h"

static constexpr int W=800, H=480, BYTES=W*H/8;
SPIClass epdSPI(HSPI);
GxEPD2_BW<GxEPD2_750_GDEY075T7, 160> display(GxEPD2_750_GDEY075T7(10,11,12,13));
WiFiClient net;
PubSubClient mqtt(net);
Preferences prefs;
uint8_t *bitmap = nullptr;
String shown;
bool refreshRequested = true;
unsigned long lastPoll=0, lastWifi=0, lastMqtt=0;
String base() { return String(API_BASE) + "/v1/devices/" + DEVICE_ID; }
void headers(HTTPClient &h) { h.addHeader("Authorization", String("Bearer ")+API_TOKEN); }
void notify(char*, byte*, unsigned int) { refreshRequested=true; }
void ack(const String &version, const char *status) {
  HTTPClient h; h.setTimeout(10000); h.begin(base()+"/ack"); headers(h); h.addHeader("Content-Type","application/json");
  String body = "{\"version\":\""+version+"\",\"status\":\""+status+"\"}";
  int code=h.POST(body); Serial.printf("ack HTTP %d\n",code); h.end();
}
bool download(const String &version) {
  HTTPClient h; h.setTimeout(15000); h.begin(base()+"/bitmap?version="+version); headers(h);
  int code=h.GET(); if(code!=200 || h.getSize()!=BYTES) { Serial.printf("bitmap HTTP %d length %d\n",code,h.getSize()); h.end(); return false; }
  WiFiClient *s=h.getStreamPtr(); size_t n=0; unsigned long deadline=millis()+15000;
  while(n<BYTES && (long)(deadline-millis())>0) {
    int available=s->available(); if(available>0) { int got=s->readBytes(bitmap+n, min((size_t)available,(size_t)BYTES-n)); n+=got; deadline=millis()+15000; } else delay(5);
  }
  h.end(); if(n!=BYTES) return false;
  unsigned char digest[32]; mbedtls_sha256_ret(bitmap,BYTES,digest,0); char hex[65];
  for(int i=0;i<32;i++) sprintf(hex+2*i,"%02x",digest[i]);
  return version == String(hex);
}
void syncDisplay() {
  HTTPClient h; h.setTimeout(10000); h.begin(base()+"/desired"); headers(h); int code=h.GET();
  if(code!=200) { Serial.printf("desired HTTP %d\n",code); h.end(); return; }
  DynamicJsonDocument doc(1024); DeserializationError err=deserializeJson(doc,h.getString()); h.end();
  if(err || !doc["version"].is<const char*>()) return;
  String version=doc["version"].as<String>(); if(version.length()!=64) return;
  if(version==shown) { if(doc["displayed_version"].as<String>()!=shown) ack(shown,"displayed"); return; }
  if(!download(version)) { Serial.println("download or SHA-256 failed"); ack(version,"failed"); return; }
  display.setRotation(0); display.setFullWindow(); display.firstPage();
  do { display.fillScreen(GxEPD_WHITE); display.drawBitmap(0,0,bitmap,W,H,GxEPD_WHITE,GxEPD_BLACK); } while(display.nextPage());
  display.hibernate(); shown=version; prefs.putString("shown",shown); ack(version,"displayed");
  Serial.println("displayed "+shown);
}
void setup() {
  Serial.begin(115200); delay(200);
  bitmap=(uint8_t*)malloc(BYTES); if(!bitmap) { Serial.println("frame allocation failed"); while(true) delay(1000); }
  prefs.begin("pixel",false); shown=prefs.getString("shown","");
  epdSPI.begin(7,-1,9,-1); display.epd2.selectSPI(epdSPI,SPISettings(2000000,MSBFIRST,SPI_MODE0)); display.init(0);
  WiFi.mode(WIFI_STA); WiFi.begin(WIFI_SSID,WIFI_PASSWORD);
  mqtt.setServer(MQTT_HOST,MQTT_PORT); mqtt.setCallback(notify); mqtt.setBufferSize(512);
}
void loop() {
  unsigned long now=millis();
  if(WiFi.status()!=WL_CONNECTED) {
    if(now-lastWifi>15000) { lastWifi=now; WiFi.disconnect(); WiFi.begin(WIFI_SSID,WIFI_PASSWORD); }
    delay(50); return;
  }
  if(!mqtt.connected() && now-lastMqtt>5000) {
    lastMqtt=now; String id=String("pixel-")+DEVICE_ID;
    if(mqtt.connect(id.c_str())) { String topic=String("pixel/v1/devices/")+DEVICE_ID+"/notify"; mqtt.subscribe(topic.c_str(),1); refreshRequested=true; }
  }
  mqtt.loop();
  if(refreshRequested || now-lastPoll>=POLL_INTERVAL_MS) { refreshRequested=false; lastPoll=now; syncDisplay(); }
  delay(20);
}
