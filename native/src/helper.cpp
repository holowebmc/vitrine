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
//
// World panels can also raise elements off the panel as real solids (Raised.java): the page sets
// --vitrine-depth: <length> on them (registered: no inheritance, can be transitioned) and reports each one's
// outline, in device pixels: bottom, top (heights over the panel), point count, then x, y per point, clockwise.
// - The outline follows clip-path polygon()/circle()/ellipse(), else border-radius.
// - Depth is on top of the nearest raised ancestor, so a raised child stands on its parent.
// - The outline is cut by the page and by the box of every scrolling/clipping ancestor.
// ponytail: transforms use the bounding box; clip-path path()/inset() and rounded clipping ancestors are ignored.
// The same scan finds them, and pointer/focus events hurry it, so a depth that starts at :hover shows within 100 ms.
//
// Screen pages can leave slots for the game to draw over them (Binds.java): --vitrine-bind: <name> on an element (registered:
// no inheritance). One line per slot, tab separated: "x,y,width,height,clip x0,y0,x1,y1,opacity,corner radii
// (TL, TR, BR, BL)" in device pixels (clip = the page and the box of every scrolling/clipping ancestor), the
// name, the element's data-vitrine attribute. World panels have them too.
// ponytail: transforms use the bounding box; clip-path and rounded clipping ancestors are ignored.
const char kBlurScript[] = R"JS((function (report, reportDepth, reportBind) {
  var els = [], raised = [], binds = [], last = null, lastDepth = null, lastShape = '', lastBind = null, mark = null, markOn = false, dirty = true, scanAt = -1e9;
  try {
    CSS.registerProperty({name: '--vitrine-bind', syntax: '*', inherits: false});
  } catch (e) {}
  try {
    CSS.registerProperty({name: '--vitrine-depth', syntax: '<length>', inherits: false, initialValue: '0px'});
  } catch (e) {}
  function scan() {
    els = [];
    raised = [];
    binds = [];
    var all = document.querySelectorAll('*'), found = new Map();
    for (var i = 0; i < all.length; i++) {
      var cs = getComputedStyle(all[i]);
      if (cs.backdropFilter.indexOf('blur(') >= 0) els.push(all[i]);
      if (cs.getPropertyValue('--vitrine-bind').trim()) {
        // Computed styles are live: kept from scan to scan, so a frame only reads them (its own, then its ancestors').
        var slot = {el: all[i], styles: [cs], clips: []};
        for (var b = all[i].parentElement; b; b = b.parentElement) {
          var bcs = getComputedStyle(b);
          slot.styles.push(bcs);
          if (bcs.overflowX !== 'visible' || bcs.overflowY !== 'visible') slot.clips.push(b);
        }
        binds.push(slot);
      }
      if (!(parseFloat(cs.getPropertyValue('--vitrine-depth')) > 0)) continue;
      // Document order: ancestors come first. Remember what it stands on and what cuts it.
      var entry = {el: all[i], on: null, clips: []};
      for (var a = all[i].parentElement; a; a = a.parentElement) {
        if (!entry.on && found.has(a)) entry.on = a;
        var acs = getComputedStyle(a);
        if (acs.overflowX !== 'visible' || acs.overflowY !== 'visible') entry.clips.push(a);
      }
      found.set(all[i], entry);
      raised.push(entry);
    }
  }
  function len(value, box) {
    var n = parseFloat(value) || 0;
    return value.indexOf('%') > 0 ? n / 100 * box : n;
  }
  // Points of an ellipse arc, about one every 4 px; angles in screen space (y down), so increasing = clockwise.
  function arc(pts, cx, cy, rx, ry, from, to, closed) {
    if (!(rx > 0 && ry > 0)) return pts.push(cx, cy);
    var n = Math.max(2, Math.min(closed ? 64 : 16, Math.ceil(Math.max(rx, ry) * (to - from) / 4)));
    for (var i = 0; i < (closed ? n : n + 1); i++) {
      var t = from + (to - from) * i / n;
      pts.push(cx + rx * Math.cos(t), cy + ry * Math.sin(t));
    }
  }
  function outline(cs, r) {
    var pts = [], w = r.width, h = r.height, m, i, PI = Math.PI;
    if ((m = /^polygon\((.*)\)$/.exec(cs.clipPath))) {
      var parts = m[1].split(',');
      for (i = 0; i < parts.length; i++) {
        var xy = parts[i].trim().split(/\s+/);
        if (xy.length === 2) pts.push(r.left + len(xy[0], w), r.top + len(xy[1], h));  // else: the fill rule
      }
      return pts;
    }
    if ((m = /^(circle|ellipse)\((.*)\)$/.exec(cs.clipPath))) {
      var at = m[2].split(/\bat\b/), rad = at[0].trim().split(/\s+/), c = (at[1] || '50% 50%').trim().split(/\s+/);
      var cx = len(c[0], w), cy = len(c[1] || '50%', h);
      var sized = function (v, box, closest) { return v && /^[\d.]/.test(v) ? len(v, box) : closest; };
      var rx = m[1] === 'circle' ? sized(rad[0], Math.sqrt((w * w + h * h) / 2), Math.min(cx, w - cx, cy, h - cy))
          : sized(rad[0], w, Math.min(cx, w - cx));
      var ry = m[1] === 'circle' ? rx : sized(rad[1], h, Math.min(cy, h - cy));
      arc(pts, r.left + cx, r.top + cy, rx, ry, 0, 2 * PI, true);
      return pts;
    }
    // border-radius: horizontal and vertical radius per corner, shrunk together when they overlap (as CSS does).
    var names = ['TopLeft', 'TopRight', 'BottomRight', 'BottomLeft'], hr = [], vr = [];
    for (i = 0; i < 4; i++) {
      var v = cs['border' + names[i] + 'Radius'].split(' ');
      hr[i] = len(v[0], w);
      vr[i] = len(v[1] || v[0], h);
    }
    var f = Math.min(1, w / (hr[0] + hr[1]), w / (hr[3] + hr[2]), h / (vr[0] + vr[3]), h / (vr[1] + vr[2]));
    for (i = 0; i < 4; i++) { hr[i] *= f; vr[i] *= f; }
    arc(pts, r.left + hr[0], r.top + vr[0], hr[0], vr[0], PI, 1.5 * PI);
    arc(pts, r.right - hr[1], r.top + vr[1], hr[1], vr[1], 1.5 * PI, 2 * PI);
    arc(pts, r.right - hr[2], r.bottom - vr[2], hr[2], vr[2], 0, 0.5 * PI);
    arc(pts, r.left + hr[3], r.bottom - vr[3], hr[3], vr[3], 0.5 * PI, PI);
    return pts;
  }
  // Keeps the part of polygon p where sign * (coordinate - v) >= 0 (Sutherland-Hodgman, one edge).
  function cut(p, axis, v, sign) {
    var out = [], n = p.length / 2;
    for (var i = 0; i < n; i++) {
      var j = (i + 1) % n, ax = p[2 * i], ay = p[2 * i + 1], bx = p[2 * j], by = p[2 * j + 1];
      var da = sign * ((axis ? ay : ax) - v), db = sign * ((axis ? by : bx) - v);
      if (da >= 0) out.push(ax, ay);
      if ((da >= 0) !== (db >= 0)) out.push(ax + (bx - ax) * da / (da - db), ay + (by - ay) * da / (da - db));
    }
    return out;
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

    out = [];
    var shape = [];  // the same without the heights: when it changes, the page's picture changes too
    var tops = new Map();  // element -> height of its top over the panel
    for (i = 0; i < raised.length && out.length < 12000; i++) {  // the browser side takes 16384 numbers
      el = raised[i].el;
      var bottom = tops.get(raised[i].on) || 0;
      tops.set(el, bottom);
      if (!el.isConnected) continue;
      cs = getComputedStyle(el);
      var depth = parseFloat(cs.getPropertyValue('--vitrine-depth'));
      if (!(depth > 0) || cs.visibility !== 'visible') continue;
      tops.set(el, bottom + depth);
      r = el.getBoundingClientRect();
      var x0 = 0, y0 = 0, x1 = innerWidth, y1 = innerHeight, clips = raised[i].clips;
      for (var c = 0; c < clips.length; c++) {
        var cr = clips[c].getBoundingClientRect();
        x0 = Math.max(x0, cr.left); y0 = Math.max(y0, cr.top); x1 = Math.min(x1, cr.right); y1 = Math.min(y1, cr.bottom);
      }
      if (r.right <= x0 || r.bottom <= y0 || r.left >= x1 || r.top >= y1) continue;
      var pts = outline(cs, r);
      if (r.left < x0) pts = cut(pts, 0, x0, 1);
      if (r.right > x1) pts = cut(pts, 0, x1, -1);
      if (r.top < y0) pts = cut(pts, 1, y0, 1);
      if (r.bottom > y1) pts = cut(pts, 1, y1, -1);
      if (pts.length < 6) continue;
      out.push(bottom * k, (bottom + depth) * k, pts.length / 2);
      shape.push(pts.length / 2);
      for (var p = 0; p < pts.length; p++) {
        out.push(pts[p] * k);
        shape.push(Math.round(pts[p] * k * 100));
      }
    }
    s = out.map(function (n) { return Math.round(n * 100) / 100; }).join(',');
    if (s !== lastDepth) {
      // First number: 1 when outlines moved (the browser side then waits for this frame's pixels), 0 when only heights did.
      var moved = shape.join(',');
      reportDepth((moved !== lastShape ? '1,' : '0,') + s);
      lastDepth = s;
      lastShape = moved;
    }

    out = [];
    var boxes = new Map(), styles;  // slots in one scrolling container share its box: read it once a frame
    for (i = 0; i < binds.length && out.length < 64; i++) {  // the browser side takes 16 KB
      el = binds[i].el;
      if (!el.isConnected) continue;
      styles = binds[i].styles;
      cs = styles[0];
      var name = cs.getPropertyValue('--vitrine-bind').trim().replace(/^["']|["']$/g, '');
      if (!name || cs.visibility !== 'visible') continue;
      r = el.getBoundingClientRect();
      x0 = 0; y0 = 0; x1 = innerWidth; y1 = innerHeight; clips = binds[i].clips;
      for (c = 0; c < clips.length; c++) {
        cr = boxes.get(clips[c]);
        if (!cr) boxes.set(clips[c], cr = clips[c].getBoundingClientRect());
        x0 = Math.max(x0, cr.left); y0 = Math.max(y0, cr.top); x1 = Math.min(x1, cr.right); y1 = Math.min(y1, cr.bottom);
      }
      if (r.width <= 0 || r.height <= 0 || r.right <= x0 || r.bottom <= y0 || r.left >= x1 || r.top >= y1) continue;
      opacity = 1;
      for (c = 0; c < styles.length; c++) opacity *= +styles[c].opacity;
      if (!(opacity > 0.004)) continue;
      var nums = [r.left * k, r.top * k, r.width * k, r.height * k, x0 * k, y0 * k, x1 * k, y1 * k, opacity,
          corner(cs.borderTopLeftRadius, r) * k, corner(cs.borderTopRightRadius, r) * k,
          corner(cs.borderBottomRightRadius, r) * k, corner(cs.borderBottomLeftRadius, r) * k];
      out.push(nums.map(function (n) { return Math.round(n * 100) / 100; }).join(',') + '\t' +
          name.replace(/\s/g, ' ') + '\t' + (el.getAttribute('data-vitrine') || '').replace(/\s/g, ' '));
    }
    s = out.join('\n');
    if (s !== lastBind) {
      // The report is for this frame, but frames painted before it are still on their way to the game. So this
      // frame is marked: its top left pixel changes (not to the eye), and the browser side waits for that paint.
      if (!mark) {
        mark = document.createElement('div');
        mark.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;z-index:2147483647;pointer-events:none';
      }
      if (!mark.isConnected) document.documentElement.appendChild(mark);
      mark.style.background = (markOn = !markOn) ? 'rgba(0,0,0,.01)' : 'rgba(0,0,0,.02)';
      reportBind(lastBind = s);
    }
  }
  function hurry() { dirty = true; }
  function changed(list) {  // our own mark is no reason to look at the page again
    for (var i = 0; i < list.length; i++) {
      if (list[i].target !== mark && list[i].addedNodes[0] !== mark) return hurry();
    }
  }
  new MutationObserver(changed).observe(document, {subtree: true, childList: true, attributes: true});
  ['pointerover', 'pointerout', 'pointerdown', 'pointerup', 'focusin', 'focusout'].forEach(function (type) {
    addEventListener(type, hurry, true);
  });
  requestAnimationFrame(tick);
}))JS";

class BlurReport : public CefV8Handler {
 public:
  explicit BlurReport(const char* name) : name_(name) {}
  bool Execute(const CefString&, CefRefPtr<CefV8Value>, const CefV8ValueList& args,
               CefRefPtr<CefV8Value>&, CefString&) override {
    auto message = CefProcessMessage::Create(name_);
    message->GetArgumentList()->SetString(0, args.empty() ? CefString() : args[0]->GetStringValue());
    CefV8Context::GetCurrentContext()->GetFrame()->SendProcessMessage(PID_BROWSER, message);
    return true;
  }

 private:
  const char* name_;
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
    start->ExecuteFunction(nullptr, {CefV8Value::CreateFunction("report", new BlurReport("vitrine.blur")),
                                     CefV8Value::CreateFunction("reportDepth", new BlurReport("vitrine.depth")),
                                     CefV8Value::CreateFunction("reportBind", new BlurReport("vitrine.bind"))});
  }
  IMPLEMENT_REFCOUNTING(HelperApp);
};

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE, LPWSTR, int) {
  CefMainArgs args(instance);
  return CefExecuteProcess(args, new HelperApp(), nullptr);
}
