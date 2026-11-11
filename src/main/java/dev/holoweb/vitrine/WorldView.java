package dev.holoweb.vitrine;

import dev.holoweb.vitrine.impl.WorldPanel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;

/**
 * A page on a flat panel in the world, from {@link Vitrine#world}. The panel is readable from +Z of its
 * rotation and keeps the page's aspect. Players aim at it and click with the left button; clicking a text
 * field takes the keyboard until Esc. It costs nothing while hidden: beyond {@link #maxDistance}, outside
 * the view, or {@link #visible visible(false)}; and it renders at a lower frame rate when far.
 */
public final class WorldView {
	private final WorldPanel panel;

	WorldView(WorldPanel panel) {
		this.panel = panel;
	}

	/** Places the panel: {@code center} in world coordinates, {@code rotation} of its +Z face, width in blocks. */
	public void move(Vec3 center, Quaternionfc rotation, float width) {
		panel.move(center, rotation, width);
	}

	/**
	 * Billboard: the panel keeps its position but turns (yaw and pitch, no roll) to face the camera.
	 * It follows where the player is, not where they look. Off by default; turning it off keeps the last rotation.
	 */
	public void billboard(boolean on) {
		panel.billboard(on);
	}

	/** Same as {@link ScreenView#send}. */
	public void send(String json) {
		panel.send(json);
	}

	/** Same as {@link ScreenView#on}. */
	public WorldView on(String name, Vitrine.Handler handler) {
		panel.on(name, handler);
		return this;
	}

	/** Visible by default. */
	public void visible(boolean visible) {
		panel.visible(visible);
	}

	/** Beyond this distance (blocks, default 64) the panel is hidden. */
	public void maxDistance(float blocks) {
		panel.maxDistance(blocks);
	}

	/**
	 * Sound comes from the panel and fades linearly to silence at {@code range} blocks (default 24), like
	 * vanilla sounds; {@code volume} 0..1 (default 1) is on top of the game's "Jukebox/Note Blocks" slider.
	 */
	public void audio(float volume, float range) {
		panel.audio(volume, range);
	}

	/** Removes the panel and ends the page. Later calls do nothing. */
	public void close() {
		panel.close();
	}
}
