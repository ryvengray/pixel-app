#include <Arduino.h>
#include <WiFi.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <esp_sleep.h>
#include <driver/rtc_io.h>
#include <esp_partition.h>
#include "core.h"
#include "keys.h"
#include "rtc_clock.h"
#include "screen.h"
#include "local_web.h"
#include "power.h"

using namespace standalone;
Preferences prefs;
Album album;
LocalWeb web(album);
RtcClock clockRTC;
Screen screen;
Keys keys;
QueueHandle_t keyEvents;
DNSServer dns;
String savedSSID,savedPassword,uploadToken,lastURL,apName,apPassword,notice,network="off";
int utcOffset=480,year=2026,month=1,lastDay=-1;
uint32_t selectedPhoto=0,shownPhoto=0,lastKey=0,connectAt=0,dirtyAt=0;
bool pictureMode=false,dirty=true,storageOK=false,rtcOK=false,provisioning=false,connecting=false;
bool settings=false,confirmReset=false;
bool observedServiceRunning=false;
int shownBattery=0;
bool screenshotRequested=false;
int settingChoice=0; // cancel, reconfigure, factory reset
ConfigureRequest pendingConfig={};
SystemRequest pendingSystem={};
bool systemRequestWaiting=false;
struct SystemResult { uint32_t id; bool ok; const char *message; };
SystemResult systemResults[4]={};
unsigned nextSystemResult=0;
void updateStatus();
void readKeys(void *) {
  uint8_t lastPins=255;
  while(true) {
    bool refresh=digitalRead(3)==LOW,next=digitalRead(4)==LOW,previous=digitalRead(5)==LOW;
    uint8_t pins=uint8_t(refresh)|(uint8_t(next)<<1)|(uint8_t(previous)<<2);
    if(pins!=lastPins) { Serial.printf("[standalone] keys pressed: refresh=%d next=%d previous=%d\n",refresh,next,previous); lastPins=pins; }
    auto event=keys.update(millis(),refresh,next,previous);
    if(event!=KeyEvent::None) xQueueSend(keyEvents,&event,0);
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

String randomHex(int bytes) {
  String result; result.reserve(bytes*2);
  for(int i=0;i<bytes;i++) { char b[3]; snprintf(b,sizeof(b),"%02x",uint8_t(esp_random())); result+=b; }
  return result;
}
int currentDay() { return clockRTC.valid() ? int((clockRTC.now()+utcOffset*60)/86400) : -1; }
bool serviceRunning() { return web.setupActive() || (web.uploadActive() && WiFi.status()==WL_CONNECTED); }
void requestDraw() { dirty=true; dirtyAt=millis(); }
void persistState() {
  if(prefs.getBool("picture",false)!=pictureMode) prefs.putBool("picture",pictureMode);
  if(prefs.getUInt("photo",0)!=selectedPhoto) prefs.putUInt("photo",selectedPhoto);
  if(prefs.getInt("year",0)!=year) prefs.putInt("year",year);
  if(prefs.getInt("month",0)!=month) prefs.putInt("month",month);
  int day=currentDay(); if(day>=0 && prefs.getInt("day",-1)!=day) prefs.putInt("day",day);
}
// Only an entirely erased partition may be formatted automatically on first use.
// A mount failure with existing data is exposed to the user instead of erasing photos.
bool virginFilesystem() {
  auto partition=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_SPIFFS,"spiffs");
  if(!partition) return false;
  uint8_t block[1024];
  for(size_t offset=0;offset<partition->size;offset+=sizeof(block)) {
    if(esp_partition_read(partition,offset,block,sizeof(block))!=ESP_OK) return false;
    for(auto b:block) if(b!=0xff) return false;
    yield();
  }
  return true;
}
void drawFrame() {
  Frame f; if(!f.pixels) { Serial.println("frame allocation failed"); return; }
  uint32_t photo=0;
  if(settings) {
    f.text(40,60,"设备设置");
    if(confirmReset) {
      f.text(40,124,"确认恢复出厂？"); f.text(40,168,"将清除网络、所有照片、显示状态和上传码。");
      f.text(40,224,"短按绿色刷新键确认清除。"); f.text(40,264,"按前进或后退键取消。");
    } else {
      const char *labels[]={"返回画面","重配网络（保留照片）","恢复出厂（清空照片）"};
      for(int i=0;i<3;i++) { if(i==settingChoice) f.fillRoundRect(32,96+i*70,730,52,6,5); f.text(50,129+i*70,labels[i],i==settingChoice ? 0:1); }
      f.text(40,365,"前后键选择，绿色刷新键确认。"); f.text(40,407,"同时长按前后键也可返回画面。");
    }
  } else if(provisioning && network!="connected") {
    f.text(34,40,"欢迎使用 E1002"); f.text(34,80,"一、扫码连接设备热点");
    drawQr(f,"WIFI:T:WPA;S:"+apName+";P:"+apPassword+";;",34,105,230);
    f.text(300,142,"热点："+apName); f.text(300,181,"密码："+apPassword);
    f.text(300,236,"二、连接热点后打开配网页"); f.text(300,275,"选择家里的 2.4 GHz Wi-Fi 并校时。");
    drawQr(f,"http://192.168.4.1/",610,307,140);
    f.text(34,377,"未弹出配网页时，扫描右侧网址二维码。"); f.text(34,417,"也可打开 http://192.168.4.1");
    f.text(610,474,"配网页二维码");
    if(network=="connecting") f.text(34,459,"正在连接，请保持热点连接…",5);
    else if(network=="failed") f.text(34,459,"连接失败，请在手机页面检查密码后重试。",2);
    else if(network=="off") f.text(34,459,"服务已关闭，长按绿色刷新键重新开启。",1);
  } else if(notice.length()) {
    f.text(36,85,notice,2); f.text(36,150,"同时长按前进和后退键约 1.5 秒进入设置。");
    f.text(36,195,"选择重配网络可保留已有照片。"); f.text(36,240,"绿色刷新键短按可返回画面，长按可重试连接。");
  } else {
    if(pictureMode) {
      if(selectedPhoto && album.read(selectedPhoto,f.pixels)) photo=selectedPhoto;
      else { f.text(230,185,"相册还是空的"); f.text(230,230,"长按绿色刷新键，再扫码上传照片。"); }
    } else if(currentDay()>=0) calendarFrame(f,year,month,currentDay());
    else { f.text(230,175,"日期尚未设置"); f.text(230,220,"同时长按前后键，选择重配网络。"); f.text(230,265,"打开配网页，用手机时间初始化时钟。"); }
    // Calendar keeps its scan entry; photos are unobstructed at rest. Service
    // open/close already repaints, so the QR appears and disappears with it.
    if(!pictureMode || serviceRunning()) {
      bool uploadReady=web.uploadActive() && WiFi.status()==WL_CONNECTED && lastURL.length();
      drawQr(f,uploadReady ? lastURL : String(OFFLINE_QR_HELP));
    }
  }
  shownBattery=readBatteryPercent(); statusBar(f,shownBattery,serviceRunning());
  screen.submit(f,photo); dirty=false;
}
void startSetup() {
  Serial.println("[standalone] starting provisioning hotspot");
  web.stop(); dns.stop(); WiFi.disconnect(true); WiFi.mode(WIFI_AP_STA);
  provisioning=true; connecting=false; network="setup"; notice="";
  apName="E1002-"+WiFi.macAddress().substring(12); apName.replace(":",""); apPassword="12345678";
  WiFi.softAPConfig(IPAddress(192,168,4,1),IPAddress(192,168,4,1),IPAddress(255,255,255,0));
  if(!WiFi.softAP(apName.c_str(),apPassword.c_str())) { notice="无法开启配网热点，请重试。"; provisioning=false; requestDraw(); return; }
  dns.start(53,"*",IPAddress(192,168,4,1));
  // Scan once; a slow scan must never run inside HTTP callbacks.
  DynamicJsonDocument networks(4096); auto list=networks.createNestedArray("networks");
  int count=WiFi.scanNetworks(false,true); String added[20]; int used=0;
  for(int i=0;i<count && used<20;i++) {
    String ssid=WiFi.SSID(i); bool duplicate=false; for(int j=0;j<used;j++) if(added[j]==ssid) duplicate=true;
    if(ssid.length() && !duplicate) { list.add(ssid); added[used++]=ssid; }
  }
  WiFi.scanDelete(); String encoded; serializeJson(networks,encoded); web.setNetworks(encoded);
  if(!web.startSetup()) { notice="无法启动配网页，请重试。"; provisioning=false; WiFi.mode(WIFI_OFF); }
  Serial.printf("[standalone] provisioning HTTP ready=%d\n",web.setupActive());
  web.touch(); requestDraw();
}
void connectSaved() {
  if(provisioning && web.setupActive()) { web.touch(); return; }
  if(savedSSID.isEmpty()) { startSetup(); return; }
  if(web.uploadActive() && WiFi.status()==WL_CONNECTED) { web.touch(); return; }
  if(notice.length()) { notice=""; requestDraw(); }
  WiFi.mode(WIFI_STA); WiFi.begin(savedSSID.c_str(),savedPassword.c_str());
  connecting=true; web.connecting=true; connectAt=millis(); network="connecting";
}
void factoryReset() {
  // Resume an interrupted reset at next boot before exposing any old capability.
  prefs.putBool("resetting",true); web.stop(); dns.stop(); WiFi.disconnect(true,true); WiFi.mode(WIFI_OFF);
  bool cleared=storageOK ? album.clear() : LittleFS.format();
  if(!cleared) { notice="存储清理失败，请重新上电后重试。"; settings=false; requestDraw(); return; }
  prefs.clear(); prefs.end(); ESP.restart();
}
void keyAction(KeyEvent event) {
  if(event==KeyEvent::None) return;
  const char *names[]={"none","refresh","upload","previous","next","settings"};
  Serial.printf("[standalone] key=%s month=%04d-%02d picture=%d settings=%d\n",names[int(event)],year,month,pictureMode,settings);
  lastKey=millis();
  if(event==KeyEvent::Settings) { settings=!settings; confirmReset=false; settingChoice=0; requestDraw(); return; }
  if(settings) {
    if(event==KeyEvent::Previous || event==KeyEvent::Next) {
      if(confirmReset) confirmReset=false;
      else settingChoice=std::max(0,std::min(2,settingChoice+(event==KeyEvent::Next ? 1:-1)));
      requestDraw();
    } else if(event==KeyEvent::Refresh) {
      if(confirmReset) {
        if(screen.busy() || dirty) return; // confirmation must be visible first
        factoryReset(); return;
      }
      if(settingChoice==0) { settings=false; requestDraw(); }
      else if(settingChoice==1) {
        if(web.transferring.load()) { notice="照片正在写入，请稍后重配。"; settings=false; requestDraw(); return; }
        prefs.remove("wifi"); savedSSID=""; savedPassword=""; settings=false; startSetup();
      } else { confirmReset=true; requestDraw(); }
    }
    return;
  }
  if(event==KeyEvent::OpenUpload) { connectSaved(); return; }
  if(provisioning && network!="connected") return;
  if(notice.length()) { notice=""; requestDraw(); return; }
  if(event==KeyEvent::Refresh) pictureMode=!pictureMode;
  else {
    int direction=event==KeyEvent::Next ? 1:-1;
    if(pictureMode) selectedPhoto=album.adjacent(selectedPhoto,direction);
    else {
      shiftMonth(year,month,direction);
      Serial.printf("[standalone] calendar selected=%04d-%02d\n",year,month);
    }
  }
  persistState(); requestDraw();
}
void tickNetwork() {
  ConfigureRequest request;
  if(web.takeConfig(request)) {
    pendingConfig=request;
    WiFi.disconnect(); WiFi.begin(request.ssid,request.password);
    connecting=true; web.connecting=true; network="connecting"; connectAt=millis(); requestDraw();
  }
  if(!connecting) return;
  if(WiFi.status()==WL_CONNECTED) {
    connecting=false; web.connecting=false; network="connected";
    if(provisioning) {
      rtcOK=clockRTC.set(pendingConfig.epoch); utcOffset=pendingConfig.offset;
      savedSSID=pendingConfig.ssid; savedPassword=pendingConfig.password;
      StaticJsonDocument<256> config; config["ssid"]=savedSSID; config["password"]=savedPassword; config["offset"]=utcOffset;
      String encoded; serializeJson(config,encoded); prefs.putString("wifi",encoded);
      prefs.putInt("offset",utcOffset);
      auto today=fromDay(currentDay()); year=today.year; month=today.month; pictureMode=false; persistState(); requestDraw();
    }
    String nextURL="http://"+WiFi.localIP().toString()+":8080/#"+uploadToken;
    if(!web.startUpload()) { notice="上传服务启动失败，请长按刷新键重试。"; requestDraw(); return; }
    if(lastURL!=nextURL) { lastURL=nextURL; prefs.putString("url",lastURL); requestDraw(); }
    if(notice.length()) { notice=""; requestDraw(); }
  } else if(elapsed(millis(),connectAt,CONNECT_MS)) {
    connecting=false; web.connecting=false; network="failed"; WiFi.disconnect();
    if(provisioning) requestDraw();
    else { WiFi.mode(WIFI_OFF); notice="已保存的 Wi-Fi 连接失败。"; requestDraw(); }
  }
}
void updateStatus() {
  StaticJsonDocument<2048> doc;
  doc["network"]=network; doc["rtc"]=rtcOK; doc["refreshing"]=dirty || screen.busy(); doc["shown_photo"]=shownPhoto;
  doc["photos"]=album.count(); doc["latest_photo"]=album.latest(); doc["storage"]=storageOK;
  doc["battery_percent"]=shownBattery; doc["service_running"]=serviceRunning();
  doc["clock_set"]=clockRTC.valid(); doc["epoch"]=clockRTC.now(); doc["timezone_offset"]=utcOffset;
  doc["display_mode"]=pictureMode ? "photo":"calendar";
  doc["ssid"]=savedSSID; doc["ip"]=WiFi.status()==WL_CONNECTED ? WiFi.localIP().toString():String("");
  auto results=doc.createNestedArray("setting_results");
  for(const auto &result:systemResults) if(result.id) {
    auto entry=results.createNestedObject(); entry["id"]=result.id; entry["ok"]=result.ok; entry["message"]=result.message;
  }
  uint32_t remaining=WINDOW_MS-std::min(WINDOW_MS,uint32_t(millis()-web.accessed.load())); doc["remaining_seconds"]=remaining/1000;
  String result; serializeJson(doc,result); web.updateStatus(result);
}
void tickSystemSettings() {
  if(!systemRequestWaiting && web.takeSystemRequest(pendingSystem)) systemRequestWaiting=true;
  // Let the 202 response leave the HTTP task before changing Wi-Fi or stopping
  // its server. All NVS, display and RTC mutations stay on the main task.
  if(!systemRequestWaiting || !elapsed(millis(),pendingSystem.queuedAt,500)) return;
  if(web.transferring.load()) return;
  const auto &request=pendingSystem; bool ok=true; const char *message="已应用设置。";
  switch(request.action) {
    case SystemAction::SyncTime: {
      rtcOK=clockRTC.set(request.epoch); utcOffset=request.offset;
      bool saved=prefs.putInt("offset",utcOffset)>0;
      if(!pictureMode) { auto date=fromDay(currentDay()); year=date.year; month=date.month; }
      lastDay=currentDay(); persistState(); requestDraw();
      ok=rtcOK && saved;
      message=!saved ? "时区未能保存，请重试。" : !rtcOK ? "本次已校时，RTC 写入失败；断电后需重新校时。" : "已同步手机时间并保存时区。";
      break;
    }
    case SystemAction::Calendar: {
      pictureMode=false; settings=confirmReset=false; notice="";
      if(clockRTC.valid()) { auto date=fromDay(currentDay()); year=date.year; month=date.month; }
      persistState(); requestDraw(); message="已切换到本月月历，屏幕刷新中。"; break;
    }
    case SystemAction::Photo:
      if(!album.count()) { ok=false; message="相册还是空的，请先上传照片。"; }
      else {
        pictureMode=true; selectedPhoto=album.latest(); settings=confirmReset=false; notice="";
        persistState(); requestDraw(); message="已切换到最近照片，屏幕刷新中。";
      }
      break;
    case SystemAction::FactoryReset:
      settings=true; settingChoice=2; confirmReset=true; requestDraw();
      message="请等设备显示确认画面，再短按绿色刷新键清除。前后键可取消。"; break;
    case SystemAction::Reconfigure:
      if(prefs.isKey("wifi") && !prefs.remove("wifi")) { ok=false; message="网络配置清除失败，请重试。"; }
      else {
        message="即将打开配网热点，照片保留。请连接屏幕上的热点后重新配网。";
        savedSSID=""; savedPassword=""; settings=confirmReset=false;
      }
      break;
    default: ok=false; message="不支持的设置。"; break;
  }
  systemResults[nextSystemResult++%4]={request.id,ok,message};
  systemRequestWaiting=false; updateStatus(); web.finishSystemRequest();
  if(request.action==SystemAction::Reconfigure && ok) startSetup();
}
void sleepNow() {
  if(screenshotRequested || web.transferring.load() || connecting || screen.busy() || dirty || uxQueueMessagesWaiting(keyEvents)>0) return;
  for(int pin=3;pin<=5;pin++) if(digitalRead(pin)==LOW) return;
  web.stop(); dns.stop(); WiFi.disconnect(true); WiFi.mode(WIFI_OFF); persistState();
  if(uxQueueMessagesWaiting(keyEvents)>0) return;
  for(int pin=3;pin<=5;pin++) if(digitalRead(pin)==LOW) return;
  uint64_t mask=0;
  for(int pin=3;pin<=5;pin++) {
    auto gpio=static_cast<gpio_num_t>(pin); rtc_gpio_init(gpio); rtc_gpio_set_direction(gpio,RTC_GPIO_MODE_INPUT_ONLY); rtc_gpio_pullup_en(gpio); rtc_gpio_pulldown_dis(gpio); mask|=1ULL<<pin;
  }
  esp_sleep_enable_ext1_wakeup(mask,ESP_EXT1_WAKEUP_ANY_LOW);
  if(!pictureMode && clockRTC.valid()) {
    uint64_t seconds=secondsUntilLocalMidnight(clockRTC.now(),utcOffset);
    esp_sleep_enable_timer_wakeup(seconds*1000000ULL);
    Serial.printf("[standalone] next daily calendar wake in %llu seconds\n",(unsigned long long)seconds);
  }
  Serial.flush(); esp_deep_sleep_start();
}
void setup() {
  Serial.begin(115200,SERIAL_8N1,44,43); delay(150);
  Serial.printf("[e1002-standalone] built %s %s, wake=%d\n",__DATE__,__TIME__,int(esp_sleep_get_wakeup_cause()));
  for(int pin=3;pin<=5;pin++) { rtc_gpio_deinit(static_cast<gpio_num_t>(pin)); pinMode(pin,INPUT_PULLUP); }
  keyEvents=xQueueCreate(32,sizeof(KeyEvent));
  if(esp_sleep_get_wakeup_cause()==ESP_SLEEP_WAKEUP_EXT1) {
    uint64_t wake=esp_sleep_get_ext1_wakeup_status();
    uint8_t mask=((wake&(1ULL<<3)) ? 1:0)|((wake&(1ULL<<4)) ? 2:0)|((wake&(1ULL<<5)) ? 4:0);
    Serial.printf("[standalone] wake GPIO mask=%llx key mask=%u\n",(unsigned long long)wake,mask);
    keys.resumeFromWake(millis(),mask);
  }
  if(xTaskCreate(readKeys,"keys",2048,nullptr,2,nullptr)!=pdPASS) { Serial.println("key task creation failed"); while(true) delay(1000); }
  pinMode(21,OUTPUT); digitalWrite(21,LOW);
  analogReadResolution(12); analogSetPinAttenuation(1,ADC_11db);
  WiFi.persistent(false); WiFi.setAutoReconnect(false);
  prefs.begin("e1002-alone",false);
  if(!psramFound()) { Serial.println("PSRAM required; check OPI PSRAM configuration"); while(true) delay(1000); }
  rtcOK=clockRTC.begin() && clockRTC.restore();
  Serial.printf("[standalone] PSRAM=%u RTC=%d\n",ESP.getPsramSize(),rtcOK);
  storageOK=album.begin();
  if(!storageOK && virginFilesystem()) { LittleFS.format(); storageOK=album.begin(); }
  web.storageReady=storageOK;
  Serial.printf("[standalone] album ready=%d photos=%d\n",storageOK,album.count());
  if(prefs.getBool("resetting",false)) factoryReset();
  uploadToken=prefs.getString("token",""); if(uploadToken.length()!=32) { uploadToken=randomHex(16); prefs.putString("token",uploadToken); }
  web.setToken(uploadToken);
  StaticJsonDocument<256> config;
  if(!deserializeJson(config,prefs.getString("wifi",""))) {
    savedSSID=config["ssid"].as<String>(); savedPassword=config["password"].as<String>(); utcOffset=config["offset"] | 480;
  }
  utcOffset=prefs.getInt("offset",utcOffset);
  if(utcOffset< -720 || utcOffset>840 || utcOffset%15) utcOffset=480;
  int today=currentDay(); auto date=fromDay(today>=0 ? today : civilDay(2026,1,1)); lastDay=today;
  year=prefs.getInt("year",date.year); month=prefs.getInt("month",date.month);
  if(year<2000 || year>2099 || month<1 || month>12) { year=date.year; month=date.month; }
  pictureMode=prefs.getBool("picture",false); selectedPhoto=prefs.getUInt("photo",album.latest());
  if(!pictureMode) followCurrentMonth(year,month,prefs.getInt("day",today),today);
  if(selectedPhoto) selectedPhoto=album.adjacent(selectedPhoto,0);
  shownPhoto=prefs.getUInt("shown-photo",0); lastURL=prefs.getString("url","");
  auto wake=esp_sleep_get_wakeup_cause();
  // A flash/update or power-on must actually redraw. Only deep-sleep resumes
  // can rely on the frame fingerprint retained in NVS.
  bool resume=wake==ESP_SLEEP_WAKEUP_EXT1 || wake==ESP_SLEEP_WAKEUP_TIMER;
  if(!screen.begin(resume ? prefs.getUInt("shown",0) : 0)) { Serial.println("display task creation failed"); while(true) delay(1000); }
  if(savedSSID.isEmpty()) startSetup();
  // Wi-Fi stays off for ordinary boot, calendar timer, and offline navigation.
  else WiFi.mode(WIFI_OFF);
  lastKey=millis(); dirtyAt=millis();
  updateStatus();
  Serial.println("[standalone] setup complete; first frame pending");
}
void loop() {
  uint32_t now=millis();
  static char serialCommand[20]={}; static size_t serialLength=0;
  while(Serial.available()) {
    char c=Serial.read();
    if(c=='\n') {
      serialCommand[serialLength]=0;
      if(!strcmp(serialCommand,"SCREENSHOT")) { screenshotRequested=true; lastKey=millis(); }
      serialLength=0;
    } else if(c!='\r') {
      if(serialLength<sizeof(serialCommand)-1) serialCommand[serialLength++]=c;
      else serialLength=0;
    }
  }
  KeyEvent event; while(xQueueReceive(keyEvents,&event,0)==pdTRUE) keyAction(event);
  if(provisioning) dns.processNextRequest();
  tickNetwork();
  tickSystemSettings();
  bool running=serviceRunning();
  if(running!=observedServiceRunning) { observedServiceRunning=running; requestDraw(); }
  uint32_t uploaded;
  if(web.takeUpload(uploaded)) { selectedPhoto=uploaded; pictureMode=true; notice=""; persistState(); requestDraw(); }
  int today=currentDay();
  if(today!=lastDay) {
    if(!pictureMode) { followCurrentMonth(year,month,lastDay,today); requestDraw(); }
    lastDay=today; persistState();
  }
  RenderDone result;
  if(screen.poll(result)) {
    if(prefs.getUInt("shown",0)!=result.signature) prefs.putUInt("shown",result.signature);
    shownPhoto=result.photo;
    if(prefs.getUInt("shown-photo",0)!=shownPhoto) prefs.putUInt("shown-photo",shownPhoto);
  }
  if(dirty && elapsed(now,dirtyAt,150)) drawFrame();
  if(screenshotRequested && !dirty && !screen.busy()) {
    if(screen.writeSnapshot(Serial)) { screenshotRequested=false; lastKey=millis(); }
    else requestDraw();
  }
  updateStatus();
  bool services=web.setupActive() || web.uploadActive();
  if(services && elapsed(now,web.accessed.load(),WINDOW_MS) && !web.transferring.load() && !connecting) {
    // The e-paper retains its pixels in deep sleep: explicitly repaint the
    // off indicator after shutting down the services, then wait for that job
    // to finish before sleepNow() is allowed to enter deep sleep.
    web.stop(); dns.stop(); WiFi.disconnect(true); WiFi.mode(WIFI_OFF); network="off";
    if(!savedSSID.isEmpty()) provisioning=false;
    observedServiceRunning=false; lastKey=millis(); requestDraw();
    Serial.println("[standalone] services stopped; repainting off indicator before sleep");
  }
  else if(!services && !connecting && elapsed(now,lastKey,settings ? 60000 : 2500) && digitalRead(3)==HIGH && digitalRead(4)==HIGH && digitalRead(5)==HIGH) sleepNow();
  delay(10);
}
