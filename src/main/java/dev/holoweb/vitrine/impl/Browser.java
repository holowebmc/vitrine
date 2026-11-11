package dev.holoweb.vitrine.impl;

import dev.holoweb.vitrine.Vitrine;
import com.mojang.blaze3d.systems.RenderSystem;
//? if >=1.21.5 {
import com.mojang.renderpearl.backend.opengl.GlTexture;
//?} else
/*import dev.holoweb.vitrine.impl.mixin.NativeImageAccessor;*/
//? if >=26.2 {
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
//?}
//? if >=1.21.11
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
//? if <1.21.5 {
/*import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
*///?}

/** One off-screen CEF page and the Minecraft texture it paints into. Render thread only. */
final class Browser {
	/** Live browsers by native handle, so vitrine:// requests reach the page that made them. */
	static final Map<Long, Browser> BY_HANDLE = new HashMap<>();

	/** How frames reach Minecraft's texture. */
	private enum Path { CPU, GL, VULKAN }

	/** GL interop available at all (decided once, on the first GL page). */
	private static Boolean glAvailable;

	long handle;
	final Identifier textureId;
	Map<String, Vitrine.Handler> handlers = new HashMap<>(); // the owning page's map
	AudioOut audio;
	private final String url;
	private final boolean monoAudio;
	private final SoundSource category;
	private Path path;
	//? if >=26.2
	private VulkanFrames vulkan;
	int width, height;
	/** Mouse buttons currently held over this page (bit0 left, bit1 middle, bit2 right). */
	int buttons;
	private DynamicTexture texture;
	private boolean hasFrame;
	private int fps;

	/** {@code monoAudio} for pages placed in the world (positional sound); {@code category} = volume slider. */
	Browser(String url, int width, int height, int fps, Identifier textureId, boolean monoAudio, SoundSource category) {
		this.textureId = textureId;
		this.fps = fps;
		this.url = url;
		this.monoAudio = monoAudio;
		this.category = category;
		allocate(width, height);
		start(choosePath());
	}

	/** Zero-copy when the backend allows it; -Dvitrine.gpu=false forces CPU frames. */
	private Path choosePath() {
		if (!Boolean.parseBoolean(System.getProperty("vitrine.gpu", "true"))) return Path.CPU;
		//? if >=26.2 {
		if (texture.getTexture() instanceof VulkanGpuTexture && vulkanEncoder() instanceof VulkanCommandEncoder enc
				&& VulkanFrames.available(enc)) {
			return Path.VULKAN;
		}
		//?}
		if (glId() == 0) return Path.CPU;
		if (glAvailable == null) {
			glAvailable = Native.gpuInit();
			VitrineClient.LOG.info("Vitrine: frames via {}", glAvailable ? "GPU (D3D11 -> GL interop)" : "CPU");
		}
		return glAvailable ? Path.GL : Path.CPU;
	}

	/** OpenGL name of the page texture; 0 on another backend. */
	private int glId() {
		//? if >=1.21.5 {
		return texture.getTexture() instanceof GlTexture gl ? gl.glId() : 0;
		//?} else
		/*return texture.getId();*/
	}

	/** Address of the CPU-side RGBA pixels. */
	private long pixels() {
		//? if >=1.21.5 {
		return texture.getPixels().getPointer();
		//?} else
		/*return ((NativeImageAccessor) (Object) texture.getPixels()).vitrine$pixels();*/
	}

	//? if >=26.2 {
	private static Object vulkanEncoder() {
		return RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder f ? f.backend() : null;
	}
	//?}

	private void start(Path path) {
		this.path = path;
		handle = Native.create(url, width, height, fps, monoAudio, path != Path.CPU);
		audio = new AudioOut(handle, monoAudio, category);
		//? if >=26.2
		if (path == Path.VULKAN) vulkan = new VulkanFrames();
		BY_HANDLE.put(handle, this);
	}

	/** Brings the latest CEF frame into the texture, if there is a new one. */
	void update() {
		if (path != Path.CPU && Native.gpuFailed(handle)) {
			// ponytail: reloads the page; only happens when CEF renders on another GPU than Minecraft.
			VitrineClient.LOG.warn("Vitrine: GPU frames failed for {}, falling back to CPU", url);
			close();
			start(Path.CPU);
			return;
		}
		if (path == Path.GL) {
			int id = glId();
			if (id == 0 || !Native.copyGpu(handle, id, width, height)) return;
		//? if >=26.2 {
		} else if (path == Path.VULKAN) {
			if (!(texture.getTexture() instanceof VulkanGpuTexture vk) || !vulkan.update(handle, vk, width, height)) return;
		//?}
		} else {
			// ponytail: CPU fallback copies and uploads the full frame.
			if (!Native.copyIfDirty(handle, pixels(), (long) width * height * 4)) return;
			texture.upload();
		}
		hasFrame = true;
	}

	void resize(int w, int h) {
		if (w == width && h == height) return;
		Native.resize(handle, w, h);
		allocate(w, h);
	}

	private void allocate(int w, int h) {
		width = w;
		height = h;
		// Linear filtering: pages are scaled in the world and by GUI scale on screen.
		//? if >=1.21.11 {
		texture = new DynamicTexture(textureId::toString, w, h, false) {
			{
				sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			}
		};
		//?} else if >=1.21.5 {
		/*texture = new DynamicTexture(textureId::toString, w, h, false);
		*///?} else {
		/*texture = new DynamicTexture(w, h, false);
		*///?}
		//? if <1.21.11
		/*texture.setFilter(true, false);*/
		//? if >=1.21.5 <1.21.11 {
		/*texture.setClamp(true);
		*///?} else if <1.21.5 {
		/*// setClamp only exists from 1.21.4: plain GL keeps one jar working on 1.21.2-1.21.4.
		texture.bind(); // setFilter left it bound
		com.mojang.blaze3d.platform.GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		com.mojang.blaze3d.platform.GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		*///?}
		Minecraft.getInstance().getTextureManager().register(textureId, texture); // closes the old one
		hasFrame = false;
	}

	/** CEF frame-rate cap; cheap to call every frame. */
	void fps(int value) {
		if (value == fps) return;
		fps = value;
		Native.setFps(handle, value);
	}

	boolean hasFrame() {
		return hasFrame;
	}

	/** 0 arrow, 1 text, 2 hand, 3 crosshair, 4 resize EW, 5 resize NS, 6 move, 7 not allowed. */
	int cursor() {
		int c = Native.cursor(handle);
		return c >= 0 && c <= 7 ? c : 0;
	}

	/** Ends the page; the texture stays registered until {@link #release}. */
	private void close() {
		//? if >=26.2 {
		if (vulkan != null) vulkan.close(handle);
		vulkan = null;
		//?}
		audio.close();
		BY_HANDLE.remove(handle);
		Native.destroy(handle);
	}

	void release() {
		close();
		Minecraft.getInstance().getTextureManager().release(textureId);
	}
}
