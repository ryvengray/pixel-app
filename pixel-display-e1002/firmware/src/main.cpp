#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <SPI.h>
#include <GxEPD2_7C.h>
#include <mbedtls/sha256.h>
#include "config.h"

static constexpr int W=800, H=480, BYTES=W*H/2;
SPIClass epdSPI(HSPI);
GxEPD2_7C<GxEPD2_730c_GDEP073E01, 40> display(GxEPD2_730c_GDEP073E01(10,11,12,13));
const uint16_t colors[] = {GxEPD_WHITE, GxEPD_BLACK, GxEPD_RED, GxEPD_YELLOW, GxEPD_GREEN, GxEPD_BLUE};
WiFiClient net;
PubSubClient mqtt(net);
Preferences prefs;
uint8_t *bitmap = nullptr;
String shown;
volatile bool keyEvent[3] = {false,false,false};
volatile uint32_t keyAt[3] = {0,0,0};
void IRAM_ATTR key0() { uint32_t n=millis(); if(n-keyAt[0]>180) {keyAt[0]=n; keyEvent[0]=true;} }
void IRAM_ATTR key1() { uint32_t n=millis(); if(n-keyAt[1]>180) {keyAt[1]=n; keyEvent[1]=true;} }
void IRAM_ATTR key2() { uint32_t n=millis(); if(n-keyAt[2]>180) {keyAt[2]=n; keyEvent[2]=true;} }
bool browsing=false;
long cursor=0;
String navigation="latest";
bool refreshRequested = true;
unsigned long lastPoll=0, lastWifi=0, lastMqtt=0, lastRefresh=0;
bool hasRefreshed=false;
String base() { return String(API_BASE) + "/v1/devices/" + DEVICE_ID; }
void headers(HTTPClient &h) { h.addHeader("Authorization", String("Bearer ")+API_TOKEN); }
void notify(char*, byte*, unsigned int) { if(!browsing) refreshRequested=true; }
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
  if(hasRefreshed && millis()-lastRefresh < 60000UL) { refreshRequested=true; return; }
  HTTPClient h; h.setTimeout(10000); h.begin(base()+"/history?cursor="+String(cursor)+"&direction="+navigation); headers(h); int code=h.GET();
  if(code!=200) { Serial.printf("desired HTTP %d\n",code); h.end(); return; }
  DynamicJsonDocument doc(1024); DeserializationError err=deserializeJson(doc,h.getString()); h.end();
  if(err || !doc["version"].is<const char*>()) return;
  long selectedCursor=doc["seq"].as<long>();
  String version=doc["version"].as<String>(); if(version.length()!=64) return;
  if(version==shown) { cursor=selectedCursor; navigation="latest"; if(doc["displayed_version"].as<String>()!=shown) ack(shown,"displayed"); return; }
  if(hasRefreshed && millis()-lastRefresh < 60000UL) { refreshRequested=true; return; }
  if(!download(version)) { Serial.println("download or SHA-256 failed"); ack(version,"failed"); return; }
  display.setRotation(0); display.setFullWindow(); display.firstPage();
  int page = 0;
  do {
    display.fillScreen(GxEPD_WHITE);
    for(int y=page*40; y<min((page+1)*40,H); ++y) {
      for(int x=0; x<W; ++x) {
        uint8_t packed=bitmap[(y*W+x)/2];
        uint8_t idx=(x%2==0) ? packed>>4 : packed&15;
        display.drawPixel(x,y,idx<6 ? colors[idx] : GxEPD_WHITE);
      }
      yield();
    }
    ++page;
  } while(display.nextPage());
  display.hibernate(); cursor=selectedCursor; navigation="latest"; hasRefreshed=true; lastRefresh=millis(); shown=version; prefs.putString("shown",shown); ack(version,"displayed");
  Serial.println("displayed "+shown);
}
void setup() {
  Serial.begin(115200); delay(200);
  pinMode(3,INPUT_PULLUP); pinMode(4,INPUT_PULLUP); pinMode(5,INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(3),key0,FALLING);
  attachInterrupt(digitalPinToInterrupt(4),key1,FALLING);
  attachInterrupt(digitalPinToInterrupt(5),key2,FALLING);
  bitmap=(uint8_t*)ps_malloc(BYTES); if(!bitmap) bitmap=(uint8_t*)malloc(BYTES); if(!bitmap) { Serial.println("frame allocation failed"); while(true) delay(1000); }
  prefs.begin("pixel",false); shown=prefs.getString("shown","");
  epdSPI.begin(7,-1,9,-1); display.epd2.selectSPI(epdSPI,SPISettings(2000000,MSBFIRST,SPI_MODE0)); display.init(0);
  WiFi.mode(WIFI_STA); WiFi.begin(WIFI_SSID,WIFI_PASSWORD);
  mqtt.setServer(MQTT_HOST,MQTT_PORT); mqtt.setCallback(notify); mqtt.setBufferSize(512);
}
void loop() {
  unsigned long now=millis();
  if(keyEvent[2]) { keyEvent[0]=keyEvent[1]=keyEvent[2]=false; browsing=false; navigation="latest"; refreshRequested=true; }
  else if(keyEvent[0] || keyEvent[1]) { navigation=keyEvent[0] ? "previous" : "next"; keyEvent[0]=keyEvent[1]=false; browsing=true; refreshRequested=true; }
  if(WiFi.status()!=WL_CONNECTED) {
    if(now-lastWifi>15000) { lastWifi=now; WiFi.disconnect(); WiFi.begin(WIFI_SSID,WIFI_PASSWORD); }
    delay(50); return;
  }
  if(!mqtt.connected() && now-lastMqtt>5000) {
    lastMqtt=now; String id=String("pixel-")+DEVICE_ID;
    if(mqtt.connect(id.c_str())) { String topic=String("pixel/v1/devices/")+DEVICE_ID+"/notify"; mqtt.subscribe(topic.c_str(),1); if(!browsing) refreshRequested=true; }
  }
  mqtt.loop();
  if(refreshRequested || (!browsing && now-lastPoll>=POLL_INTERVAL_MS)) { refreshRequested=false; lastPoll=now; syncDisplay(); if(refreshRequested) delay(100); }
  delay(20);
}
