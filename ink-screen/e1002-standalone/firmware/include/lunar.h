#pragma once
#include "core.h"
#include "lunar_data.h"
namespace standalone {
struct Lunar {
  int year,month,day; bool leap;
  Lunar(int y=0,int m=0,int d=0,bool l=false):year(y),month(m),day(d),leap(l) {}
};
inline Lunar lunarFor(int day) {
  const size_t count=sizeof(LUNAR_STARTS)/sizeof(LUNAR_STARTS[0]);
  if(day<LUNAR_STARTS[0].day || day>=LUNAR_STARTS[count-1].day) return {};
  size_t lo=0,hi=count-1;
  while(lo+1<hi) { size_t mid=(lo+hi)/2; if(LUNAR_STARTS[mid].day<=day) lo=mid; else hi=mid; }
  const auto &s=LUNAR_STARTS[lo]; return {s.year,s.month,day-s.day+1,s.leap};
}
inline const char *lunarDay(int day) {
  static const char *names[]={"","初一","初二","初三","初四","初五","初六","初七","初八","初九","初十","十一","十二","十三","十四","十五","十六","十七","十八","十九","二十","廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"};
  return day>=1 && day<=30 ? names[day] : "";
}
inline const char *lunarMonth(int month) {
  static const char *names[]={"","正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月"};
  return month>=1 && month<=12 ? names[month] : "";
}
inline const char *festival(int day) {
  Date d=fromDay(day); Lunar l=lunarFor(day), next=lunarFor(day+1);
  if(!l.leap) {
    if(l.month==1 && l.day==1) return "春节";
    if(l.month==1 && l.day==15) return "元宵节";
    if(l.month==5 && l.day==5) return "端午节";
    if(l.month==7 && l.day==7) return "七夕";
    if(l.month==8 && l.day==15) return "中秋节";
    if(l.month==9 && l.day==9) return "重阳节";
    if(l.month==12 && l.day==8) return "腊八节";
  }
  if(next.month==1 && next.day==1 && !next.leap) return "除夕";
  if(d.month==1 && d.day==1) return "元旦";
  if(d.month==5 && d.day==1) return "劳动节";
  if(d.month==10 && d.day==1) return "国庆节";
  return "";
}
}
