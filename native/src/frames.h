// CPU frames: CEF's pixels (OnPaint) -> an RGBA buffer Java copies from. The counterpart of gpu::Surface
// for pages without shared textures; both take the same paints (view, popup) and hand out the newest frame.
#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "include/internal/cef_types_wrappers.h"

class CpuFrames {
 public:
  // Render thread. The frame starts over: paints of another size are ignored until CEF catches up.
  void Resize(int width, int height);

  // CEF UI thread. `bgra` is premultiplied BGRA, w x h; only `dirty` rects are read.
  void PaintView(const uint8_t* bgra, int w, int h, const std::vector<CefRect>& dirty, bool popupShown,
                 const CefRect& popup);
  // Dropdowns (<select>, datalist...) paint separately; kept to draw over every view paint.
  void PaintPopup(const uint8_t* bgra, int w, int h, const CefRect& popup);
  void ClearPopup();

  // Render thread. Copies the frame (straight-alpha RGBA) into dst if a new one arrived since the last
  // call; `size` must be width*height*4 of the current size.
  bool CopyIfDirty(uint8_t* dst, size_t size);

 private:
  void BlitPopup(const CefRect& popup);  // caller holds mu_

  std::mutex mu_;
  int w_ = 0, h_ = 0;
  std::vector<uint8_t> rgba_, popup_;
  bool dirty_ = false;
};
