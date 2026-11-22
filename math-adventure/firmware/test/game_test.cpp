#include <assert.h>
#include "../src/adventure.h"
#include "../src/calibration.h"
using namespace adventure;
int main() {
  Calibration cal;
  assert(!cal.valid());
  float rx[] = {3600,3600,400}, ry[] = {400,3600,400}; // swapped + inverted axes
  assert(cal.fit(rx,ry));
  int x,y;cal.map(400,3600,x,y);assert(x==296 && y==212);
  cal.map(2000,2000,x,y);assert(x==160 && y==130);
  float flat[]={1,2,3};assert(!cal.fit(flat,flat));
  cal.ax=NAN;assert(!cal.valid());
  Game g;
  assert(valid(g.state));
  assert(!g.answer(1)); // Ignore input outside answer page.
  g.select(0); assert(!g.answer(0)); assert(g.solved()==0);
  g.page=Page::Question;g.hint(); assert(g.state.hints==1);
  g.page=Page::Question;assert(g.answer(1));assert(g.solved()==1);
  g.select(0);assert(g.page==Page::Win);assert(!g.answer(1));assert(g.solved()==1);
  g.select(1);g.skip();assert(g.skipped()==1);assert(!g.finished());
  g.select(2);assert(g.answer(0));assert(g.finished());assert(g.skipped()==1);
  g.select(1);assert(g.answer(2));assert(g.solved()==3);assert(g.skipped()==0);
  Saved restored=g.state;assert(valid(restored));g.state=restored;assert(g.finished());
  restored.solved=128;assert(!valid(restored));restored=g.state;restored.current=3;assert(!valid(restored));
  g.select(255);assert(g.state.current==1);
  g.reset();assert(valid(g.state));assert(!g.finished());assert(g.solved()==0);
}
