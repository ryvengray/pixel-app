#pragma once
#include <stdint.h>
namespace adventure {
template<class PixelReader> uint32_t tileHash(PixelReader pixel,int tx,int ty){
 uint32_t hash=2166136261u;
 for(int y=ty*16;y<(ty+1)*16;++y)for(int x=tx*16;x<(tx+1)*16;++x){hash^=pixel(x,y);hash*=16777619u;}
 return hash;
}
inline bool configuredNetwork(bool saved,bool connected,bool matches,bool address){return saved&&connected&&matches&&address;}
}
