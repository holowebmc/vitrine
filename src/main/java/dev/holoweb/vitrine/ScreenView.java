package dev.holoweb.vitrine;

import dev.holoweb.vitrine.impl.ScreenPage;

/**
 * A full-screen page over the game, from {@link Vitrine#screen}. Unfocused it is drawn with the HUD
 * (transparent where the page is) and ignores input; focused it frees the cursor and gets all input,
 * and Esc gives focus back to the game.
 */
public final class ScreenView {
	private final ScreenPage page;

	ScreenView(ScreenPage page) {
		this.page = page;
	}

	/** Gives the page the mouse and keyboard (true) or hands them back to the game (false). */
	public void focus(boolean focus) {
		page.focus(focus);
	}

	public boolean isFocused() {
		return page.isFocused();
	}

	/** Hidden pages are not drawn and stop rendering, but keep their state. Visible by default. */
	public void visible(boolean visible) {
		page.visible(visible);
	}

	public boolean isVisible() {
		return page.isVisible();
	}

	/** Volume 0..1 (default 1), on top of the game's "Interface" slider ("Master" before 1.21.6). Stereo. */
	public void volume(float volume) {
		page.volume(volume);
	}

	/**
	 * Sends {@code json} (must be valid JSON) to the page: {@code addEventListener('message', e => e.data)}.
	 * Messages sent before the page has loaded are delivered once it has.
	 */
	public void send(String json) {
		page.send(json);
	}

	/** Handles {@code fetch('vitrine://<modid>/<name>', {method: 'POST', body})} from this page. */
	public ScreenView on(String name, Vitrine.Handler handler) {
		page.on(name, handler);
		return this;
	}

	/** Ends the page. Later calls do nothing. */
	public void close() {
		page.close();
	}
}
