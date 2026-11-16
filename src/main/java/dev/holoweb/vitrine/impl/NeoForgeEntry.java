package dev.holoweb.vitrine.impl;
//? if neoforge {
/*
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

// NeoForge entry: hooks Vitrine into NeoForge's events (only 1.21.1 for now). Line comments only: this file is a block comment elsewhere.
@Mod(value = "vitrine", dist = Dist.CLIENT)
public final class NeoForgeEntry {
	public NeoForgeEntry(IEventBus modBus) {
		if (!VitrineClient.start(FMLPaths.GAMEDIR.get())) return;

		modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAboveAll(Identifier.fromNamespaceAndPath("vitrine", "screens"),
				(gui, delta) -> Pages.drawHud(gui, VitrineScreen.current())));
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> {
			if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
				Pages.renderWorld(e.getCamera(), e.getProjectionMatrix(), e.getFrustum(), e.getPoseStack(), Minecraft.getInstance().renderBuffers().bufferSource());
		});
		// Posted by Minecraft.stop(): on the render thread when quitting, again from the JVM's shutdown hook (skipped).
		NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent e) -> {
			if (Minecraft.getInstance().isSameThread()) Pages.shutdown();
		});

		if (VitrineClient.DEMO) {
			var tick = VitrineClient.demo(k -> {
				modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(k));
				return k;
			});
			NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick.accept(Minecraft.getInstance()));
		}
	}
}
*///?}
