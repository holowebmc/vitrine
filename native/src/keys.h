// Key translation, free of CEF: Minecraft's scancodes -> what Windows and Chromium call the key.
#pragma once
#include <cstdint>

// USB HID usage (SDL3 scancode) -> PS/2 set-1 scancode (0xE0xx = extended), 0 if unknown.
uint16_t HidToSet1(int hid);

// Set-1 scancode -> Windows virtual key for the current keyboard layout, 0 if none.
// sdlMods: SDL_Keymod bits (NumLock decides what the keypad digits are).
int VirtualKey(uint16_t scancode, int sdlMods);
