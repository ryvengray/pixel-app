#pragma once
#include <stddef.h>
#include <stdint.h>
namespace adventure {
struct ButtonRect { int x,y,w,h,action;bool primary; };
template<size_t N> bool registerButton(ButtonRect (&buttons)[N],uint8_t &count,ButtonRect button) {
  if(count>=N)return false;
  buttons[count++]=button;return true;
}
template<size_t N> int hitButton(const ButtonRect (&buttons)[N],uint8_t count,int x,int y) {
  for(size_t i=0;i<count&&i<N;++i){const auto &b=buttons[i];if(x>=b.x&&x<b.x+b.w&&y>=b.y&&y<b.y+b.h)return int(i);}
  return -1;
}
inline bool pairingNetworkReady(bool wifi,bool backend,uint32_t checked,uint32_t now) {
  return wifi&&backend&&uint32_t(now-checked)<30000;
}
}
