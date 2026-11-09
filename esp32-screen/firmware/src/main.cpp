#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEHIDDevice.h>
#include <BLESecurity.h>
#include <BLE2902.h>
#include <atomic>

// E32R32P: https://www.lcdwiki.com/3.2inch_ESP32-32E_Display
TFT_eSPI screen;
TFT_eSprite lineBuffer(&screen);
String drawnLines[13];
bool displayInitialized=false;
Preferences prefs;
BLEHIDDevice *hid;
BLECharacteristic *keyboardReport, *mouseReport;
std::atomic<bool> bleConnected(false);
std::atomic<int> lastNotifyStatus(-1);
bool armed=false, serverOK=false;
String ssid, password, baseURL, token, bootID, lastID, lastResult;
String logs[6];
uint32_t lastDraw=0, lastPoll=0;
bool lastButton=true;
uint32_t buttonChanged=0;

static uint8_t reportMap[] = {
  // Keyboard, report 1, boot-compatible 8-byte report.
  0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x01,
  0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,
  0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x01,
  0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,
  0x95,0x01,0x75,0x03,0x91,0x01,
  0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,
  0x19,0x00,0x29,0x65,0x81,0x00,0xC0,
  // Relative mouse with three buttons and wheel, report 2.
  0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x02,0x09,0x01,0xA1,0x00,
  0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,
  0x95,0x03,0x75,0x01,0x81,0x02,0x95,0x01,0x75,0x05,0x81,0x01,
  0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,
  0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0
};

void logLine(String s) {
  Serial.println(s);
  for(int i=0;i<5;i++) logs[i]=logs[i+1];
  logs[5]=s.substring(0,48);
}
class ReportCallbacks: public BLECharacteristicCallbacks {
  void onStatus(BLECharacteristic*, Status status, uint32_t) override {
    lastNotifyStatus=(int)status;
  }
};
bool notifyReport(BLECharacteristic *report,uint8_t *data,size_t size) {
  lastNotifyStatus=-1;
  report->setValue(data,size);report->notify();
  return lastNotifyStatus==(int)BLECharacteristicCallbacks::SUCCESS_NOTIFY;
}
bool releaseAll() {
  if(!bleConnected) return false;
  uint8_t k[8]={0}, m[4]={0};
  bool keyOK=notifyReport(keyboardReport,k,8);
  bool mouseOK=notifyReport(mouseReport,m,4);
  return keyOK && mouseOK;
}
class Callbacks: public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    // Bluedroid's local CCCD flags are not restored on bonded reconnects.
    // Match the reconnect compatibility handling in ESP32-BLE-Keyboard.
    ((BLE2902*)keyboardReport->getDescriptorByUUID((uint16_t)0x2902))->setNotifications(true);
    ((BLE2902*)mouseReport->getDescriptorByUUID((uint16_t)0x2902))->setNotifications(true);
    bleConnected=true;
  }
  void onDisconnect(BLEServer*) override {
    bleConnected=false;
    ((BLE2902*)keyboardReport->getDescriptorByUUID((uint16_t)0x2902))->setNotifications(false);
    ((BLE2902*)mouseReport->getDescriptorByUUID((uint16_t)0x2902))->setNotifications(false);
    // Advertising restarts in loop; never touch TFT or Strings from BLE task.
  }
};
void setupBLE() {
  BLEDevice::init("Pixel-Control");
  auto server=BLEDevice::createServer();
  server->setCallbacks(new Callbacks());
  hid=new BLEHIDDevice(server);
  keyboardReport=hid->inputReport(1);
  hid->outputReport(1);
  mouseReport=hid->inputReport(2);
  keyboardReport->setCallbacks(new ReportCallbacks());
  mouseReport->setCallbacks(new ReportCallbacks());
  hid->manufacturer()->setValue("ESP32 Phone Control");
  hid->pnp(0x02,0x303A,0x4001,0x0100);
  hid->hidInfo(0x00,0x01);
  auto security=new BLESecurity();
  security->setAuthenticationMode(ESP_LE_AUTH_BOND);
  security->setCapability(ESP_IO_CAP_NONE);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  hid->reportMap(reportMap,sizeof(reportMap));
  hid->startServices(); hid->setBatteryLevel(100);
  auto adv=server->getAdvertising();
  adv->setAppearance(0x03C0);
  adv->addServiceUUID(hid->hidService()->getUUID());
  adv->setScanResponse(true); adv->start();
}
void drawStatus() {
  // Paint the background once. Compose each changed line off-screen, then
  // transfer it in one SPI operation; never clear the visible screen in loop.
  if(!displayInitialized) {
    screen.fillScreen(TFT_BLACK);
    screen.drawFastHLine(8,155,224,TFT_DARKGREY);
    displayInitialized=true;
  }
  String lines[13];
  lines[0]="PIXEL CONTROL";
  lines[1]=armed?"ARMED / BOOT to pause":"PAUSED / BOOT to arm";
  lines[2]=bleConnected?"BLE: connected":"BLE: pair Pixel-Control";
  lines[3]=WiFi.status()==WL_CONNECTED?"IP: "+WiFi.localIP().toString():"WiFi: not connected";
  lines[4]=serverOK?"Server: online":"Server: waiting";
  lines[5]="Heap: "+String(ESP.getFreeHeap());
  for(int i=0;i<6;i++) lines[6+i]=logs[i].substring(0,36);
  lines[12]="USB serial: 115200";
  const int y[]={8,34,58,82,106,130,168,188,208,228,248,268,298};
  for(int i=0;i<13;i++) {
    if(lines[i]==drawnLines[i]) continue;
    uint16_t color=i==0?TFT_CYAN:(i==1?(armed?TFT_GREEN:TFT_YELLOW):TFT_WHITE);
    lineBuffer.fillSprite(TFT_BLACK);
    lineBuffer.setTextColor(color,TFT_BLACK);
    lineBuffer.drawString(lines[i],0,0,i<6?2:1);
    lineBuffer.pushSprite(8,y[i]);
    drawnLines[i]=lines[i];
  }
}
// US keyboard mapping only; reject unsupported characters before any side effect.
bool asciiKey(char c,uint8_t &key,uint8_t &mod) {
  mod=0;
  if(c>='a' && c<='z') {key=c-'a'+4;return true;}
  if(c>='A' && c<='Z') {key=c-'A'+4;mod=2;return true;}
  if(c>='1' && c<='9') {key=c-'1'+30;return true;}
  if(c=='0') {key=39;return true;}
  if(c=='\n') {key=40;return true;}
  if(c=='\t') {key=43;return true;}
  if(c==' ') {key=44;return true;}
  if(c=='\'') {key=52;return true;}
  const char *symbols="-=[]\\;`,./";
  const uint8_t keys[]={45,46,47,48,49,51,53,54,55,56};
  for(int i=0;i<10;i++) if(c==symbols[i]) {key=keys[i];return true;}
  const char *shifted="!@#$%^&*()_+{}|:\"~<>?";
  const uint8_t shiftKeys[]={30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,51,52,53,54,55,56};
  for(int i=0;i<21;i++) if(c==shifted[i]) {key=shiftKeys[i];mod=2;return true;}
  return false;
}
bool sendKey(uint8_t key,uint8_t mod) {
  uint8_t k[8]={mod,0,key,0,0,0,0,0};
  bool pressed=notifyReport(keyboardReport,k,8);delay(30);
  memset(k,0,8);bool released=notifyReport(keyboardReport,k,8);delay(30);
  return pressed && released;
}
String execute(JsonObject a) {
  if(!armed || !bleConnected) return "not_ready";
  String type=a["type"] | "";
  if(type=="move") {
    int dx=a["dx"] | 0,dy=a["dy"] | 0,w=a["wheel"] | 0;
    if(dx < -127 || dx>127 || dy < -127 || dy>127 || w < -127 || w>127) return "invalid_move";
    uint8_t m[]={0,(uint8_t)(int8_t)dx,(uint8_t)(int8_t)dy,(uint8_t)(int8_t)w};
    if(!notifyReport(mouseReport,m,4)) return "mouse_notify_error";
  } else if(type=="click") {
    int button=a["button"] | 1;
    if(button!=1 && button!=2 && button!=4) return "invalid_button";
    uint8_t m[]={(uint8_t)button,0,0,0};
    bool pressed=notifyReport(mouseReport,m,4);delay(30);
    bool released=releaseAll();
    if(!pressed || !released) return "mouse_notify_error";
  } else if(type=="key") {
    int key=a["usage"] | 0,mod=a["modifiers"] | 0;
    if(key<4 || key>101 || mod<0 || mod>255) return "invalid_key";
    if(!sendKey(key,mod)) return "keyboard_notify_error";
  } else if(type=="text") {
    String text=a["text"] | "";
    if(text.length()>160) return "text_too_long";
    uint8_t key,mod;
    for(size_t i=0;i<text.length();i++) if(!asciiKey(text[i],key,mod)) return "ascii_only";
    for(size_t i=0;i<text.length();i++) {
      // Physical stop is sampled even during a text command.
      if(digitalRead(0)==LOW || !bleConnected) {
        armed=false;lastButton=digitalRead(0);buttonChanged=millis();
        releaseAll();return "interrupted";
      }
      asciiKey(text[i],key,mod);
      if(!sendKey(key,mod)) {releaseAll();return "keyboard_notify_error";}
    }
  } else if(type=="release") {if(!releaseAll()) return "release_notify_error";}
  else return "invalid_action";
  return "ok";
}
void status(JsonDocument &d) {
  d["boot_id"]=bootID;d["ble_connected"]=bleConnected.load();d["armed"]=armed;
  d["ip"]=WiFi.localIP().toString();d["heap"]=ESP.getFreeHeap();d["uptime_ms"]=millis();
  d["firmware"]="0.1.1";
  JsonArray arr=d.createNestedArray("logs");for(auto &s:logs) arr.add(s);
  if(lastID.length()) {auto ack=d.createNestedObject("ack");ack["id"]=lastID;ack["result"]=lastResult;}
}
void pollServer() {
  if(WiFi.status()!=WL_CONNECTED || !baseURL.length() || !token.length()) {serverOK=false;return;}
  HTTPClient http;
  if(!http.begin(baseURL+"/v1/phone-control/device/poll")) return;
  http.setTimeout(1200);http.addHeader("Content-Type","application/json");
  http.addHeader("Authorization","Bearer "+token);
  StaticJsonDocument<1536> body;status(body);String payload;serializeJson(body,payload);
  int code=http.POST(payload);
  serverOK=(code==200);
  static int previousCode=0;
  if(code!=previousCode) {
    previousCode=code;
    logLine("Server HTTP: "+String(code));
  }
  if(code==200 && http.getSize()<=1536) {
    // Keep the loop task's 8KB stack well below its limit during HID execution.
    StaticJsonDocument<1024> response;
    auto err=deserializeJson(response,http.getStream());
    if(!err && response["command"].is<JsonObject>()) {
      JsonObject cmd=response["command"];
      String id=cmd["id"] | "";
      if(id.length() && id!=lastID) {
        // Save before executing. No automatic retries of input side effects.
        lastID=id;lastResult="interrupted";
        lastResult=execute(cmd["action"].as<JsonObject>());
        logLine(String(cmd["action"]["type"] | "action")+": "+lastResult);
      }
    }
  }
  http.end();
}
void readConfig() {
  static String line;
  while(Serial.available()) {
    char c=Serial.read();
    if(c=='\n') {
      StaticJsonDocument<1536> d;
      auto error=deserializeJson(d,line);
      if(!error && d["op"]=="status") {
        StaticJsonDocument<1536> report;status(report);
        report["wifi_mac"]=WiFi.macAddress();
        report["wifi_rssi"]=WiFi.RSSI();
        report["wifi_status"]=(int)WiFi.status();
        report["keyboard_notify"]=((BLE2902*)keyboardReport->getDescriptorByUUID((uint16_t)0x2902))->getNotifications();
        report["mouse_notify"]=((BLE2902*)mouseReport->getDescriptorByUUID((uint16_t)0x2902))->getNotifications();
        report["last_notify_status"]=lastNotifyStatus.load();
        serializeJson(report,Serial);Serial.println();
      } else if(!error && d["op"]=="network") {
        String ip=d["ip"] | "";
        String gateway=d["gateway"] | "";
        String mask=d["mask"] | "255.255.255.0";
        IPAddress address,router,subnet;
        if(ip=="") {
          prefs.remove("static_ip");prefs.remove("gateway");prefs.remove("mask");
        } else if(address.fromString(ip) && router.fromString(gateway) && subnet.fromString(mask)
                  && address[0]!=0 && address[0]<224 && address[3]!=0 && address[3]!=255) {
          prefs.putString("static_ip",ip);prefs.putString("gateway",gateway);prefs.putString("mask",mask);
        } else {logLine("Invalid static network config");line="";continue;}
        logLine("Network config saved. Restarting.");delay(200);ESP.restart();
      } else if(!error && d["op"]=="config") {
        String url=d["server"] | "";
        String key=d["token"] | "";
        String net=d["ssid"] | "";
        if(url.startsWith("http://") && url.length()<200 && key.length()>=24 && net.length()<=32) {
          while(url.endsWith("/")) url.remove(url.length()-1);
          prefs.putString("ssid",net);prefs.putString("password",d["password"] | "");
          prefs.putString("server",url);prefs.putString("token",key);
          logLine("Configuration saved. Restarting.");delay(200);ESP.restart();
        } else logLine("Invalid config (HTTP LAN / token)");
      } else logLine("Expected config JSON");
      line="";
    } else if(c!='\r') {
      if(line.length()<1536) line+=c;else line="";
    }
  }
}
void setup() {
  Serial.begin(115200);pinMode(0,INPUT_PULLUP);
  pinMode(33,OUTPUT);digitalWrite(33,HIGH); // deselect shared-bus touch
  pinMode(5,OUTPUT);digitalWrite(5,HIGH); // deselect SD
  pinMode(4,OUTPUT);digitalWrite(4,HIGH); // audio amplifier off
  screen.init();screen.setRotation(0);pinMode(27,OUTPUT);digitalWrite(27,HIGH);
  lineBuffer.setColorDepth(16);
  if(!lineBuffer.createSprite(224,20)) {
    Serial.println("Display line buffer allocation failed");
    while(true) delay(1000);
  }
  logLine("Starting firmware 0.1.1");drawStatus();
  prefs.begin("phone-control",false);
  if(prefs.isKey("ssid")) ssid=prefs.getString("ssid");
  if(prefs.isKey("password")) password=prefs.getString("password");
  if(prefs.isKey("server")) baseURL=prefs.getString("server");
  if(prefs.isKey("token")) token=prefs.getString("token");
  bootID=String(esp_random(),HEX)+String(esp_random(),HEX);
  setupBLE();logLine("BLE: Pixel-Control");
  // Classic ESP32 requires modem sleep for Wi-Fi/Bluetooth coexistence.
  WiFi.mode(WIFI_STA);WiFi.setSleep(true);
  if(prefs.isKey("static_ip")) {
    IPAddress ip,gateway,mask;
    ip.fromString(prefs.getString("static_ip"));
    gateway.fromString(prefs.getString("gateway"));
    mask.fromString(prefs.getString("mask","255.255.255.0"));
    if(WiFi.config(ip,gateway,mask,gateway)) logLine("Static IP: "+ip.toString());
    else logLine("Static IP config failed");
  }
  if(ssid.length()) {WiFi.begin(ssid.c_str(),password.c_str());logLine("Connecting WiFi...");}
  else logLine("Use tools/configure.py over USB");
  logLine("BOOT button arms / pauses");
}
void loop() {
  readConfig();
  static bool previousWiFi=false;
  bool online=WiFi.status()==WL_CONNECTED;
  if(online!=previousWiFi) {
    previousWiFi=online;
    logLine(online?"WiFi IP: "+WiFi.localIP().toString():"WiFi disconnected");
  }
  bool b=digitalRead(0);
  if(b!=lastButton && millis()-buttonChanged>200) {
    buttonChanged=millis();lastButton=b;
    if(!b) {armed=!armed;releaseAll();logLine(armed?"Control armed":"Control paused");}
  }
  static bool previousBLE=false;
  bool connected=bleConnected;
  if(connected!=previousBLE) {
    previousBLE=connected;
    if(!connected) {armed=false;BLEDevice::startAdvertising();}
    logLine(connected?"BLE connected":"BLE disconnected; paused");
  }
  if(millis()-lastPoll>750) {lastPoll=millis();pollServer();}
  if(millis()-lastDraw>1200) {lastDraw=millis();drawStatus();}
  delay(5);
}
