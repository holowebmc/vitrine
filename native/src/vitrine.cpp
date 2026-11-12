// JNI bridge: Java <-> CEF, off-screen rendering into frames Java takes (frames.h, gpu.h).
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
#include <cstdlib>
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
#include "frames.h"
#include "gpu.h"
#include "input.h"
#include "keys.h"
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

  // --- backdrop-filter: blur() rects, reported by the page's renderer (helper.cpp) ---

  bool OnProcessMessageReceived(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>, CefProcessId,
                                CefRefPtr<CefProcessMessage> message) override {
    if (message->GetName() != "vitrine.blur") return false;
    std::string text = message->GetArgumentList()->GetString(0).ToString();
    std::vector<float> rects;
    for (const char* p = text.c_str(); *p && rects.size() < 10 * 64;) {  // pages are untrusted: bounded
      char* end;
      rects.push_back(std::strtof(p, &end));
      if (end == p) break;
      p = *end == ',' ? end + 1 : end;
    }
    rects.resize(rects.size() / 10 * 10);
    std::lock_guard lock(mu_);
    blur_.swap(rects);
    return true;
  }

  /** 10 floats per rect (see helper.cpp); empty when the page blurs nothing. */
  std::vector<float> BlurRects() {
    std::lock_guard lock(mu_);
    return blur_;
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

  // CPU path: CEF hands pixels.
  void OnPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList& dirty,
               const void* buffer, int bw, int bh) override {
    bool popupShown;
    CefRect popup;
    {
      std::lock_guard lock(mu_);
      popupShown = popup_shown_;
      popup = popup_;
    }
    const uint8_t* src = static_cast<const uint8_t*>(buffer);
    if (type == PET_POPUP) cpu_.PaintPopup(src, bw, bh, popup);
    else cpu_.PaintView(src, bw, bh, dirty, popupShown, popup);
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
                  : surface_.PaintView(info.shared_texture_handle, popupShown, popup);
    if (!ok) gpu_failed_ = true;
  }

  void OnPopupShow(CefRefPtr<CefBrowser> browser, bool show) override {
    {
      std::lock_guard lock(mu_);
      popup_shown_ = show;
      if (show) return;
    }
    cpu_.ClearPopup();
    browser->GetHost()->Invalidate(PET_VIEW);  // repaint what the popup covered
  }

  void OnPopupSize(CefRefPtr<CefBrowser>, const CefRect& rect) override {
    std::lock_guard lock(mu_);
    popup_ = rect;  // keep it inside the view, like cefclient does
    if (popup_.x + popup_.width > w_) popup_.x = w_ - popup_.width;
    if (popup_.y + popup_.height > h_) popup_.y = h_ - popup_.height;
    if (popup_.x < 0) popup_.x = 0;
    if (popup_.y < 0) popup_.y = 0;
    cpu_.ClearPopup();
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
    // The audio handler only takes a stream over (and silences Chromium's own output) once it is
    // already playing: its first milliseconds reach the speakers directly, whatever the game's volume.
    // Muted from the start nothing does; the handler still receives the audio.
    host->SetAudioMuted(true);
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
    }
    if (!gpu_) cpu_.Resize(w, h);
    if (auto host = Host()) host->WasResized();
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

  /** Render thread: ends the page. */
  void Close() {
    if (gpu_) surface_.Close();
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
  bool GpuFailed() const { return gpu_failed_; }
  gpu::Surface& Surface() { return surface_; }
  CpuFrames& Cpu() { return cpu_; }
  bool Editable() const { return editable_; }
  Input input;  // render thread only

 private:
  void Push(float v) {  // caller holds audio_mu_; a full ring drops the oldest sample
    v = v > 1 ? 1 : v < -1 ? -1 : v;
    ring_[head_] = static_cast<int16_t>(v * 32767);
    head_ = (head_ + 1) % ring_.size();
    if (count_ < ring_.size()) ++count_;
  }

  const bool mono_;
  const bool gpu_;
  std::atomic<bool> gpu_failed_ = false;
  gpu::Surface surface_;  // frames: one of the two, by gpu_
  CpuFrames cpu_;
  std::mutex audio_mu_;  // guards the audio fields
  std::vector<int16_t> ring_;
  size_t head_ = 0, count_ = 0;
  int rate_ = 0, channels_ = 1;

  std::mutex mu_;  // guards everything below except cursor_
  int w_ = 0, h_ = 0;
  CefRect popup_;
  bool popup_shown_ = false;
  bool hidden_ = false, focused_ = false, closing_ = false, loaded_ = false;
  std::vector<std::string> messages_;
  std::vector<float> blur_;
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
  v->Close();
  v->Release();
}

VITRINE(void, resize)(JNIEnv*, jclass, jlong handle, jint w, jint h) { AsView(handle)->Resize(w, h); }

VITRINE(jboolean, copyIfDirty)(JNIEnv*, jclass, jlong handle, jlong dst, jlong size) {
  return AsView(handle)->Cpu().CopyIfDirty(reinterpret_cast<uint8_t*>(dst), static_cast<size_t>(size))
             ? JNI_TRUE
             : JNI_FALSE;
}

VITRINE(void, send)(JNIEnv* env, jclass, jlong handle, jstring json) {
  const char* utf = env->GetStringUTFChars(json, nullptr);
  AsView(handle)->Send(utf);
  env->ReleaseStringUTFChars(json, utf);
}

// Next queued vitrine:// request as a Native.Request, or null.
VITRINE(jobject, nextRequest)(JNIEnv* env, jclass) {
  std::shared_ptr<Request> r;
  {
    std::lock_guard lock(g_req_mu);
    if (g_queue.empty()) return nullptr;
    r = g_queue.front();
    g_queue.pop_front();
  }
  // record Request(long id, long view, String method, String host, String path, byte[] body, boolean trusted)
  jclass type = env->FindClass("dev/holoweb/vitrine/impl/Native$Request");
  jmethodID make = env->GetMethodID(
      type, "<init>", "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[BZ)V");
  return env->NewObject(type, make, static_cast<jlong>(r->id), r->view, ToJString(env, r->method),
                        ToJString(env, r->host), ToJString(env, r->path), ToBytes(env, r->body),
                        static_cast<jboolean>(r->trusted ? JNI_TRUE : JNI_FALSE));
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

// backdrop-filter: blur() rects of the page (10 floats each), or null when there are none.
VITRINE(jfloatArray, blurRects)(JNIEnv* env, jclass, jlong handle) {
  std::vector<float> rects = AsView(handle)->BlurRects();
  if (rects.empty()) return nullptr;
  jfloatArray out = env->NewFloatArray(static_cast<jsize>(rects.size()));
  env->SetFloatArrayRegion(out, 0, static_cast<jsize>(rects.size()), rects.data());
  return out;
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

// --- Input: only type conversion here; what CEF receives is decided in input.cpp ---

VITRINE(void, mouseMove)(JNIEnv*, jclass, jlong handle, jint x, jint y, jint mods, jint buttons,
                         jboolean leave) {
  Input::MouseMove(AsView(handle)->Host(), x, y, mods, buttons, leave);
}

VITRINE(void, mouseButton)(JNIEnv*, jclass, jlong handle, jint x, jint y, jint button, jboolean up,
                           jint clicks, jint mods, jint buttons) {
  Input::MouseButton(AsView(handle)->Host(), x, y, button, up, clicks, mods, buttons);
}

VITRINE(void, mouseWheel)(JNIEnv*, jclass, jlong handle, jint x, jint y, jint dx, jint dy,
                          jint mods) {
  Input::MouseWheel(AsView(handle)->Host(), x, y, dx, dy, mods);
}

VITRINE(jint, hidToSet1)(JNIEnv*, jclass, jint hid) { return HidToSet1(hid); }

VITRINE(void, key)(JNIEnv*, jclass, jlong handle, jint scancode, jint mods, jboolean down,
                   jboolean repeat) {
  Input::Key(AsView(handle)->Host(), scancode, mods, down, repeat);
}

VITRINE(void, text)(JNIEnv* env, jclass, jlong handle, jstring text, jint mods) {
  View* v = AsView(handle);
  v->input.Text(v->Host(), ToWide(env, text), mods);
}

VITRINE(void, preedit)(JNIEnv* env, jclass, jlong handle, jstring text, jint caret) {
  View* v = AsView(handle);
  v->input.Preedit(v->Host(), ToWide(env, text), caret);
}

}  // extern "C"
