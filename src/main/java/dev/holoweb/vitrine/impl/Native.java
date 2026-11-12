package dev.holoweb.vitrine.impl;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JNI surface of vitrine.dll. Call from the render thread; CEF itself runs on its own thread.
 * Input: set-1 scancodes (0xE0xx = extended) and SDL_Keymod bits; {@code Keys} converts from each Minecraft version.
 */
public final class Native {
	private Native() {}

	/** Loads libcef and the bridge from {@code cefDir}. Order matters: vitrine.dll links libcef.dll. */
	public static void load(Path cefDir) {
		for (String lib : new String[] {"chrome_elf.dll", "libcef.dll", "vitrine.dll"}) {
			Path p = cefDir.resolve(lib);
			if (!Files.isRegularFile(p)) throw new IllegalStateException("Vitrine: missing " + p);
			System.load(p.toAbsolutePath().toString());
		}
	}

	public static native boolean init(String cefDir, String cacheDir);

	/** Waits (bounded) for destroyed browsers to finish closing, then shuts CEF down. */
	public static native void shutdown();

	/**
	 * {@code monoAudio}: downmix captured audio to mono (needed for 3D positioning in OpenAL).
	 * {@code gpuFrames}: frames arrive as shared GPU textures ({@link #copyGpu}) instead of pixels ({@link #copyIfDirty}).
	 */
	public static native long create(String url, int width, int height, int fps, boolean monoAudio, boolean gpuFrames);

	/** Render thread, GL context current: sets up D3D11 + WGL_NV_DX_interop2. False = CPU frames only. */
	public static native boolean gpuInit();

	/** Copies the newest GPU frame into GL texture {@code glTexture} (GL_RGBA8, w x h); false if none is new. */
	public static native boolean copyGpu(long handle, int glTexture, int width, int height);

	/** Render thread, Vulkan backend: D3D11 device on the adapter with this LUID. False = CPU frames. */
	public static native boolean gpuInitVulkan(long luid);

	/** Vulkan: newest frame into {@code out} = {slot, NT handle, generation, width, height}; false if none. */
	public static native boolean vkTake(long handle, long[] out);

	/** Vulkan: the GPU is done reading {@code slot}; CEF may write it again. */
	public static native void vkRelease(long handle, int slot, int generation);

	/** The page's shared textures could not be opened; recreate it with CPU frames. */
	public static native boolean gpuFailed(long handle);

	/**
	 * Where the page has {@code backdrop-filter: blur()}, 10 floats per element in page pixels:
	 * x, y, width, height, blur radius, corner radii (TL, TR, BR, BL), opacity. Null when there is none.
	 */
	public static native float[] blurRects(long handle);

	/** Sample rate of the page's current audio stream, 0 while it is silent. */
	public static native int audioRate(long handle);

	/** Copies up to {@code maxBytes} of int16 PCM (mono, or interleaved stereo) to {@code dst}; returns bytes copied. */
	public static native int readAudio(long handle, long dst, int maxBytes);

	public static native void destroy(long handle);

	public static native void resize(long handle, int width, int height);

	/**
	 * Copies the newest frame (RGBA, straight alpha) to {@code dst} if one arrived since the last call.
	 * {@code size} must equal width*height*4 of the current size, otherwise nothing is copied.
	 */
	public static native boolean copyIfDirty(long handle, long dst, long size);

	/** Posts {@code json} to the page as a {@code message} event; queued until the page has loaded. */
	public static native void send(long handle, String json);

	/** Next queued vitrine:// request as {id, view handle, method, host, path, body bytes, trusted}, or null. */
	public static native Object[] nextRequest();

	/** Answers a request from {@link #nextRequest}; ignored if it was cancelled meanwhile. */
	public static native void respond(long id, int status, String mime, byte[] body);

	/** Index into {@code Browser.CURSORS}. */
	public static native int cursor(long handle);

	public static native void setHidden(long handle, boolean hidden);

	public static native void setFocus(long handle, boolean focus);

	/** True while a text field (input, textarea, contenteditable) in the page has focus. */
	public static native boolean editable(long handle);

	/** Changes the page's frame-rate cap (windowless_frame_rate). */
	public static native void setFps(long handle, int fps);

	/** {@code buttons}: bit0 left, bit1 middle, bit2 right (held). */
	public static native void mouseMove(long handle, int x, int y, int mods, int buttons, boolean leave);

	/** {@code button}: 0 left, 1 middle, 2 right. */
	public static native void mouseButton(long handle, int x, int y, int button, boolean up, int clicks, int mods, int buttons);

	public static native void mouseWheel(long handle, int x, int y, int dx, int dy, int mods);

	/** USB HID usage (SDL3 scancode) -> set-1 scancode, 0 if unknown. */
	public static native int hidToSet1(int hid);

	public static native void key(long handle, int scancode, int mods, boolean down, boolean repeat);

	public static native void text(long handle, String text, int mods);

	/** IME composition; empty text cancels it. */
	public static native void preedit(long handle, String text, int caret);
}
