package dev.holoweb.vitrine.impl;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import org.joml.Vector3f;

/**
 * The player's hands on world panels, with no screen open:
 * <ul>
 *   <li>the crosshair is the mouse: attack/use buttons click the page, holding attack drags, the wheel scrolls;</li>
 *   <li>clicking a text field captures the keyboard (with an on-screen notice) until Esc or the field loses focus.</li>
 * </ul>
 * While the crosshair is on a panel, vanilla attack/use/pick/hotbar-scroll are suppressed (mixins ask {@link #capturesMouse}).
 * Internal: public only for the mixins.
 */
public final class WorldInput {
	/** How far the crosshair reaches world panels, in blocks. */
	private static final double REACH = 12;
	private static final long DOUBLE_CLICK_NS = 500_000_000L;

	private static WorldPanel hovered, pressed, focused, lastClicked;
	private static int[] at; // page pixels under the crosshair (on hovered, or on pressed while dragging)
	private static boolean attackDown, useDown, typing;
	private static int buttons, mods, clicks;
	private static long lastClickNs, typingSinceNs;

	private WorldInput() {}

	public static boolean capturesMouse() {
		return hovered != null || pressed != null;
	}

	public static boolean typing() {
		return typing;
	}

	/** Once per frame, after panels moved. */
	static void frame(List<WorldPanel> panels) {
		var mc = Minecraft.getInstance();
		if (VitrineScreen.current() != null || mc.player == null) {
			reset();
			return;
		}
		Vector3f dir = WorldPanel.ray(0, 0);

		// Panel under the crosshair, unless a block is in front of it.
		WorldPanel best = null;
		double[] bestHit = null;
		for (WorldPanel v : panels) {
			double[] h = v.hit(WorldPanel.camPos, dir, true);
			if (h != null && h[0] <= REACH && (bestHit == null || h[0] < bestHit[0])) {
				best = v;
				bestHit = h;
			}
		}
		HitResult block = mc.hitResult;
		if (bestHit != null && block != null && block.getType() != HitResult.Type.MISS
				&& block.getLocation().distanceTo(WorldPanel.camPos) < bestHit[0]) {
			best = null;
		}

		if (hovered != null && hovered != best && hovered != pressed) leave(hovered);
		hovered = best;
		// While dragging, keep tracking the pressed panel's plane even off its edges.
		WorldPanel target = pressed != null ? pressed : best;
		double[] h = target == null ? null : target == best ? bestHit : target.hit(WorldPanel.camPos, dir, false);
		at = h == null ? null : new int[] {(int) h[1], (int) h[2]};
		if (at != null) Native.mouseMove(target.browser.handle, at[0], at[1], mods, buttons, false);

		boolean attack = mc.options.keyAttack.isDown(), use = mc.options.keyUse.isDown();
		if (attack != attackDown) button(0, attack);
		if (use != useDown) button(2, use);
		attackDown = attack;
		useDown = use;

		// Keyboard follows the page's text focus.
		boolean editable = focused != null && Native.editable(focused.browser.handle);
		if (editable && !typing) startTyping();
		else if (!editable && typing) stopTyping(false);
	}

	/** {@code cef}: 0 left (attack), 2 right (use). */
	private static void button(int cef, boolean down) {
		if (down) {
			if (hovered == null) {
				if (cef == 0 && focused != null) unfocus(); // clicking the world blurs the panel
				return;
			}
			if (focused != hovered) {
				if (focused != null) unfocus();
				focused = hovered;
				Native.setFocus(focused.browser.handle, true);
			}
			long now = System.nanoTime();
			clicks = lastClicked == hovered && now - lastClickNs < DOUBLE_CLICK_NS ? clicks + 1 : 1;
			lastClicked = hovered;
			lastClickNs = now;
			buttons |= 1 << cef;
			if (cef == 0) pressed = hovered;
			Native.mouseButton(hovered.browser.handle, at[0], at[1], cef, false, clicks, mods, buttons);
		} else {
			WorldPanel target = cef == 0 ? pressed : hovered;
			if ((buttons & (1 << cef)) == 0 || target == null) return;
			buttons &= ~(1 << cef);
			int[] p = at != null ? at : new int[] {-1, -1};
			Native.mouseButton(target.browser.handle, p[0], p[1], cef, true, clicks, mods, buttons);
			if (cef == 0) pressed = null;
		}
	}

	/** Mouse wheel (mixin); true if a panel took it. */
	public static boolean scroll(double dx, double dy) {
		if (hovered == null || at == null) return false;
		Native.mouseWheel(hovered.browser.handle, at[0], at[1], (int) (-dx * 120), (int) (dy * 120), mods);
		return true;
	}

	// --- keyboard capture ---

	private static void startTyping() {
		typing = true;
		typingSinceNs = System.nanoTime();
		KeyMapping.releaseAll(); // stop walking etc. with keys held when the capture starts
		//? if >=26.2 {
		Minecraft.getInstance().textInputManager().startTextInput(WorldInput.class);
		//?} else if >=26.1
		/*Minecraft.getInstance().textInputManager().startTextInput();*/
	}

	private static void stopTyping(boolean blur) {
		typing = false;
		//? if >=26.2 {
		Minecraft.getInstance().textInputManager().stopTextInput(WorldInput.class);
		//?} else if >=26.1
		/*Minecraft.getInstance().textInputManager().stopTextInput();*/
		if (focused != null) Native.preedit(focused.browser.handle, "", 0);
		if (blur) unfocus();
	}

	/**
	 * Key event while typing (mixin). Esc hands the keyboard back to the game.
	 * {@code key}: the game's key code; {@code scancode}: set-1 (see {@link Keys}); {@code modifiers}: the game's.
	 */
	public static void key(int action, int key, int scancode, int modifiers) {
		mods = Keys.mods(modifiers);
		if (key == InputConstants.KEY_ESCAPE) {
			if (action == InputConstants.PRESS) stopTyping(true);
			return;
		}
		Native.key(focused.browser.handle, scancode, mods, action != InputConstants.RELEASE, action == InputConstants.REPEAT);
	}

	public static void text(String text) {
		Native.text(focused.browser.handle, text, mods);
	}

	//? if >=26.1 {
	public static void preedit(PreeditEvent e) {
		Native.preedit(focused.browser.handle, e == null ? "" : e.fullText(), e == null ? 0 : e.caretPosition());
	}
	//?}

	/** HUD: notice while the keyboard belongs to a panel. */
	static void drawHud(GuiGraphicsExtractor gui) {
		if (!typing) return;
		// Fully visible for 5 s, then fades out over 1 s (stays hidden until the next capture).
		float age = (System.nanoTime() - typingSinceNs) / 1e9f;
		float alpha = Math.max(0, Math.min(1, 6 - age));
		if (alpha <= 0.02f) return;
		int a = (int) (alpha * 255) << 24;
		var mc = Minecraft.getInstance();
		// The font is a bitmap: only whole screen pixels per font pixel stay crisp. With GUI scale g,
		// the message uses g-1 px per font pixel and the Esc hint g-2 (never below 1).
		int g = (int) mc.getWindow().getGuiScale(); // a double before 1.21.6, always whole
		int y = gui.guiHeight() - 64; // just above the armor/health rows
		line(gui, mc.font, Component.translatable("vitrine.keyboard_captured"), y, g, Math.max(1, g - 1), a | 0xD8D8D8);
		line(gui, mc.font, Component.translatable("vitrine.keyboard_release"), y + 8, g, Math.max(1, g - 2), a | 0x8C8C8C);
	}

	/** Centered text at {@code px} screen pixels per font pixel, snapped to the pixel grid. */
	private static void line(GuiGraphicsExtractor gui, net.minecraft.client.gui.Font font, Component text, int y, int g, int px, int argb) {
		float scale = (float) px / g;
		int screenX = Math.round((gui.guiWidth() - font.width(text) * scale) / 2 * g);
		var pose = gui.pose();
		//? if >=1.21.6 {
		pose.pushMatrix();
		pose.translate((float) screenX / g, y);
		pose.scale(scale);
		//?} else {
		/*pose.pushPose();
		pose.translate((float) screenX / g, y, 0);
		pose.scale(scale, scale, 1);
		*///?}
		//? if >=26.1 {
		gui.text(font, text, 0, 0, argb);
		//?} else
		/*gui.drawString(font, text, 0, 0, argb);*/
		//? if >=1.21.6 {
		pose.popMatrix();
		//?} else
		/*pose.popPose();*/
	}

	// --- state cleanup ---

	private static void unfocus() {
		if (typing) stopTyping(false);
		Native.setFocus(focused.browser.handle, false);
		focused = null;
	}

	private static void leave(WorldPanel v) {
		Native.mouseMove(v.browser.handle, 0, 0, 0, 0, true);
	}

	/** A screen opened (pause, chat...) or the world is gone: let go of everything. */
	private static void reset() {
		if (pressed != null) {
			Native.mouseButton(pressed.browser.handle, -1, -1, 0, true, 1, 0, 0);
			pressed = null;
		}
		buttons = 0;
		if (hovered != null) leave(hovered);
		hovered = null;
		at = null;
		if (focused != null) unfocus();
		attackDown = useDown = false;
	}

	/** A panel is closing. */
	static void forget(WorldPanel v) {
		if (focused == v) {
			if (typing) stopTyping(false);
			focused = null;
		}
		if (hovered == v) hovered = null;
		if (pressed == v) pressed = null;
		if (lastClicked == v) lastClicked = null;
	}
}
