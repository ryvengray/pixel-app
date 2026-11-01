#include <cassert>
#include "battery.h"
int main() {
  assert(batteryPercent(0)==0);
  assert(batteryPercent(3300)==0);
  assert(batteryPercent(4200)==100);
  assert(batteryPercent(5000)==100);
  assert(batteryPercent(3600)==10);
  assert(batteryPercent(3700)==20);
  assert(batteryBand(9)==2);
  assert(batteryBand(10)==1);
  assert(batteryBand(19)==1);
  assert(batteryBand(20)==0);
  int previous=0;
  for(unsigned int mv=0;mv<=5000;++mv) {
    int percent=batteryPercent(mv);
    assert(percent>=previous && percent<=100);
    assert(batteryFillWidth(percent)>=0 && batteryFillWidth(percent)<=40);
    previous=percent;
  }
  assert(batteryFillWidth(1)>0);
  assert(batteryFillWidth(100)==40);
}
