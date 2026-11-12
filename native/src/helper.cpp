// CEF subprocess (renderer, GPU, utility). Must sit next to libcef.dll.
#include <windows.h>
#include "include/cef_app.h"
#include "include/cef_v8.h"
#include "scheme.h"

// backdrop-filter: blur() only sees the page, and behind the page there is nothing: the game is
// composited later, by the mod. So every page reports where its backdrop blurs are and the mod blurs
// the game there (GameBlur). Per rect, in device pixels:
// x, y, width, height, blur radius, corner radii (TL, TR, BR, BL), opacity (the element's and its ancestors').
// ponytail: main frame only, no shadow DOM, transforms use the bounding box, overflow clipping is ignored.
// ponytail: looks for elements by scanning the whole document (at most every 100 ms after a DOM change,
// every 500 ms otherwise, e.g. for :hover); read the style sheets instead if huge pages get slow.
const char kBlurScript[] = R"JS((function (report) {
  var els = [], last = null, dirty = true, scanAt = -1e9;
  function scan() {
    els = [];
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      if (getComputedStyle(all[i]).backdropFilter.indexOf('blur(') >= 0) els.push(all[i]);
    }
  }
  function corner(value, box) {
    var n = parseFloat(value) || 0;
    return value.indexOf('%') > 0 ? n / 100 * Math.min(box.width, box.height) : n;
  }
  function tick(now) {
    requestAnimationFrame(tick);
    if (now - scanAt > (dirty ? 100 : 500)) {
      scan();
      scanAt = now;
      dirty = false;
    }
    var out = [], k = devicePixelRatio;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!el.isConnected) continue;
      var cs = getComputedStyle(el), m = /blur\(([\d.]+)px\)/.exec(cs.backdropFilter);
      if (!m || !(+m[1] > 0) || cs.visibility !== 'visible') continue;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0 || r.right <= 0 || r.bottom <= 0 || r.left >= innerWidth || r.top >= innerHeight) continue;
      var opacity = 1;
      for (var e = el; e; e = e.parentElement) opacity *= +getComputedStyle(e).opacity;
      if (!(opacity > 0.004)) continue;
      out.push(r.left * k, r.top * k, r.width * k, r.height * k, m[1] * k,
          corner(cs.borderTopLeftRadius, r) * k, corner(cs.borderTopRightRadius, r) * k,
          corner(cs.borderBottomRightRadius, r) * k, corner(cs.borderBottomLeftRadius, r) * k, opacity);
    }
    var s = out.map(function (n) { return Math.round(n * 100) / 100; }).join(',');
    if (s !== last) report(last = s);
  }
  new MutationObserver(function () { dirty = true; }).observe(document, {subtree: true, childList: true, attributes: true});
  requestAnimationFrame(tick);
}))JS";

class BlurReport : public CefV8Handler {
 public:
  bool Execute(const CefString&, CefRefPtr<CefV8Value>, const CefV8ValueList& args,
               CefRefPtr<CefV8Value>&, CefString&) override {
    auto message = CefProcessMessage::Create("vitrine.blur");
    message->GetArgumentList()->SetString(0, args.empty() ? CefString() : args[0]->GetStringValue());
    CefV8Context::GetCurrentContext()->GetFrame()->SendProcessMessage(PID_BROWSER, message);
    return true;
  }
  IMPLEMENT_REFCOUNTING(BlurReport);
};

class HelperApp : public CefApp, public CefRenderProcessHandler {
 public:
  void OnRegisterCustomSchemes(CefRawPtr<CefSchemeRegistrar> registrar) override {
    RegisterVitrineScheme(registrar);
  }

  CefRefPtr<CefRenderProcessHandler> GetRenderProcessHandler() override { return this; }

  void OnContextCreated(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame,
                        CefRefPtr<CefV8Context> context) override {
    if (!frame->IsMain()) return;
    CefRefPtr<CefV8Value> start;
    CefRefPtr<CefV8Exception> error;
    if (!context->Eval(kBlurScript, "vitrine://vitrine/blur.js", 1, start, error)) return;
    start->ExecuteFunction(nullptr, {CefV8Value::CreateFunction("report", new BlurReport())});
  }
  IMPLEMENT_REFCOUNTING(HelperApp);
};

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE, LPWSTR, int) {
  CefMainArgs args(instance);
  return CefExecuteProcess(args, new HelperApp(), nullptr);
}
