#include <Arduino.h>
#include <Preferences.h>
#include <TFT_eSPI.h>
#include <U8g2_for_TFT_eSPI.h>
#include <math.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <time.h>
#include "trusted_roots.h"


#include "calibration.h"
#include "touch_gesture.h"
#include "ui_controls.h"
#include "frame_diff.h"

namespace {
TFT_eSPI display;
TFT_eSprite lcd(&display);
bool frameDirty=true;
uint32_t frameTiles[300]={};
bool firstFrame=true;
void flushFrame(){
 if(!frameDirty)return;frameDirty=false;
 // The sprite is fixed at 320x240, 8-bit. Hash its bytes directly to keep touch redraws fast.
 const auto *pixels=static_cast<const uint8_t*>(lcd.getPointer());
 for(int ty=0;ty<15;++ty)for(int tx=0;tx<20;++tx){
  uint32_t hash=adventure::tileHash([pixels](int x,int y){return pixels[y*320+x];},tx,ty);
  int index=ty*20+tx;if(firstFrame||frameTiles[index]!=hash){lcd.pushSprite(tx*16,ty*16,tx*16,ty*16,16,16);frameTiles[index]=hash;}
 }
 firstFrame=false;
}
U8g2_for_TFT_eSPI chinese;
Preferences prefs;
constexpr uint16_t cream = 0xFF9A, green = 0x346C, ink = 0x2947;
constexpr uint16_t pale = 0xD718, blue = 0x8D9F, gold = 0xFE48;
using Button=adventure::ButtonRect;
Button buttons[16];
static_assert(sizeof(buttons)/sizeof(buttons[0])>=13,"Pairing keypad requires thirteen touch buttons");
uint8_t buttonCount = 0;
bool dirty = true, held = false;
adventure::TouchGesture gesture;
uint32_t lastTouch = 0, lastBlink = 0;
// Three-point affine transform handles rotation, inversion and swapped axes.
adventure::Calibration calibration;
bool calibrating = false;
uint8_t calibrationStep = 0, samples = 0;
float rawX[3], rawY[3], sampleX = 0, sampleY = 0;
const int targets[4][2] = {{24,48}, {296,48}, {24,212}, {296,212}};
bool touchEnabled = false, inputDown=false, suppressTouch=false;

#include "screen_primitives.h"
#include "online.h"

void draw() {if(inputDown){suppressTouch=true;gesture=adventure::TouchGesture{};}online::render();}
void drawCalibration(const char *message = "请轻点十字中心，再松手") {
  lcd.fillSprite(cream); buttonCount=0;
  centered("触摸校准",24);
  centered(message,126);
  const int x=targets[calibrationStep][0], y=targets[calibrationStep][1];
  lcd.drawCircle(x,y,12,green);
  lcd.drawFastHLine(x-16,y,33,green); lcd.drawFastVLine(x,y-16,33,green);
  Serial.printf("CALIBRATE point=%u target=%d,%d\n",calibrationStep+1,x,y);
}
void beginCalibration() {
  calibrating=true; calibrationStep=0; held=false; gesture=adventure::TouchGesture{}; samples=0;
  sampleX=sampleY=0; dirty=false; drawCalibration();
}
bool solveCalibration() { return calibration.fit(rawX,rawY); }
void point(float rx,float ry,int &x,int &y) { calibration.map(rx,ry,x,y); }
void action(int id) {
  if(id==41) {beginCalibration();return;}
  online::handle(id);
}
int hit(int x,int y) {return adventure::hitButton(buttons,buttonCount,x,y);}
void readTouch() {
  if (!touchEnabled || millis()-lastTouch<20) return;
  lastTouch=millis();
  uint16_t rx=0,ry=0;
  bool down=display.getTouchRawZ()>350;
  if(down) { display.getTouchRaw(&rx,&ry); down=rx>30 && rx<4065 && ry>30 && ry<4065; }
  if(calibrating) {
    if(down) {
      if(samples<12) {sampleX+=rx; sampleY+=ry; ++samples;}
      held=true;
    } else if(held) {
      held=false;
      if(samples>=5) {
        float x=sampleX/samples,y=sampleY/samples;
        if(calibrationStep<3) {
          rawX[calibrationStep]=x; rawY[calibrationStep]=y;
          if(calibrationStep==2 && !solveCalibration()) {calibrationStep=0; drawCalibration("位置太近啦，请重新点十字");}
          else {++calibrationStep; drawCalibration(calibrationStep==3?"最后一次，确认这个十字":"请轻点十字中心，再松手");}
        } else {
          int sx,sy; point(x,y,sx,sy);
          if(abs(sx-296)<=22 && abs(sy-212)<=22) {
            prefs.putBytes("calibration",&calibration,sizeof(calibration));
            calibrating=false; online::screen=0;dirty=true; Serial.println("CALIBRATION OK");
          } else {calibrationStep=0; drawCalibration("再来一次，轻点十字中心");}
        }
      }
      samples=0; sampleX=sampleY=0;
    }
    return;
  }
  inputDown=down;
  if(suppressTouch){if(!down)suppressTouch=false;return;}
  int target=-1;
  if(down) {
    int x,y; point(rx,ry,x,y);
    target=hit(x,y);
  }
  const auto update=gesture.update(down,target,millis());
  // Restore only the old border; blank taps and cancelled gestures never clear the screen.
  if(update.restore>=0) {frameDirty=true;
    const auto &b=buttons[update.restore];
    lcd.drawRoundRect(b.x,b.y,b.w,b.h,11,b.primary?green:pale);
  }
  if(update.highlight>=0) {frameDirty=true;
    const auto &b=buttons[update.highlight];
    lcd.drawRoundRect(b.x,b.y,b.w,b.h,11,gold);
  }
  if(update.activate>=0) action(buttons[update.activate].action);
}
void serialControl() {
  if(!Serial.available())return;
  char c=Serial.read();
  if(c=='h')Serial.println("MATH ONLINE v0.3: C calibrate; W Wi-Fi setup; R sync; H home. Configure device token via local setup portal; no secrets in serial output.");
  if(c=='C')beginCalibration();
  if(c=='W')online::startPortal();
  if(c=='R')online::sync();
  if(c=='D')Serial.printf("ONLINE wifi=%d configured=%d screen=%d heap=%u ready=%d tasks=%u pending=%d\n",int(WiFi.status()),online::wifiConfiguredOnline(),online::screen,ESP.getFreeHeap(),online::ready,unsigned(online::tasks().size()),!online::pendingBody.isEmpty());
  if(c=='P')online::beginPair();
  if(c=='V')Serial.printf("FRAME width=%d height=%d corner=%u expected=%u\n",lcd.width(),lcd.height(),lcd.readPixelValue(319,239),unsigned((cream&0xE000)>>8|(cream&0x0700)>>6|(cream&0x0018)>>3));
  if(c=='H'){calibrating=false;online::screen=0;dirty=true;}
}

} // namespace

void setup() {
  Serial.begin(115200);
  pinMode(4,OUTPUT);digitalWrite(4,HIGH); // Vendor: audio enable is active low.
  pinMode(33,OUTPUT);digitalWrite(33,HIGH);
  for(int pin : {22,16,17}) {pinMode(pin,OUTPUT);digitalWrite(pin,HIGH);}
  pinMode(27,OUTPUT);digitalWrite(27,HIGH);
  pinMode(0,INPUT_PULLUP);
  display.init();display.setRotation(1);
  lcd.setColorDepth(8);
  if(!lcd.createSprite(320,240)){Serial.println("FRAMEBUFFER ALLOCATION FAILED");while(true)delay(1000);}

  chinese.begin(lcd);chinese.setFont(u8g2_font_wqy16_t_gb2312);chinese.setFontMode(1);
  prefs.begin("math-playtest",false);
  touchEnabled=true;
  if(prefs.getBytesLength("calibration")==sizeof(calibration)) prefs.getBytes("calibration",&calibration,sizeof(calibration));
  online::init();
  if(!calibration.valid()) beginCalibration();
  else draw();
  Serial.println("MATH ADVENTURE v0.4.3 ONLINE READY; h=help. Server-authoritative tasks and rewards.");
}
void loop() {
  serialControl();online::tick();readTouch();
  if(dirty && !calibrating) draw();
  if(!calibrating && online::screen==0) {
    uint32_t phase=millis()%4000;
    bool blink=phase>3700;
    if(blink!=(lastBlink!=0)) {face(54,107,true,blink);lastBlink=blink?1:0;}
  }
  flushFrame();
  delay(5);
}
