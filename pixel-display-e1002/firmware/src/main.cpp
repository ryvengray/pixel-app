#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <esp_sleep.h>
#include <driver/rtc_io.h>
#include <sys/time.h>
#include <time.h>
#include "wake_schedule.h"
#include <ArduinoJson.h>
#include <Preferences.h>
#include <SPI.h>
#include <GxEPD2_7C.h>
#include <mbedtls/sha256.h>
#include "config.h"
#include "battery.h"

static constexpr int W=800, H=480, BYTES=W*H/2;
SPIClass epdSPI(HSPI);
GxEPD2_7C<GxEPD2_730c_GDEP073E01, 40> display(GxEPD2_730c_GDEP073E01(10,11,12,13));
const uint16_t colors[] = {GxEPD_WHITE, GxEPD_BLACK, GxEPD_RED, GxEPD_YELLOW, GxEPD_GREEN, GxEPD_BLUE};
int wakeMinutes[24]={390,570,750,1110,1350};
int wakeCount=5;
bool scheduleChecked=false;
bool otaChecked=false;
unsigned long activeAt=0;
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
unsigned long lastWifi=0, lastRefresh=0;
bool hasRefreshed=false;
int readBatteryPercent() {
  digitalWrite(21,HIGH);
  delay(10); // Seeed recommends settling before reading the divided voltage.
  uint32_t sum=0;
  for(int i=0;i<16;++i) { sum+=analogReadMilliVolts(1); delay(2); }
  digitalWrite(21,LOW);
  return batteryPercent((sum*2+8)/16);
}
void drawBattery(int percent) {
  // Opaque backing keeps the icon legible on uploaded pictures too.
  display.fillRoundRect(714,22,62,34,5,GxEPD_WHITE);
  uint16_t color=percent<10 ? GxEPD_RED : percent<20 ? GxEPD_YELLOW : GxEPD_BLACK;
  display.fillRoundRect(720,27,48,24,5,color);
  display.fillRoundRect(722,29,44,20,3,GxEPD_WHITE);
  display.fillRoundRect(769,34,3,10,1,color);
  int width=batteryFillWidth(percent);
  if(width) display.fillRoundRect(724,31,width,16,2,color);
}
void renderDisplay(int percent);

String base() { return String(API_BASE) + "/v1/devices/" + DEVICE_ID; }
void headers(HTTPClient &h) { h.addHeader("Authorization", String("Bearer ")+API_TOKEN); }
#include "ota_client.h"

bool decodeSchedule(const String &body, bool syncClock) {
  DynamicJsonDocument doc(2048);
  if(deserializeJson(doc,body) || doc["timezone"].as<String>()!="Asia/Shanghai") return false;
  JsonArray minutes=doc["minutes"].as<JsonArray>();
  if(minutes.size()<1 || minutes.size()>24) return false;
  int next[24], count=0;
  for(JsonVariant v:minutes) {
    if(!v.is<int>()) return false;
    int n=v.as<int>();
    if(n<0 || n>=1440 || (count && n<=next[count-1])) return false;
    next[count++]=n;
  }
  int64_t epoch=doc["server_time"].as<int64_t>();
  if(syncClock && (epoch<1700000000LL || epoch>4102444800LL)) return false;
  for(int i=0;i<count;++i) wakeMinutes[i]=next[i];
  wakeCount=count;
  if(syncClock) { struct timeval tv={}; tv.tv_sec=epoch; settimeofday(&tv,nullptr); }
  return true;
}
void fetchSchedule() {
  HTTPClient h; h.setTimeout(10000); h.begin(base()+"/schedule"); headers(h);
  int code=h.GET(); String body=code==200 ? h.getString() : ""; h.end();
  if(code==200 && decodeSchedule(body,true)) {
    DynamicJsonDocument cached(2048); deserializeJson(cached,body);
    cached.remove("server_time"); String stable; serializeJson(cached,stable);
    if(prefs.getString("schedule","")!=stable) prefs.putString("schedule",stable);
    Serial.println("wake schedule synchronized");
  } else Serial.println("schedule unavailable; using cached/default times");
}
void sleepUntilNextWake() {
  int64_t now=time(nullptr);
  uint64_t seconds=now>=1700000000LL ? secondsUntilWake(now,wakeMinutes,wakeCount) : 3600;
  // Exclude held keys to avoid an immediate wake loop.
  uint64_t mask=0;
  for(int pin=3;pin<=5;++pin) {
    if(digitalRead(pin)==HIGH) {
      gpio_num_t gpio=static_cast<gpio_num_t>(pin);
      rtc_gpio_init(gpio); rtc_gpio_set_direction(gpio,RTC_GPIO_MODE_INPUT_ONLY);
      rtc_gpio_pullup_en(gpio); rtc_gpio_pulldown_dis(gpio);
      mask|=1ULL<<pin;
    }
  }
  if(mask) esp_sleep_enable_ext1_wakeup(mask,ESP_EXT1_WAKEUP_ANY_LOW);
  if(prefs.getLong("cursor",0)!=cursor) prefs.putLong("cursor",cursor);
  WiFi.disconnect(true); WiFi.mode(WIFI_OFF); display.hibernate();
  digitalWrite(21,LOW);
  esp_sleep_enable_timer_wakeup(seconds*1000000ULL);
  Serial.printf("sleep for %llu seconds\n",(unsigned long long)seconds); Serial.flush();
  esp_deep_sleep_start();
}
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
  if(version==shown && hasRefreshed) { cursor=selectedCursor; navigation="latest"; if(doc["displayed_version"].as<String>()!=shown) ack(shown,"displayed"); return; }
  if(hasRefreshed && millis()-lastRefresh < 60000UL) { refreshRequested=true; return; }
  if(!download(version)) { Serial.println("download or SHA-256 failed"); ack(version,"failed"); return; }
  renderDisplay(readBatteryPercent());
  cursor=selectedCursor; navigation="latest"; shown=version; prefs.putString("shown",shown); ack(version,"displayed");
  Serial.println("displayed "+shown);
}
void renderDisplay(int percent) {
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
    drawBattery(percent);
    ++page;
  } while(display.nextPage());
  display.hibernate();
  hasRefreshed=true; lastRefresh=millis();
}

void setup() {
  Serial.begin(115200); delay(200);
  Serial.println(FIRMWARE_MARKER);
  pinMode(21,OUTPUT); digitalWrite(21,LOW);
  analogReadResolution(12); analogSetPinAttenuation(1,ADC_11db);
  for(int pin=3;pin<=5;++pin) rtc_gpio_deinit(static_cast<gpio_num_t>(pin));
  pinMode(3,INPUT_PULLUP); pinMode(4,INPUT_PULLUP); pinMode(5,INPUT_PULLUP);
  // Physical refresh is GPIO3; logical key2 exits history browsing.
  attachInterrupt(digitalPinToInterrupt(3),key2,FALLING);
  attachInterrupt(digitalPinToInterrupt(4),key1,FALLING);
  attachInterrupt(digitalPinToInterrupt(5),key0,FALLING);
  bitmap=(uint8_t*)ps_malloc(BYTES); if(!bitmap) bitmap=(uint8_t*)malloc(BYTES); if(!bitmap) { Serial.println("frame allocation failed"); while(true) delay(1000); }
  prefs.begin("pixel",false); shown=prefs.getString("shown","");
  decodeSchedule(prefs.getString("schedule",""),false);
  // Resume navigation relative to the page retained on the e-paper panel.
  if(esp_sleep_get_wakeup_cause()==ESP_SLEEP_WAKEUP_EXT1) {
    cursor=prefs.getLong("cursor",0);
    uint64_t keys=esp_sleep_get_ext1_wakeup_status();
    if(keys & (1ULL<<5)) keyEvent[2]=true;
    else if(keys & (1ULL<<3)) keyEvent[0]=true;
    else if(keys & (1ULL<<4)) keyEvent[1]=true;
  }
  epdSPI.begin(7,-1,9,-1); display.epd2.selectSPI(epdSPI,SPISettings(2000000,MSBFIRST,SPI_MODE0)); display.init(0);
  WiFi.mode(WIFI_STA); WiFi.begin(WIFI_SSID,WIFI_PASSWORD);
  activeAt=millis();
}
void loop() {
  unsigned long now=millis();
  // Network failures are bounded; never leave Wi-Fi running indefinitely.
  if(now-activeAt>=120000UL) sleepUntilNextWake();
  if(scheduleChecked && !refreshRequested && now-activeAt>=30000UL) sleepUntilNextWake();
  if(keyEvent[0] || keyEvent[1] || keyEvent[2]) activeAt=now;
  if(keyEvent[2]) { keyEvent[0]=keyEvent[1]=keyEvent[2]=false; browsing=false; navigation="latest"; refreshRequested=true; }
  else if(keyEvent[0] || keyEvent[1]) { navigation=keyEvent[0] ? "previous" : "next"; keyEvent[0]=keyEvent[1]=false; browsing=true; refreshRequested=true; }
  if(WiFi.status()!=WL_CONNECTED) {
    if(now-lastWifi>15000) { lastWifi=now; WiFi.disconnect(); WiFi.begin(WIFI_SSID,WIFI_PASSWORD); }
    delay(50); return;
  }
  if(!scheduleChecked) { fetchSchedule(); scheduleChecked=true; }
  if(refreshRequested) { refreshRequested=false; syncDisplay(); }
  if(!otaChecked && !refreshRequested) { otaChecked=true; checkFirmwareUpdate(); activeAt=millis(); }
  delay(20);
}
