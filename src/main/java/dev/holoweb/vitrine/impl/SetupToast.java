package dev.holoweb.vitrine.impl;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;
//? if >=1.21.2 {
import net.minecraft.client.gui.components.toasts.ToastManager;
//?} else
/*import net.minecraft.client.gui.components.toasts.ToastComponent;*/

/**
 * First-launch toast while the CEF engine downloads and installs: title + percent, size and time left, and a
 * progress bar; then "ready" (or "unavailable") before it slides away. A click (with the cursor free) folds it
 * into a small pill with just the percent and the bar, and back. Drawn with plain fills and text, so it looks
 * the same on every Minecraft version. Render thread only; internal, public only for the mouse mixin.
 */
public final class SetupToast implements Toast {
	private static final int W = 200, H = 40, MINI_W = 84, MINI_H = 17;
	private static final int BACKGROUND = 0xF0121316, BORDER = 0xFF2B2E35, TRACK = 0xFF2A2D34;
	private static final int TITLE = 0xFFFFFFFF, DETAIL = 0xFF9AA0A6, ICON = 0xFF6B7078;
	private static final int BLUE = 0xFF4DA3FF, GREEN = 0xFF57C46A, RED = 0xFFE5534B;

	private enum End { NONE, READY, FAILED }

	/** The toast on screen, for clicks. */
	private static SetupToast current;

	private End end = End.NONE;
	private long endNs;
	private boolean minimized;
	// Where it was last drawn (GUI coordinates), for clicks.
	private float drawnX, drawnY;
	private long drawnNs;
	// Download speed, smoothed, for the time left.
	private long sampleNs, sampleBytes;
	private double bytesPerSec;

	SetupToast() {
		current = this;
	}

	void ready() {
		end = End.READY;
		endNs = System.nanoTime();
	}

	void failed() {
		end = End.FAILED;
		endNs = System.nanoTime();
	}

	private boolean expired() {
		long shown = end == End.READY ? 4_000_000_000L : 10_000_000_000L;
		return end != End.NONE && System.nanoTime() - endNs > shown;
	}

	@Override
	public int width() {
		return minimized ? MINI_W : W;
	}

	@Override
	public int height() {
		return minimized ? MINI_H : H;
	}

	/** Left click anywhere (mixin): true if it hit the toast, which then folds or unfolds. */
	public static boolean click() {
		SetupToast t = current;
		var mc = Minecraft.getInstance();
		if (t == null || mc.mouseHandler.isMouseGrabbed() || System.nanoTime() - t.drawnNs > 200_000_000L) return false;
		var window = mc.getWindow();
		double scale = (double) window.getGuiScaledWidth() / window.getScreenWidth();
		double x = mc.mouseHandler.xpos() * scale - t.drawnX, y = mc.mouseHandler.ypos() * scale - t.drawnY;
		if (x < 0 || y < 0 || x >= t.width() || y >= t.height()) return false;
		t.minimized = !t.minimized;
		return true;
	}

	//? if >=1.21.2 {
	private Visibility visibility = Visibility.SHOW;

	@Override
	public Visibility getWantedVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long time) {
		visibility = expired() ? Visibility.HIDE : Visibility.SHOW;
		if (visibility == Visibility.HIDE && current == this) current = null;
	}

	@Override
	//? if >=26.1 {
	public void extractRenderState(GuiGraphicsExtractor gui, Font font, long time) {
	//?} else
	/*public void render(GuiGraphicsExtractor gui, Font font, long time) {*/
		draw(gui, font);
	}
	//?} else {
	/*@Override
	public Visibility render(GuiGraphicsExtractor gui, ToastComponent toasts, long time) {
		draw(gui, Minecraft.getInstance().font);
		if (!expired()) return Visibility.SHOW;
		if (current == this) current = null;
		return Visibility.HIDE;
	}
	*///?}

	private void draw(GuiGraphicsExtractor gui, Font font) {
		// The toast manager translates the pose to where this toast sits.
		//? if >=1.21.6 {
		drawnX = gui.pose().m20();
		drawnY = gui.pose().m21();
		//?} else {
		/*drawnX = gui.pose().last().pose().m30();
		drawnY = gui.pose().last().pose().m31();
		*///?}
		drawnNs = System.nanoTime();

		CefRuntime.Stage stage = CefRuntime.stage;
		long done = CefRuntime.done, total = CefRuntime.total;
		float fraction = total > 0 ? Math.min(1, (float) done / total) : 0;
		String title, detail, percent = total > 0 ? Math.round(fraction * 100) + "%" : "";
		int accent = BLUE;
		if (end == End.READY) {
			title = tr("vitrine.setup.ready");
			detail = tr("vitrine.setup.ready.detail");
			percent = "OK";
			accent = GREEN;
			fraction = 1;
		} else if (end == End.FAILED) {
			title = tr("vitrine.setup.failed");
			detail = tr("vitrine.setup.failed.detail");
			percent = "!";
			accent = RED;
			fraction = 1;
		} else if (stage == CefRuntime.Stage.INSTALLING) {
			title = tr("vitrine.setup.installing");
			detail = tr("vitrine.setup.installing.detail");
		} else {
			title = tr("vitrine.setup.downloading");
			detail = "Vitrine · " + megabytes(done, total) + timeLeft(done, total);
		}

		int w = width(), h = height();
		panel(gui, w, h);
		if (minimized) {
			// [42%  ━━━━━━━]
			gui.text(font, percent, 6, 5, accent, false);
			bar(gui, 6 + Math.max(font.width("100%"), font.width(percent)) + 5, w - 6, 7, fraction, accent);
			return;
		}
		gui.text(font, title, 8, 8, TITLE, false);
		gui.fill(w - 13, 11, w - 7, 12, ICON); // "–": click folds it
		if (!percent.isEmpty() && end == End.NONE) gui.text(font, percent, w - 19 - font.width(percent), 8, accent, false);
		gui.text(font, detail, 8, 19, DETAIL, false);
		bar(gui, 8, w - 8, h - 11, fraction, accent);
	}

	private static void panel(GuiGraphicsExtractor gui, int w, int h) {
		gui.fill(0, 0, w, h, BACKGROUND);
		gui.fill(0, 0, w, 1, BORDER);
		gui.fill(0, h - 1, w, h, BORDER);
		gui.fill(0, 1, 1, h - 1, BORDER);
		gui.fill(w - 1, 1, w, h - 1, BORDER);
	}

	/** 3 px tall from {@code y}. */
	private static void bar(GuiGraphicsExtractor gui, int x0, int x1, int y, float fraction, int color) {
		gui.fill(x0, y, x1, y + 3, TRACK);
		gui.fill(x0, y, x0 + Math.round((x1 - x0) * fraction), y + 3, color);
	}

	private static String megabytes(long done, long total) {
		long mb = 1024 * 1024;
		return total > 0 ? String.format(Locale.ROOT, "%d / %d MB", done / mb, total / mb) : (done / mb) + " MB";
	}

	/** " · ~45 s" from the smoothed speed, or "" until there's a reading. */
	private String timeLeft(long done, long total) {
		long now = System.nanoTime();
		if (sampleNs == 0) {
			sampleNs = now;
			sampleBytes = done;
		} else if (now - sampleNs >= 1_000_000_000L) {
			double speed = (done - sampleBytes) / ((now - sampleNs) / 1e9);
			bytesPerSec = bytesPerSec == 0 ? speed : bytesPerSec * 0.7 + speed * 0.3;
			sampleNs = now;
			sampleBytes = done;
		}
		if (total <= 0 || bytesPerSec <= 0) return "";
		long seconds = Math.round((total - done) / bytesPerSec);
		return seconds < 60 ? " · ~" + Math.max(1, seconds) + " s" : " · ~" + (seconds + 30) / 60 + " min";
	}

	private static String tr(String key) {
		return Component.translatable(key).getString();
	}
}
