#pragma once
#include <Wire.h>
#include <sys/time.h>
#include <esp_timer.h>
#include "core.h"
// PCF8563 stores UTC, so changing the display offset never changes RTC data.
class RtcClock {
  int64_t anchor=0,anchorMicros=0;
  void sync(int64_t epoch) { anchor=epoch; anchorMicros=esp_timer_get_time(); }
  int dec(uint8_t b) { return (b>>4)*10+(b&15); }
  uint8_t bcd(int n) { return (n/10)*16+n%10; }
  bool write(uint8_t reg,uint8_t value) {
    Wire.beginTransmission(0x51); Wire.write(reg); Wire.write(value); return Wire.endTransmission()==0;
  }
public:
  bool valid() const { return anchor>0; }
  int64_t now() const { return anchor ? anchor+(esp_timer_get_time()-anchorMicros)/1000000 : 0; }
  bool begin() {
    Wire.begin(19,20); Wire.setTimeOut(100);
    return write(0,0) && write(1,0) && write(0x0d,0);
  }
  bool restore() {
    Wire.beginTransmission(0x51); Wire.write(2);
    if(Wire.endTransmission(false) || Wire.requestFrom(uint8_t(0x51),uint8_t(7))!=7) return false;
    uint8_t b[7]; for(auto &v:b) v=Wire.read();
    if((b[0]&0x80) || (b[5]&0x80)) return false;
    int y=2000+dec(b[6]),m=dec(b[5]&31),d=dec(b[3]&63),hh=dec(b[2]&63),mm=dec(b[1]&127),ss=dec(b[0]&127);
    if(m<1 || m>12 || d<1 || d>standalone::monthDays(y,m) || hh>23 || mm>59 || ss>59) return false;
    int64_t epoch=int64_t(standalone::civilDay(y,m,d))*86400+hh*3600+mm*60+ss;
    sync(epoch); return true;
  }
  bool set(int64_t epoch) {
    if(epoch<1577836800LL || epoch>=4102444800LL) return false;
    auto d=standalone::fromDay(epoch/86400);
    Wire.beginTransmission(0x51); Wire.write(2);
    Wire.write(bcd(epoch%60)); Wire.write(bcd(epoch/60%60)); Wire.write(bcd(epoch/3600%24));
    Wire.write(bcd(d.day)); Wire.write(uint8_t((epoch/86400+4)%7)); Wire.write(bcd(d.month)); Wire.write(bcd(d.year-2000));
    bool ok=Wire.endTransmission()==0;
    sync(epoch); return ok;
  }
};
