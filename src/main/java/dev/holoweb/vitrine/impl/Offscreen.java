package dev.holoweb.vitrine.impl;

//? if >=26.2 {
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Vector4f;

/** Draws what the game's renderers submit into a texture of ours, as the vanilla picture-in-picture GUI renderers do. */
final class Offscreen {
	private static final Projection PROJECTION = new Projection();
	private static final Vector4f TRANSPARENT = new Vector4f(0f);
	private static SubmitNodeStorage nodes = new SubmitNodeStorage();

	private Offscreen() {}

	/** Colour to transparent, depth to far. One call for the whole target: clears of a part are slow on Vulkan. */
	static void clear(GpuTexture color, GpuTexture depth) {
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, TRANSPARENT, depth, 0);
	}

	/**
	 * Draws what {@code submit} queues into the part {@code x, y, w, h} (from the top left, inside the target) of a
	 * target of {@code width} x {@code height}, as {@code GuiItemAtlas.drawToSlot} does: nothing is drawn outside
	 * that part. {@link #clear} the target first. Orthographic, one unit per pixel, origin at the target's top
	 * left, y down.
	 */
	static void draw(GpuTexture color, GpuTextureView colorView, GpuTexture depth, GpuTextureView depthView,
			ProjectionMatrixBuffer projection, int width, int height, int x, int y, int w, int h, Consumer<SubmitNodeCollector> submit) {
		GpuDevice device = RenderSystem.getDevice();
		int bottom = height - y - h; // render targets are bottom-up
		PROJECTION.setupOrtho(-1000, 1000, width, height, true);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projection.getBuffer(PROJECTION), ProjectionType.ORTHOGRAPHIC);
		RenderSystem.enableScissorForRenderTypeDraws(x, bottom, w, h);
		try {
			submit.accept(nodes);
			try (var frame = Minecraft.getInstance().gameRenderer.featureRenderDispatcher().prepareFrame(nodes);
					RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Vitrine offscreen", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				FeatureRenderDispatcher.renderAllFeatures(pass, frame);
			}
		} catch (RuntimeException e) {
			nodes = new SubmitNodeStorage(); // the old one may hold half a submission
			throw e;
		} finally {
			RenderSystem.disableScissorForRenderTypeDraws();
			RenderSystem.restoreProjectionMatrix();
		}
	}
}
//?}
