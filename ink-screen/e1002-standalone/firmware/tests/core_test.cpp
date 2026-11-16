#include <assert.h>
#include <vector>
#include <iostream>
#include "core.h"
#include "lunar.h"
#include "keys.h"
using namespace standalone;
void lunar(int y,int m,int d,int ly,int lm,int ld,bool leap=false) {
  auto l=lunarFor(civilDay(y,m,d));
  assert(l.year==ly && l.month==lm && l.day==ld && l.leap==leap);
}
int main() {
  assert(civilDay(1970,1,1)==0);
  for(int y=2000;y<=2099;y++) for(int m=1;m<=12;m++) {
    int cells=monthRows(y,m)*7,start=civilDay(y,m,1)-mondayColumn(civilDay(y,m,1));
    assert(cells>=monthDays(y,m)); assert(start<=civilDay(y,m,1));
    assert(start+cells>civilDay(y,m,monthDays(y,m)));
    for(int d=1;d<=monthDays(y,m);d++) {
      auto date=fromDay(civilDay(y,m,d)); assert(date.year==y && date.month==m && date.day==d);
      auto l=lunarFor(civilDay(y,m,d)); assert(l.month>=1 && l.month<=12 && l.day>=1 && l.day<=30);
      auto next=lunarFor(civilDay(y,m,d)+1);
      assert(next.day==l.day+1 || next.day==1);
    }
  }
  assert(monthDays(2024,2)==29 && monthDays(2100,2)==28);
  assert(monthRows(2026,3)==6);
  // The daily timer uses local midnight, including fractional-hour timezones.
  int64_t day=int64_t(civilDay(2026,11,16))*86400;
  assert(secondsUntilLocalMidnight(day+16*3600-1,480)==1);
  assert(secondsUntilLocalMidnight(day+16*3600,480)==86400);
  assert(secondsUntilLocalMidnight(day+16*3600+1,480)==86399);
  assert(secondsUntilLocalMidnight(day+18*3600+15*60-1,345)==1);
  assert(secondsUntilLocalMidnight(day+12*3600-1,-720)==1);
  assert(secondsUntilLocalMidnight(day+10*3600-1,840)==1);
  int trackedYear=2026,trackedMonth=12;
  followCurrentMonth(trackedYear,trackedMonth,civilDay(2026,12,31),civilDay(2027,1,1));
  assert(trackedYear==2027 && trackedMonth==1);
  trackedYear=2026;trackedMonth=10;
  followCurrentMonth(trackedYear,trackedMonth,civilDay(2026,12,31),civilDay(2027,1,1));
  assert(trackedYear==2026 && trackedMonth==10); // preserve deliberately browsed month
  assert(UPLOAD_QR_X==0 && UPLOAD_QR_Y+UPLOAD_QR_SIZE==HEIGHT);
  int y=2026,m=1; shiftMonth(y,m,-1); assert(y==2025 && m==12); shiftMonth(y,m,1); assert(y==2026 && m==1);
  y=2000;m=1;shiftMonth(y,m,-1);assert(y==2000&&m==1);
  y=2099;m=12;shiftMonth(y,m,1);assert(y==2099&&m==12);
  // HKO Gregorian-lunar conversion tables, including leap months and CNY eve.
  lunar(2024,2,10,2024,1,1); lunar(2025,1,29,2025,1,1);
  lunar(2023,3,22,2023,2,1,true); lunar(2025,7,25,2025,6,1,true);
  lunar(2033,12,22,2033,11,1,true);
  assert(!strcmp(festival(civilDay(2025,1,28)),"除夕"));
  assert(!strcmp(festival(civilDay(2025,1,29)),"春节"));
  assert(!strcmp(festival(civilDay(2023,3,22)),""));
  assert(crc32((const uint8_t*)"123456789",9)==0xcbf43926);
  std::vector<uint8_t> image(BITMAP_BYTES,0x05); assert(validPixels(image.data(),image.size()));
  image[0]=0x60;assert(!validPixels(image.data(),image.size()));image[0]=0x06;assert(!validPixels(image.data(),image.size()));
  assert(!validPixels(image.data(),image.size()-1));
  assert(elapsed(100,0xffffff00,300)); assert(!elapsed(100,90,300));
  assert(!elapsed(100,101,300)); // callback may update the timestamp during the loop
  Keys k; auto tick=[&](int t,bool r,bool n,bool p){return k.update(t,r,n,p);};
  tick(0,true,false,false); tick(31,true,false,false); assert(tick(1531,true,false,false)==KeyEvent::OpenUpload);
  tick(1540,false,false,false);assert(tick(1571,false,false,false)==KeyEvent::None); // no short after long
  tick(1600,true,false,false);tick(1631,true,false,false);tick(1700,false,false,false);assert(tick(1731,false,false,false)==KeyEvent::Refresh);
  tick(2000,false,true,false);tick(2031,false,true,false);tick(2100,false,true,true);tick(2131,false,true,true);
  assert(tick(3631,false,true,true)==KeyEvent::Settings); assert(tick(4000,false,true,true)==KeyEvent::None);
  tick(4100,false,false,true);assert(tick(4131,false,false,true)==KeyEvent::None);
  tick(4200,false,false,false);assert(tick(4231,false,false,false)==KeyEvent::None);
  tick(4300,false,true,false);tick(4331,false,true,false);tick(4400,false,false,false);assert(tick(4431,false,false,false)==KeyEvent::Next);
  // An aborted chord must not turn into two page changes.
  tick(4500,false,true,true);tick(4531,false,true,true);tick(4600,false,false,false);assert(tick(4631,false,false,false)==KeyEvent::None);
  // Previous press released before the key task's first poll, or in the
  // interval between startup sampling and the first poll: never lose it.
  Keys wakePrevious; wakePrevious.resumeFromWake(100,4);
  assert(wakePrevious.update(101,false,false,false)==KeyEvent::None);
  assert(wakePrevious.update(132,false,false,false)==KeyEvent::Previous);
  assert(wakePrevious.update(200,false,false,false)==KeyEvent::None);
  // A held wake press must not produce both synthetic and ordinary events.
  Keys heldPrevious; heldPrevious.resumeFromWake(100,4);
  assert(heldPrevious.update(200,false,false,true)==KeyEvent::None);
  assert(heldPrevious.update(300,false,false,false)==KeyEvent::None);
  assert(heldPrevious.update(331,false,false,false)==KeyEvent::Previous);
  assert(heldPrevious.update(400,false,false,false)==KeyEvent::None);
  Keys wakeRefresh; wakeRefresh.resumeFromWake(100,1);
  assert(wakeRefresh.update(1600,true,false,false)==KeyEvent::OpenUpload);
  wakeRefresh.update(1700,false,false,false);
  assert(wakeRefresh.update(1731,false,false,false)==KeyEvent::None);
  Keys wakeChord; wakeChord.resumeFromWake(100,6);
  wakeChord.update(100,false,true,true);
  assert(wakeChord.update(1600,false,true,true)==KeyEvent::Settings);
  wakeChord.update(1700,false,false,false);
  assert(wakeChord.update(1731,false,false,false)==KeyEvent::None);
  std::cout<<"calendar, lunar, bitmap, CRC, rollover and button tests passed\n";
}
