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
import org.joml.Matrix3x2f;

/**
 * The slots one screen page leaves for the game ({@code --vitrine-bind}, see helper.cpp): each is drawn by its
 * {@link Vitrine.Bind} at its place in one texture the size of the screen, and from there put over the page (so
 * the backgrounds of the slot and of what it sits in are behind it, as for any content). Nothing goes back to
 * the page. One texture for all of them, as {@code GuiItemAtlas} does: slots that move or change size cost
 * nothing, and only the part the page lets show is drawn (scissor).
 */
final class Binds {
	private static final Map<String, Vitrine.Bind> REGISTRY = new HashMap<>();
	private static final int MAX_SLOTS = 64; // pages are untrusted
	/**
	 * Slots are drawn again at most this often: about the page's own rate (60 a second, ScreenPage), so a game
	 * running faster than the page does not draw them for frames in which nothing around them changes.
	 * Under one page frame, so a game at the page's rate never skips one.
	 */
	private static final long REDRAW_NANOS = 12_500_000;

	private List<Slot> slots = List.of();
	private boolean[] drawn = {}; // by slot index: its part of the target holds this frame's picture
	private Target target; // while the page has slots
	private long drawnAt; // System.nanoTime() of the last draw

	/** In framebuffer pixels: the element's box, and the part of it the page lets show ({@code x0 < x1}, {@code y0 < y1}). */
	record Slot(int x, int y, int w, int h, int x0, int y0, int x1, int y1, float opacity, String name, String data) {}

	static void register(String name, Vitrine.Bind bind) {
		if (bind == null) REGISTRY.remove(name);
		else REGISTRY.put(name, bind);
	}

	/** Start of the frame, after the page's new frame was taken: draws every slot that has a bind. */
	void render(long handle) {
		byte[] report = Native.bindSlots(handle); // null: as before
		if (report != null) {
			slots = parse(new String(report, StandardCharsets.UTF_8));
			drawn = new boolean[slots.size()];
		}
		if (slots.isEmpty()) {
			if (target != null) target.close();
			target = null;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (target == null) target = new Target();
		boolean fresh = target.size(mc.getWindow().getWidth(), mc.getWindow().getHeight());
		long now = System.nanoTime();
		if (report == null && !fresh && now - drawnAt < REDRAW_NANOS) return; // what is in the target still stands
		drawnAt = now;
		// ponytail: one depth buffer for all, so slots that overlap on screen cut into each other.
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

	/** GUI extraction, after the page's own blit. */
	void draw(GuiGraphicsExtractor gui) {
		if (slots.isEmpty() || target == null) return;
		var window = Minecraft.getInstance().getWindow();
		gui.pose().pushMatrix();
		gui.pose().scale((float) gui.guiWidth() / window.getWidth(), (float) gui.guiHeight() / window.getHeight()); // work in framebuffer pixels
		// One texture, so the GUI draws all the slots in one batch.
		TextureSetup texture = TextureSetup.singleTexture(target.colorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		Matrix3x2f pose = new Matrix3x2f(gui.pose());
		float w = target.w, h = target.h;
		for (int i = 0; i < slots.size(); i++) {
			if (!drawn[i]) continue;
			Slot s = slots.get(i);
			int x1 = Math.min(s.x1, target.w), y1 = Math.min(s.y1, target.h);
			int a = Math.round(Math.clamp(s.opacity, 0, 1) * 255);
			// The texture is premultiplied (drawn over transparent) and bottom-up (render targets are).
			gui.guiRenderState.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, texture, pose,
					s.x0, s.y0, x1, y1, s.x0 / w, x1 / w, 1 - s.y0 / h, 1 - y1 / h, a << 24 | a << 16 | a << 8 | a, gui.scissorStack.peek()));
		}
		gui.pose().popMatrix();
	}

	void close() {
		if (target != null) target.close();
		target = null;
		slots = List.of();
	}

	/** One line per slot: {@code x,y,w,h,clipX0,clipY0,clipX1,clipY1,opacity TAB name TAB data}. Bad and hidden ones are dropped. */
	static List<Slot> parse(String text) {
		List<Slot> out = new ArrayList<>();
		for (String line : text.split("\n")) {
			if (out.size() == MAX_SLOTS) break;
			String[] parts = line.split("\t", -1);
			String[] n = parts[0].split(",");
			if (parts.length != 3 || n.length != 9 || parts[1].isEmpty()) continue;
			try {
				int[] v = new int[8];
				for (int i = 0; i < 8; i++) v[i] = Math.round(Float.parseFloat(n[i])); // NaN is 0
				int x0 = Math.max(Math.max(v[0], v[4]), 0), y0 = Math.max(Math.max(v[1], v[5]), 0);
				int x1 = Math.min(v[0] + v[2], v[6]), y1 = Math.min(v[1] + v[3], v[7]);
				if (x1 <= x0 || y1 <= y0) continue;
				out.add(new Slot(v[0], v[1], v[2], v[3], x0, y0, x1, y1, Float.parseFloat(n[8]), parts[1], parts[2]));
			} catch (NumberFormatException e) {
				// not ours
			}
		}
		return out;
	}

	/** Colour and depth, the size of the framebuffer. */
	private static final class Target {
		final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("Vitrine bind");
		GpuTexture color, depth;
		GpuTextureView colorView, depthView;
		int w, h;

		/** True if the textures are new (and empty). */
		boolean size(int w, int h) {
			if (color != null && this.w == w && this.h == h) return false;
			free();
			GpuDevice device = RenderSystem.getDevice();
			color = device.createTexture("Vitrine bind", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			depth = device.createTexture("Vitrine bind depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, w, h, 1, 1);
			colorView = device.createTextureView(color);
			depthView = device.createTextureView(depth);
			this.w = w;
			this.h = h;
			return true;
		}

		private void free() {
			if (color == null) return;
			colorView.close();
			depthView.close();
			color.close();
			depth.close();
			color = null;
		}

		void close() {
			free();
			projection.close();
		}
	}

	/** Self-check of the parser, run by hand with the game on the classpath. */
	public static void main(String[] args) {
		List<Slot> slots = parse("10.4,20.6,100,50,0,0,1920,1080,0.5\tmod:a\tx y\nbroken\n1,2,0,5,0,0,1,1,1\tmod:b\t\n-5,2,30,40,0,10,20,30,1\tmod:c\t\n500,2,3,4,0,0,9,9,1\tmod:d\t");
		if (slots.size() != 2) throw new AssertionError(slots);
		Slot a = slots.get(0), c = slots.get(1);
		if (a.x != 10 || a.y != 21 || a.w != 100 || a.h != 50 || a.x0 != 10 || a.x1 != 110 || a.y1 != 71 || a.opacity != 0.5f || !a.name.equals("mod:a") || !a.data.equals("x y")) throw new AssertionError(a);
		if (!c.name.equals("mod:c") || !c.data.isEmpty() || c.x != -5 || c.x0 != 0 || c.y0 != 10 || c.x1 != 20 || c.y1 != 30) throw new AssertionError(c);
		System.out.println("Binds.parse ok");
	}
}
//?}
