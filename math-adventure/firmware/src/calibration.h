#pragma once
#include <math.h>
#include <stdint.h>
namespace adventure {
constexpr uint32_t kCalibrationMagic = 0x43414C32;
struct Calibration {
  uint32_t magic = 0;
  float ax = 0, bx = 0, cx = 0, ay = 0, by = 0, cy = 0;
  bool valid() const {
    return magic == kCalibrationMagic && isfinite(ax) && isfinite(bx) && isfinite(cx) &&
      isfinite(ay) && isfinite(by) && isfinite(cy) &&
      fabsf(ax*by-bx*ay)>0.0001f;
  }
  bool fit(const float *x, const float *y) {
    float dx1=x[1]-x[0], dy1=y[1]-y[0], dx2=x[2]-x[0], dy2=y[2]-y[0];
    float d=dx1*dy2-dx2*dy1;
    if (fabsf(d)<200000) return false;
    magic=kCalibrationMagic;
    ax=272*dy2/d; bx=-272*dx2/d; ay=-164*dy1/d; by=164*dx1/d;
    cx=24-ax*x[0]-bx*y[0]; cy=48-ay*x[0]-by*y[0];
    return valid();
  }
  void map(float rx,float ry,int &x,int &y) const {
    x=lroundf(ax*rx+bx*ry+cx);y=lroundf(ay*rx+by*ry+cy);
  }
};
}
