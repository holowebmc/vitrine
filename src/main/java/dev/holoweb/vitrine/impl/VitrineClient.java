package dev.holoweb.vitrine.impl;

import dev.holoweb.vitrine.ScreenView;
import dev.holoweb.vitrine.Vitrine;
import dev.holoweb.vitrine.WorldView;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else if >=1.21.9 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
*///?}
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VitrineClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("vitrine");

	/** Set by the setup thread once the runtime is on disk and its DLLs are loaded; consumed on the render thread. */
	private static volatile Path loaded;
	private static volatile boolean setupFailed;
	/** Shown only when there's something to install (or it failed); render thread. */
	private static SetupToast setupToast;

	@Override
	public void onInitializeClient() {
		String os = System.getProperty("os.name", ""), arch = System.getProperty("os.arch", "");
		if (!os.startsWith("Windows") || !arch.equals("amd64")) {
			LOG.warn("Vitrine: only Windows x64 is supported for now ({} {}); pages are disabled", os, arch);
			return;
		}
		// First run downloads CEF (~170 MB): keep it off the game's startup. Pages work once it's done.
		Thread setup = new Thread(VitrineClient::setUp, "Vitrine CEF setup");
		setup.setDaemon(true);
		setup.start();

		//? if >=1.21.6 {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("vitrine", "screens"),
				(gui, delta) -> Pages.drawHud(gui, VitrineScreen.current()));
		//?} else
		/*HudRenderCallback.EVENT.register((gui, delta) -> Pages.drawHud(gui, VitrineScreen.current()));*/
		//? if >=26.1 {
		LevelRenderEvents.COLLECT_SUBMITS.register(Pages::renderWorld);
		//?} else if >=1.21.9 {
		/*WorldRenderEvents.END_EXTRACTION.register(WorldPanel::captureCamera);
		WorldRenderEvents.BEFORE_ENTITIES.register(Pages::renderWorld);
		*///?} else {
		/*WorldRenderEvents.AFTER_ENTITIES.register(Pages::renderWorld);
		*///?}
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Pages.shutdown());

		BuiltinBinds.register();

		if (Boolean.getBoolean("vitrine.demo")) registerDemo(); // set by this project's runClient only
	}

	private static void setUp() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		String devDir = System.getProperty("vitrine.cefDir"); // dev: the CMake output, used as is
		try {
			Path dir = devDir != null ? Path.of(devDir) : CefRuntime.prepare(gameDir.resolve("vitrine/cef"));
			Native.load(dir);
			loaded = dir;
		} catch (Throwable t) {
			LOG.error("Vitrine: CEF runtime unavailable; pages are disabled", t);
			setupFailed = true;
		}
	}

	/** Render thread, every frame until CEF is up (Pages.frame). CefInitialize must run on this thread. */
	static void startIfLoaded() {
		setupToast();
		Path dir = loaded;
		if (dir == null) return;
		loaded = null;
		try {
			Path cache = Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve("vitrine/cache"));
			if (!Native.init(dir.toString(), cache.toString())) {
				LOG.error("Vitrine: CEF failed to initialize from {}", dir);
				return;
			}
		} catch (Throwable t) {
			LOG.error("Vitrine: CEF failed to initialize from {}", dir, t);
			return;
		}
		Pages.markReady();
		LOG.info("Vitrine: CEF initialized from {}", dir);
		if (setupToast != null) setupToast.ready();
	}

	/** First launch: mirrors the CEF download in a toast, so players know why pages aren't there yet. */
	private static void setupToast() {
		boolean failed = setupFailed;
		if (setupToast == null && (CefRuntime.stage != null || failed)) {
			setupToast = new SetupToast();
			var mc = Minecraft.getInstance();
			//? if >=26.2 {
			mc.gui.toastManager().addToast(setupToast);
			//?} else if >=1.21.2 {
			/*mc.getToastManager().addToast(setupToast);
			*///?} else {
			/*mc.getToasts().addToast(setupToast);
			*///?}
		}
		if (failed) {
			setupFailed = false;
			setupToast.failed();
		}
	}

	/**
	 * Vitrine's own runClient only (-Dvitrine.demo=true), and an example of the public API: F8 opens/focuses a test page served from vitrine://vitrine/demo.html.
	 * Java -> JS: player position every half second. JS -> Java: "echo" and "close" handlers.
	 */
	private static void registerDemo() {
		//? if >=1.21.9 {
		var category = KeyMapping.Category.MISC;
		//?} else
		/*var category = "key.categories.misc";*/
		KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vitrine.demo", InputConstants.KEY_F8, category));
		KeyMapping worldKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vitrine.demo_world", InputConstants.KEY_F9, category));
		// -Pgallery: F8/F9 show the Modrinth gallery pages (branding/gallery) instead of the jar's demo, and
		// control.txt there drives the shoot, one order per line: "focus on|off", "panel", "run <command>", "shot <name>" (into screenshots/).
		String dir = System.getProperty("vitrine.demoDir");
		if (dir != null) Vitrine.serve("vitrine", path -> {
			Path file = Path.of(dir, path);
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		});
		long[] control = {0};
		ScreenView[] demo = {null};
		WorldView[] panel = {null};
		// F9: put a panel 3 blocks in front of the player, facing them (again = move it here).
		Runnable place = () -> {
			var player = Minecraft.getInstance().player;
			float yaw = player.getYRot();
			Vec3 look = Vec3.directionFromRotation(0, yaw);
			Vec3 center = player.getEyePosition().add(look.scale(3));
			Quaternionf facing = new Quaternionf().rotateY((float) Math.toRadians(180 - yaw));
			if (panel[0] == null) {
				panel[0] = Vitrine.world("vitrine://vitrine/demo.html?world", 1280, 720, center, facing, 3.2f);
				WorldView p = panel[0];
				p.on("echo", (body, reply) -> reply.ok("{\"echo\":" + body + ",\"javaTime\":" + System.currentTimeMillis() + "}"));
				boolean[] billboard = {false};
				p.on("billboard", (body, reply) -> {
					billboard[0] = !billboard[0];
					p.billboard(billboard[0]);
					reply.ok("{\"billboard\":" + billboard[0] + "}");
				});
			} else {
				panel[0].move(center, facing, 3.2f);
			}
		};
		// F8: open the screen page (first time) and give it the focus.
		Runnable open = () -> {
			if (demo[0] == null) {
				ScreenView v = Vitrine.screen("vitrine://vitrine/demo.html");
				v.on("echo", (body, reply) -> reply.ok("{\"echo\":" + body + ",\"javaTime\":" + System.currentTimeMillis() + "}"));
				v.on("close", (body, reply) -> {
					v.focus(false);
					reply.ok("{}");
				});
				demo[0] = v;
			}
			demo[0].focus(true);
		};
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (worldKey.consumeClick() && mc.player != null) place.run();
			while (key.consumeClick()) open.run();
			Path orders = dir == null ? null : Path.of(dir, "control.txt");
			if (orders != null && mc.player != null && orders.toFile().lastModified() != control[0]) {
				control[0] = orders.toFile().lastModified();
				try {
					for (String order : Files.readAllLines(orders)) {
						if (order.startsWith("focus ")) {
							open.run();
							demo[0].focus(order.endsWith("on"));
						} else if (order.equals("panel")) place.run();
						else if (order.startsWith("run ")) mc.player.connection.sendCommand(order.substring(4));
						//? if >=1.21.6 {
						else if (order.startsWith("shot ")) net.minecraft.client.Screenshot.grab(mc.gameDirectory, order.substring(5) + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
						//?} else
						/*else if (order.startsWith("shot ")) net.minecraft.client.Screenshot.grab(mc.gameDirectory, order.substring(5) + ".png", mc.getMainRenderTarget(), msg -> {});*/
					}
				} catch (java.io.IOException e) {
					LOG.warn("Vitrine: gallery orders not read", e);
				}
			}
			if (demo[0] != null && mc.player != null && mc.level.getGameTime() % 10 == 0) {
				var p = mc.player.position();
				demo[0].send(String.format(java.util.Locale.ROOT,
						"{\"type\":\"pos\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f}", p.x, p.y, p.z));
			}
		});
	}
}
