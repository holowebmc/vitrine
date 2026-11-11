package dev.holoweb.vitrine.impl;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if >=1.21.9 {
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?} else {
/*import org.lwjgl.glfw.GLFW;
*///?}
//? if >=26.1
import net.minecraft.client.input.PreeditEvent;

/** Open while a {@link ScreenPage} has focus: draws it and forwards all input. Esc hands focus back. */
final class VitrineScreen extends Screen {
	final ScreenPage view;
	private final Browser b;
	private int mods;

	VitrineScreen(ScreenPage view) {
		super(Component.literal("Vitrine"));
		this.view = view;
		this.b = view.browser;
	}

	/** The open screen; it moved from Minecraft to Gui after 26.1. */
	static Screen current() {
		//? if >=26.2 {
		return Minecraft.getInstance().gui.screen();
		//?} else {
		/*return Minecraft.getInstance().screen;
		*///?}
	}

	static void show(Screen screen) {
		//? if >=26.2 {
		Minecraft.getInstance().gui.setScreen(screen);
		//?} else {
		/*Minecraft.getInstance().setScreen(screen);
		*///?}
	}

	@Override
	protected void init() {
		Native.setFocus(b.handle, true);
		//? if >=26.2 {
		minecraft.textInputManager().startTextInput(this);
		//?} else if >=26.1
		/*minecraft.textInputManager().startTextInput();*/
	}

	@Override
	public void removed() {
		//? if >=26.2 {
		minecraft.textInputManager().stopTextInput(this);
		//?} else if >=26.1
		/*minecraft.textInputManager().stopTextInput();*/
		//? if <1.21.9
		/*setCursor(-1);*/
		Native.preedit(b.handle, "", 0);
		Native.mouseMove(b.handle, 0, 0, 0, 0, true);
		Native.setFocus(b.handle, false);
		b.buttons = 0;
	}

	@Override
	//? if >=26.1 {
	public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {
	//?} else
	/*public void render(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {*/
		view.draw(gui);
		//? if >=1.21.9 {
		gui.requestCursor(CURSORS[b.cursor()]);
		//?} else
		/*setCursor(b.cursor());*/
	}

	//? if >=1.20.2 {
	@Override
	//? if >=26.1 {
	public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {
	//?} else
	/*public void renderBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {*/
		// no blur/dim: the page decides what covers the game (before 1.20.2 screens draw it themselves)
	}
	//?}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// --- cursor: CEF's cursor index (Browser#cursor) -> the game's ---

	//? if >=1.21.9 {
	private static final CursorType[] CURSORS = {
		CursorTypes.ARROW, CursorTypes.IBEAM, CursorTypes.POINTING_HAND, CursorTypes.CROSSHAIR,
		CursorTypes.RESIZE_EW, CursorTypes.RESIZE_NS, CursorTypes.RESIZE_ALL, CursorTypes.NOT_ALLOWED,
	};
	//?} else {
	/*// No cursor API in the game before 1.21.9: set GLFW's standard cursors ourselves.
	private static final int[] SHAPES = {
		GLFW.GLFW_ARROW_CURSOR, GLFW.GLFW_IBEAM_CURSOR, GLFW.GLFW_POINTING_HAND_CURSOR, GLFW.GLFW_CROSSHAIR_CURSOR,
		GLFW.GLFW_RESIZE_EW_CURSOR, GLFW.GLFW_RESIZE_NS_CURSOR, GLFW.GLFW_RESIZE_ALL_CURSOR, GLFW.GLFW_NOT_ALLOWED_CURSOR,
	};
	private static final long[] GLFW_CURSORS = new long[SHAPES.length];
	private int cursor = -1;

	// -1 = back to the default cursor.
	private void setCursor(int c) {
		if (c == cursor) return;
		cursor = c;
		if (c >= 0 && GLFW_CURSORS[c] == 0) GLFW_CURSORS[c] = GLFW.glfwCreateStandardCursor(SHAPES[c]);
		GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().getWindow(), c < 0 ? 0 : GLFW_CURSORS[c]);
	}
	*///?}

	// --- input: GUI coords -> page pixels ---

	private int px(double x) {
		return (int) (x * b.width / width);
	}

	private int py(double y) {
		return (int) (y * b.height / height);
	}

	@Override
	public void mouseMoved(double x, double y) {
		Native.mouseMove(b.handle, px(x), py(y), mods, b.buttons, false);
	}

	private boolean button(double x, double y, int button, boolean up, int clicks) {
		int cef = switch (button) { // GLFW 0 left, 1 right, 2 middle; SDL 1, 3, 2 -> CEF: 0 left, 1 middle, 2 right
			case InputConstants.MOUSE_BUTTON_LEFT -> 0;
			case InputConstants.MOUSE_BUTTON_MIDDLE -> 1;
			case InputConstants.MOUSE_BUTTON_RIGHT -> 2;
			default -> -1;
		};
		if (cef < 0) return false;
		b.buttons = up ? b.buttons & ~(1 << cef) : b.buttons | (1 << cef);
		Native.mouseButton(b.handle, px(x), py(y), cef, up, clicks, mods, b.buttons);
		return true;
	}

	/** {@code key}: the game's key code (for Esc); {@code scancode}: set-1, see {@link Keys}. */
	private boolean key(int key, int scancode, int modifiers, boolean down) {
		mods = Keys.mods(modifiers);
		Native.key(b.handle, scancode, mods, down, false);
		// ponytail: Esc always returns focus to the game; make it opt-out if pages need Esc themselves.
		if (down && key == InputConstants.KEY_ESCAPE) onClose();
		return true;
	}

	//? if <1.20.2 {
	/*@Override
	public boolean mouseScrolled(double x, double y, double dy) {
		return mouseScrolled(x, y, 0, dy);
	}
	*///?}

	//? if >=1.20.2
	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		// 120 = one wheel notch on Windows. Chromium's horizontal axis is inverted vs the game's.
		Native.mouseWheel(b.handle, px(x), py(y), (int) (-dx * 120), (int) (dy * 120), mods);
		return true;
	}

	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		mods = Keys.mods(e.modifiers());
		return button(e.x(), e.y(), e.button(), false, doubleClick ? 2 : 1);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		mods = Keys.mods(e.modifiers());
		return button(e.x(), e.y(), e.button(), true, 1);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		return true; // movement already arrives through mouseMoved
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		return key(e.key(), Keys.scancode(e), e.modifiers(), true);
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		return key(e.key(), Keys.scancode(e), e.modifiers(), false);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		Native.text(b.handle, e.codepointAsString(), mods);
		return true;
	}
	//?} else {
	/*private long lastClickMs;
	private int lastButton = -1, clicks;

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		long now = System.currentTimeMillis(); // the game reports no double clicks before 1.21.9
		clicks = button == lastButton && now - lastClickMs < 500 ? clicks + 1 : 1;
		lastButton = button;
		lastClickMs = now;
		return button(x, y, button, false, clicks);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return button(x, y, button, true, clicks);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return true; // movement already arrives through mouseMoved
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return key(key, Keys.glfwScancode(scancode), modifiers, true);
	}

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		return key(key, Keys.glfwScancode(scancode), modifiers, false);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		Native.text(b.handle, String.valueOf(c), mods);
		return true;
	}
	*///?}

	//? if >=26.1 {
	@Override
	public boolean preeditUpdated(PreeditEvent e) {
		Native.preedit(b.handle, e == null ? "" : e.fullText(), e == null ? 0 : e.caretPosition());
		return true;
	}
	//?}
}
