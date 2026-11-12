package dev.holoweb.vitrine.impl;

//? if >=26.2 {
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.holoweb.vitrine.Vitrine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * The slots one page leaves for the game ({@code --vitrine-bind}, see helper.cpp): each is drawn by its
 * {@link Vitrine.Bind} at its place in one texture the size of the page, and from there put over the page (so
 * the backgrounds of the slot and of what it sits in are behind it, as for any content). Nothing goes back to
 * the page. One texture for all of them, as {@code GuiItemAtlas} does: slots that move or change size cost
 * nothing, and only the part the page lets show is drawn (scissor).
 */
final class Binds {
	private static final Map<String, Vitrine.Bind> REGISTRY = new HashMap<>();
	private static final int MAX_SLOTS = 64; // pages are untrusted
	/**
	 * Slots are drawn again at most this often: about the page's own rate (60 a second at most), so a game
	 * running faster than the page does not draw them for frames in which nothing around them changes.
	 * Under one page frame, so a game at the page's rate never skips one.
	 */
	private static final long REDRAW_NANOS = 12_500_000;

	private final Identifier textureId; // the target's name for world render types, or null (screen pages blit its view)
	private List<Slot> slots = List.of();
	private boolean[] drawn = {}; // by slot index: its part of the target holds this frame's picture
	private Target target; // while the page has slots
	private long drawnAt; // System.nanoTime() of the last draw

	/**
	 * In page pixels: the element's box, the part of it the page lets show ({@code x0 < x1}, {@code y0 < y1}),
	 * and its corner radii (top left, top right, bottom right, bottom left).
	 */
	record Slot(int x, int y, int w, int h, int x0, int y0, int x1, int y1, float opacity, float tl, float tr, float br, float bl, String name, String data) {}

	/** A piece of a slot's shape, in page pixels. */
	@FunctionalInterface
	interface Rects {
		void rect(Slot slot, int x0, int y0, int x1, int y1);
	}

	Binds(Identifier textureId) {
		this.textureId = textureId;
	}

	static void register(String name, Vitrine.Bind bind) {
		if (bind == null) REGISTRY.remove(name);
		else REGISTRY.put(name, bind);
	}

	/** Start of the frame, after the page's new frame was taken: draws every slot that has a bind. The page is {@code width} x {@code height}. */
	void render(long handle, int width, int height) {
		byte[] report = Native.bindSlots(handle); // null: as before
		if (report != null) {
			slots = parse(new String(report, StandardCharsets.UTF_8));
			drawn = new boolean[slots.size()];
		}
		if (slots.isEmpty()) {
			close();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (target == null) target = new Target();
		boolean fresh = target.size(width, height, textureId);
		long now = System.nanoTime();
		if (report == null && !fresh && now - drawnAt < REDRAW_NANOS) return; // what is in the target still stands
		drawnAt = now;
		// ponytail: one depth buffer for all, so slots that overlap on the page cut into each other.
		Offscreen.clear(target.color, target.depth);
		for (int i = 0; i < slots.size(); i++) {
			Slot slot = slots.get(i);
			drawn[i] = false;
			Vitrine.Bind bind = REGISTRY.get(slot.name);
			int x1 = Math.min(slot.x1, target.w), y1 = Math.min(slot.y1, target.h);
			if (bind == null || x1 <= slot.x0 || y1 <= slot.y0) continue;
			try {
				Offscreen.draw(target.color, target.colorView, target.depth, target.depthView, target.projection, target.w, target.h,
						slot.x0, slot.y0, x1 - slot.x0, y1 - slot.y0, nodes -> {
					// As PictureInPictureRenderer.prepare with scale 1: origin at the centre, y down, z towards the viewer flipped.
					PoseStack pose = new PoseStack();
					pose.translate(slot.x + slot.w / 2f, slot.y + slot.h / 2f, 0);
					pose.scale(1, 1, -1);
					mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
					bind.render(new Vitrine.Slot(slot.w, slot.h, slot.data), pose, nodes);
				});
				drawn[i] = true;
			} catch (RuntimeException e) {
				VitrineClient.LOG.error("Vitrine: bind '{}' failed and was removed", slot.name, e);
				REGISTRY.remove(slot.name);
			}
		}
	}

	/** The target's name while it has something to show (world panels), else null. */
	Identifier texture() {
		if (target == null) return null;
		for (boolean d : drawn) if (d) return textureId;
		return null;
	}

	/**
	 * The shape of every drawn slot, as rectangles: the part the page lets show, minus its rounded corners.
	 * ponytail: corners are 1 px rows, no antialiasing (as GameBlur's).
	 */
	void cover(Rects out) {
		if (target == null) return;
		for (int i = 0; i < slots.size(); i++) if (drawn[i]) cover(slots.get(i), target.w, target.h, out);
	}

	static void cover(Slot s, int width, int height, Rects out) {
		float max = Math.min(s.w, s.h) / 2f;
		float tl = Math.min(s.tl, max), tr = Math.min(s.tr, max), br = Math.min(s.br, max), bl = Math.min(s.bl, max);
		int top = (int) Math.ceil(Math.max(tl, tr)), bottom = (int) Math.ceil(Math.max(bl, br));
		int right = s.x + s.w, end = s.y + s.h;
		for (int row = 0; row < top; row++) piece(s, s.x + GameBlur.inset(tl, row), right - GameBlur.inset(tr, row), s.y + row, s.y + row + 1, width, height, out);
		piece(s, s.x, right, s.y + top, end - bottom, width, height, out);
		for (int row = 0; row < bottom; row++) piece(s, s.x + GameBlur.inset(bl, row), right - GameBlur.inset(br, row), end - row - 1, end - row, width, height, out);
	}

	/** The rectangle {@code left..right} x {@code top..bottom}, cut to what the page lets show. */
	private static void piece(Slot s, int left, int right, int top, int bottom, int width, int height, Rects out) {
		int x0 = Math.max(left, s.x0), x1 = Math.min(Math.min(right, s.x1), width);
		int y0 = Math.max(top, s.y0), y1 = Math.min(Math.min(bottom, s.y1), height);
		if (x0 < x1 && y0 < y1) out.rect(s, x0, y0, x1, y1);
	}

	/** GUI extraction, after the page's own blit; the page fills the framebuffer. */
	void draw(GuiGraphicsExtractor gui) {
		if (slots.isEmpty() || target == null) return;
		var window = Minecraft.getInstance().getWindow();
		gui.pose().pushMatrix();
		gui.pose().scale((float) gui.guiWidth() / window.getWidth(), (float) gui.guiHeight() / window.getHeight()); // work in framebuffer pixels
		// One texture, so the GUI draws all the slots in one batch.
		TextureSetup texture = TextureSetup.singleTexture(target.colorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		Matrix3x2f pose = new Matrix3x2f(gui.pose());
		float w = target.w, h = target.h;
		var scissor = gui.scissorStack.peek();
		cover((s, x0, y0, x1, y1) -> {
			int a = Math.round(Math.clamp(s.opacity, 0, 1) * 255);
			// The texture is premultiplied (drawn over transparent) and bottom-up (render targets are).
			gui.guiRenderState.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, texture, pose,
					x0, y0, x1, y1, x0 / w, x1 / w, 1 - y0 / h, 1 - y1 / h, a << 24 | a << 16 | a << 8 | a, scissor));
		});
		gui.pose().popMatrix();
	}

	void close() {
		if (target != null) target.free(textureId);
		target = null;
	}

	/**
	 * One line per slot: {@code x,y,w,h,clipX0,clipY0,clipX1,clipY1,opacity,tl,tr,br,bl TAB name TAB data}.
	 * Bad and hidden ones are dropped.
	 */
	static List<Slot> parse(String text) {
		List<Slot> out = new ArrayList<>();
		for (String line : text.split("\n")) {
			if (out.size() == MAX_SLOTS) break;
			String[] parts = line.split("\t", -1);
			String[] n = parts[0].split(",");
			if (parts.length != 3 || n.length != 13 || parts[1].isEmpty()) continue;
			try {
				float[] f = new float[13];
				for (int i = 0; i < 13; i++) f[i] = Float.parseFloat(n[i]);
				int x = Math.round(f[0]), y = Math.round(f[1]), w = Math.round(f[2]), h = Math.round(f[3]); // NaN is 0
				int x0 = Math.max(Math.max(x, Math.round(f[4])), 0), y0 = Math.max(Math.max(y, Math.round(f[5])), 0);
				int x1 = Math.min(x + w, Math.round(f[6])), y1 = Math.min(y + h, Math.round(f[7]));
				if (x1 <= x0 || y1 <= y0) continue;
				out.add(new Slot(x, y, w, h, x0, y0, x1, y1, f[8], Math.max(f[9], 0), Math.max(f[10], 0), Math.max(f[11], 0), Math.max(f[12], 0), parts[1], parts[2]));
			} catch (NumberFormatException e) {
				// not ours
			}
		}
		return out;
	}

	/** Colour and depth, the size of the page. A texture by name too, for world render types. */
	private static final class Target extends AbstractTexture {
		final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("Vitrine bind");
		GpuTexture color, depth;
		GpuTextureView colorView, depthView;
		int w, h;

		/** True if the textures are new (and empty). */
		boolean size(int w, int h, Identifier id) {
			if (color != null && this.w == w && this.h == h) return false;
			release();
			GpuDevice device = RenderSystem.getDevice();
			color = device.createTexture("Vitrine bind", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			depth = device.createTexture("Vitrine bind depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, w, h, 1, 1);
			colorView = device.createTextureView(color);
			depthView = device.createTextureView(depth);
			this.w = w;
			this.h = h;
			if (id != null) {
				texture = color;
				textureView = colorView;
				sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR); // panels are scaled
				Minecraft.getInstance().getTextureManager().register(id, this);
			}
			return true;
		}

		private void release() {
			if (color == null) return;
			colorView.close();
			depthView.close();
			color.close();
			depth.close();
			color = null;
		}

		void free(Identifier id) {
			if (id != null && color != null) Minecraft.getInstance().getTextureManager().release(id);
			release();
			projection.close();
		}

		@Override
		public void close() {
			// we own the textures; the texture manager must not free them
		}
	}

	/** Self-check of the parser and the shapes, run by hand with the game on the classpath. */
	public static void main(String[] args) {
		List<Slot> slots = parse("10.4,20.6,100,50,0,0,1920,1080,0.5,0,0,0,0\tmod:a\tx y\nbroken\n1,2,0,5,0,0,1,1,1,0,0,0,0\tmod:b\t\n"
				+ "-5,2,30,40,0,10,20,30,1,0,0,0,0\tmod:c\t\n500,2,3,4,0,0,9,9,1,0,0,0,0\tmod:d\t\n0,0,100,100,0,0,100,60,1,10,0,0,200\tmod:e\t");
		if (slots.size() != 3) throw new AssertionError(slots);
		Slot a = slots.get(0), c = slots.get(1), e = slots.get(2);
		if (a.x != 10 || a.y != 21 || a.w != 100 || a.h != 50 || a.x0 != 10 || a.x1 != 110 || a.y1 != 71 || a.opacity != 0.5f || !a.name.equals("mod:a") || !a.data.equals("x y")) throw new AssertionError(a);
		if (!c.name.equals("mod:c") || !c.data.isEmpty() || c.x != -5 || c.x0 != 0 || c.y0 != 10 || c.x1 != 20 || c.y1 != 30) throw new AssertionError(c);
		// Square corners: one rectangle, the visible part.
		int[] count = {0}, area = {0};
		Rects sum = (s, x0, y0, x1, y1) -> {
			if (x0 < s.x0 || y0 < s.y0 || x1 > s.x1 || y1 > s.y1) throw new AssertionError("outside the clip");
			count[0]++;
			area[0] += (x1 - x0) * (y1 - y0);
		};
		cover(a, 1920, 1080, sum);
		if (count[0] != 1 || area[0] != 100 * 50) throw new AssertionError(count[0] + " " + area[0]);
		// Top left radius 10, bottom left clamped to 50, cut at y = 60: rows for the top corner and for 10 rows of the bottom one.
		count[0] = area[0] = 0;
		cover(e, 100, 100, sum);
		if (count[0] != 10 + 1 + 10 || area[0] >= 100 * 60 || area[0] <= 100 * 60 - 10 * 10 - 50 * 10) throw new AssertionError(count[0] + " " + area[0]);
		System.out.println("Binds ok");
	}
}
//?}
