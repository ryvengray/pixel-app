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
#include <time.h>
#include "trusted_roots.h"


#include "calibration.h"
#include "touch_gesture.h"

namespace {
TFT_eSPI lcd;
U8g2_for_TFT_eSPI chinese;
Preferences prefs;
constexpr uint16_t cream = 0xFF9A, green = 0x346C, ink = 0x2947;
constexpr uint16_t pale = 0xD718, blue = 0x8D9F, gold = 0xFE48;
struct Button { int x, y, w, h, action; bool primary; };
Button buttons[8];
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

void text(const char *s, int x, int baseline, uint16_t color = ink) {
  if (x < 0 || x + chinese.getUTF8Width(s) > 320 || baseline > 236) Serial.printf("TEXT OVERFLOW x=%d y=%d: %s\n",x,baseline,s);
  chinese.setForegroundColor(color);
  chinese.setCursor(x, baseline);
  chinese.print(s);
}
void centered(const char *s, int baseline) {
  text(s, (320 - chinese.getUTF8Width(s)) / 2, baseline);
}
void button(int x, int y, int w, const char *label, int action, bool primary = true) {
  lcd.fillRoundRect(x, y + 2, w, 44, 11, primary ? ink : 0xC671);
  lcd.fillRoundRect(x, y, w, 44, 11, primary ? green : pale);
  text(label, x + (w - chinese.getUTF8Width(label)) / 2, y + 28, primary ? TFT_WHITE : ink);
  if (buttonCount < 8) buttons[buttonCount++] = {x,y,w,44,action,primary};
}
void face(int x, int y, bool wizard, bool blink = false) {
  lcd.fillCircle(x, y, 23, wizard ? 0xFE96 : 0x9ED5);
  if (blink) {
    lcd.drawFastHLine(x-10, y-2, 5, ink); lcd.drawFastHLine(x+5, y-2, 5, ink);
  } else {
    lcd.fillCircle(x-8,y-2,2,ink); lcd.fillCircle(x+8,y-2,2,ink);
  }
  lcd.drawLine(x-4,y+7,x,y+9,ink); lcd.drawLine(x,y+9,x+4,y+7,ink);
  lcd.fillCircle(x-15,y+6,4,0xFBAE); lcd.fillCircle(x+15,y+6,4,0xFBAE);
  if (wizard) {
    lcd.fillTriangle(x-25,y-18,x+25,y-18,x-8,y-53,0x8C9B);
    lcd.fillRoundRect(x-29,y-20,58,7,3,0x8C9B);
    lcd.fillCircle(x-8,y-39,3,gold);
  }
}
void star(int x, int y, bool filled) {
  uint16_t color = filled ? gold : 0xC6D2;
  lcd.fillTriangle(x,y-9,x-8,y+7,x+8,y+7,color);
  lcd.fillTriangle(x-10,y-3,x+10,y-3,x,y+10,color);
}
#include "online.h"

void draw() {if(inputDown){suppressTouch=true;gesture=adventure::TouchGesture{};}online::render();}
void drawCalibration(const char *message = "请轻点十字中心，再松手") {
  lcd.fillScreen(cream); buttonCount=0;
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
int hit(int x,int y) {
  for (int i=0;i<buttonCount;++i) {
    const auto &b=buttons[i];
    if(x>=b.x && x<b.x+b.w && y>=b.y && y<b.y+b.h) return i;
  }
  return -1;
}
void readTouch() {
  if (!touchEnabled || millis()-lastTouch<20) return;
  lastTouch=millis();
  uint16_t rx=0,ry=0;
  bool down=lcd.getTouchRawZ()>350;
  if(down) { lcd.getTouchRaw(&rx,&ry); down=rx>30 && rx<4065 && ry>30 && ry<4065; }
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
  if(update.restore>=0) {
    const auto &b=buttons[update.restore];
    lcd.drawRoundRect(b.x,b.y,b.w,b.h,11,b.primary?green:pale);
  }
  if(update.highlight>=0) {
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
  if(c=='D')Serial.printf("ONLINE wifi=%d ready=%d tasks=%u pending=%d mode=%s\n",int(WiFi.status()),online::ready,unsigned(online::tasks().size()),!online::pendingBody.isEmpty(),online::mode.c_str());
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
  lcd.init();lcd.setRotation(1);
  chinese.begin(lcd);chinese.setFont(u8g2_font_wqy16_t_gb2312);chinese.setFontMode(1);
  prefs.begin("math-playtest",false);
  touchEnabled=true;
  if(prefs.getBytesLength("calibration")==sizeof(calibration)) prefs.getBytes("calibration",&calibration,sizeof(calibration));
  online::init();
  if(!calibration.valid()) beginCalibration();
  else draw();
  Serial.println("MATH ADVENTURE v0.3 ONLINE READY; h=help. Server-authoritative tasks and rewards.");
}
void loop() {
  serialControl();online::tick();readTouch();
  if(dirty && !calibrating) draw();
  if(!calibrating && online::screen==0) {
    uint32_t phase=millis()%4000;
    bool blink=phase>3700;
    if(blink!=(lastBlink!=0)) {face(54,107,true,blink);lastBlink=blink?1:0;}
  }
  delay(5);
}
