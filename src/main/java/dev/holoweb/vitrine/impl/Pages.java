package dev.holoweb.vitrine.impl;

import java.util.ArrayList;
import java.util.List;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
//?} else if >=1.21.9 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
*///?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;

/** Every live page, and the per-frame work for them. Render thread only. */
public final class Pages {
	private static final List<ScreenPage> SCREENS = new ArrayList<>();
	private static final List<WorldPanel> WORLDS = new ArrayList<>();
	private static boolean ready;
	private static int nextId;

	private Pages() {}

	public static boolean isReady() {
		return ready;
	}

	public static void serve(String host, dev.holoweb.vitrine.Vitrine.Files files) {
		Bridge.serve(host, files);
	}

	/** A page created before CEF is up starts loading once it is. */
	public static ScreenPage screen(String url) {
		ScreenPage page = new ScreenPage(url, id("screen/" + nextId++));
		SCREENS.add(page);
		if (ready) page.start();
		return page;
	}

	public static WorldPanel world(String url, int pxWidth, int pxHeight, Vec3 center, Quaternionfc rotation, float width) {
		WorldPanel panel = new WorldPanel(url, pxWidth, pxHeight, center, rotation, width, id("world/" + nextId++));
		WORLDS.add(panel);
		if (ready) panel.start();
		return panel;
	}

	static Identifier id(String path) {
		//? if >=1.21 {
		return Identifier.fromNamespaceAndPath("vitrine", path);
		//?} else
		/*return new Identifier("vitrine", path);*/
	}

	static void markReady() {
		ready = true;
		for (ScreenPage p : SCREENS) p.start();
		for (WorldPanel p : WORLDS) p.start();
	}

	static void remove(ScreenPage view) {
		SCREENS.remove(view);
	}

	static void remove(WorldPanel view) {
		WORLDS.remove(view);
		WorldInput.forget(view);
	}

	/** Start of every client frame (mixin): answer page requests, turn billboards, pull new frames into textures. */
	public static void frame() {
		if (!ready) {
			VitrineClient.startIfLoaded();
			return;
		}
		Bridge.drain();
		for (ScreenPage v : List.copyOf(SCREENS)) v.frame();
		for (WorldPanel v : List.copyOf(WORLDS)) v.frame();
		WorldInput.frame(WORLDS);
	}

	/** Level rendering: submit world panels (the camera was captured by {@link WorldPanel#captureCamera}). */
	//? if >=26.1 {
	static void renderWorld(LevelRenderContext ctx) {
		WorldPanel.captureCamera(ctx.levelState().cameraRenderState);
		for (WorldPanel v : WORLDS) v.submit(ctx.submitNodeCollector(), ctx.poseStack());
	}
	//?} else if >=1.21.9 {
	/*static void renderWorld(WorldRenderContext ctx) {
		for (WorldPanel v : WORLDS) v.submit(ctx.commandQueue(), ctx.matrices());
	}
	*///?} else {
	/*static void renderWorld(WorldRenderContext ctx) {
		WorldPanel.captureCamera(ctx);
		//? if >=1.20.5 {
		var pose = new com.mojang.blaze3d.vertex.PoseStack().last(); // the camera rotation is in the model-view
		//?} else
		var pose = ctx.matrixStack().last(); // the camera rotation is in this stack
		for (WorldPanel v : WORLDS) v.submit(ctx.consumers(), pose);
		// Draw now, while the level's model-view is set (vanilla may have flushed this type already).
		if (ctx.consumers() instanceof MultiBufferSource.BufferSource buffers) buffers.endBatch();
	}
	*///?}

	/** HUD layer: draws unfocused pages (a focused one is drawn by its own screen, on top). */
	static void drawHud(GuiGraphicsExtractor gui, Screen current) {
		for (ScreenPage v : SCREENS) {
			if (!(current instanceof VitrineScreen s && s.view == v)) v.draw(gui);
		}
		WorldInput.drawHud(gui);
	}

	static void shutdown() {
		if (!ready) return;
		for (ScreenPage v : List.copyOf(SCREENS)) v.close();
		for (WorldPanel v : List.copyOf(WORLDS)) v.close();
		Native.shutdown();
		ready = false;
	}
}
