#include "gpu.h"

#include <d3d11_1.h>
#include <dxgi1_2.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <GL/gl.h>

#include <algorithm>
#include <mutex>

using Microsoft::WRL::ComPtr;

namespace gpu {
namespace {

// --- WGL_NV_DX_interop2 / GL 4.3 entry points (resolved with wglGetProcAddress) ---
constexpr GLenum kAccessReadOnly = 0x0000;  // WGL_ACCESS_READ_ONLY_NV
using DXOpenDevice = HANDLE(WINAPI*)(void*);
using DXRegisterObject = HANDLE(WINAPI*)(HANDLE, void*, GLuint, GLenum, GLenum);
using DXUnregisterObject = BOOL(WINAPI*)(HANDLE, HANDLE);
using DXLockObjects = BOOL(WINAPI*)(HANDLE, GLint, HANDLE*);
using CopyImageSubData = void(APIENTRY*)(GLuint, GLenum, GLint, GLint, GLint, GLint, GLuint, GLenum,
                                         GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei);
DXOpenDevice wglDXOpenDevice;
DXRegisterObject wglDXRegisterObject;
DXUnregisterObject wglDXUnregisterObject;
DXLockObjects wglDXLockObjects, wglDXUnlockObjects;
CopyImageSubData glCopyImageSubData;

// --- shared D3D11 state; the immediate context is used from two threads, so everything is under g_mu ---
std::mutex g_mu;
ComPtr<ID3D11Device> g_device;
ComPtr<ID3D11Device1> g_device1;
ComPtr<ID3D11DeviceContext> g_ctx;
ComPtr<ID3D11VertexShader> g_vs;
ComPtr<ID3D11PixelShader> g_ps;
ComPtr<ID3D11Buffer> g_offset;  // cbuffer: where the drawn texture starts in the target
ComPtr<ID3D11RasterizerState> g_raster;
ComPtr<ID3D11Query> g_done;
HANDLE g_interop = nullptr;
bool g_ready = false;
bool g_vulkan = false;  // export ring textures instead of GL interop

// Fullscreen triangle; the pixel shader reads the source 1:1 (no filtering) and un-premultiplies
// alpha: CEF renders premultiplied, Minecraft blends straight alpha.
const char* kShader = R"(
Texture2D src : register(t0);
cbuffer C : register(b0) { int2 offset; int2 pad; };
float4 vs(uint id : SV_VertexID) : SV_Position {
  float2 uv = float2((id << 1) & 2, id & 2);
  return float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
}
float4 ps(float4 p : SV_Position) : SV_Target {
  float4 c = src.Load(int3(int2(p.xy) - offset, 0));
  if (c.a > 0) c.rgb = saturate(c.rgb / c.a);
  return c;
}
)";

template <class T>
bool Proc(T& out, const char* name) {
  out = reinterpret_cast<T>(wglGetProcAddress(name));
  return out != nullptr;
}

ComPtr<ID3DBlob> Compile(const char* entry, const char* target) {
  ComPtr<ID3DBlob> code, errors;
  if (FAILED(D3DCompile(kShader, strlen(kShader), "vitrine", nullptr, nullptr, entry, target, 0, 0,
                        &code, &errors))) {
    return nullptr;
  }
  return code;
}

ComPtr<ID3D11Texture2D> OpenShared(HANDLE handle) {
  ComPtr<ID3D11Texture2D> tex;
  if (FAILED(g_device1->OpenSharedResource1(handle, IID_PPV_ARGS(&tex)))) return nullptr;
  return tex;
}

// Waits until the GPU has executed everything submitted so far (CEF reuses its texture after we return).
void Finish() {
  g_ctx->End(g_done.Get());
  g_ctx->Flush();
  while (g_ctx->GetData(g_done.Get(), nullptr, 0, 0) == S_FALSE) SwitchToThread();
}

// Draws `src` into `rtv` at (x, y, w, h), converting it on the way.
void Draw(ID3D11Texture2D* src, ID3D11RenderTargetView* rtv, int x, int y, int w, int h) {
  ComPtr<ID3D11ShaderResourceView> srv;
  if (FAILED(g_device->CreateShaderResourceView(src, nullptr, &srv))) return;
  int offset[4] = {x, y, 0, 0};
  g_ctx->UpdateSubresource(g_offset.Get(), 0, nullptr, offset, 0, 0);
  D3D11_VIEWPORT vp{static_cast<float>(x), static_cast<float>(y), static_cast<float>(w),
                    static_cast<float>(h), 0, 1};
  g_ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
  g_ctx->IASetInputLayout(nullptr);
  g_ctx->VSSetShader(g_vs.Get(), nullptr, 0);
  g_ctx->PSSetShader(g_ps.Get(), nullptr, 0);
  g_ctx->PSSetShaderResources(0, 1, srv.GetAddressOf());
  g_ctx->PSSetConstantBuffers(0, 1, g_offset.GetAddressOf());
  g_ctx->RSSetState(g_raster.Get());
  g_ctx->RSSetViewports(1, &vp);
  g_ctx->OMSetRenderTargets(1, &rtv, nullptr);
  g_ctx->Draw(3, 0);
  ID3D11ShaderResourceView* none = nullptr;
  g_ctx->PSSetShaderResources(0, 1, &none);
  g_ctx->OMSetRenderTargets(0, nullptr, nullptr);
}

ComPtr<ID3D11Texture2D> MakeTarget(int w, int h, ComPtr<ID3D11RenderTargetView>* rtv) {
  D3D11_TEXTURE2D_DESC d{};
  d.Width = w;
  d.Height = h;
  d.MipLevels = 1;
  d.ArraySize = 1;
  d.Format = DXGI_FORMAT_R8G8B8A8_UNORM;  // same layout as Minecraft's GL_RGBA8 textures
  d.SampleDesc.Count = 1;
  d.Usage = D3D11_USAGE_DEFAULT;
  d.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
  ComPtr<ID3D11Texture2D> tex;
  if (FAILED(g_device->CreateTexture2D(&d, nullptr, &tex))) return nullptr;
  if (rtv && FAILED(g_device->CreateRenderTargetView(tex.Get(), nullptr, rtv->ReleaseAndGetAddressOf()))) {
    return nullptr;
  }
  return tex;
}

}  // namespace

// D3D11 device + conversion shader. luid 0 = default adapter.
bool CreateDevice(long long luid) {
  ComPtr<IDXGIAdapter1> adapter;
  if (luid) {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;
    for (UINT i = 0; factory->EnumAdapters1(i, adapter.ReleaseAndGetAddressOf()) != DXGI_ERROR_NOT_FOUND; ++i) {
      DXGI_ADAPTER_DESC1 d;
      adapter->GetDesc1(&d);
      long long id = (static_cast<long long>(d.AdapterLuid.HighPart) << 32) | d.AdapterLuid.LowPart;
      if (id == luid) break;
      adapter.Reset();
    }
    if (!adapter) return false;
  }
  D3D_FEATURE_LEVEL level = D3D_FEATURE_LEVEL_11_0;
  if (FAILED(D3D11CreateDevice(adapter.Get(), adapter ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE,
                               nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT, &level, 1, D3D11_SDK_VERSION,
                               &g_device, nullptr, &g_ctx)) ||
      FAILED(g_device.As(&g_device1))) {
    return false;
  }
  ComPtr<ID3D10Multithread> mt;
  if (SUCCEEDED(g_ctx.As(&mt))) mt->SetMultithreadProtected(TRUE);

  auto vs = Compile("vs", "vs_5_0"), ps = Compile("ps", "ps_5_0");
  D3D11_BUFFER_DESC cb{16, D3D11_USAGE_DEFAULT, D3D11_BIND_CONSTANT_BUFFER};
  D3D11_RASTERIZER_DESC rs{D3D11_FILL_SOLID, D3D11_CULL_NONE};
  rs.DepthClipEnable = TRUE;
  D3D11_QUERY_DESC q{D3D11_QUERY_EVENT};
  return vs && ps &&
         SUCCEEDED(g_device->CreateVertexShader(vs->GetBufferPointer(), vs->GetBufferSize(), nullptr, &g_vs)) &&
         SUCCEEDED(g_device->CreatePixelShader(ps->GetBufferPointer(), ps->GetBufferSize(), nullptr, &g_ps)) &&
         SUCCEEDED(g_device->CreateBuffer(&cb, nullptr, &g_offset)) &&
         SUCCEEDED(g_device->CreateRasterizerState(&rs, &g_raster)) &&
         SUCCEEDED(g_device->CreateQuery(&q, &g_done));
}


bool InitVulkan(long long luid) {
  if (g_ready) return g_vulkan;
  g_vulkan = true;
  g_ready = CreateDevice(luid);
  return g_ready;
}


bool Init() {
  if (g_ready) return !g_vulkan;
  if (!Proc(wglDXOpenDevice, "wglDXOpenDeviceNV") ||
      !Proc(wglDXRegisterObject, "wglDXRegisterObjectNV") ||
      !Proc(wglDXUnregisterObject, "wglDXUnregisterObjectNV") ||
      !Proc(wglDXLockObjects, "wglDXLockObjectsNV") ||
      !Proc(wglDXUnlockObjects, "wglDXUnlockObjectsNV") ||
      !Proc(glCopyImageSubData, "glCopyImageSubData")) {
    return false;
  }
  // ponytail: default adapter; on hybrid-GPU laptops the trial below or the first paint fails and
  // the page falls back to the CPU path. Pick the GL adapter by LUID if that turns out common.
  if (!CreateDevice(0)) return false;

  g_interop = wglDXOpenDevice(g_device.Get());
  if (!g_interop) return false;

  // Trial run: if GL and D3D are on different GPUs this is where it fails.
  auto probe = MakeTarget(4, 4, nullptr);
  GLuint tex = 0;
  glGenTextures(1, &tex);
  HANDLE obj = probe ? wglDXRegisterObject(g_interop, probe.Get(), tex, GL_TEXTURE_2D, kAccessReadOnly) : nullptr;
  bool ok = obj && wglDXLockObjects(g_interop, 1, &obj) && wglDXUnlockObjects(g_interop, 1, &obj);
  if (obj) wglDXUnregisterObject(g_interop, obj);
  glDeleteTextures(1, &tex);
  g_ready = ok;
  return ok;
}

struct Surface::Impl {
  ComPtr<ID3D11Texture2D> target;  // converted frame, R8G8B8A8, view size
  ComPtr<ID3D11RenderTargetView> rtv;
  ComPtr<ID3D11Texture2D> popup;  // raw copy of the last popup paint (CEF's format)
  int width = 0, height = 0;
  int generation = 0;   // bumps when `target` is recreated
  bool fresh = false;   // a frame arrived since the last CopyTo
  bool closed = false;
  // Vulkan export ring (same size as `target`, recreated with it)
  struct Slot {
    ComPtr<ID3D11Texture2D> tex;
    HANDLE handle = nullptr;
    enum { kFree, kWritten, kInUse } state = kFree;
  } ring[3];
  int latest = -1;  // newest written slot not yet taken
  // GL side (render thread)
  GLuint glTex = 0;
  HANDLE glObj = nullptr;
  int glGeneration = -1;
};

namespace {
void CloseRing(Surface::Impl& s) {
  for (auto& slot : s.ring) {
    if (slot.handle) CloseHandle(slot.handle);
    slot = {};
  }
  s.latest = -1;
}

ComPtr<ID3D11Texture2D> MakeShared(int w, int h, HANDLE* handle) {
  D3D11_TEXTURE2D_DESC d{};
  d.Width = w;
  d.Height = h;
  d.MipLevels = 1;
  d.ArraySize = 1;
  d.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
  d.SampleDesc.Count = 1;
  d.Usage = D3D11_USAGE_DEFAULT;
  d.BindFlags = D3D11_BIND_SHADER_RESOURCE;
  d.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED;
  ComPtr<ID3D11Texture2D> tex;
  ComPtr<IDXGIResource1> res;
  if (FAILED(g_device->CreateTexture2D(&d, nullptr, &tex)) || FAILED(tex.As(&res)) ||
      FAILED(res->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE,
                                     nullptr, handle))) {
    return nullptr;
  }
  return tex;
}
}  // namespace

Surface::Surface() : impl_(new Impl()) {}

Surface::~Surface() {
  std::lock_guard lock(g_mu);
  CloseRing(*impl_);
  delete impl_;
}

void Surface::Export() {
  Impl& s = *impl_;
  if (!s.ring[0].tex) {  // (re)created together with `target`
    for (auto& slot : s.ring) {
      slot.tex = MakeShared(s.width, s.height, &slot.handle);
      if (!slot.tex) return;
    }
  }
  int k = -1;
  for (int i = 0; i < 3 && k < 0; ++i) {
    if (s.ring[i].state == Impl::Slot::kFree) k = i;
  }
  if (k < 0 && s.latest >= 0) k = s.latest;  // Vulkan holds the others: replace the untaken frame
  if (k < 0) return;                         // all slots in use: drop this frame
  g_ctx->CopyResource(s.ring[k].tex.Get(), s.target.Get());
  if (s.latest >= 0 && s.latest != k) s.ring[s.latest].state = Impl::Slot::kFree;
  s.ring[k].state = Impl::Slot::kWritten;
  s.latest = k;
}

bool Surface::VkTake(VkFrame* out) {
  std::lock_guard lock(g_mu);
  if (impl_->closed || impl_->latest < 0) return false;
  Impl& s = *impl_;
  int k = s.latest;
  s.ring[k].state = Impl::Slot::kInUse;
  s.latest = -1;
  *out = {k, s.ring[k].handle, s.generation, s.width, s.height};
  return true;
}

void Surface::VkRelease(int slot, int generation) {
  std::lock_guard lock(g_mu);
  if (impl_->generation != generation || slot < 0 || slot > 2) return;
  if (impl_->ring[slot].state == Impl::Slot::kInUse) impl_->ring[slot].state = Impl::Slot::kFree;
}

bool Surface::PaintView(HANDLE shared, bool popupShown, const CefRect& popup) {
  std::lock_guard lock(g_mu);
  Impl& s = *impl_;
  if (s.closed) return true;
  auto src = OpenShared(shared);
  if (!src) return false;
  D3D11_TEXTURE2D_DESC desc;
  src->GetDesc(&desc);  // the frame's real size (it can lag behind a resize)
  int width = static_cast<int>(desc.Width), height = static_cast<int>(desc.Height);
  if (!s.target || s.width != width || s.height != height) {
    s.target = MakeTarget(width, height, &s.rtv);
    if (!s.target) return false;
    s.width = width;
    s.height = height;
    s.generation++;
    // ponytail: old ring textures may still be read by Vulkan; its imports hold their own reference.
    CloseRing(s);
  }
  Draw(src.Get(), s.rtv.Get(), 0, 0, width, height);
  if (popupShown && s.popup) DrawPopup(popup);
  if (g_vulkan) Export();
  Finish();
  s.fresh = true;
  return true;
}

bool Surface::PaintPopup(HANDLE shared, const CefRect& popup) {
  std::lock_guard lock(g_mu);
  if (!impl_->target || impl_->closed) return true;  // no view yet: nothing to draw over
  Impl& s = *impl_;
  auto src = OpenShared(shared);
  if (!src) return false;
  D3D11_TEXTURE2D_DESC d;
  src->GetDesc(&d);
  D3D11_TEXTURE2D_DESC have{};
  if (s.popup) s.popup->GetDesc(&have);
  if (!s.popup || have.Width != d.Width || have.Height != d.Height || have.Format != d.Format) {
    d.MiscFlags = 0;
    d.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    d.Usage = D3D11_USAGE_DEFAULT;
    d.CPUAccessFlags = 0;
    if (FAILED(g_device->CreateTexture2D(&d, nullptr, s.popup.ReleaseAndGetAddressOf()))) return false;
  }
  g_ctx->CopyResource(s.popup.Get(), src.Get());
  DrawPopup(popup);
  if (g_vulkan) Export();
  Finish();
  s.fresh = true;
  return true;
}

void Surface::DrawPopup(const CefRect& r) {
  Impl& s = *impl_;
  int w = (std::min)(r.width, s.width - r.x), h = (std::min)(r.height, s.height - r.y);
  if (w > 0 && h > 0) Draw(s.popup.Get(), s.rtv.Get(), r.x, r.y, w, h);
}

bool Surface::CopyTo(unsigned glTexture, int width, int height) {
  std::lock_guard lock(g_mu);
  if (!impl_->fresh || impl_->closed) return false;
  Impl& s = *impl_;
  if (s.width != width || s.height != height) return false;  // mid-resize: wait for a matching frame
  if (s.glGeneration != s.generation) {  // (re)attach the GL name to the current D3D texture
    if (s.glObj) wglDXUnregisterObject(g_interop, s.glObj);
    if (!s.glTex) glGenTextures(1, &s.glTex);
    s.glObj = wglDXRegisterObject(g_interop, s.target.Get(), s.glTex, GL_TEXTURE_2D, kAccessReadOnly);
    if (!s.glObj) return false;
    s.glGeneration = s.generation;
  }
  if (!wglDXLockObjects(g_interop, 1, &s.glObj)) return false;
  glCopyImageSubData(s.glTex, GL_TEXTURE_2D, 0, 0, 0, 0, glTexture, GL_TEXTURE_2D, 0, 0, 0, 0,
                     width, height, 1);
  wglDXUnlockObjects(g_interop, 1, &s.glObj);
  s.fresh = false;
  return true;
}

void Surface::Close() {
  std::lock_guard lock(g_mu);
  Impl& s = *impl_;
  s.closed = true;
  if (s.glObj) wglDXUnregisterObject(g_interop, s.glObj);
  if (s.glTex) glDeleteTextures(1, &s.glTex);
  s.glObj = nullptr;
  s.glTex = 0;
}

}  // namespace gpu
