package dev.holoweb.vitrine;

import dev.holoweb.vitrine.impl.Pages;
import java.io.IOException;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;

/**
 * Vitrine: web pages (Chromium, via CEF) drawn over the game or on panels in the world.
 *
 * <p>Pages load from any URL, or from your mod's jar with {@code vitrine://<modid>/<path>}, which serves
 * {@code assets/<modid>/vitrine/<path>}. Java and the page talk in JSON strings: {@link ScreenView#send}
 * (Java to page) and {@link ScreenView#on} (page to Java, as a {@code fetch}).
 *
 * <p>Game items as images (Minecraft 26.2+, while in a world): {@code <img src="vitrine://item/minecraft:chest">}
 * is the item's inventory icon, modded items included; add {@code /128} for a size in pixels (default 64).
 *
 * <p>Everything here runs on the render thread (client events, screens, key handlers). Views can be
 * created at any time, also while the engine is still downloading on first launch: a page starts
 * loading once {@link #isReady()}, and what you set or send before that is kept for it. On a system
 * where the engine can't run (only Windows x64 for now), views exist but stay empty.
 */
public final class Vitrine {
	private Vitrine() {}

	/** True once the engine is running and pages load. */
	public static boolean isReady() {
		return Pages.isReady();
	}

	/** A full-screen page over the game: drawn with the HUD, visible and unfocused. */
	public static ScreenView screen(String url) {
		return new ScreenView(Pages.screen(url));
	}

	/**
	 * A page of {@code pxWidth} x {@code pxHeight} pixels on a panel {@code width} blocks wide, centred at
	 * {@code center}. It is readable from +Z of {@code rotation}; e.g. facing a player with yaw {@code y}:
	 * {@code new Quaternionf().rotateY((float) Math.toRadians(180 - y))}.
	 */
	public static WorldView world(String url, int pxWidth, int pxHeight, Vec3 center, Quaternionfc rotation, float width) {
		return new WorldView(Pages.world(url, pxWidth, pxHeight, center, rotation, width));
	}

	/**
	 * Serves {@code vitrine://<host>/<path>} from {@code files} instead of the jar, e.g. pages a mod downloads
	 * at runtime; {@code null} goes back to the jar. {@code host} is lowercase ({@code a-z 0-9 . _ -}). Any
	 * thread; {@code files} is read on the render thread.
	 */
	public static void serve(String host, Files files) {
		Pages.serve(host, files);
	}

	/** Files for {@link #serve}: the bytes at {@code path} ({@code "index.html"}, {@code "img/logo.png"}), or null. */
	@FunctionalInterface
	public interface Files {
		byte[] read(String path) throws IOException;
	}

	/**
	 * Answers {@code fetch('vitrine://<modid>/<name>', {method: 'POST', body})} from a page, on the render
	 * thread. Only pages loaded from vitrine:// can call handlers.
	 */
	@FunctionalInterface
	public interface Handler {
		/** {@code body} is the request body as text (usually JSON). Answer exactly once, now or later. */
		void handle(String body, Reply reply);
	}

	/** The answer to one page request: the page's {@code fetch} resolves when it is sent. Render thread only. */
	public interface Reply {
		/** 200 with a JSON body. */
		void ok(String json);

		/** 500 with {@code message} as text; the page sees {@code response.ok === false}. */
		void error(String message);
	}
}
