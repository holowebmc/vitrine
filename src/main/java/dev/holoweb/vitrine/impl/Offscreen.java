package dev.holoweb.vitrine.impl;

//? if >=1.21.6 {
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
//? if >=26.2 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Vector4f;
//?} else if >=1.21.11 {
/*import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
*///?} else {
/*import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
*///?}
//? if >=26.1 {
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
//?} else
/*import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;*/
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else
/*import net.minecraft.client.renderer.MultiBufferSource;*/

/**
 * A colour + depth target of ours that the game's own renderers draw into, as the vanilla GUI item atlas
 * does: what {@code --vitrine-bind} slots and {@code vitrine://item} icons are drawn in. What is drawn over
 * the cleared (transparent) target comes out premultiplied; rows are bottom-up (render targets are). It is
 * a texture too, to register under a name: world panels draw it through a render type.
 */
final class Offscreen extends AbstractTexture {
	//? if >=26.2 {
	private static SubmitNodeStorage nodes = new SubmitNodeStorage();
	private static final Vector4f TRANSPARENT = new Vector4f(0f);
	//?}
	//? if >=26.1 {
	private static final Projection PROJECTION = new Projection();
	private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("Vitrine offscreen");
	//?} else
	/*private final CachedOrthoProjectionMatrixBuffer projection = new CachedOrthoProjectionMatrixBuffer("Vitrine offscreen", -1000, 1000, true);*/
	final int width, height;
	final GpuTexture color, depth;
	final GpuTextureView colorView, depthView;

	/** Empty (transparent); {@code linear}: sampled with linear filtering where it is drawn scaled. */
	Offscreen(String label, int width, int height, boolean linear) {
		this.width = width;
		this.height = height;
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
		//? if >=26.2 {
		color = device.createTexture(label, usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		depth = device.createTexture(label + " depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, width, height, 1, 1);
		//?} else {
		/*color = device.createTexture(label, usage, TextureFormat.RGBA8, width, height, 1, 1);
		depth = device.createTexture(label + " depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, TextureFormat.DEPTH32, width, height, 1, 1);
		*///?}
		colorView = device.createTextureView(color);
		depthView = device.createTextureView(depth);
		texture = color;
		textureView = colorView;
		//? if >=1.21.11 {
		sampler = RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST);
		//?} else {
		/*color.setTextureFilter(linear ? FilterMode.LINEAR : FilterMode.NEAREST, false);
		color.setAddressMode(AddressMode.CLAMP_TO_EDGE);
		*///?}
		clear();
	}

	/** Colour to transparent, depth to far. One call for the whole target: clears of a part are slow on Vulkan. */
	void clear() {
		//? if >=26.2 {
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, TRANSPARENT, depth, 0);
		//?} else
		/*RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, 0, depth, 1);*/
	}

	/**
	 * Draws what {@code submit} queues into the part {@code x, y, w, h} (from the top left) and nothing outside
	 * it, as {@code GuiItemAtlas.drawToSlot} does. Orthographic, one unit per pixel, origin at the top left, y down.
	 */
	//? if >=1.21.9 {
	void draw(int x, int y, int w, int h, Consumer<SubmitNodeCollector> submit) {
	//?} else
	/*void draw(int x, int y, int w, int h, Consumer<MultiBufferSource> submit) {*/
		int bottom = height - y - h; // render targets are bottom-up
		RenderSystem.backupProjectionMatrix();
		//? if >=26.1 {
		PROJECTION.setupOrtho(-1000, 1000, width, height, true);
		RenderSystem.setProjectionMatrix(projection.getBuffer(PROJECTION), ProjectionType.ORTHOGRAPHIC);
		//?} else
		/*RenderSystem.setProjectionMatrix(projection.getBuffer(width, height), ProjectionType.ORTHOGRAPHIC);*/
		RenderSystem.enableScissorForRenderTypeDraws(x, bottom, w, h);
		//? if >=26.2 {
		try {
			submit.accept(nodes);
			try (var frame = Minecraft.getInstance().gameRenderer.featureRenderDispatcher().prepareFrame(nodes);
					RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Vitrine offscreen", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
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
		//?} else {
		/*Minecraft mc = Minecraft.getInstance();
		var buffers = mc.renderBuffers().bufferSource();
		RenderSystem.outputColorTextureOverride = colorView;
		RenderSystem.outputDepthTextureOverride = depthView;
		try {
			//? if >=1.21.9 {
			submit.accept(mc.gameRenderer.getSubmitNodeStorage());
			mc.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
			//?} else
			submit.accept(buffers);
			buffers.endBatch();
		} catch (RuntimeException e) {
			//? if >=1.21.9
			mc.gameRenderer.getSubmitNodeStorage().clear(); // it may hold half a submission
			throw e;
		} finally {
			RenderSystem.outputColorTextureOverride = null;
			RenderSystem.outputDepthTextureOverride = null;
			RenderSystem.disableScissorForRenderTypeDraws();
			RenderSystem.restoreProjectionMatrix();
		}
		*///?}
	}

	/** Hands {@code done} the RGBA pixels (bottom-up rows) once the GPU has them; null if that failed. */
	void read(Consumer<ByteBuffer> done) {
		GpuDevice device = RenderSystem.getDevice();
		GpuBuffer pixels = device.createBuffer(() -> "Vitrine readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, width * height * 4);
		var encoder = device.createCommandEncoder();
		encoder.copyTextureToBuffer(color, pixels, 0, () -> {
			//? if >=26.2 {
			GpuBufferSlice.MappedView view;
			//?} else
			/*GpuBuffer.MappedView view;*/
			try {
				//? if >=26.2 {
				view = pixels.map(true, false);
				//?} else
				/*view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(pixels, true, false);*/
			} catch (RuntimeException e) {
				VitrineClient.LOG.error("Vitrine: readback failed", e);
				pixels.close();
				done.accept(null);
				return;
			}
			try (view) {
				done.accept(view.data());
			} finally {
				pixels.close();
			}
		}, 0);
	}

	/** Frees the textures (unregister it first if it was registered). */
	void free() {
		colorView.close();
		depthView.close();
		color.close();
		depth.close();
		projection.close();
	}

	@Override
	public void close() {
		// we own the textures (free); the texture manager must not free them
	}
}
//?} else {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4f;
//? if >=1.21.5 {
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
//?} else {
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
//?}
//? if >=1.21.2 <1.21.5
import com.mojang.blaze3d.ProjectionType;
//? if <1.21.2
import com.mojang.blaze3d.vertex.VertexSorting;

// A colour + depth target of ours that the game's own renderers draw into: what {@code --vitrine-bind} slots and
// {@code vitrine://item} icons are drawn in. What is drawn over the cleared (transparent) target comes out
// premultiplied; rows are bottom-up. It is a texture too, to register under a name. Before 1.21.6 vanilla render
// types draw into the main target, so while we draw it answers for it (MainTargetMixin).
public final class Offscreen extends AbstractTexture {
	// While drawing: what {@code Minecraft.getMainRenderTarget()} answers, else null.
	public static RenderTarget drawing;
	final int width, height;
	private final TextureTarget target;

	// Empty (transparent); {@code linear}: sampled with linear filtering where it is drawn scaled.
	Offscreen(String label, int width, int height, boolean linear) {
		this.width = width;
		this.height = height;
		//? if >=1.21.5 {
		target = new TextureTarget(label, width, height, true);
		texture = target.getColorTexture();
		texture.setTextureFilter(linear ? FilterMode.LINEAR : FilterMode.NEAREST, false);
		texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
		//?} else {
		//? if >=1.21.2 {
		target = new TextureTarget(width, height, true);
		//?} else
		target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
		id = target.getColorTextureId();
		setFilter(linear, false); // binds it
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		//?}
		clear();
	}

	// Colour to transparent, depth to far.
	void clear() {
		//? if >=1.21.5 {
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1);
		//?} else {
		target.setClearColor(0, 0, 0, 0);
		//? if >=1.21.2 {
		target.clear();
		//?} else
		target.clear(Minecraft.ON_OSX);
		Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
		//?}
	}

	// Draws what {@code submit} queues into the part {@code x, y, w, h} (from the top left) and nothing outside
	// it. Orthographic, one unit per pixel, origin at the top left, y down.
	void draw(int x, int y, int w, int h, Consumer<MultiBufferSource> submit) {
		Minecraft mc = Minecraft.getInstance();
		var buffers = mc.renderBuffers().bufferSource();
		buffers.endBatch(); // nothing of anyone else's into our target
		Matrix4f ortho = new Matrix4f().setOrtho(0, width, height, 0, -1000, 1000);
		RenderSystem.backupProjectionMatrix();
		//? if >=1.21.2 {
		RenderSystem.setProjectionMatrix(ortho, ProjectionType.ORTHOGRAPHIC);
		//?} else
		RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
		var modelView = RenderSystem.getModelViewStack();
		//? if >=1.20.5 {
		modelView.pushMatrix();
		modelView.identity();
		//?} else {
		modelView.pushPose();
		modelView.setIdentity();
		//?}
		//? if <1.21.2
		RenderSystem.applyModelViewMatrix();
		drawing = target;
		//? if <1.21.5
		target.bindWrite(true);
		RenderSystem.enableScissor(x, height - y - h, w, h); // render targets are bottom-up
		try {
			submit.accept(buffers);
			buffers.endBatch();
		} finally {
			RenderSystem.disableScissor();
			drawing = null;
			//? if >=1.20.5 {
			modelView.popMatrix();
			//?} else
			modelView.popPose();
			//? if <1.21.2
			RenderSystem.applyModelViewMatrix();
			RenderSystem.restoreProjectionMatrix();
			//? if <1.21.5
			mc.getMainRenderTarget().bindWrite(true);
		}
	}

	// Hands {@code done} the RGBA pixels (bottom-up rows) once they are read; null if that failed.
	void read(Consumer<ByteBuffer> done) {
		//? if >=1.21.5 {
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuBuffer pixels = RenderSystem.getDevice().createBuffer(() -> "Vitrine readback", BufferType.PIXEL_PACK, BufferUsage.STREAM_READ, width * height * 4);
		encoder.copyTextureToBuffer(target.getColorTexture(), pixels, 0, () -> {
			GpuBuffer.ReadView view;
			try {
				view = encoder.readBuffer(pixels);
			} catch (RuntimeException e) {
				VitrineClient.LOG.error("Vitrine: readback failed", e);
				pixels.close();
				done.accept(null);
				return;
			}
			try (view) {
				done.accept(view.data());
			} finally {
				pixels.close();
			}
		}, 0);
		//?} else {
		ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
		try {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
			GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
			Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
			done.accept(pixels);
		} finally {
			MemoryUtil.memFree(pixels);
		}
		//?}
	}

	// Frees the target (unregister it first if it was registered).
	void free() {
		target.destroyBuffers();
	}

	@Override
	public void close() {
		// the target owns the texture (free); the texture manager must not free it
	}

	//? if <1.21.5 {
	@Override
	public void releaseId() {
		// as close()
	}
	//?}

	//? if <1.21.5 {
	// No @Override: abstract until 1.21.3, gone in 1.21.4, and one jar runs on 1.21.2-1.21.4.
	public void load(net.minecraft.server.packs.resources.ResourceManager resources) {
		// nothing to load: the target is the texture
	}
	//?}
}
*///?}
