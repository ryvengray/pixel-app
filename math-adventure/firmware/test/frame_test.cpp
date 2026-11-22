#include <assert.h>
#include "../src/frame_diff.h"
using namespace adventure;
int main(){
 uint8_t frame[320*240]={};uint32_t previous[300];
 auto pixel=[&](int x,int y){return frame[y*320+x];};
 for(int y=0;y<15;++y)for(int x=0;x<20;++x)previous[y*20+x]=tileHash(pixel,x,y);
 // Repainting identical content does not send any region to the screen.
 for(int y=0;y<15;++y)for(int x=0;x<20;++x)assert(previous[y*20+x]==tileHash(pixel,x,y));
 frame[20*320+250]=255;
 int changed=0;
 for(int y=0;y<15;++y)for(int x=0;x<20;++x)if(previous[y*20+x]!=tileHash(pixel,x,y)){++changed;assert(x==15&&y==1);}
 assert(changed==1);
 assert(!configuredNetwork(false,true,true,true));
 assert(!configuredNetwork(true,false,true,true));
 assert(!configuredNetwork(true,true,false,true));
 assert(!configuredNetwork(true,true,true,false));
 assert(configuredNetwork(true,true,true,true));
}
