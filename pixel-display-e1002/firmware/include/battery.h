#pragma once

// Voltage-based estimate for a single LiPo cell, not a fuel-gauge reading.
// Linear interpolation between approximate resting-voltage points.
inline int batteryPercent(unsigned int mv) {
  const unsigned int volts[]={3300,3500,3600,3700,3750,3790,3830,3870,3920,4000,4200};
  const int percent[]={0,5,10,20,30,40,50,60,70,80,100};
  if(mv<=volts[0]) return 0;
  for(int i=1;i<11;++i) {
    if(mv<=volts[i]) return percent[i-1]+(mv-volts[i-1])*(percent[i]-percent[i-1])/(volts[i]-volts[i-1]);
  }
  return 100;
}
inline int batteryFillWidth(int percent) {
  return percent<=0 ? 0 : (percent*40+99)/100;
}
inline int batteryBand(int percent) { return percent<10 ? 2 : percent<20 ? 1 : 0; }


// SY6974B: REG08 charge state [4:3], power-good [2]; REG0A bus-good [7].
// Never infer external power from the battery's voltage estimate.
enum class PowerStatus { Unknown, Battery, Connected, Charging, Full };
inline PowerStatus decodePowerStatus(unsigned char status, unsigned char input) {
  if(!(input & 0x80)) return PowerStatus::Battery;
  if(!(status & 0x04)) return PowerStatus::Connected;
  int charge=(status>>3)&3;
  if(charge==1 || charge==2) return PowerStatus::Charging;
  return charge==3 ? PowerStatus::Full : PowerStatus::Connected;
}
