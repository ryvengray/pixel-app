#pragma once
#include <stdint.h>

namespace adventure {
constexpr uint32_t kMagic = 0x4D415432;
constexpr uint8_t kCount = 3;
enum class Page : uint8_t { Home, Map, Question, Hint, Retry, Win, Summary, Settings };
struct Question {
  const char *name;
  const char *line1;
  const char *line2;
  const char *options[3];
  uint8_t answer;
  const char *hint1;
  const char *hint2;
  const char *method1;
  const char *method2;
};
const Question questions[kCount] = {
  {"凑整森林", "27 + 38 + 13", "一共有多少？", {"68", "78", "88"}, 1,
   "先找能凑成整十的数", "27 和 13 是好朋友", "27 + 13 = 40", "再加 38，就得到 78"},
  {"规律山谷", "2、5、8、11", "接下来是哪个数？", {"12", "13", "14"}, 2,
   "看看相邻的数差多少", "每次都比前一个多 3", "每次多 3", "11 + 3 = 14"},
  {"逻辑城堡", "小猫排在小兔前面", "谁排得更靠前？", {"小猫", "小兔", "一样"}, 0,
   "可以在纸上画出队伍", "先找到前后的位置", "看清前后关系", "小猫排在小兔前面"}
};
struct Saved {
  uint32_t magic = kMagic;
  uint8_t solved = 0;
  uint8_t skipped = 0;
  uint8_t hints = 0;
  uint8_t current = 0;
};
inline bool valid(const Saved &s) {
  return s.magic == kMagic && s.current < kCount &&
    ((s.solved | s.skipped | s.hints) & ~7) == 0 && !(s.solved & s.skipped);
}
class Game {
 public:
  Saved state;
  Page page = Page::Home;
  unsigned solved() const { return count(state.solved); }
  unsigned skipped() const { return count(state.skipped); }
  bool finished() const { return (state.solved | state.skipped) == 7; }
  bool isSolved(unsigned i) const { return i < kCount && (state.solved & (1 << i)); }
  const Question &question() const { return questions[state.current]; }
  void select(uint8_t i) {
    if (i >= kCount) return;
    state.current = i;
    page = isSolved(i) ? Page::Win : Page::Question;
  }
  bool answer(uint8_t choice) {
    if (page != Page::Question || choice >= 3) return false;
    bool correct = choice == question().answer;
    if (correct) {
      state.solved |= 1 << state.current;
      state.skipped &= ~(1 << state.current);
    }
    page = correct ? Page::Win : Page::Retry;
    return correct;
  }
  void hint() { state.hints |= 1 << state.current; page = Page::Hint; }
  void skip() {
    if (page != Page::Question) return;
    state.skipped |= 1 << state.current;
    page = finished() ? Page::Summary : Page::Map;
  }
  void reset() { state = Saved{}; page = Page::Home; }
 private:
  static unsigned count(uint8_t bits) {
    unsigned result = 0;
    while (bits) { result += bits & 1; bits >>= 1; }
    return result;
  }
};
} // namespace adventure
