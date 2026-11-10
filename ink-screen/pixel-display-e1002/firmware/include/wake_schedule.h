#pragma once
#include <stdint.h>
// Fixed UTC+8 daily schedule. Strictly future: a completed slot is not repeated.
inline uint64_t secondsUntilWake(int64_t epoch, const int *minutes, int count) {
  int64_t local=epoch+8*3600;
  int64_t midnight=local-local%86400;
  int64_t best=86400;
  for(int i=0;i<count;++i) {
    int64_t delta=midnight+minutes[i]*60-local;
    if(delta<=0) delta+=86400;
    if(delta<best) best=delta;
  }
  return best;
}
