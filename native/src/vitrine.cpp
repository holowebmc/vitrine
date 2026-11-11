// JNI bridge: Java <-> CEF, off-screen rendering into an RGBA buffer Java copies from.
//
// Threading: multi_threaded_message_loop (Windows/Linux). CEF runs its own UI thread with its
// own Win32 message loop, so pages stay responsive no matter the game's frame rate. CEF
// callbacks (OnPaint, OnCursorChange, OnAfterCreated...) run on that thread; JNI calls run on
// the Minecraft render thread. Everything they share lives behind View::mu_.
// CefBrowserHost input/resize methods are safe from any thread (CEF posts them to its UI thread).
#include <jni.h>
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "include/cef_app.h"
#include "include/cef_audio_handler.h"
#include "include/cef_client.h"
#include "include/cef_dialog_handler.h"
#include "include/cef_download_handler.h"
#include "include/cef_permission_handler.h"
#include "include/cef_parser.h"
#include "include/cef_render_handler.h"
#include "include/cef_resource_handler.h"
#include "gpu.h"
#include "scheme.h"

namespace {

// Cursor codes shared with Java (Browser.CURSORS).
enum Cursor { kArrow, kIBeam, kHand, kCross, kResizeEW, kResizeNS, kMove, kNotAllowed };

int ToCursor(cef_cursor_type_t t) {
  switch (t) {
    case CT_IBEAM: return kIBeam;
    case CT_HAND: return kHand;
    case CT_CROSS: return kCross;
    case CT_EASTRESIZE: case CT_WESTRESIZE: case CT_EASTWESTRESIZE: case CT_COLUMNRESIZE:
      return kResizeEW;
    case CT_NORTHRESIZE: case CT_SOUTHRESIZE: case CT_NORTHSOUTHRESIZE: case CT_ROWRESIZE:
      return kResizeNS;
    case CT_MOVE: case CT_GRAB: case CT_GRABBING: return kMove;
    case CT_NOTALLOWED: case CT_NODROP: return kNotAllowed;
    default: return kArrow;
  }
}

// Live browsers, so shutdown can wait for every OnBeforeClose before CefShutdown().
std::mutex g_live_mu;
std::condition_variable g_live_cv;
int g_live = 0;

class View : public CefClient,
             public CefAudioHandler,
             public CefRenderHandler,
             public CefLifeSpanHandler,
             public CefDisplayHandler,
             public CefLoadHandler,
             public CefRequestHandler,
             public CefPermissionHandler,
             public CefDownloadHandler,
             public CefDialogHandler {
 public:
  View(int w, int h, bool mono, bool gpu) : mono_(mono), gpu_(gpu) { Resize(w, h); }

  CefRefPtr<CefAudioHandler> GetAudioHandler() override { return this; }
  CefRefPtr<CefRenderHandler> GetRenderHandler() override { return this; }
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefRequestHandler> GetRequestHandler() override { return this; }
  CefRefPtr<CefPermissionHandler> GetPermissionHandler() override { return this; }
  CefRefPtr<CefDownloadHandler> GetDownloadHandler() override { return this; }
  CefRefPtr<CefDialogHandler> GetDialogHandler() override { return this; }

  // --- Audio (CEF audio thread): captured instead of played, handed to Java for OpenAL ---
  // PCM goes into a ring of int16 samples: mono (world panels, so OpenAL can place it) or
  // interleaved stereo (screens). Java drains it every frame (ReadAudio).

  void OnAudioStreamStarted(CefRefPtr<CefBrowser>, const CefAudioParameters& params,
                            int channels) override {
    std::lock_guard lock(audio_mu_);
    rate_ = params.sample_rate;
    channels_ = channels;
    ring_.assign(static_cast<size_t>(rate_) * (mono_ ? 1 : 2) / 2, 0);  // 0.5 s
    head_ = count_ = 0;
  }

  void OnAudioStreamPacket(CefRefPtr<CefBrowser>, const float** data, int frames,
                           int64_t) override {
    std::lock_guard lock(audio_mu_);
    if (ring_.empty()) return;
    for (int f = 0; f < frames; ++f) {
      float l = data[0][f], r = channels_ > 1 ? data[1][f] : l;
      if (mono_) {
        float sum = 0;
        for (int c = 0; c < channels_; ++c) sum += data[c][f];
        Push(sum / channels_);
      } else {
        Push(l);
        Push(r);
      }
    }
  }

  void OnAudioStreamStopped(CefRefPtr<CefBrowser>) override {
    std::lock_guard lock(audio_mu_);
    rate_ = 0;
  }

  void OnAudioStreamError(CefRefPtr<CefBrowser>, const CefString&) override {
    std::lock_guard lock(audio_mu_);
    rate_ = 0;
  }

  /** Sample rate of the current stream, 0 when silent. */
  int AudioRate() {
    std::lock_guard lock(audio_mu_);
    return rate_;
  }

  /** Copies up to max bytes of int16 PCM (whole samples) into dst; returns bytes copied. */
  int ReadAudio(int16_t* dst, int max_bytes) {
    std::lock_guard lock(audio_mu_);
    size_t n = (std::min)(count_, static_cast<size_t>(max_bytes / 2));
    if (!mono_) n &= ~static_cast<size_t>(1);  // keep L/R pairs together
    size_t tail = (head_ + ring_.size() - count_) % (ring_.empty() ? 1 : ring_.size());
    for (size_t i = 0; i < n; ++i) dst[i] = ring_[(tail + i) % ring_.size()];
    count_ -= n;
    return static_cast<int>(n * 2);
  }

  // --- Security: no popups, no file:// navigation, no devices/permissions, no downloads, no file picker ---
  // ponytail: everything denied; a per-page allow list (e.g. microphone for a voice page) can come later.

  bool OnRequestMediaAccessPermission(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, const CefString&,
                                      uint32_t, CefRefPtr<CefMediaAccessCallback> callback) override {
    callback->Cancel();  // camera, microphone, screen capture
    return true;
  }

  bool OnShowPermissionPrompt(CefRefPtr<CefBrowser>, uint64_t, const CefString&, uint32_t,
                              CefRefPtr<CefPermissionPromptCallback> callback) override {
    callback->Continue(CEF_PERMISSION_RESULT_DENY);  // geolocation, notifications, clipboard read...
    return true;
  }

  bool CanDownload(CefRefPtr<CefBrowser>, const CefString&, const CefString&) override {
    return false;
  }

  bool OnBeforeDownload(CefRefPtr<CefBrowser>, CefRefPtr<CefDownloadItem>, const CefString&,
                        CefRefPtr<CefBeforeDownloadCallback>) override {
    return true;  // handled without calling the callback = cancelled (CanDownload already refuses)
  }

  bool OnFileDialog(CefRefPtr<CefBrowser>, FileDialogMode, const CefString&, const CefString&,
                    const std::vector<CefString>&, const std::vector<CefString>&,
                    const std::vector<CefString>&, CefRefPtr<CefFileDialogCallback> callback) override {
    callback->Cancel();  // <input type="file">: no native picker over the game, no local files
    return true;
  }

  bool OnBeforePopup(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, int, const CefString&,
                     const CefString&, CefLifeSpanHandler::WindowOpenDisposition, bool,
                     const CefPopupFeatures&,
                     CefWindowInfo&, CefRefPtr<CefClient>&, CefBrowserSettings&,
                     CefRefPtr<CefDictionaryValue>&, bool*) override {
    return true;  // cancel
  }

  bool OnBeforeBrowse(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, CefRefPtr<CefRequest> request,
                      bool, bool) override {
    return request->GetURL().ToString().rfind("file:", 0) == 0;  // true = cancel
  }

  // --- Java -> JS messages, held until the page has finished loading ---

  void OnLoadingStateChange(CefRefPtr<CefBrowser> browser, bool isLoading, bool, bool) override {
    std::vector<std::string> pending;
    {
      std::lock_guard lock(mu_);
      loaded_ = !isLoading;
      if (!loaded_) return;
      pending.swap(messages_);
    }
    for (auto& js : pending) browser->GetMainFrame()->ExecuteJavaScript(js, "", 0);
  }

  void Send(const std::string& json) {
    // The payload is JSON, which is a valid JS expression.
    std::string js = "window.postMessage(" + json + ", '*');";
    CefRefPtr<CefBrowser> browser;
    {
      std::lock_guard lock(mu_);
      if (!loaded_ || !browser_) return messages_.push_back(std::move(js));
      browser = browser_;
    }
    browser->GetMainFrame()->ExecuteJavaScript(js, "", 0);
  }

  // --- CEF UI thread ---

  void GetViewRect(CefRefPtr<CefBrowser>, CefRect& rect) override {
    std::lock_guard lock(mu_);
    rect = CefRect(0, 0, w_, h_);
  }

  void OnPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList& dirty,
               const void* buffer, int bw, int bh) override {
    const uint8_t* src = static_cast<const uint8_t*>(buffer);
    std::lock_guard lock(mu_);
    if (type == PET_POPUP) {
      // Dropdowns (<select>, datalist...) paint separately; keep a copy to draw over the view.
      if (bw != popup_.width || bh != popup_.height) return;
      popup_px_.resize(static_cast<size_t>(bw) * bh * 4);
      Convert(src, bw, popup_px_.data(), bw, 0, 0, bw, bh);
    } else {
      if (bw != w_ || bh != h_) return;  // stale frame from before a resize
      for (const CefRect& r : dirty) Convert(src, w_, rgba_.data(), w_, r.x, r.y, r.width, r.height);
    }
    if (popup_shown_ && !popup_px_.empty()) BlitPopup();
    dirty_ = true;
  }

  // GPU path (shared_texture_enabled): CEF hands a D3D11 texture instead of pixels.
  void OnAcceleratedPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList&,
                          const CefAcceleratedPaintInfo& info) override {
    bool popupShown;
    CefRect popup;
    {
      std::lock_guard lock(mu_);
      popupShown = popup_shown_;
      popup = popup_;
    }
    bool ok = type == PET_POPUP
                  ? surface_.PaintPopup(info.shared_texture_handle, popup)
                  : surface_.PaintView(info.shared_texture_handle, 0, 0, popupShown, popup);
    if (!ok) gpu_failed_ = true;
  }

  void OnPopupShow(CefRefPtr<CefBrowser> browser, bool show) override {
    {
      std::lock_guard lock(mu_);
      popup_shown_ = show;
      if (show) return;
      popup_px_.clear();
    }
    browser->GetHost()->Invalidate(PET_VIEW);  // repaint what the popup covered
  }

  void OnPopupSize(CefRefPtr<CefBrowser>, const CefRect& rect) override {
    std::lock_guard lock(mu_);
    popup_ = rect;  // keep it inside the view, like cefclient does
    if (popup_.x + popup_.width > w_) popup_.x = w_ - popup_.width;
    if (popup_.y + popup_.height > h_) popup_.y = h_ - popup_.height;
    if (popup_.x < 0) popup_.x = 0;
    if (popup_.y < 0) popup_.y = 0;
    popup_px_.clear();
  }

  // A text field gained (mode != NONE) or lost focus; Java uses it to capture the keyboard.
  void OnVirtualKeyboardRequested(CefRefPtr<CefBrowser>, TextInputMode mode) override {
    editable_ = mode != CEF_TEXT_INPUT_MODE_NONE;
  }

  bool OnCursorChange(CefRefPtr<CefBrowser>, CefCursorHandle, cef_cursor_type_t type,
                      const CefCursorInfo&) override {
    cursor_ = ToCursor(type);
    return true;
  }

  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
    bool hidden, focused, closing;
    {
      std::lock_guard lock(mu_);
      browser_ = browser;
      hidden = hidden_;
      focused = focused_;
      closing = closing_;
    }
    {
      std::lock_guard lock(g_live_mu);
      ++g_live;
    }
    auto host = browser->GetHost();
    if (closing) return host->CloseBrowser(true);  // destroy() came before creation finished
    if (hidden) host->WasHidden(true);
    if (focused) host->SetFocus(true);
  }

  void OnBeforeClose(CefRefPtr<CefBrowser>) override {
    {
      std::lock_guard lock(mu_);
      browser_ = nullptr;
    }
    std::lock_guard lock(g_live_mu);
    --g_live;
    g_live_cv.notify_all();
  }

  // --- Minecraft render thread ---

  void Resize(int w, int h) {
    {
      std::lock_guard lock(mu_);
      w_ = w;
      h_ = h;
      rgba_.assign(gpu_ ? 0 : static_cast<size_t>(w) * h * 4, 0);  // GPU frames never touch it
      dirty_ = false;
    }
    if (auto host = Host()) host->WasResized();
  }

  /** Copies the frame into dst (w*h*4 bytes) if a new one arrived since the last call. */
  bool CopyIfDirty(uint8_t* dst, size_t size) {
    std::lock_guard lock(mu_);
    if (!dirty_ || size != rgba_.size()) return false;
    std::memcpy(dst, rgba_.data(), size);
    dirty_ = false;
    return true;
  }

  void SetHidden(bool hidden) {
    {
      std::lock_guard lock(mu_);
      hidden_ = hidden;
    }
    if (auto host = Host()) host->WasHidden(hidden);
  }

  void SetFocus(bool focus) {
    {
      std::lock_guard lock(mu_);
      focused_ = focus;
    }
    if (auto host = Host()) host->SetFocus(focus);
  }

  void Close() {
    {
      std::lock_guard lock(mu_);
      closing_ = true;
    }
    if (auto host = Host()) host->CloseBrowser(true);
  }

  CefRefPtr<CefBrowserHost> Host() {
    std::lock_guard lock(mu_);
    return browser_ ? browser_->GetHost() : nullptr;
  }

  int CursorCode() const { return cursor_; }
  bool Gpu() const { return gpu_; }
  bool GpuFailed() const { return gpu_failed_; }
  gpu::Surface& Surface() { return surface_; }
  bool Editable() const { return editable_; }
  bool composing = false;  // render thread only

 private:
  // Premultiplied BGRA (CEF) -> straight RGBA (Minecraft blending), for one rect.
  // ponytail: CPU swizzle under the lock; GPU zero-copy path is phase 6.
  static void Convert(const uint8_t* src, int srcW, uint8_t* dst, int dstW, int x0, int y0, int w,
                      int h) {
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

  void BlitPopup() {  // caller holds mu_
    int w = (std::min)(popup_.width, w_ - popup_.x), h = (std::min)(popup_.height, h_ - popup_.y);
    if (w <= 0 || h <= 0) return;
    for (int y = 0; y < h; ++y) {
      std::memcpy(rgba_.data() + (static_cast<size_t>(popup_.y + y) * w_ + popup_.x) * 4,
                  popup_px_.data() + static_cast<size_t>(y) * popup_.width * 4,
                  static_cast<size_t>(w) * 4);
    }
  }

  void Push(float v) {  // caller holds audio_mu_; a full ring drops the oldest sample
    v = v > 1 ? 1 : v < -1 ? -1 : v;
    ring_[head_] = static_cast<int16_t>(v * 32767);
    head_ = (head_ + 1) % ring_.size();
    if (count_ < ring_.size()) ++count_;
  }

  const bool mono_;
  const bool gpu_;
  std::atomic<bool> gpu_failed_ = false;
  gpu::Surface surface_;
  std::mutex audio_mu_;  // guards the audio fields
  std::vector<int16_t> ring_;
  size_t head_ = 0, count_ = 0;
  int rate_ = 0, channels_ = 1;

  std::mutex mu_;  // guards everything below except cursor_
  int w_ = 0, h_ = 0;
  std::vector<uint8_t> rgba_;
  CefRect popup_;
  std::vector<uint8_t> popup_px_;
  bool popup_shown_ = false;
  bool dirty_ = false, hidden_ = false, focused_ = false, closing_ = false, loaded_ = false;
  std::vector<std::string> messages_;
  CefRefPtr<CefBrowser> browser_;
  std::atomic<int> cursor_ = kArrow;
  std::atomic<bool> editable_ = false;
  IMPLEMENT_REFCOUNTING(View);
};

// --- vitrine:// requests ---
// CEF's IO thread queues each request; the render thread drains the queue every frame
// (Native.nextRequest), answers it (Native.respond), and the handler streams the answer back.
// ponytail: one frame of latency per request; serve assets off-thread if page loads get slow.

struct Request {
  int64_t id = 0;
  jlong view = 0;
  std::string method, host, path;
  std::vector<uint8_t> body;
  bool trusted = false;  // made by a vitrine:// page (bridge access)
  CefRefPtr<CefCallback> callback;
  int status = 0;
  std::string mime;
  std::vector<uint8_t> data;
  size_t offset = 0;
};

std::mutex g_req_mu;
std::deque<std::shared_ptr<Request>> g_queue;
std::unordered_map<int64_t, std::shared_ptr<Request>> g_pending;
int64_t g_next_id = 1;

class VitrineHandler : public CefResourceHandler {
 public:
  explicit VitrineHandler(std::shared_ptr<Request> req) : req_(std::move(req)) {}

  bool Open(CefRefPtr<CefRequest>, bool& handle_request, CefRefPtr<CefCallback> callback) override {
    handle_request = false;  // answered later, from the render thread
    std::lock_guard lock(g_req_mu);
    req_->callback = callback;
    g_pending[req_->id] = req_;
    g_queue.push_back(req_);
    return true;
  }

  void GetResponseHeaders(CefRefPtr<CefResponse> response, int64_t& length, CefString&) override {
    std::lock_guard lock(g_req_mu);
    response->SetStatus(req_->status);
    response->SetMimeType(req_->mime);
    response->SetHeaderByName("Cache-Control", "no-store", true);
    length = static_cast<int64_t>(req_->data.size());
  }

  bool Read(void* out, int max, int& read, CefRefPtr<CefResourceReadCallback>) override {
    std::lock_guard lock(g_req_mu);
    size_t n = (std::min)(static_cast<size_t>(max), req_->data.size() - req_->offset);
    std::memcpy(out, req_->data.data() + req_->offset, n);
    req_->offset += n;
    read = static_cast<int>(n);
    return n > 0;
  }

  void Cancel() override {
    std::lock_guard lock(g_req_mu);
    g_pending.erase(req_->id);
  }

 private:
  std::shared_ptr<Request> req_;
  IMPLEMENT_REFCOUNTING(VitrineHandler);
};

class VitrineSchemeFactory : public CefSchemeHandlerFactory {
 public:
  CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                       const CefString&, CefRefPtr<CefRequest> request) override {
    if (!browser) return nullptr;
    CefURLParts parts;
    if (!CefParseURL(request->GetURL(), parts)) return nullptr;

    auto req = std::make_shared<Request>();
    // Every client we create is a View, so the browser's client tells us which page asked.
    req->view = reinterpret_cast<jlong>(static_cast<View*>(browser->GetHost()->GetClient().get()));
    req->method = request->GetMethod().ToString();
    req->host = CefString(&parts.host).ToString();
    req->path = CefURIDecode(CefString(&parts.path), false,
                             static_cast<cef_uri_unescape_rule_t>(
                                 UU_SPACES | UU_URL_SPECIAL_CHARS_EXCEPT_PATH_SEPARATORS))
                    .ToString();
    req->trusted = frame && frame->GetURL().ToString().rfind("vitrine://", 0) == 0;
    if (auto post = request->GetPostData()) {
      CefPostData::ElementVector elements;
      post->GetElements(elements);
      for (auto& e : elements) {
        if (e->GetType() != PDE_TYPE_BYTES) continue;
        size_t at = req->body.size();
        req->body.resize(at + e->GetBytesCount());
        e->GetBytes(e->GetBytesCount(), req->body.data() + at);
      }
    }
    {
      std::lock_guard lock(g_req_mu);
      req->id = g_next_id++;
    }
    return new VitrineHandler(req);
  }
  IMPLEMENT_REFCOUNTING(VitrineSchemeFactory);
};

jbyteArray ToBytes(JNIEnv* env, const std::vector<uint8_t>& v) {
  jbyteArray a = env->NewByteArray(static_cast<jsize>(v.size()));
  env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()),
                          reinterpret_cast<const jbyte*>(v.data()));
  return a;
}

jstring ToJString(JNIEnv* env, const std::string& utf8) { return env->NewStringUTF(utf8.c_str()); }

// Trims Chrome-runtime features an embedded UI never uses.
class App : public CefApp {
 public:
  void OnRegisterCustomSchemes(CefRawPtr<CefSchemeRegistrar> registrar) override {
    RegisterVitrineScheme(registrar);
  }

  void OnBeforeCommandLineProcessing(const CefString& process_type,
                                     CefRefPtr<CefCommandLine> cl) override {
    if (!process_type.empty()) return;
    cl->AppendSwitch("disable-extensions");
    cl->AppendSwitch("disable-component-update");
    cl->AppendSwitch("disable-background-networking");
    // UIs and in-world screens play media without a click, like a game UI would.
    cl->AppendSwitchWithValue("autoplay-policy", "no-user-gesture-required");
    cl->AppendSwitchWithValue("disable-features",
                              "OptimizationHints,OnDeviceModelService,MediaRouter,Translate");
  }
  IMPLEMENT_REFCOUNTING(App);
};

std::wstring ToWide(JNIEnv* env, jstring s) {
  const jchar* chars = env->GetStringChars(s, nullptr);
  std::wstring out(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(s));
  env->ReleaseStringChars(s, chars);
  return out;
}

View* AsView(jlong handle) { return reinterpret_cast<View*>(handle); }

// --- Input translation (Minecraft 26.x uses SDL3: USB HID scancodes, SDL_Keymod) ---

constexpr int kSdlShift = 0x0003, kSdlCtrl = 0x00C0, kSdlAlt = 0x0300, kSdlGui = 0x0C00,
              kSdlNum = 0x1000, kSdlCaps = 0x2000, kSdlAltGr = 0x4000;

uint32_t Flags(int sdlMods, int buttons) {
  uint32_t f = 0;
  if (sdlMods & kSdlShift) f |= EVENTFLAG_SHIFT_DOWN;
  if (sdlMods & kSdlCtrl) f |= EVENTFLAG_CONTROL_DOWN;
  if (sdlMods & kSdlAlt) f |= EVENTFLAG_ALT_DOWN;
  if (sdlMods & kSdlGui) f |= EVENTFLAG_COMMAND_DOWN;
  if (sdlMods & kSdlNum) f |= EVENTFLAG_NUM_LOCK_ON;
  if (sdlMods & kSdlCaps) f |= EVENTFLAG_CAPS_LOCK_ON;
  if (sdlMods & kSdlAltGr) f |= EVENTFLAG_ALTGR_DOWN;
  if (buttons & 1) f |= EVENTFLAG_LEFT_MOUSE_BUTTON;
  if (buttons & 2) f |= EVENTFLAG_MIDDLE_MOUSE_BUTTON;
  if (buttons & 4) f |= EVENTFLAG_RIGHT_MOUSE_BUTTON;
  return f;
}

// USB HID usage -> PS/2 set-1 scancode (0xE0xx = extended). Physical, layout-independent;
// the layout-aware virtual key comes from MapVirtualKeyW below.
uint16_t HidToSet1(int hid) {
  static const uint16_t kAlnum[] = {  // HID 4..56
      0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,  // A-M
      0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,  // N-Z
      0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,                    // 1-0
      0x1C, 0x01, 0x0E, 0x0F, 0x39,                              // Enter Esc Bksp Tab Space
      0x0C, 0x0D, 0x1A, 0x1B, 0x2B, 0x2B, 0x27, 0x28, 0x29, 0x33, 0x34, 0x35};  // - = [ ] \ # ; ' ` , . /
  static const uint16_t kNav[] = {  // HID 57..99
      0x3A,                                                              // CapsLock
      0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x57, 0x58,  // F1-F12
      0xE037, 0x46, 0x45,                                // PrintScreen ScrollLock Pause*
      0xE052, 0xE047, 0xE049, 0xE053, 0xE04F, 0xE051,    // Ins Home PgUp Del End PgDn
      0xE04D, 0xE04B, 0xE050, 0xE048,                    // Right Left Down Up
      0xE045, 0xE035, 0x37, 0x4A, 0x4E, 0xE01C,          // NumLock KP/ KP* KP- KP+ KPEnter
      0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52, 0x53};  // KP1-9 KP0 KP.
  if (hid >= 4 && hid <= 56) return kAlnum[hid - 4];
  if (hid >= 57 && hid <= 99) return kNav[hid - 57];
  if (hid >= 104 && hid <= 114) return static_cast<uint16_t>(0x64 + hid - 104);  // F13-F23
  switch (hid) {
    case 100: return 0x56;    // NonUS backslash
    case 101: return 0xE05D;  // Menu
    case 115: return 0x76;    // F24
    case 133: return 0x7E;    // KP comma (ABNT2)
    case 135: return 0x73;    // International1 (ABNT2 "/?")
    case 136: return 0x70;    // International2
    case 137: return 0x7D;    // International3
    case 224: return 0x1D;    // LCtrl
    case 225: return 0x2A;    // LShift
    case 226: return 0x38;    // LAlt
    case 227: return 0xE05B;  // LGui
    case 228: return 0xE01D;  // RCtrl
    case 229: return 0x36;    // RShift
    case 230: return 0xE038;  // RAlt
    case 231: return 0xE05C;  // RGui
    default: return 0;
  }
}

int VirtualKey(uint16_t sc, int sdlMods) {
  if (sc == 0x45) return VK_PAUSE;  // E1-prefixed, MapVirtualKey can't express it (NumLock is 0xE045)
  // Keypad digits produce VK_NUMPADn only with NumLock on (as Windows does).
  // GLFW (older Minecraft) doesn't report lock states, so ask Windows.
  if ((sdlMods & kSdlNum) || (GetKeyState(VK_NUMLOCK) & 1)) {
    switch (sc) {
      case 0x52: return VK_NUMPAD0;
      case 0x4F: return VK_NUMPAD1;
      case 0x50: return VK_NUMPAD2;
      case 0x51: return VK_NUMPAD3;
      case 0x4B: return VK_NUMPAD4;
      case 0x4C: return VK_NUMPAD5;
      case 0x4D: return VK_NUMPAD6;
      case 0x47: return VK_NUMPAD7;
      case 0x48: return VK_NUMPAD8;
      case 0x49: return VK_NUMPAD9;
      case 0x53: return VK_DECIMAL;
    }
  }
  UINT vk = MapVirtualKeyW(sc, MAPVK_VSC_TO_VK_EX);
  switch (vk) {  // Chromium expects the generic modifier codes
    case VK_LSHIFT: case VK_RSHIFT: return VK_SHIFT;
    case VK_LCONTROL: case VK_RCONTROL: return VK_CONTROL;
    case VK_LMENU: case VK_RMENU: return VK_MENU;
    default: return static_cast<int>(vk);
  }
}

void SendChar(CefRefPtr<CefBrowserHost> host, char16_t c, uint32_t flags) {
  CefKeyEvent e;
  e.type = KEYEVENT_CHAR;
  e.windows_key_code = c;
  e.character = c;
  e.unmodified_character = c;
  e.modifiers = flags;
  host->SendKeyEvent(e);
}

}  // namespace

#define VITRINE(ret, name) JNIEXPORT ret JNICALL Java_dev_holoweb_vitrine_impl_Native_##name

extern "C" {

VITRINE(jboolean, init)(JNIEnv* env, jclass, jstring cefDir, jstring cacheDir) {
  std::wstring dir = ToWide(env, cefDir);
  std::wstring cache = ToWide(env, cacheDir);

  CefMainArgs args(GetModuleHandleW(nullptr));
  CefSettings settings;
  settings.no_sandbox = true;
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = true;
  settings.log_severity = LOGSEVERITY_WARNING;
  CefString(&settings.browser_subprocess_path) = dir + L"\\vitrine_helper.exe";
  CefString(&settings.resources_dir_path) = dir;
  CefString(&settings.locales_dir_path) = dir + L"\\locales";
  CefString(&settings.root_cache_path) = cache;
  CefString(&settings.log_file) = cache + L"\\cef.log";
  if (!CefInitialize(args, settings, new App(), nullptr)) return JNI_FALSE;
  CefRegisterSchemeHandlerFactory("vitrine", "", new VitrineSchemeFactory());
  return JNI_TRUE;
}

// Call after destroy() on every view. Waits (bounded) for the browsers to finish closing.
VITRINE(void, shutdown)(JNIEnv*, jclass) {
  std::unique_lock lock(g_live_mu);
  g_live_cv.wait_for(lock, std::chrono::seconds(3), [] { return g_live == 0; });
  lock.unlock();
  CefShutdown();
}

VITRINE(jlong, create)(JNIEnv* env, jclass, jstring url, jint w, jint h, jint fps,
                       jboolean monoAudio, jboolean gpuFrames) {
  View* view = new View(w, h, monoAudio, gpuFrames);
  view->AddRef();  // owned by Java until destroy()

  CefWindowInfo info;
  info.SetAsWindowless(nullptr);
  info.shared_texture_enabled = gpuFrames;
  CefBrowserSettings bs;
  bs.windowless_frame_rate = fps;
  bs.background_color = CefColorSetARGB(0, 0, 0, 0);  // pages without a background show the game
  CefBrowserHost::CreateBrowser(info, view, ToWide(env, url), bs, nullptr, nullptr);
  return reinterpret_cast<jlong>(view);
}

VITRINE(void, destroy)(JNIEnv*, jclass, jlong handle) {
  View* v = AsView(handle);
  if (v->Gpu()) v->Surface().ReleaseGl();  // GL objects must go on the render thread
  v->Close();
  v->Release();
}

VITRINE(void, resize)(JNIEnv*, jclass, jlong handle, jint w, jint h) { AsView(handle)->Resize(w, h); }

VITRINE(jboolean, copyIfDirty)(JNIEnv*, jclass, jlong handle, jlong dst, jlong size) {
  return AsView(handle)->CopyIfDirty(reinterpret_cast<uint8_t*>(dst), static_cast<size_t>(size))
             ? JNI_TRUE
             : JNI_FALSE;
}

VITRINE(void, send)(JNIEnv* env, jclass, jlong handle, jstring json) {
  const char* utf = env->GetStringUTFChars(json, nullptr);
  AsView(handle)->Send(utf);
  env->ReleaseStringUTFChars(json, utf);
}

// Next queued vitrine:// request: {id, view, method, host, path, body, trusted}, or null.
VITRINE(jobjectArray, nextRequest)(JNIEnv* env, jclass) {
  std::shared_ptr<Request> r;
  {
    std::lock_guard lock(g_req_mu);
    if (g_queue.empty()) return nullptr;
    r = g_queue.front();
    g_queue.pop_front();
  }
  jclass objectClass = env->FindClass("java/lang/Object");
  jclass longClass = env->FindClass("java/lang/Long");
  jclass boolClass = env->FindClass("java/lang/Boolean");
  jmethodID longOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
  jmethodID boolOf = env->GetStaticMethodID(boolClass, "valueOf", "(Z)Ljava/lang/Boolean;");
  jobjectArray out = env->NewObjectArray(7, objectClass, nullptr);
  env->SetObjectArrayElement(out, 0, env->CallStaticObjectMethod(longClass, longOf, static_cast<jlong>(r->id)));
  env->SetObjectArrayElement(out, 1, env->CallStaticObjectMethod(longClass, longOf, r->view));
  env->SetObjectArrayElement(out, 2, ToJString(env, r->method));
  env->SetObjectArrayElement(out, 3, ToJString(env, r->host));
  env->SetObjectArrayElement(out, 4, ToJString(env, r->path));
  env->SetObjectArrayElement(out, 5, ToBytes(env, r->body));
  env->SetObjectArrayElement(out, 6, env->CallStaticObjectMethod(boolClass, boolOf, r->trusted ? JNI_TRUE : JNI_FALSE));
  return out;
}

// Answers a request from nextRequest. Unknown ids (cancelled meanwhile) are ignored.
VITRINE(void, respond)(JNIEnv* env, jclass, jlong id, jint status, jstring mime, jbyteArray data) {
  CefRefPtr<CefCallback> callback;
  {
    std::lock_guard lock(g_req_mu);
    auto it = g_pending.find(id);
    if (it == g_pending.end()) return;
    Request& r = *it->second;
    r.status = status;
    const char* m = env->GetStringUTFChars(mime, nullptr);
    r.mime = m;
    env->ReleaseStringUTFChars(mime, m);
    r.data.resize(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, static_cast<jsize>(r.data.size()),
                            reinterpret_cast<jbyte*>(r.data.data()));
    callback = r.callback;
    g_pending.erase(it);
  }
  callback->Continue();
}

VITRINE(jint, cursor)(JNIEnv*, jclass, jlong handle) { return AsView(handle)->CursorCode(); }

VITRINE(void, setHidden)(JNIEnv*, jclass, jlong handle, jboolean hidden) {
  AsView(handle)->SetHidden(hidden);
}

// Render thread, GL current. True if pages can hand frames over on the GPU.
VITRINE(jboolean, gpuInit)(JNIEnv*, jclass) { return gpu::Init() ? JNI_TRUE : JNI_FALSE; }

// Render thread. Copies the newest GPU frame into Minecraft's GL texture; false if none is new.
VITRINE(jboolean, copyGpu)(JNIEnv*, jclass, jlong handle, jint glTexture, jint w, jint h) {
  return AsView(handle)->Surface().CopyTo(static_cast<unsigned>(glTexture), w, h) ? JNI_TRUE : JNI_FALSE;
}

// Render thread. Vulkan backend: D3D11 on the adapter Minecraft's Vulkan device uses.
VITRINE(jboolean, gpuInitVulkan)(JNIEnv*, jclass, jlong luid) {
  return gpu::InitVulkan(luid) ? JNI_TRUE : JNI_FALSE;
}

// Vulkan: newest frame as {slot, ntHandle, generation, width, height}; it stays untouched until vkRelease.
VITRINE(jboolean, vkTake)(JNIEnv* env, jclass, jlong handle, jlongArray out) {
  gpu::VkFrame f;
  if (!AsView(handle)->Surface().VkTake(&f)) return JNI_FALSE;
  jlong v[5] = {f.slot, reinterpret_cast<jlong>(f.handle), f.generation, f.width, f.height};
  env->SetLongArrayRegion(out, 0, 5, v);
  return JNI_TRUE;
}

VITRINE(void, vkRelease)(JNIEnv*, jclass, jlong handle, jint slot, jint generation) {
  AsView(handle)->Surface().VkRelease(slot, generation);
}

// The page's shared textures could not be opened (e.g. CEF on another GPU): switch it to CPU frames.
VITRINE(jboolean, gpuFailed)(JNIEnv*, jclass, jlong handle) {
  return AsView(handle)->GpuFailed() ? JNI_TRUE : JNI_FALSE;
}

VITRINE(jint, audioRate)(JNIEnv*, jclass, jlong handle) { return AsView(handle)->AudioRate(); }

VITRINE(jint, readAudio)(JNIEnv*, jclass, jlong handle, jlong dst, jint maxBytes) {
  return AsView(handle)->ReadAudio(reinterpret_cast<int16_t*>(dst), maxBytes);
}

VITRINE(jboolean, editable)(JNIEnv*, jclass, jlong handle) {
  return AsView(handle)->Editable() ? JNI_TRUE : JNI_FALSE;
}

VITRINE(void, setFps)(JNIEnv*, jclass, jlong handle, jint fps) {
  if (auto host = AsView(handle)->Host()) host->SetWindowlessFrameRate(fps);
}

VITRINE(void, setFocus)(JNIEnv*, jclass, jlong handle, jboolean focus) {
  AsView(handle)->SetFocus(focus);
}

VITRINE(void, mouseMove)(JNIEnv*, jclass, jlong handle, jint x, jint y, jint mods, jint buttons,
                         jboolean leave) {
  auto host = AsView(handle)->Host();
  if (!host) return;
  CefMouseEvent e;
  e.x = x;
  e.y = y;
  e.modifiers = Flags(mods, buttons);
  host->SendMouseMoveEvent(e, leave);
}

VITRINE(void, mouseButton)(JNIEnv*, jclass, jlong handle, jint x, jint y, jint button, jboolean up,
                           jint clicks, jint mods, jint buttons) {
  auto host = AsView(handle)->Host();
  if (!host) return;
  CefMouseEvent e;
  e.x = x;
  e.y = y;
  e.modifiers = Flags(mods, buttons);
  host->SendMouseClickEvent(e, static_cast<cef_mouse_button_type_t>(button), up, clicks);
}

VITRINE(void, mouseWheel)(JNIEnv*, jclass, jlong handle, jint x, jint y, jint dx, jint dy,
                          jint mods) {
  auto host = AsView(handle)->Host();
  if (!host) return;
  CefMouseEvent e;
  e.x = x;
  e.y = y;
  e.modifiers = Flags(mods, 0);
  host->SendMouseWheelEvent(e, dx, dy);
}

// hid = SDL scancode. Mirrors Win32: WM_KEYDOWN -> RAWKEYDOWN (+ WM_CHAR for control keys).
VITRINE(jint, hidToSet1)(JNIEnv*, jclass, jint hid) {
  return HidToSet1(hid);
}

VITRINE(void, key)(JNIEnv*, jclass, jlong handle, jint scancode, jint mods, jboolean down,
                   jboolean repeat) {
  auto host = AsView(handle)->Host();
  if (!host) return;
  uint16_t sc = static_cast<uint16_t>(scancode);
  int vk = VirtualKey(sc, mods);
  if (vk == 0) return;

  uint32_t flags = Flags(mods, 0);
  if (repeat) flags |= EVENTFLAG_IS_REPEAT;
  CefKeyEvent e;
  e.type = down ? KEYEVENT_RAWKEYDOWN : KEYEVENT_KEYUP;
  e.windows_key_code = vk;
  // CEF maps this straight to the DOM `code` (KeycodeConverter): set-1 scancode, 0xE0xx if extended.
  e.native_key_code = sc;
  e.is_system_key = (mods & kSdlAlt) && !(mods & kSdlCtrl);
  e.modifiers = flags;
  host->SendKeyEvent(e);

  // SDL text input never carries control characters; Win32 would send these as WM_CHAR.
  if (down && !(mods & kSdlCtrl)) {
    switch (vk) {
      case VK_RETURN: SendChar(host, u'\r', flags); break;
      case VK_TAB: SendChar(host, u'\t', flags); break;
      case VK_BACK: SendChar(host, u'\b', flags); break;
      case VK_ESCAPE: SendChar(host, 0x1B, flags); break;
    }
  }
}

VITRINE(void, text)(JNIEnv* env, jclass, jlong handle, jstring text, jint mods) {
  View* v = AsView(handle);
  auto host = v->Host();
  if (!host) return;
  std::wstring t = ToWide(env, text);
  if (v->composing) {
    v->composing = false;
    host->ImeCommitText(t, CefRange::InvalidRange(), 0);
    return;
  }
  for (wchar_t c : t) SendChar(host, static_cast<char16_t>(c), Flags(mods, 0));
}

// IME composition (preedit). Empty text cancels an active composition.
VITRINE(void, preedit)(JNIEnv* env, jclass, jlong handle, jstring text, jint caret) {
  View* v = AsView(handle);
  auto host = v->Host();
  if (!host) return;
  std::wstring t = ToWide(env, text);
  if (t.empty()) {
    if (v->composing) host->ImeCancelComposition();
    v->composing = false;
    return;
  }
  CefCompositionUnderline u{};
  u.size = sizeof(u);
  u.range = {0, static_cast<uint32_t>(t.size())};
  u.color = CefColorSetARGB(255, 0, 0, 0);
  u.background_color = CefColorSetARGB(0, 0, 0, 0);
  u.thick = 0;
  u.style = CEF_CUS_SOLID;
  v->composing = true;
  host->ImeSetComposition(t, {u}, CefRange::InvalidRange(),
                          CefRange(static_cast<uint32_t>(caret), static_cast<uint32_t>(caret)));
}

}  // extern "C"
