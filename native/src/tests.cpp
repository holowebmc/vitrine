// Checks for the CEF-free parts of the bridge. No framework: exits non-zero on the first failure.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "frames.h"
#include "keys.h"

#define CHECK(cond)                                                     \
  do {                                                                  \
    if (!(cond)) {                                                      \
      std::printf("FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond);     \
      std::exit(1);                                                     \
    }                                                                   \
  } while (0)

namespace {

std::vector<uint8_t> Fill(int w, int h, uint8_t b, uint8_t g, uint8_t r, uint8_t a) {
  std::vector<uint8_t> px(static_cast<size_t>(w) * h * 4);
  for (size_t i = 0; i < px.size(); i += 4) {
    px[i] = b; px[i + 1] = g; px[i + 2] = r; px[i + 3] = a;
  }
  return px;
}

void CpuFramesTest() {
  CpuFrames f;
  f.Resize(4, 4);
  std::vector<uint8_t> out(4 * 4 * 4, 7);
  CHECK(!f.CopyIfDirty(out.data(), out.size()));  // nothing painted yet

  // Premultiplied BGRA (b=10, g=20, r=40, a=128) -> straight RGBA.
  auto view = Fill(4, 4, 10, 20, 40, 128);
  f.PaintView(view.data(), 4, 4, {CefRect(0, 0, 4, 4)}, false, CefRect());
  CHECK(!f.CopyIfDirty(out.data(), 8));  // wrong size: nothing copied
  CHECK(f.CopyIfDirty(out.data(), out.size()));
  CHECK(out[0] == 40 * 255 / 128 && out[1] == 20 * 255 / 128 && out[2] == 10 * 255 / 128 && out[3] == 128);
  CHECK(!f.CopyIfDirty(out.data(), out.size()));  // only once per frame

  // Only dirty rects are read.
  auto red = Fill(4, 4, 0, 0, 255, 255);
  f.PaintView(red.data(), 4, 4, {CefRect(1, 1, 1, 1)}, false, CefRect());
  CHECK(f.CopyIfDirty(out.data(), out.size()));
  CHECK(out[(1 * 4 + 1) * 4] == 255 && out[(1 * 4 + 1) * 4 + 3] == 255);
  CHECK(out[3] == 128);  // pixel (0,0) untouched

  // A frame of another size (mid-resize) is ignored.
  auto stale = Fill(2, 2, 1, 2, 3, 255);
  f.PaintView(stale.data(), 2, 2, {CefRect(0, 0, 2, 2)}, false, CefRect());
  CHECK(!f.CopyIfDirty(out.data(), out.size()));

  // The popup is drawn over the view, clipped at the edge, and again over every later view paint.
  CefRect popup(3, 3, 2, 2);
  auto green = Fill(2, 2, 0, 255, 0, 255);
  f.PaintPopup(green.data(), 2, 2, popup);
  CHECK(f.CopyIfDirty(out.data(), out.size()));
  CHECK(out[(3 * 4 + 3) * 4 + 1] == 255 && out[(3 * 4 + 3) * 4] == 0);
  f.PaintView(red.data(), 4, 4, {CefRect(0, 0, 4, 4)}, true, popup);
  CHECK(f.CopyIfDirty(out.data(), out.size()));
  CHECK(out[(3 * 4 + 3) * 4 + 1] == 255);  // popup still on top
  CHECK(out[0] == 255);                    // view elsewhere

  // Hidden popup: gone from the next view paint.
  f.ClearPopup();
  f.PaintView(red.data(), 4, 4, {CefRect(0, 0, 4, 4)}, true, popup);
  CHECK(f.CopyIfDirty(out.data(), out.size()));
  CHECK(out[(3 * 4 + 3) * 4] == 255 && out[(3 * 4 + 3) * 4 + 1] == 0);
}

void KeysTest() {
  CHECK(HidToSet1(4) == 0x1E);      // A
  CHECK(HidToSet1(29) == 0x2C);     // Z
  CHECK(HidToSet1(40) == 0x1C);     // Enter
  CHECK(HidToSet1(56) == 0x35);     // /
  CHECK(HidToSet1(57) == 0x3A);     // CapsLock
  CHECK(HidToSet1(79) == 0xE04D);   // Right: extended
  CHECK(HidToSet1(99) == 0x53);     // keypad .
  CHECK(HidToSet1(104) == 0x64);    // F13
  CHECK(HidToSet1(135) == 0x73);    // ABNT2 "/?"
  CHECK(HidToSet1(230) == 0xE038);  // right Alt
  CHECK(HidToSet1(0) == 0 && HidToSet1(3) == 0 && HidToSet1(300) == 0);
  // Keypad digits depend on NumLock (bit 0x1000); Pause can't come from MapVirtualKey.
  CHECK(VirtualKey(0x4F, 0x1000) == 0x61);  // VK_NUMPAD1
  CHECK(VirtualKey(0x45, 0) == 0x13);       // VK_PAUSE
  CHECK(VirtualKey(0x2A, 0) == 0x10);       // left Shift -> generic VK_SHIFT
}

}  // namespace

int main() {
  CpuFramesTest();
  KeysTest();
  std::printf("ok\n");
  return 0;
}
