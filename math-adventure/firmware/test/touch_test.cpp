#include <assert.h>
#include "../src/touch_gesture.h"
using namespace adventure;
void unchanged(TouchUpdate u) { assert(u.highlight==-1 && u.restore==-1 && u.activate==-1); }
int main() {
  TouchGesture g;
  unchanged(g.update(false,-1,0));
  unchanged(g.update(true,-1,100)); // Blank taps never request repaint or activation.
  unchanged(g.update(false,-1,200));
  assert(g.update(true,0,300).highlight==0);
  unchanged(g.update(true,0,350));
  auto release=g.update(false,-1,400);
  assert(release.restore==0 && release.activate==0);
  assert(g.update(true,1,500).highlight==1);
  auto cancel=g.update(true,-1,550);
  assert(cancel.restore==1 && cancel.activate==-1);
  unchanged(g.update(true,1,600)); // Reentering does not reactivate.
  unchanged(g.update(false,-1,650));
  g.update(true,2,700);
  release=g.update(false,-1,710);
  assert(release.restore==2 && release.activate==-1); // Noise pulse.
  g.update(true,2,800);
  release=g.update(false,-1,3400);
  assert(release.restore==2 && release.activate==-1); // Long press.
  g.update(true,0,0xfffffff0u);
  assert(g.update(false,-1,60).activate==0); // millis() wrap.
}
