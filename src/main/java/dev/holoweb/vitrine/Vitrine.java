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
 * <p>Game rendering in a page (Minecraft 26.2+): see {@code bind}.
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

	//? if >=26.2 {
	/**
	 * Fills the slots that screen pages leave for {@code name} with something the game draws: an element with
	 * {@code --vitrine-bind: <name>} in its CSS (e.g. {@code <div style="--vitrine-bind: mymod:preview">}) is a slot.
	 * The page decides where the slot is, how big, what clips it ({@code overflow} of ancestors) and its
	 * {@code opacity}; what shows in it is {@code bind}'s alone, and the page can neither read nor change it.
	 *
	 * <p>Ready to use, with what to show in the element's {@code data-vitrine}: {@code vitrine:item}
	 * ({@code "minecraft:diamond_sword"}, as in the inventory), {@code vitrine:entity}
	 * ({@code "minecraft:zombie"}, optionally followed by a yaw and a pitch in degrees: {@code "minecraft:zombie 30 10"})
	 * and {@code vitrine:player} (the player as they are now; {@code "30 10"} turns them). They fit themselves to
	 * the slot and need a world. A page can change {@code data-vitrine} at any time, e.g. to turn a model as the
	 * mouse drags.
	 *
	 * <p>The slot is drawn over the page, in the element's box with its {@code border-radius}: its background and what it sits in are behind it, but
	 * so is anything the page puts on top of it (a tooltip, a menu). It moves with the element, in step with
	 * the page's picture (scrolling, animations), and is drawn again about 60 times a second, the page's own rate. Names are global: prefix them with your mod id. {@code null} removes the
	 * bind; one that throws is logged and removed. World panels have slots too, flat on the panel.
	 */
	public static void bind(String name, Bind bind) {
		Pages.bind(name, bind);
	}

	/** Draws one slot, while it is visible. See {@link #bind}. */
	@FunctionalInterface
	public interface Bind {
		/**
		 * Submit what the slot shows to {@code nodes}, as a {@code PictureInPictureRenderer.renderToTexture} does:
		 * {@code pose} has its origin at the centre of the slot, one unit per screen pixel, y down, and the
		 * lighting is the inventory's ({@code Lighting.Entry.ENTITY_IN_UI}; set another if you need). Scale it
		 * to taste, e.g. {@code pose.scale(s, s, s)} then an entity as {@code InventoryScreen} submits it.
		 */
		void render(Slot slot, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector nodes);
	}

	/**
	 * One slot being drawn: its size in screen pixels, and the element's {@code data-vitrine} attribute ("" if
	 * absent), for one bind to tell its slots apart (which entity, which item...). {@code data} comes from the page.
	 */
	public record Slot(int width, int height, String data) {}
	//?}

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
