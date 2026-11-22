#include <Arduino.h>
#include <Preferences.h>
#include <TFT_eSPI.h>
#include <U8g2_for_TFT_eSPI.h>
#include <math.h>
#include "adventure.h"
#include "calibration.h"
#include "touch_gesture.h"

namespace {
using adventure::Page;
TFT_eSPI lcd;
U8g2_for_TFT_eSPI chinese;
Preferences prefs;
adventure::Game game;
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
bool touchEnabled = false;

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
void save() { prefs.putBytes("progress", &game.state, sizeof(game.state)); }
void show(Page page) { game.page = page; dirty = true; }
void draw() {
  dirty = false; buttonCount = 0;
  lcd.fillScreen(cream);
  lcd.fillRect(0,0,320,28,pale);
  text("小语冒险岛",8,20);
  text("试玩",276,20);
  const auto &q = game.question();
  char progress[48];
  switch (game.page) {
    case Page::Home:
      lcd.fillRoundRect(8,42,304,130,18,0xF757);
      face(61,112,true);
      text("你好，小勇士！",106,70);
      text("今天帮帮森林朋友吧",106,96);
      snprintf(progress,sizeof(progress),"找到 %u / 3 颗星星",game.solved());
      text(progress,106,122);
      for (int i=0;i<3;++i) star(126+i*32,148,game.isSolved(i));
      button(8,188,96,"小设置",40,false);
      button(112,188,200,game.finished()?"看看今日收获":"开始冒险",1);
      break;
    case Page::Map:
      text("选一位朋友，解开谜题",8,54);
      for (int i=0;i<3;++i) {
        int x=8+i*104;
        face(x+48,87,false);
        if (game.isSolved(i)) star(x+76,72,true);
        text(adventure::questions[i].name,x+16,128);
        button(x,140,96,game.isSolved(i)?"看方法":((game.state.skipped&(1<<i))?"再想想":"帮帮它"),10+i,!game.isSolved(i));
      }
      button(8,188,96,"回家",0,false);
      button(112,188,200,"保存并休息",30,false);
      break;
    case Page::Question:
      text(q.name,8,51);
      face(282,68,false);
      text(q.line1,12,79); text(q.line2,12,105);
      for (int i=0;i<3;++i) button(8+i*104,124,96,q.options[i],20+i);
      button(8,188,148,"想一想",23,false);
      button(164,188,148,"稍后再想",24,false);
      break;
    case Page::Retry:
    case Page::Hint:
      face(46,96,false);
      text(game.page==Page::Hint?"一起找个好办法":"再想想，你可以的",86,62);
      text(q.hint1,86,99); text(q.hint2,86,127);
      button(8,188,148,"保存并休息",30,false);
      button(164,188,148,"再试一次",25);
      break;
    case Page::Win:
      // Keep the complete question visible while reviewing the solution.
      lcd.fillRoundRect(8,36,304,56,10,0xF757);
      text(q.line1,16,58); text(q.line2,16,82);
      text("好办法，一起回顾",16,116);
      star(288,110,true);
      text(q.method1,16,142); text(q.method2,16,168);
      button(8,188,96,"回家",0,false);
      button(112,188,200,game.finished()?"看看今日收获":"回到地图",26);
      break;
    case Page::Summary:
      centered(game.finished()?"今天的冒险结束啦":"进度存好啦，休息一下",58);
      for (int i=0;i<3;++i) star(112+i*48,88,game.isSolved(i));
      snprintf(progress,sizeof(progress),"解决 %u 题，%u 题稍后再想",game.solved(),game.skipped());
      centered(progress,133);
      centered("可以和家人说说你的好办法",160);
      button(72,188,176,"回家休息",0);
      break;
    case Page::Settings:
      centered("试玩进度只保存在这块板子",57);
      centered("不发放正式经验或金币",82);
      button(8,96,148,"校准触摸",41,false);
      button(164,96,148,"重玩试玩",42,false);
      centered("串口也可控制，按 h 查看帮助",171);
      button(72,188,176,"返回首页",0);
      break;
  }
  Serial.printf("PAGE %u solved=%u skipped=%u current=%u heap=%u\n",
    unsigned(game.page),game.solved(),game.skipped(),game.state.current,ESP.getFreeHeap());
}
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
  switch(id) {
    case 0: show(Page::Home); break;
    case 1: show(game.finished()?Page::Summary:Page::Map); break;
    case 10: case 11: case 12: game.select(id-10); dirty=true; save(); break;
    case 20: case 21: case 22: game.answer(id-20); dirty=true; save(); break;
    case 23: game.hint(); dirty=true; save(); break;
    case 24: game.skip(); dirty=true; save(); break;
    case 25: show(Page::Question); break;
    case 26: show(game.finished()?Page::Summary:Page::Map); break;
    case 30: show(Page::Summary); break;
    case 40: show(Page::Settings); break;
    case 41: beginCalibration(); break;
    case 42: game.reset(); dirty=true; save(); break;
  }
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
            calibrating=false; show(Page::Home); Serial.println("CALIBRATION OK");
          } else {calibrationStep=0; drawCalibration("再来一次，轻点十字中心");}
        }
      }
      samples=0; sampleX=sampleY=0;
    }
    return;
  }
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
  if(!Serial.available()) return;
  char c=Serial.read();
  if(c=='h') Serial.println("MATH ADVENTURE LOCAL PLAYTEST. h help; 0 home; 1 map; a/b/c choose; n continue; t hint; s skip; r retry; C calibrate; x reset playtest; d raw touch diagnostics. No real rewards/API.");
  if(c=='0') {calibrating=false; show(Page::Home);}
  if(c=='1') {calibrating=false;show(Page::Map);}
  if(c=='C') beginCalibration();
  if(c=='d') {uint16_t x,y;lcd.getTouchRaw(&x,&y);Serial.printf("RAW x=%u y=%u pressure=%u\n",x,y,lcd.getTouchRawZ());}
  if(calibrating) return;
  if(c=='x') action(42);
  if(c=='a'||c=='b'||c=='c') {
    int i=c-'a';
    if(game.page==Page::Map) action(10+i);
    else if(game.page==Page::Question) action(20+i);
  }
  if(c=='t' && game.page==Page::Question) action(23);
  if(c=='s' && game.page==Page::Question) action(24);
  if(c=='r' && (game.page==Page::Hint||game.page==Page::Retry)) action(25);
  if(c=='n') {
    if(game.page==Page::Home) action(1);
    else if(game.page==Page::Win) action(26);
  }
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
  if(prefs.getBytesLength("progress")==sizeof(game.state)) {
    adventure::Saved saved;
    prefs.getBytes("progress",&saved,sizeof(saved));
    if(adventure::valid(saved)) game.state=saved;
  }
  touchEnabled=true;
  if(prefs.getBytesLength("calibration")==sizeof(calibration)) prefs.getBytes("calibration",&calibration,sizeof(calibration));
  if(!calibration.valid()) beginCalibration();
  else draw();
  Serial.println("MATH ADVENTURE v0.2.2 PLAYTEST READY; h=help. No sensor uploads, network or real rewards.");
}
void loop() {
  serialControl();readTouch();
  if(dirty && !calibrating) draw();
  if(!calibrating && game.page==Page::Home) {
    uint32_t phase=millis()%4000;
    bool blink=phase>3700;
    if(blink!=(lastBlink!=0)) {face(61,112,true,blink);lastBlink=blink?1:0;}
  }
  delay(5);
}
