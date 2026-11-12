package dev.holoweb.vitrine.impl;

//? if >=26.2 {
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * {@code backdrop-filter: blur()} against the game. Chromium only blurs what is in the page, and the game
 * is not: the page reports where its blurs are ({@link Native#blurRects}) and we draw a blurred copy of
 * the game's framebuffer there, under the page. The page's own pixels then blend over it as usual.
 *
 * <p>GUI drawing is deferred: {@link #draw} (extraction) only queues quads that sample a slot's texture;
 * {@link #render} fills those textures when the GUI is actually drawn (GuiRendererMixin).
 */
public final class GameBlur {
	// ponytail: 4 different radii per frame; further ones share the last slot.
	private static final Slot[] SLOTS = new Slot[4];
	private static int used;
	/** We split the GUI's draws at the first blurred page: what is below it (HUD included) gets blurred. */
	private static boolean triedSplit, ownsSplit;

	private static RenderPipeline blurPipeline, copyPipeline;
	private static GpuBuffer white; // BlitConfig.ColorModulate = 1

	private GameBlur() {}

	static void newFrame() {
		used = 0;
		triedSplit = ownsSplit = false;
	}

	/** Queues the blurred backdrop of one page; {@code rects} in framebuffer pixels (10 floats each). */
	static void draw(GuiGraphicsExtractor gui, float[] rects) {
		var window = Minecraft.getInstance().getWindow();
		int fbW = window.getWidth(), fbH = window.getHeight();
		if (!triedSplit) {
			triedSplit = true;
			// A vanilla screen may blur too, and the GUI can only be split once: then we blur the world alone.
			Screen screen = VitrineScreen.current();
			if (screen == null || screen instanceof VitrineScreen) {
				try {
					gui.nextStratum();
					gui.blurBeforeThisStratum();
					ownsSplit = true;
				} catch (IllegalStateException alreadySplit) {
					// someone else blurs this frame
				}
			}
		}
		gui.pose().pushMatrix();
		gui.pose().scale((float) gui.guiWidth() / fbW, (float) gui.guiHeight() / fbH); // work in framebuffer pixels
		for (int i = 0; i + 10 <= rects.length; i += 10) {
			int x = Math.round(rects[i]), y = Math.round(rects[i + 1]);
			int w = Math.round(rects[i + 2]), h = Math.round(rects[i + 3]);
			if (w <= 0 || h <= 0) continue;
			Identifier texture = slot(rects[i + 4], fbW, fbH).id;
			int color = Math.round(Math.clamp(rects[i + 9], 0, 1) * 255) << 24 | 0xFFFFFF;
			float max = Math.min(w, h) / 2f;
			float tl = Math.min(rects[i + 5], max), tr = Math.min(rects[i + 6], max);
			float br = Math.min(rects[i + 7], max), bl = Math.min(rects[i + 8], max);
			// ponytail: rounded corners are 1 px rows, no antialiasing (blurred vs sharp game barely shows it).
			int top = (int) Math.ceil(Math.max(tl, tr)), bottom = (int) Math.ceil(Math.max(bl, br));
			for (int row = 0; row < top; row++) strip(gui, texture, x, y + row, w, 1, inset(tl, row), inset(tr, row), fbW, fbH, color);
			strip(gui, texture, x, y + top, w, h - top - bottom, 0, 0, fbW, fbH, color);
			for (int row = 0; row < bottom; row++) strip(gui, texture, x, y + h - 1 - row, w, 1, inset(bl, row), inset(br, row), fbW, fbH, color);
		}
		gui.pose().popMatrix();
	}

	/** How far a corner of {@code radius} cuts into the row {@code row} pixels from the edge. */
	private static int inset(float radius, int row) {
		float d = radius - row - 0.5f;
		return d <= 0 ? 0 : Math.round(radius - (float) Math.sqrt(radius * radius - d * d));
	}

	private static void strip(GuiGraphicsExtractor gui, Identifier texture, int x, int y, int w, int h, int left, int right, int fbW, int fbH, int color) {
		x += left;
		w -= left + right;
		if (w <= 0 || h <= 0) return;
		// The slot shows the whole screen: sample the same spot we draw at (render targets are bottom-up).
		gui.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, x, fbH - y, w, h, w, -h, fbW, fbH, color);
	}

	private static Slot slot(float sigma, int fbW, int fbH) {
		int key = Math.max(1, Math.round(sigma));
		for (int i = 0; i < used; i++) if (SLOTS[i].sigma == key) return SLOTS[i];
		if (used == SLOTS.length) return SLOTS[used - 1];
		if (SLOTS[used] == null) SLOTS[used] = new Slot(used);
		Slot slot = SLOTS[used++];
		slot.prepare(key, fbW, fbH);
		return slot;
	}

	/**
	 * GUI drawing (GuiRendererMixin). {@code atSplit}: between the draws below the first blurred page and
	 * the rest, in place of the vanilla blur; otherwise before any GUI draw. True if it took the frame's blur.
	 */
	public static boolean render(boolean atSplit) {
		if (atSplit != ownsSplit) return false;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		for (int i = 0; i < used; i++) SLOTS[i].render(main);
		return ownsSplit;
	}

	private static RenderPipeline pipeline(String name, String fragment, String... uniforms) {
		BindGroupLayout.Builder layout = BindGroupLayout.builder().withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER);
		for (String uniform : uniforms) layout.withUniform(uniform, UniformType.UNIFORM_BUFFER);
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Pages.id("pipeline/" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.withDefaultNamespace(fragment))
				.withBindGroupLayout(layout.build())
				// Alpha is never written: the targets are cleared to 1, so the blur draws opaque whatever the game left there.
				.withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.DEFAULT.format(), ColorTargetState.WRITE_COLOR))
				.build();
	}

	private static GpuBuffer uniform(float a, float b, float c, float d) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			return RenderSystem.getDevice().createBuffer(() -> "Vitrine blur uniform", GpuBuffer.USAGE_UNIFORM,
					Std140Builder.onStack(stack, 16).putVec4(a, b, c, d).get());
		}
	}

	/** One blur radius: the game's frame, blurred, as a texture GUI quads can sample. */
	private static final class Slot extends AbstractTexture {
		final Identifier id;
		int sigma; // CSS blur radius = standard deviation, in framebuffer pixels
		private TextureTarget a, b; // b holds the result
		private GpuBuffer info, horizontal, vertical;
		private int radius;

		Slot(int index) {
			id = Pages.id("blur/" + index);
			sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		/** Extraction: the texture must exist (queued quads hold its view), its content comes in {@link #render}. */
		void prepare(int sigma, int fbW, int fbH) {
			this.sigma = sigma;
			// Two box blurs in a row are close to a gaussian of sigma = radius * 0.82.
			int full = Math.max(1, Math.round(sigma * 1.22f));
			int scale = full >= 8 ? 2 : 1; // wide blurs run at half resolution
			int w = Math.max(1, fbW / scale), h = Math.max(1, fbH / scale), r = Math.max(1, full / scale);
			if (a == null || a.width != w || a.height != h) {
				if (a != null) {
					a.destroyBuffers();
					b.destroyBuffers();
					info.close();
				}
				var format = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getFormat();
				a = new TextureTarget("Vitrine blur A", w, h, format, null);
				b = new TextureTarget("Vitrine blur B", w, h, format, null);
				CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
				encoder.clearColorTexture(a.getColorTexture(), new Vector4f(0, 0, 0, 1));
				encoder.clearColorTexture(b.getColorTexture(), new Vector4f(0, 0, 0, 1));
				info = uniform(w, h, w, h); // SamplerInfo: OutSize, InSize
				texture = b.getColorTexture();
				textureView = b.getColorTextureView();
				Minecraft.getInstance().getTextureManager().register(id, this);
			}
			if (r != radius) {
				radius = r;
				if (horizontal != null) {
					horizontal.close();
					vertical.close();
				}
				horizontal = uniform(1, 0, r, 0); // BlurConfig: BlurDir, Radius
				vertical = uniform(0, 1, r, 0);
			}
		}

		void render(RenderTarget main) {
			if (blurPipeline == null) {
				blurPipeline = pipeline("blur", "post/box_blur", "SamplerInfo", "BlurConfig");
				copyPipeline = pipeline("blur_copy", "post/blit", "BlitConfig");
				white = uniform(1, 1, 1, 1);
			}
			pass(copyPipeline, main.getColorTextureView(), b, "BlitConfig", white, null);
			for (int i = 0; i < 2; i++) {
				pass(blurPipeline, b.getColorTextureView(), a, "BlurConfig", horizontal, info);
				pass(blurPipeline, a.getColorTextureView(), b, "BlurConfig", vertical, info);
			}
		}

		private static void pass(RenderPipeline pipeline, GpuTextureView in, RenderTarget out, String name, GpuBuffer config, GpuBuffer info) {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			try (RenderPass pass = encoder.createRenderPass(() -> "Vitrine blur", out.getColorTextureView(), Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
				RenderSystem.bindDefaultUniforms(pass);
				if (info != null) pass.setUniform("SamplerInfo", info);
				pass.setUniform(name, config);
				pass.setUniform("InSampler", in, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
		}

		@Override
		public void close() {
			// the targets own the textures; the texture manager must not free them
		}
	}
}
//?}
