package dev.holoweb.vitrine.impl;
//? if fabric {

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
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

// Fabric entry: hooks Vitrine into Fabric API's events. Line comments only: this file is a block comment on NeoForge.
public final class FabricEntry implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (!VitrineClient.start(FabricLoader.getInstance().getGameDir())) return;

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
		/*WorldRenderEvents.AFTER_ENTITIES.register(ctx -> Pages.renderWorld(ctx.camera(), ctx.projectionMatrix(), ctx.frustum(), ctx.matrixStack(), ctx.consumers()));
		*///?}
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Pages.shutdown());

		if (VitrineClient.DEMO) {
			var tick = VitrineClient.demo(k -> KeyMappingHelper.registerKeyMapping(k));
			ClientTickEvents.END_CLIENT_TICK.register(tick::accept);
		}
	}
}
//?}
