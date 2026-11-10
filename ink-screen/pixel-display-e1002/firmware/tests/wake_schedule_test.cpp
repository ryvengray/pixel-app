#include <cassert>
#include "wake_schedule.h"
int main() {
  const int times[]={390,570,750,1110,1350};
  // 2026-11-02 00:00 UTC = 08:00 Shanghai.
  const int64_t day=1793577600;
  assert(secondsUntilWake(day,times,5)==90*60);
  assert(secondsUntilWake(day-90*60-1,times,5)==1);
  assert(secondsUntilWake(day-90*60,times,5)==3*3600);
  assert(secondsUntilWake(day+870*60,times,5)==8*3600);
  assert(secondsUntilWake(day+16*3600,times,5)==390*60);
  const int changed[]={480,600};
  assert(secondsUntilWake(day,changed,2)==2*3600);
  const int midnight[]={0};
  assert(secondsUntilWake(day+16*3600,midnight,1)==86400);
}
