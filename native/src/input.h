// Input for one page: Minecraft's mouse, key and text events -> the events CEF receives.
// Keys are set-1 scancodes (0xE0xx = extended) and modifiers are SDL_Keymod bits (Java's Keys converts
// from each Minecraft version). Render thread only. A null host (page still being created) is ignored.
#pragma once
#include <string>

#include "include/cef_browser.h"

class Input {
 public:
  // buttons: bit0 left, bit1 middle, bit2 right (held). button: 0 left, 1 middle, 2 right.
  static void MouseMove(CefRefPtr<CefBrowserHost> host, int x, int y, int mods, int buttons, bool leave);
  static void MouseButton(CefRefPtr<CefBrowserHost> host, int x, int y, int button, bool up, int clicks,
                          int mods, int buttons);
  static void MouseWheel(CefRefPtr<CefBrowserHost> host, int x, int y, int dx, int dy, int mods);

  static void Key(CefRefPtr<CefBrowserHost> host, int scancode, int mods, bool down, bool repeat);

  // Typed text; commits the IME composition if one is active.
  void Text(CefRefPtr<CefBrowserHost> host, const std::wstring& text, int mods);
  // IME composition (preedit); empty text cancels it.
  void Preedit(CefRefPtr<CefBrowserHost> host, const std::wstring& text, int caret);

 private:
  bool composing_ = false;
};
