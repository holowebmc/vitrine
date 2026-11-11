package dev.holoweb.vitrine.impl;

//? if >=1.21.9
import net.minecraft.client.input.KeyEvent;

/**
 * Keyboard values as native wants them: set-1 scancodes (0xE0xx = extended) and SDL_Keymod bits.
 * Internal: public only for the mixins.
 */
public final class Keys {
	private Keys() {}

	//? if >=1.21.9 {
	public static int scancode(KeyEvent e) {
		//? if >=26.2 {
		return Native.hidToSet1(e.key()); // SDL3: key() is the HID usage
		//?} else
		/*return glfwScancode(e.scancode());*/
	}
	//?}

	/** GLFW on Windows reports set-1 with 0x100 = extended. */
	public static int glfwScancode(int sc) {
		return (sc & 0x100) != 0 ? 0xE000 | (sc & 0xFF) : sc;
	}

	static int mods(int mods) {
		//? if >=26.2 {
		return mods; // already SDL_Keymod
		//?} else {
		/*int sdl = 0; // GLFW_MOD_* -> SDL_KMOD_*
		if ((mods & 0x01) != 0) sdl |= 0x0001; // shift
		if ((mods & 0x02) != 0) sdl |= 0x0040; // ctrl
		if ((mods & 0x04) != 0) sdl |= 0x0100; // alt
		if ((mods & 0x08) != 0) sdl |= 0x0400; // super
		if ((mods & 0x10) != 0) sdl |= 0x2000; // caps lock
		if ((mods & 0x20) != 0) sdl |= 0x1000; // num lock
		return sdl;
		*///?}
	}
}
