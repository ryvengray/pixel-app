#include <assert.h>
#include "../src/ui_controls.h"
using namespace adventure;
int main(){
 ButtonRect buttons[16];uint8_t count=0;
 for(int i=0;i<9;++i)assert(registerButton(buttons,count,{8+(i%3)*80,40+(i/3)*48,72,44,150+i,true}));
 assert(registerButton(buttons,count,{88,188,152,44,159,true}));
 assert(registerButton(buttons,count,{248,40,64,44,104,false}));
 assert(registerButton(buttons,count,{248,88,64,44,160,false}));
 assert(registerButton(buttons,count,{248,136,64,44,161,true}));
 assert(count==13);
 for(int i=0;i<count;++i){const auto &b=buttons[i];int hit=hitButton(buttons,count,b.x+b.w/2,b.y+b.h/2);assert(hit==i);}
 assert(buttons[hitButton(buttons,count,280,62)].action==104);
 assert(buttons[hitButton(buttons,count,280,110)].action==160);
 assert(buttons[hitButton(buttons,count,280,158)].action==161);
 assert(hitButton(buttons,count,244,62)==-1);
 assert(!pairingNetworkReady(false,true,0,100));
 assert(!pairingNetworkReady(true,false,0,100));
 assert(!pairingNetworkReady(true,true,0,30000));
 assert(pairingNetworkReady(true,true,0,29999));
 assert(pairingNetworkReady(true,true,0xfffffff0u,60));
}
