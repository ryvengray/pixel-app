#pragma once
#include <Adafruit_GFX.h>
#include <U8g2_for_Adafruit_GFX.h>
#include <GxEPD2_7C.h>
#include <qrcode.h>
#include "lunar.h"

class Frame:public Adafruit_GFX {
public:
  uint8_t *pixels;
  U8G2_FOR_ADAFRUIT_GFX font;
  Frame():Adafruit_GFX(800,480),pixels((uint8_t*)ps_malloc(standalone::BITMAP_BYTES)) {
    if(pixels) memset(pixels,0,standalone::BITMAP_BYTES);
    font.begin(*this); font.setFont(u8g2_font_wqy16_t_gb2312);
    // setFont() resets U8g2 to opaque mode. Draw only the glyph strokes so
    // white text retains the blue selection instead of filling white boxes.
    font.setFontMode(1); font.setForegroundColor(1); font.setBackgroundColor(0);
  }
  ~Frame() { free(pixels); }
  void drawPixel(int16_t x,int16_t y,uint16_t color) override {
    if(!pixels || x<0 || x>=800 || y<0 || y>=480) return;
    int p=y*800+x; auto &b=pixels[p/2]; if(p&1) b=(b&0xf0)|(color&15); else b=(b&15)|(color<<4);
  }
  void text(int x,int y,const String &s,int color=1) { font.setForegroundColor(color); font.setCursor(x,y); font.print(s); }
  void number(int x,int y,int n,int size,int color=1) { setTextColor(color); setTextSize(size); setCursor(x,y); print(n); }
};
static Frame *qrFrame=nullptr;
static int qrX=standalone::UPLOAD_QR_X,qrY=standalone::UPLOAD_QR_Y,qrBox=standalone::UPLOAD_QR_SIZE;
static void qrDraw(esp_qrcode_handle_t qr) {
  int n=esp_qrcode_get_size(qr),scale=qrBox/(n+8),x0=qrX+(qrBox-n*scale)/2,y0=qrY+(qrBox-n*scale)/2;
  qrFrame->fillRect(qrX,qrY,qrBox,qrBox,0);
  for(int y=0;y<n;y++) for(int x=0;x<n;x++) if(esp_qrcode_get_module(qr,x,y)) qrFrame->fillRect(x0+x*scale,y0+y*scale,scale,scale,1);
}
inline bool drawQr(Frame &frame,const String &value,int x=standalone::UPLOAD_QR_X,int y=standalone::UPLOAD_QR_Y,int box=standalone::UPLOAD_QR_SIZE) {
  qrFrame=&frame; qrX=x; qrY=y; qrBox=box;
  esp_qrcode_config_t cfg=ESP_QRCODE_CONFIG_DEFAULT(); cfg.display_func=qrDraw; cfg.max_qrcode_version=8; cfg.qrcode_ecc_level=ESP_QRCODE_ECC_MED;
  return esp_qrcode_generate(&cfg,value.c_str())==ESP_OK;
}
inline void statusBar(Frame &f,int percent,bool serviceRunning) {
  // White backing keeps the icons readable over photographs as well.
  f.fillRoundRect(682,8,110,32,5,0);
  // A single status dot, distinct by fill as well as color.
  if(serviceRunning) f.fillCircle(692,25,4,4);
  else f.drawCircle(692,25,4,1);
  int color=percent<10 ? 2 : percent<20 ? 3:1;
  f.drawRoundRect(706,16,34,18,3,color);
  f.fillRect(741,21,3,8,color);
  int width=std::max(0,std::min(30,(percent*30+99)/100));
  if(width) f.fillRect(708,18,width,14,color);
  f.setTextColor(1); f.setTextSize(1); f.setCursor(750,21); f.print(percent); f.print('%');
}
inline void landscape(Frame &f) {
  f.fillCircle(205,225,25,2);
  f.fillTriangle(24,302,93,246,175,302,5); f.fillTriangle(104,302,177,266,306,302,5);
  f.fillTriangle(16,315,67,280,212,315,1); f.fillRect(16,313,290,32,5);
  for(int y=320;y<345;y+=8) f.drawFastHLine(16,y,290,0);
  for(int y=318;y<344;y+=7) f.fillRect(197+(y-318)/2,y,40-(y-318),3,3);
  f.drawLine(253,225,261,229,1); f.drawLine(261,229,270,224,1);
  f.drawLine(270,248,276,251,1); f.drawLine(276,251,282,247,1);
}
inline void calendarFrame(Frame &f,int year,int month,int today) {
  auto now=standalone::fromDay(today); auto lunar=standalone::lunarFor(today);
  char label[48]; snprintf(label,sizeof(label),"%04d / %02d",now.year,now.month); f.text(24,38,label);
  f.number(22,56,now.day,12);
  static const char *week[]={"一","二","三","四","五","六","日"};
  f.text(26,177,String("星期")+week[standalone::mondayColumn(today)]);
  f.text(26,204,String("农历 ")+(lunar.leap ? "闰":"")+standalone::lunarMonth(lunar.month)+standalone::lunarDay(lunar.day));
  landscape(f);
  f.drawFastVLine(324,22,436,1);
  snprintf(label,sizeof(label),"%d 年 %d 月",year,month); f.text(348,41,label);
  for(int c=0;c<7;c++) f.text(363+c*61,82,week[c],c>=5 ? 2:1);
  int first=standalone::civilDay(year,month,1),start=first-standalone::mondayColumn(first),rows=standalone::monthRows(year,month);
  int height=350/rows;
  for(int i=0;i<rows*7;i++) {
    int day=start+i,x=344+(i%7)*61,y=99+(i/7)*height; auto d=standalone::fromDay(day); auto l=standalone::lunarFor(day);
    bool current=day==today; int color=current ? 0 : d.month!=month ? 4 : (i%7>=5 ? 2:1);
    if(current) f.fillRoundRect(x,y,58,height-4,5,5);
    f.number(x+8,y+5,d.day,2,color);
    String sub=standalone::festival(day);
    if(!sub.length()) sub=l.day==1 ? String(l.leap ? "闰":"")+standalone::lunarMonth(l.month) : standalone::lunarDay(l.day);
    f.text(x+3,y+height-13,sub,current ? 0 : standalone::festival(day)[0] ? 2:color);
  }
}

struct RenderJob { uint8_t *pixels; uint32_t signature,photo; };
struct RenderDone { uint32_t signature,photo; };
class Screen {
  SPIClass spi=SPIClass(HSPI);
  GxEPD2_7C<GxEPD2_730c_GDEP073E01,40> display=GxEPD2_7C<GxEPD2_730c_GDEP073E01,40>(GxEPD2_730c_GDEP073E01(10,11,12,13));
  QueueHandle_t jobs=xQueueCreate(1,sizeof(RenderJob));
  QueueHandle_t done=xQueueCreate(1,sizeof(RenderDone));
  SemaphoreHandle_t snapshotMutex=xSemaphoreCreateMutex();
  uint8_t *displayedPixels=nullptr;
  volatile bool active=false;
  uint32_t lastQueued=0,desiredPhoto=0;
  static void run(void *self) { static_cast<Screen*>(self)->worker(); }
  void retain(uint8_t *pixels) {
    xSemaphoreTake(snapshotMutex,portMAX_DELAY);
    free(displayedPixels); displayedPixels=pixels;
    xSemaphoreGive(snapshotMutex);
  }
  void worker() {
    const uint16_t colors[]={GxEPD_WHITE,GxEPD_BLACK,GxEPD_RED,GxEPD_YELLOW,GxEPD_GREEN,GxEPD_BLUE};
    while(true) {
      RenderJob job; xQueuePeek(jobs,&job,portMAX_DELAY); active=true; xQueueReceive(jobs,&job,portMAX_DELAY);
      Serial.printf("[standalone] display start %08lx\n",(unsigned long)job.signature);
      display.setRotation(0); display.setFullWindow(); display.firstPage(); int page=0;
      do {
        for(int y=page*40;y<std::min((page+1)*40,480);y++) {
          for(int x=0;x<800;x++) { auto p=job.pixels[(y*800+x)/2]; display.drawPixel(x,y,colors[(x&1) ? p&15 : p>>4]); }
          taskYIELD();
        }
        ++page;
      } while(display.nextPage());
      display.hibernate(); retain(job.pixels);
      Serial.println("[standalone] display complete");
      RenderDone result={job.signature,job.photo}; xQueueOverwrite(done,&result); active=false;
    }
  }
public:
  // USB-only diagnostics: export the exact completed frame, rather than a
  // browser mockup. Four-bit BMP uses the same six palette indices as the panel.
  bool writeSnapshot(Stream &out) {
    xSemaphoreTake(snapshotMutex,portMAX_DELAY);
    if(!displayedPixels) { xSemaphoreGive(snapshotMutex); return false; }
    uint8_t header[118]={}; header[0]='B'; header[1]='M';
    auto put16=[&](int offset,uint16_t value) { header[offset]=value; header[offset+1]=value>>8; };
    auto put32=[&](int offset,uint32_t value) { for(int b=0;b<4;b++) header[offset+b]=value>>(b*8); };
    put32(2,sizeof(header)+standalone::BITMAP_BYTES); put32(10,sizeof(header)); put32(14,40);
    put32(18,800); put32(22,uint32_t(-480)); put16(26,1); put16(28,4);
    put32(34,standalone::BITMAP_BYTES); put32(46,16);
    const uint8_t rgb[6][3]={{255,255,255},{20,20,18},{172,40,45},{237,198,60},{50,115,74},{32,67,139}};
    for(int i=0;i<16;i++) for(int c=0;c<3;c++) header[54+i*4+c]=rgb[i<6 ? i:0][2-c];
    char line[64]; snprintf(line,sizeof(line),"E1002BMP %u %08lx\n",unsigned(sizeof(header)+standalone::BITMAP_BYTES),(unsigned long)standalone::crc32(displayedPixels,standalone::BITMAP_BYTES));
    out.print(line); out.write(header,sizeof(header)); out.write(displayedPixels,standalone::BITMAP_BYTES); out.println();
    xSemaphoreGive(snapshotMutex); return true;
  }
  bool begin(uint32_t retained) {
    lastQueued=retained;
    spi.begin(7,-1,9,-1); display.epd2.selectSPI(spi,SPISettings(2000000,MSBFIRST,SPI_MODE0)); display.init(115200);
    return xTaskCreatePinnedToCore(run,"paper",8192,this,1,nullptr,0)==pdPASS;
  }
  bool busy() { return active || uxQueueMessagesWaiting(jobs)>0; }
  bool poll(RenderDone &result) {
    if(xQueueReceive(done,&result,0)!=pdTRUE) return false;
    if(result.signature==lastQueued) result.photo=desiredPhoto;
    return true;
  }
  void submit(Frame &frame,uint32_t photo=0) {
    uint32_t signature=standalone::crc32(frame.pixels,standalone::BITMAP_BYTES);
    desiredPhoto=photo;
    if(signature==lastQueued) {
      // Identical pixels can belong to a newly saved album record. Acknowledge
      // the logical photo without making the user wait for a needless full refresh.
      if(!busy()) {
        retain(frame.pixels); frame.pixels=nullptr;
        RenderDone result={signature,photo}; xQueueOverwrite(done,&result);
      }
      return;
    }
    RenderJob job={frame.pixels,signature,photo}; frame.pixels=nullptr;
    // Only main loop writes. Keep at most the final requested page during a full refresh.
    RenderJob old; if(xQueueReceive(jobs,&old,0)==pdTRUE) free(old.pixels);
    xQueueSend(jobs,&job,portMAX_DELAY); lastQueued=signature;
  }
};
