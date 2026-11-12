#include "frames.h"

#include <algorithm>
#include <cstring>

namespace {

// Premultiplied BGRA (CEF) -> straight RGBA (Minecraft blending), for one rect.
// ponytail: CPU swizzle under the lock; the GPU path does this in a shader.
void Convert(const uint8_t* src, int srcW, uint8_t* dst, int dstW, int x0, int y0, int w, int h) {
  for (int y = y0; y < y0 + h; ++y) {
    const uint8_t* s = src + (static_cast<size_t>(y) * srcW + x0) * 4;
    uint8_t* d = dst + (static_cast<size_t>(y) * dstW + x0) * 4;
    for (int x = 0; x < w; ++x, s += 4, d += 4) {
      uint8_t a = s[3];
      if (a == 255 || a == 0) {
        d[0] = s[2]; d[1] = s[1]; d[2] = s[0];
      } else {
        d[0] = static_cast<uint8_t>(s[2] * 255 / a);
        d[1] = static_cast<uint8_t>(s[1] * 255 / a);
        d[2] = static_cast<uint8_t>(s[0] * 255 / a);
      }
      d[3] = a;
    }
  }
}

}  // namespace

void CpuFrames::Resize(int width, int height) {
  std::lock_guard lock(mu_);
  w_ = width;
  h_ = height;
  rgba_.assign(static_cast<size_t>(width) * height * 4, 0);
  dirty_ = false;
}

void CpuFrames::PaintView(const uint8_t* bgra, int w, int h, const std::vector<CefRect>& dirty,
                          bool popupShown, const CefRect& popup) {
  std::lock_guard lock(mu_);
  if (w != w_ || h != h_) return;  // stale frame from before a resize
  for (const CefRect& r : dirty) Convert(bgra, w_, rgba_.data(), w_, r.x, r.y, r.width, r.height);
  if (popupShown && !popup_.empty()) BlitPopup(popup);
  dirty_ = true;
}

void CpuFrames::PaintPopup(const uint8_t* bgra, int w, int h, const CefRect& popup) {
  std::lock_guard lock(mu_);
  if (w != popup.width || h != popup.height) return;
  popup_.resize(static_cast<size_t>(w) * h * 4);
  Convert(bgra, w, popup_.data(), w, 0, 0, w, h);
  BlitPopup(popup);
  dirty_ = true;
}

void CpuFrames::ClearPopup() {
  std::lock_guard lock(mu_);
  popup_.clear();
}

bool CpuFrames::CopyIfDirty(uint8_t* dst, size_t size) {
  std::lock_guard lock(mu_);
  if (!dirty_ || size != rgba_.size()) return false;
  std::memcpy(dst, rgba_.data(), size);
  dirty_ = false;
  return true;
}

void CpuFrames::BlitPopup(const CefRect& popup) {
  int w = (std::min)(popup.width, w_ - popup.x), h = (std::min)(popup.height, h_ - popup.y);
  if (w <= 0 || h <= 0 || popup.x < 0 || popup.y < 0) return;
  for (int y = 0; y < h; ++y) {
    std::memcpy(rgba_.data() + (static_cast<size_t>(popup.y + y) * w_ + popup.x) * 4,
                popup_.data() + static_cast<size_t>(y) * popup.width * 4, static_cast<size_t>(w) * 4);
  }
}
