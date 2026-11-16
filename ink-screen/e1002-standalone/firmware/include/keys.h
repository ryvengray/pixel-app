#pragma once
#include "core.h"
// Debounced events are generated on release; a chord suppresses both singles.
// This pure state machine also handles a key already held when deep sleep ends.
namespace standalone {
enum class KeyEvent { None,Refresh,OpenUpload,Previous,Next,Settings };
class Keys {
  bool raw[3]={},stable[3]={},longRefresh=false,chord=false,chordSent=false;
  uint32_t changed[3]={},pressed[3]={},chordAt=0;
public:
  // EXT1 already latched the press, even if released before the polling task
  // starts. Seed it as debounced so that release produces exactly one event.
  void resumeFromWake(uint32_t now,uint8_t mask) {
    for(int i=0;i<3;i++) {
      raw[i]=stable[i]=(mask&(1U<<i))!=0;
      changed[i]=pressed[i]=now;
    }
    longRefresh=chord=chordSent=false;
  }
  KeyEvent update(uint32_t now,bool refresh,bool next,bool previous) {
    bool values[]={refresh,next,previous}; KeyEvent event=KeyEvent::None;
    bool released[3]={};
    for(int i=0;i<3;i++) {
      if(raw[i]!=values[i]) { raw[i]=values[i]; changed[i]=now; }
      if(raw[i]!=stable[i] && elapsed(now,changed[i],30)) {
        stable[i]=raw[i]; if(stable[i]) { pressed[i]=now; if(i==0) longRefresh=false; } else released[i]=true;
      }
    }
    if(stable[1] && stable[2] && !chord) { chord=true; chordSent=false; chordAt=now; }
    if(chord) {
      if(stable[1] && stable[2] && !chordSent && elapsed(now,chordAt,1500)) { chordSent=true; event=KeyEvent::Settings; }
      if(!stable[1] && !stable[2]) { chord=false; chordSent=false; }
      // Both releases belong to the chord even when released at different times.
      released[1]=released[2]=false;
    }
    if(stable[0] && !longRefresh && !chord && elapsed(now,pressed[0],1500)) { longRefresh=true; event=KeyEvent::OpenUpload; }
    if(event==KeyEvent::None) {
      if(released[0] && !longRefresh) event=KeyEvent::Refresh;
      else if(released[1]) event=KeyEvent::Next;
      else if(released[2]) event=KeyEvent::Previous;
    }
    return event;
  }
};
}
