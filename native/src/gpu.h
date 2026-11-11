// Zero-copy frames on Windows: CEF's shared D3D11 texture -> our D3D11 texture (converted on the GPU) ->
//  - OpenGL: WGL_NV_DX_interop2 -> glCopyImageSubData into Minecraft's own GL texture;
//  - Vulkan: a ring of NT-shared D3D11 textures that Java imports (VK_KHR_external_memory_win32) and copies.
// No pixel touches the CPU.
#pragma once
#include <windows.h>

#include "include/internal/cef_types_wrappers.h"

namespace gpu {

// Render thread, with Minecraft's GL context current. Creates the D3D11 device and proves the
// interop works end to end (registers and locks a test texture). False = use the CPU path.
bool Init();

// Vulkan backend: D3D11 device on the adapter with this LUID (the one Minecraft's Vulkan device uses).
bool InitVulkan(long long luid);

// One exported Vulkan frame (VkTake).
struct VkFrame {
  int slot;
  HANDLE handle;  // NT handle of the D3D11 texture; owned by the surface, stable for this generation
  int generation, width, height;
};
bool Ready();

// One page's GPU frame. Created/painted on CEF's UI thread, copied/released on the render thread.
class Surface {
 public:
  struct Impl;
  ~Surface();

  // CEF UI thread (OnAcceleratedPaint). `shared` is only valid during the call; this returns
  // after the GPU finished reading it. False if the texture could not be opened (wrong adapter...).
  bool PaintView(HANDLE shared, int width, int height, bool popupShown, const CefRect& popup);
  bool PaintPopup(HANDLE shared, const CefRect& popup);

  // Render thread. Copies the newest frame into `glTexture` (w x h, GL_RGBA8) if there is one.
  bool CopyTo(unsigned glTexture, int width, int height);

  // Render thread: frees the GL side (interop registration, GL texture). Paint calls become no-ops.
  void ReleaseGl();

  // Render thread (Vulkan): takes the newest exported frame, which stays untouched by CEF until
  // VkRelease(slot, generation). False if no new frame.
  bool VkTake(VkFrame* out);
  void VkRelease(int slot, int generation);

 private:
  void DrawPopup(const CefRect& popup);  // caller holds the device lock
  void Export();                         // Vulkan: copy `target` into a free ring slot; holds the lock
  Impl* impl_ = nullptr;
};

}  // namespace gpu
