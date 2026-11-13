package dev.holoweb.vitrine.impl;

//? if >=1.21.6 {
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
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
import org.lwjgl.system.MemoryStack;
//? if >=26.2 {
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import org.joml.Vector4f;
//?} else if >=26.1 {
/*import com.mojang.blaze3d.pipeline.ColorTargetState;
*///?} else if <1.21.11 {
/*import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
*///?}

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
	//? if <1.21.11
	/*private static CachedOrthoProjectionMatrixBuffer projection;*/

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
			int color = Math.round(Math.max(0, Math.min(rects[i + 9], 1)) * 255) << 24 | 0xFFFFFF;
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
	static int inset(float radius, int row) {
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

	/** A vanilla post shader as a pipeline of ours. Alpha is never written: the targets are cleared to 1, so the blur draws opaque whatever the game left there. */
	private static RenderPipeline pipeline(String name, String vertex, String fragment, String... uniforms) {
		var builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Pages.id("pipeline/" + name))
				.withVertexShader(Identifier.withDefaultNamespace(vertex))
				.withFragmentShader(Identifier.withDefaultNamespace(fragment));
		//? if >=26.2 {
		BindGroupLayout.Builder layout = BindGroupLayout.builder().withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER);
		for (String uniform : uniforms) layout.withUniform(uniform, UniformType.UNIFORM_BUFFER);
		return builder.withBindGroupLayout(layout.build())
				.withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.DEFAULT.format(), ColorTargetState.WRITE_COLOR))
				.build();
		//?} else {
		/*builder.withSampler("InSampler");
		for (String uniform : uniforms) builder.withUniform(uniform, UniformType.UNIFORM_BUFFER);
		//? if >=26.1 {
		builder.withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_COLOR));
		//?} else
		builder.withColorWrite(true, false);
		return builder.build();
		*///?}
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
			//? if >=1.21.11
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
				//? if >=26.2 {
				var format = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getFormat();
				a = new TextureTarget("Vitrine blur A", w, h, format, null);
				b = new TextureTarget("Vitrine blur B", w, h, format, null);
				CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
				encoder.clearColorTexture(a.getColorTexture(), new Vector4f(0, 0, 0, 1));
				encoder.clearColorTexture(b.getColorTexture(), new Vector4f(0, 0, 0, 1));
				//?} else {
				/*a = new TextureTarget("Vitrine blur A", w, h, false);
				b = new TextureTarget("Vitrine blur B", w, h, false);
				CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
				encoder.clearColorTexture(a.getColorTexture(), 0xFF000000);
				encoder.clearColorTexture(b.getColorTexture(), 0xFF000000);
				//? if <1.21.11 {
				a.setFilterMode(FilterMode.LINEAR);
				b.setFilterMode(FilterMode.LINEAR);
				//?}
				*///?}
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
				//? if >=1.21.11 {
				blurPipeline = pipeline("blur", "core/screenquad", "post/box_blur", "SamplerInfo", "BlurConfig");
				copyPipeline = pipeline("blur_copy", "core/screenquad", "post/blit", "BlitConfig");
				//?} else {
				/*blurPipeline = pipeline("blur", "post/blur", "post/box_blur", "SamplerInfo", "BlurConfig");
				copyPipeline = pipeline("blur_copy", "post/blit", "post/blit", "SamplerInfo", "BlitConfig");
				projection = new CachedOrthoProjectionMatrixBuffer("Vitrine blur", 0.1f, 1000f, false);
				*///?}
				white = uniform(1, 1, 1, 1);
			}
			pass(copyPipeline, main.getColorTextureView(), b, "BlitConfig", white, copyInfo(main));
			for (int i = 0; i < 2; i++) {
				pass(blurPipeline, b.getColorTextureView(), a, "BlurConfig", horizontal, info);
				pass(blurPipeline, a.getColorTextureView(), b, "BlurConfig", vertical, info);
			}
		}

		/** SamplerInfo for the copy: only the vertex shader of older versions reads it (the output size). */
		private GpuBuffer copyInfo(RenderTarget main) {
			//? if >=1.21.11 {
			return null;
			//?} else {
			/*if (copyInfo == null || copyInfoW != b.width || copyInfoH != b.height) {
				if (copyInfo != null) copyInfo.close();
				copyInfo = uniform(b.width, b.height, main.width, main.height);
				copyInfoW = b.width;
				copyInfoH = b.height;
			}
			return copyInfo;
			*///?}
		}

		//? if <1.21.11
		/*private GpuBuffer copyInfo; private int copyInfoW, copyInfoH;*/

		private static void pass(RenderPipeline pipeline, GpuTextureView in, RenderTarget out, String name, GpuBuffer config, GpuBuffer info) {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			//? if >=26.2 {
			try (RenderPass pass = encoder.createRenderPass(() -> "Vitrine blur", out.getColorTextureView(), Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
				RenderSystem.bindDefaultUniforms(pass);
				if (info != null) pass.setUniform("SamplerInfo", info);
				pass.setUniform(name, config);
				pass.setUniform("InSampler", in, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
			//?} else if >=1.21.11 {
			/*try (RenderPass pass = encoder.createRenderPass(() -> "Vitrine blur", out.getColorTextureView(), java.util.OptionalInt.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				if (info != null) pass.setUniform("SamplerInfo", info);
				pass.setUniform(name, config);
				pass.bindTexture("InSampler", in, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(0, 3);
			}
			*///?} else {
			/*// A quad over the whole target, as PostPass draws one.
			RenderSystem.backupProjectionMatrix();
			RenderSystem.setProjectionMatrix(projection.getBuffer(out.width, out.height), ProjectionType.ORTHOGRAPHIC);
			var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
			try (RenderPass pass = encoder.createRenderPass(() -> "Vitrine blur", out.getColorTextureView(), java.util.OptionalInt.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("SamplerInfo", info);
				pass.setUniform(name, config);
				pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
				pass.setIndexBuffer(indices.getBuffer(6), indices.type());
				pass.bindSampler("InSampler", in);
				pass.drawIndexed(0, 0, 6, 1);
			} finally {
				RenderSystem.restoreProjectionMatrix();
			}
			*///?}
		}

		@Override
		public void close() {
			// the targets own the textures; the texture manager must not free them
		}
	}
}
//?} else {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
//? if >=1.21.5 {
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
//?} else
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
//? if >=1.21.2 {
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderType;
//?} else {
import net.minecraft.client.renderer.PostPass;
import org.joml.Matrix4f;
//?}

// {@code backdrop-filter: blur()} against the game. Chromium only blurs what is in the page, and the game is
// not: the page reports where its blurs are ({@link Native#blurRects}) and we draw a blurred copy of the game's
// framebuffer there, under the page. Before 1.21.6 the GUI draws as it goes, so the copy is taken and blurred
// (with the vanilla blur shaders) right when the page is drawn: the world and the HUD below the page.
public final class GameBlur {
	// ponytail: 4 different radii per frame; further ones share the last slot.
	private static final Slot[] SLOTS = new Slot[4];
	private static int used;
	//? if >=1.21.2
	private static final CrossFrameResourcePool POOL = new CrossFrameResourcePool(3);

	private GameBlur() {}

	static void newFrame() {
		used = 0;
		//? if >=1.21.2
		POOL.endFrame();
	}

	// Draws the blurred backdrop of one page; {@code rects} in framebuffer pixels (10 floats each).
	static void draw(GuiGraphicsExtractor gui, float[] rects) {
		var window = Minecraft.getInstance().getWindow();
		int fbW = window.getWidth(), fbH = window.getHeight();
		gui.flush(); // what is drawn so far is what gets blurred
		gui.pose().pushPose();
		gui.pose().scale((float) gui.guiWidth() / fbW, (float) gui.guiHeight() / fbH, 1); // work in framebuffer pixels
		for (int i = 0; i + 10 <= rects.length; i += 10) {
			int x = Math.round(rects[i]), y = Math.round(rects[i + 1]);
			int w = Math.round(rects[i + 2]), h = Math.round(rects[i + 3]);
			if (w <= 0 || h <= 0) continue;
			Identifier texture = slot(rects[i + 4], fbW, fbH).name;
			float alpha = Math.max(0, Math.min(rects[i + 9], 1));
			float max = Math.min(w, h) / 2f;
			float tl = Math.min(rects[i + 5], max), tr = Math.min(rects[i + 6], max);
			float br = Math.min(rects[i + 7], max), bl = Math.min(rects[i + 8], max);
			// ponytail: rounded corners are 1 px rows, no antialiasing (blurred vs sharp game barely shows it).
			int top = (int) Math.ceil(Math.max(tl, tr)), bottom = (int) Math.ceil(Math.max(bl, br));
			for (int row = 0; row < top; row++) strip(gui, texture, x, y + row, w, 1, inset(tl, row), inset(tr, row), fbW, fbH, alpha);
			strip(gui, texture, x, y + top, w, h - top - bottom, 0, 0, fbW, fbH, alpha);
			for (int row = 0; row < bottom; row++) strip(gui, texture, x, y + h - 1 - row, w, 1, inset(bl, row), inset(br, row), fbW, fbH, alpha);
		}
		gui.pose().popPose();
		gui.flush(); // under the page
	}

	// How far a corner of {@code radius} cuts into the row {@code row} pixels from the edge.
	static int inset(float radius, int row) {
		float d = radius - row - 0.5f;
		return d <= 0 ? 0 : Math.round(radius - (float) Math.sqrt(radius * radius - d * d));
	}

	private static void strip(GuiGraphicsExtractor gui, Identifier texture, int x, int y, int w, int h, int left, int right, int fbW, int fbH, float alpha) {
		x += left;
		w -= left + right;
		if (w <= 0 || h <= 0) return;
		// The slot shows the whole screen: sample the same spot we draw at (render targets are bottom-up).
		//? if >=1.21.2 {
		gui.blit(RenderType::guiTextured, texture, x, y, x, fbH - y, w, h, w, -h, fbW, fbH, Math.round(alpha * 255) << 24 | 0xFFFFFF);
		//?} else {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1, 1, 1, alpha);
		gui.blit(texture, x, y, w, h, x, fbH - y, w, -h, fbW, fbH);
		RenderSystem.setShaderColor(1, 1, 1, 1);
		RenderSystem.disableBlend();
		//?}
	}

	private static Slot slot(float sigma, int fbW, int fbH) {
		int key = Math.max(1, Math.round(sigma));
		for (int i = 0; i < used; i++) if (SLOTS[i].sigma == key) return SLOTS[i];
		if (used == SLOTS.length) return SLOTS[used - 1];
		if (SLOTS[used] == null) SLOTS[used] = new Slot(used);
		Slot slot = SLOTS[used++];
		slot.make(key, fbW, fbH);
		return slot;
	}

	// One blur radius: the framebuffer as it is now, blurred, as a texture GUI quads can sample.
	private static final class Slot extends AbstractTexture {
		final Identifier name;
		int sigma; // CSS blur radius = standard deviation, in framebuffer pixels
		private TextureTarget a; // the blur
		private TextureTarget b; // before 1.21.2 the other half of the blur, after a copy of the frame
		//? if <1.21.2
		private PostPass horizontal, vertical;
		//? if >=1.21.2
		private int fbo; // a's colour, for opaque()

		Slot(int index) {
			name = Pages.id("blur/" + index);
		}

		void make(int sigma, int fbW, int fbH) {
			this.sigma = sigma;
			// Two box blurs in a row are close to a gaussian of sigma = radius * 0.82.
			float radius = Math.max(1, Math.round(sigma * 1.22f));
			Minecraft mc = Minecraft.getInstance();
			if (a == null || a.width != fbW || a.height != fbH) {
				free();
				//? if >=1.21.5 {
				a = new TextureTarget("Vitrine blur", fbW, fbH, false);
				b = new TextureTarget("Vitrine blur copy", fbW, fbH, false);
				a.setFilterMode(FilterMode.LINEAR);
				texture = a.getColorTexture();
				fbo = GlStateManager.glGenFramebuffers();
				GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
				GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, ((GlTexture) texture).glId(), 0);
				//?} else if >=1.21.2 {
				a = new TextureTarget(fbW, fbH, false);
				b = new TextureTarget(fbW, fbH, false);
				a.setFilterMode(GL11.GL_LINEAR);
				fbo = a.frameBufferId;
				//?} else {
				a = new TextureTarget(fbW, fbH, false, Minecraft.ON_OSX);
				b = new TextureTarget(fbW, fbH, false, Minecraft.ON_OSX);
				a.setFilterMode(GL11.GL_LINEAR);
				b.setFilterMode(GL11.GL_LINEAR);
				try {
					//? if >=1.20.5 {
					horizontal = new PostPass(mc.getResourceManager(), "box_blur", a, b, true);
					vertical = new PostPass(mc.getResourceManager(), "box_blur", b, a, true);
					//?} else {
					horizontal = new PostPass(mc.getResourceManager(), "blur", a, b);
					vertical = new PostPass(mc.getResourceManager(), "blur", b, a);
					//?}
				} catch (java.io.IOException e) {
					throw new IllegalStateException("Vitrine: vanilla blur shader missing", e);
				}
				Matrix4f ortho = new Matrix4f().setOrtho(0, fbW, 0, fbH, 0.1f, 1000);
				horizontal.setOrthoMatrix(ortho);
				vertical.setOrthoMatrix(ortho);
				horizontal.getEffect().safeGetUniform("BlurDir").set(1f, 0f);
				vertical.getEffect().safeGetUniform("BlurDir").set(0f, 1f);
				//?}
				//? if <1.21.5
				id = a.getColorTextureId();
				mc.getTextureManager().register(name, this);
			}
			RenderTarget main = mc.getMainRenderTarget();
			//? if >=1.21.2 {
			// The vanilla menu blur (box blurs). Given a target of ours it leaves it as it was, so it blurs the main
			// one in place, as for menus: keep the frame, blur it, take the result, put the frame back.
			PostChain chain = mc.getShaderManager().getPostChain(Identifier.withDefaultNamespace("blur"), LevelTargetBundle.MAIN_TARGETS);
			if (chain == null) return;
			copy(main, b, fbW, fbH);
			//? if >=1.21.5 {
			chain.process(main, POOL, pass -> pass.setUniform("Radius", radius));
			//?} else {
			chain.setUniform("Radius", radius);
			chain.process(main, POOL);
			//?}
			copy(main, a, fbW, fbH);
			copy(b, main, fbW, fbH);
			// It averages alpha too, and the game leaves the frame's alpha at 0 in places: make the blur opaque.
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
			GlStateManager._colorMask(false, false, false, true);
			//? if >=1.21.5 {
			GL11.glClearColor(0, 0, 0, 1);
			//?} else
			GlStateManager._clearColor(0, 0, 0, 1);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			GlStateManager._colorMask(true, true, true, true);
			//?} else {
			copy(main, a, fbW, fbH);
			for (int i = 0; i < 2; i++) {
				horizontal.getEffect().safeGetUniform("Radius").set(radius);
				vertical.getEffect().safeGetUniform("Radius").set(radius);
				horizontal.process(0);
				vertical.process(0);
			}
			//?}
			//? if <1.21.5
			main.bindWrite(true);
		}

		private static void copy(RenderTarget from, RenderTarget to, int w, int h) {
			//? if >=1.21.5 {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(from.getColorTexture(), to.getColorTexture(), 0, 0, 0, 0, 0, w, h);
			//?} else {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
			GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
			//?}
		}

		private void free() {
			if (a == null) return;
			a.destroyBuffers();
			b.destroyBuffers();
			//? if >=1.21.5
			GlStateManager._glDeleteFramebuffers(fbo);
			//? if <1.21.2 {
			horizontal.close();
			vertical.close();
			//?}
		}

		@Override
		public void close() {
			// the targets own the textures; the texture manager must not free them
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
}
*///?}
