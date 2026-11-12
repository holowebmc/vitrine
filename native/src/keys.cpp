#include "keys.h"

#include <windows.h>

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
  if ((sdlMods & 0x1000 /* SDL_KMOD_NUM */) || (GetKeyState(VK_NUMLOCK) & 1)) {
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
