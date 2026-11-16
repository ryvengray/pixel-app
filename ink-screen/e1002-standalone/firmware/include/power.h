#pragma once
// Reuse the existing E1002 LiPo voltage curve and its calibrated band helpers.
#include "../../../pixel-display-e1002/firmware/include/battery.h"

inline int readBatteryPercent() {
  digitalWrite(21,HIGH);
  delay(10);
  uint32_t total=0;
  for(int i=0;i<16;i++) { total+=analogReadMilliVolts(1); delay(2); }
  digitalWrite(21,LOW);
  return batteryPercent((total*2+8)/16);
}
