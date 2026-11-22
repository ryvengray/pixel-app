#pragma once
#include <stdint.h>
namespace adventure {
struct TouchUpdate {
  int highlight = -1;
  int restore = -1;
  int activate = -1;
};
// A gesture may only activate the button where it began. Leaving cancels it.
class TouchGesture {
 public:
  TouchUpdate update(bool down, int target, uint32_t now) {
    TouchUpdate result;
    if (down) {
      if (!held) {
        held = true;
        pressed = target;
        started = now;
        result.highlight = pressed;
      } else if (pressed >= 0 && target != pressed) {
        result.restore = pressed;
        pressed = -1;
      }
    } else if (held) {
      held = false;
      result.restore = pressed;
      const uint32_t duration = now - started;
      if (pressed >= 0 && duration >= 40 && duration < 2500) result.activate = pressed;
      pressed = -1;
    }
    return result;
  }
 private:
  bool held = false;
  int pressed = -1;
  uint32_t started = 0;
};
}
