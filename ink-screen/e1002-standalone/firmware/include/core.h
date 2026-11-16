#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <algorithm>

namespace standalone {
constexpr int WIDTH=800, HEIGHT=480, BITMAP_BYTES=WIDTH*HEIGHT/2;
constexpr int UPLOAD_QR_X=0,UPLOAD_QR_Y=320,UPLOAD_QR_SIZE=160;
constexpr const char *OFFLINE_QR_HELP="上传/设置：长按绿色键1.5秒，联网后重扫二维码。\n离线设置：左键+中键长按1.5秒。";
constexpr uint32_t WINDOW_MS=300000, CONNECT_MS=20000;
inline bool elapsed(uint32_t now, uint32_t since, uint32_t interval) { return int32_t(now-since)>=int32_t(interval); }
inline bool validPixels(const uint8_t *p, size_t n) {
  if(n!=BITMAP_BYTES) return false;
  for(size_t i=0;i<n;i++) if((p[i]>>4)>5 || (p[i]&15)>5) return false;
  return true;
}
inline bool leapYear(int y) { return y%4==0 && (y%100!=0 || y%400==0); }
inline int monthDays(int y,int m) {
  static const int days[]={31,28,31,30,31,30,31,31,30,31,30,31};
  return days[m-1]+(m==2 && leapYear(y));
}
// Civil day number relative to 1970-01-01; independent of libc timezone.
inline int civilDay(int y,int m,int d) {
  y-=m<=2; const int era=(y>=0 ? y : y-399)/400;
  const unsigned yy=y-era*400;
  const unsigned doy=(153*(m+(m>2 ? -3 : 9))+2)/5+d-1;
  return era*146097+yy*365+yy/4-yy/100+doy-719468;
}
struct Date { int year,month,day; };
inline Date fromDay(int z) {
  z+=719468; const int era=(z>=0 ? z : z-146096)/146097;
  const unsigned doe=z-era*146097, yoe=(doe-doe/1460+doe/36524-doe/146096)/365;
  int y=yoe+era*400; const unsigned doy=doe-(365*yoe+yoe/4-yoe/100), mp=(5*doy+2)/153;
  int d=doy-(153*mp+2)/5+1, m=mp+(mp<10 ? 3 : -9); y+=m<=2;
  return {y,m,d};
}
inline int mondayColumn(int day) { return ((day+3)%7+7)%7; }
inline int monthRows(int y,int m) { return (mondayColumn(civilDay(y,m,1))+monthDays(y,m)+6)/7; }
inline void shiftMonth(int &y,int &m,int direction) {
  int v=y*12+m-1+direction; v=std::max(2000*12,std::min(2099*12+11,v)); y=v/12; m=v%12+1;
}
inline uint64_t secondsUntilLocalMidnight(int64_t epoch,int utcOffsetMinutes) {
  int64_t local=epoch+int64_t(utcOffsetMinutes)*60;
  int64_t second=((local%86400)+86400)%86400;
  return uint64_t(86400-second);
}
// Follow the current month across midnight/month/year boundaries while
// preserving a different month the user deliberately browsed to.
inline void followCurrentMonth(int &year,int &month,int previousDay,int today) {
  if(previousDay<0 || today<0) return;
  Date before=fromDay(previousDay),after=fromDay(today);
  if(year==before.year && month==before.month) { year=after.year; month=after.month; }
}
// Fingerprint both the payload and presentation, excluding background polling.
inline uint32_t crc32(const uint8_t *p,size_t n) {
  uint32_t c=~0U;
  while(n--) { c^=*p++; for(int b=0;b<8;b++) c=(c>>1)^(0xedb88320U & (0U-(c&1))); }
  return ~c;
}
}
