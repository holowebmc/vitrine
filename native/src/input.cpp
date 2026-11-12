#include "input.h"

#include <windows.h>

#include "keys.h"

namespace {

// SDL_Keymod (Minecraft 26.x input is SDL3; older versions are converted to it in Java).
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

void SendChar(CefRefPtr<CefBrowserHost> host, char16_t c, uint32_t flags) {
  CefKeyEvent e;
  e.type = KEYEVENT_CHAR;
  e.windows_key_code = c;
  e.character = c;
  e.unmodified_character = c;
  e.modifiers = flags;
  host->SendKeyEvent(e);
}

CefMouseEvent Mouse(int x, int y, int mods, int buttons) {
  CefMouseEvent e;
  e.x = x;
  e.y = y;
  e.modifiers = Flags(mods, buttons);
  return e;
}

}  // namespace

void Input::MouseMove(CefRefPtr<CefBrowserHost> host, int x, int y, int mods, int buttons, bool leave) {
  if (host) host->SendMouseMoveEvent(Mouse(x, y, mods, buttons), leave);
}

void Input::MouseButton(CefRefPtr<CefBrowserHost> host, int x, int y, int button, bool up, int clicks,
                        int mods, int buttons) {
  if (!host) return;
  host->SendMouseClickEvent(Mouse(x, y, mods, buttons), static_cast<cef_mouse_button_type_t>(button), up,
                            clicks);
}

void Input::MouseWheel(CefRefPtr<CefBrowserHost> host, int x, int y, int dx, int dy, int mods) {
  if (host) host->SendMouseWheelEvent(Mouse(x, y, mods, 0), dx, dy);
}

// Mirrors Win32: WM_KEYDOWN -> RAWKEYDOWN (+ WM_CHAR for control keys).
void Input::Key(CefRefPtr<CefBrowserHost> host, int scancode, int mods, bool down, bool repeat) {
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

void Input::Text(CefRefPtr<CefBrowserHost> host, const std::wstring& text, int mods) {
  if (!host) return;
  if (composing_) {
    composing_ = false;
    host->ImeCommitText(text, CefRange::InvalidRange(), 0);
    return;
  }
  for (wchar_t c : text) SendChar(host, static_cast<char16_t>(c), Flags(mods, 0));
}

void Input::Preedit(CefRefPtr<CefBrowserHost> host, const std::wstring& text, int caret) {
  if (!host) return;
  if (text.empty()) {
    if (composing_) host->ImeCancelComposition();
    composing_ = false;
    return;
  }
  CefCompositionUnderline u{};
  u.size = sizeof(u);
  u.range = {0, static_cast<uint32_t>(text.size())};
  u.color = CefColorSetARGB(255, 0, 0, 0);
  u.background_color = CefColorSetARGB(0, 0, 0, 0);
  u.thick = 0;
  u.style = CEF_CUS_SOLID;
  composing_ = true;
  host->ImeSetComposition(text, {u}, CefRange::InvalidRange(),
                          CefRange(static_cast<uint32_t>(caret), static_cast<uint32_t>(caret)));
}
