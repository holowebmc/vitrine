package dev.holoweb.vitrine.impl;

import dev.holoweb.vitrine.Vitrine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * Behind {@link dev.holoweb.vitrine.ScreenView}. Unfocused it is drawn with the HUD and ignores input;
 * focused it opens a {@link VitrineScreen} that frees the cursor and sends all input to the page.
 * Until CEF is up there is no browser: settings, handlers and messages wait for {@link #start}.
 */
public final class ScreenPage {
	private final String url;
	private final Identifier id;
	final Map<String, Vitrine.Handler> handlers = new HashMap<>();
	private List<String> pending = new ArrayList<>(); // messages sent before start
	Browser browser;
	private boolean visible = true, wantsFocus;
	private float volume = 1;
	private boolean closed;
	//? if >=26.2
	private final Binds binds = new Binds();

	ScreenPage(String url, Identifier id) {
		this.url = url;
		this.id = id;
	}

	void start() {
		if (closed || browser != null) return;
		var window = Minecraft.getInstance().getWindow();
		//? if >=1.21.6 {
		browser = new Browser(url, window.getWidth(), window.getHeight(), 60, id, false, SoundSource.UI);
		//?} else
		/*browser = new Browser(url, window.getWidth(), window.getHeight(), 60, id, false, SoundSource.MASTER);*/
		browser.handlers = handlers;
		if (!visible) Native.setHidden(browser.handle, true);
		for (String json : pending) Native.send(browser.handle, json);
		pending = null;
		if (wantsFocus) focus(true);
	}

	/** Gives the page the mouse and keyboard (true) or hands them back to the game (false). */
	public void focus(boolean focus) {
		wantsFocus = focus;
		if (browser == null) return;
		if (focus) {
			if (!isFocused()) VitrineScreen.show(new VitrineScreen(this));
		} else if (isFocused()) {
			VitrineScreen.show(null);
		}
	}

	public boolean isFocused() {
		return VitrineScreen.current() instanceof VitrineScreen s && s.view == this;
	}

	/** Hidden pages are not drawn and stop rendering in CEF. */
	public void visible(boolean visible) {
		this.visible = visible;
		if (browser != null) Native.setHidden(browser.handle, !visible);
	}

	/** The page's volume, 0..1 (default 1), on top of the game's "Interface" slider ("Master" before 1.21.6). Plays in stereo. */
	public void volume(float volume) {
		this.volume = volume;
	}

	public boolean isVisible() {
		return visible;
	}

	public void send(String json) {
		if (browser == null) pending.add(json);
		else Native.send(browser.handle, json);
	}

	public void on(String name, Vitrine.Handler handler) {
		handlers.put(name, handler);
	}

	public void close() {
		if (closed) return;
		focus(false);
		closed = true;
		Pages.remove(this);
		if (browser != null) browser.release();
		//? if >=26.2
		binds.close();
	}

	/** Called every frame: keeps the page at framebuffer size and uploads new frames. */
	void frame() {
		if (browser == null) return;
		var window = Minecraft.getInstance().getWindow();
		browser.resize(window.getWidth(), window.getHeight());
		browser.update();
		browser.audio.update(null, volume, 0);
		//? if >=26.2
		if (visible) binds.render(browser.handle);
	}

	void draw(GuiGraphicsExtractor gui) {
		if (visible && browser != null && browser.hasFrame()) {
			//? if >=26.2 {
			float[] blur = Native.blurRects(browser.handle); // backdrop-filter: blur() shows the game, blurred
			if (blur != null) GameBlur.draw(gui, blur);
			//?}
			//? if >=1.21.6 {
			gui.blit(browser.textureId, 0, 0, gui.guiWidth(), gui.guiHeight(), 0, 1, 0, 1);
			//? if >=26.2
			binds.draw(gui); // --vitrine-bind slots, drawn by the game over the page
			//?} else if >=1.21.2 {
			/*int w = browser.width, h = browser.height;
			gui.blit(net.minecraft.client.renderer.RenderType::guiTextured, browser.textureId, 0, 0, 0, 0, gui.guiWidth(), gui.guiHeight(), w, h, w, h);
			*///?} else {
			/*int w = browser.width, h = browser.height;
			com.mojang.blaze3d.systems.RenderSystem.enableBlend(); // this blit draws opaque otherwise
			com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
			gui.blit(browser.textureId, 0, 0, gui.guiWidth(), gui.guiHeight(), 0, 0, w, h, w, h);
			com.mojang.blaze3d.systems.RenderSystem.disableBlend();
			*///?}
		}
	}
}
