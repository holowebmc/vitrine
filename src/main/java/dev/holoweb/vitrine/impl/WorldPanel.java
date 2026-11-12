package dev.holoweb.vitrine.impl;

import dev.holoweb.vitrine.Vitrine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//? if >=26.1 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else if >=1.21.9 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
*///?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** Behind {@link dev.holoweb.vitrine.WorldView}: the panel geometry, culling and hit tests. */
public final class WorldPanel {
	// Camera of the last rendered frame (render thread).
	static Vec3 camPos = Vec3.ZERO;
	static final Quaternionf camRot = new Quaternionf();
	static float tanX = 1, tanY = 1;
	static Frustum frustum;

	private final String url;
	private final Identifier id;
	private final int pxWidth, pxHeight;
	final Map<String, Vitrine.Handler> handlers = new HashMap<>();
	private List<String> pending = new ArrayList<>(); // messages sent before start
	Browser browser; // null until CEF is up
	private Vec3 center;
	private final Quaternionf rotation = new Quaternionf();
	private float width, height;
	private boolean billboard;
	private Raised raised; // elements standing out of the panel (--vitrine-depth), or null
	private final Vector3f right = new Vector3f(), up = new Vector3f(), normal = new Vector3f();
	private float maxDistance = 64;
	private float volume = 1, audioRange = 24;
	private boolean visible = true, shown = true, closed;

	WorldPanel(String url, int pxWidth, int pxHeight, Vec3 center, Quaternionfc rotation, float width, Identifier id) {
		this.url = url;
		this.id = id;
		this.pxWidth = pxWidth;
		this.pxHeight = pxHeight;
		move(center, rotation, width);
	}

	void start() {
		if (closed || browser != null) return;
		browser = new Browser(url, pxWidth, pxHeight, 60, id, true, SoundSource.RECORDS);
		browser.handlers = handlers;
		for (String json : pending) Native.send(browser.handle, json);
		pending = null;
		shown = true; // a new browser is visible; frame() hides it if needed
	}

	/** Places the panel: {@code center} in world coordinates, {@code rotation} of its +Z face, width in blocks. */
	public void move(Vec3 center, Quaternionfc rotation, float width) {
		this.center = center;
		this.rotation.set(rotation);
		this.width = width;
		this.height = width * pxHeight / pxWidth;
		rotation.transform(1, 0, 0, right);
		rotation.transform(0, 1, 0, up);
		rotation.transform(0, 0, 1, normal);
	}

	/**
	 * Billboard: the panel stays at its position but turns (yaw and pitch, no roll) to face the camera.
	 * It reacts to where the player is, not where they look, so aiming around the page doesn't move it.
	 * Off by default; turning it off keeps the last rotation.
	 */
	public void billboard(boolean on) {
		billboard = on;
	}

	public void send(String json) {
		if (browser == null) pending.add(json);
		else Native.send(browser.handle, json);
	}

	public void on(String name, Vitrine.Handler handler) {
		handlers.put(name, handler);
	}

	public void visible(boolean visible) {
		this.visible = visible;
	}

	/** Beyond this distance (blocks, default 64) the panel is hidden and CEF stops rendering it. */
	public void maxDistance(float blocks) {
		this.maxDistance = blocks;
	}

	/**
	 * Sound comes from the panel and fades linearly to silence at {@code range} blocks (default 24),
	 * like vanilla sounds; {@code volume} 0..1 (default 1) is on top of the game's "Jukebox/Note Blocks" slider.
	 */
	public void audio(float volume, float range) {
		this.volume = volume;
		this.audioRange = range;
	}

	public void close() {
		if (closed) return;
		closed = true;
		Pages.remove(this);
		if (browser != null) browser.release();
	}

	// --- per frame ---

	//? if >=26.1 {
	static void captureCamera(CameraRenderState cam) {
		camPos = cam.pos;
		camRot.set(cam.orientation);
		tanX = 1 / cam.projectionMatrix.m00();
		tanY = 1 / cam.projectionMatrix.m11();
		frustum = cam.cullFrustum;
	}
	//?} else if >=1.21.9 {
	/*static void captureCamera(WorldExtractionContext ctx) {
		camPos = ctx.camera().position();
		camRot.set(ctx.camera().rotation());
		tanX = 1 / ctx.cullProjectionMatrix().m00();
		tanY = 1 / ctx.cullProjectionMatrix().m11();
		frustum = ctx.frustum();
	}
	*///?} else {
	/*static void captureCamera(WorldRenderContext ctx) {
		camPos = ctx.camera().getPosition();
		camRot.set(ctx.camera().rotation());
		//? if <1.20.5
		camRot.rotateY((float) Math.PI); // the old camera looks along +Z with +X to its left; ray() wants -Z / +X right
		tanX = 1 / ctx.projectionMatrix().m00();
		tanY = 1 / ctx.projectionMatrix().m11();
		frustum = ctx.frustum();
	}
	*///?}

	/** World direction through a point of the view, in normalized device coords (0,0 = crosshair). */
	static Vector3f ray(double ndcX, double ndcY) {
		return camRot.transform(new Vector3f((float) ndcX * tanX, (float) ndcY * tanY, -1)).normalize();
	}

	void frame() {
		if (billboard) faceCamera();
		if (browser == null) return;
		double d = camPos.distanceTo(center);
		boolean show = visible && d <= maxDistance && (frustum == null || frustum.isVisible(bounds()));
		if (show != shown) {
			shown = show;
			Native.setHidden(browser.handle, !show);
		}
		browser.audio.update(center, volume, audioRange); // audible even when not drawn
		if (!show) return;
		// ponytail: fixed distance tiers; make them configurable if a mod needs it.
		browser.fps(d < 16 ? 60 : d < 32 ? 30 : 15);
		browser.update();
		float[] report = Native.raised(browser.handle); // null: same as before
		if (report != null) raised = Raised.of(report);
	}

	/** Points +Z at the camera: rotY(yaw) * rotX(pitch) keeps the page's right edge level. */
	private void faceCamera() {
		Vec3 d = camPos.subtract(center);
		double len = d.length();
		if (len < 1e-3) return;
		float yaw = (float) Math.atan2(d.x, d.z);
		float pitch = (float) -Math.asin(d.y / len);
		move(center, new Quaternionf().rotateY(yaw).rotateX(pitch), width);
	}

	//? if >=1.21.9 {
	void submit(SubmitNodeCollector collector, PoseStack poseStack) {
		if (shown && browser != null && browser.hasFrame()) collector.submitCustomGeometry(poseStack, RenderTypes.text(browser.textureId), this::quad);
	}
	//?} else {
	/*// Before 1.21.9 level rendering draws immediately, with the given camera pose.
	void submit(MultiBufferSource buffers, PoseStack.Pose pose) {
		if (!shown || browser == null || !browser.hasFrame()) return;
		if (renderType == null) renderType = PanelRenderType.of(browser.textureId);
		quad(pose, buffers.getBuffer(renderType));
	}

	private net.minecraft.client.renderer.RenderType renderType;
	*///?}

	private void quad(PoseStack.Pose pose, VertexConsumer vc) {
		// Each face counter-clockwise seen from outside; the page itself: top-left, bottom-left, bottom-right, top-right.
		point(pose, vc, 0, 0, 0, 255, 0, 0);
		point(pose, vc, 0, pxHeight, 0, 255, 0, pxHeight);
		point(pose, vc, pxWidth, pxHeight, 0, 255, pxWidth, pxHeight);
		point(pose, vc, pxWidth, 0, 0, 255, pxWidth, 0);
		Raised solids = raised;
		if (solids != null) solids.draw((x, y, h, tx, ty, gray) -> point(pose, vc, x, y, h, gray, tx, ty));
	}

	/** A vertex at page pixel (x, y), {@code depth} page pixels out of the face, showing page pixel (tx, ty). */
	private void point(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float depth, int gray, float tx, float ty) {
		float s = width / pxWidth; // blocks per page pixel
		float a = (x - pxWidth / 2f) * s, b = (pxHeight / 2f - y) * s, n = depth * s;
		// Level rendering is camera-relative.
		float px = (float) (center.x - camPos.x) + right.x * a + up.x * b + normal.x * n;
		float py = (float) (center.y - camPos.y) + right.y * a + up.y * b + normal.y * n;
		float pz = (float) (center.z - camPos.z) + right.z * a + up.z * b + normal.z * n;
		float u = tx / pxWidth, v = ty / pxHeight;
		//? if >=1.21 {
		vc.addVertex(pose, px, py, pz).setColor(gray, gray, gray, 255).setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT);
		//?} else
		/*vc.vertex(pose.pose(), px, py, pz).color(gray, gray, gray, 255).uv(u, v).uv2(LightCoordsUtil.FULL_BRIGHT).endVertex();*/
	}

	/**
	 * Ray from {@code origin} along {@code dir} (unit) against the front face: {distance, pageX, pageY}, or null.
	 * {@code bounded}: false also answers off the edges (page coords outside the page), for drags.
	 */
	double[] hit(Vec3 origin, Vector3f dir, boolean bounded) {
		if (!shown || browser == null) return null;
		float facing = dir.dot(normal);
		if (facing >= 0) return null; // parallel, or seen from behind
		Vec3 toCenter = center.subtract(origin);
		double t = (toCenter.x * normal.x + toCenter.y * normal.y + toCenter.z * normal.z) / facing;
		if (t < 0) return null;
		double hx = origin.x + dir.x * t - center.x, hy = origin.y + dir.y * t - center.y, hz = origin.z + dir.z * t - center.z;
		double u = (hx * right.x + hy * right.y + hz * right.z) / width + 0.5;
		double v = 0.5 - (hx * up.x + hy * up.y + hz * up.z) / height;
		double[] best = bounded && (u < 0 || u > 1 || v < 0 || v > 1) ? null : new double[] {t, u * pxWidth, v * pxHeight};
		// Raised elements stand between the eye and the page: the ray may meet one first.
		Raised solids = raised;
		if (solids == null) return best;
		double along = dir.x * right.x + dir.y * right.y + dir.z * right.z, upward = dir.x * up.x + dir.y * up.y + dir.z * up.z;
		double[] h = solids.hit(u * pxWidth, v * pxHeight, along / facing, -upward / facing);
		if (h == null) return best;
		double sooner = t + h[0] * width / pxWidth / facing; // facing < 0: it meets the solid before the page
		return sooner < 0 ? best : new double[] {sooner, h[1], h[2]};
	}

	private AABB bounds() {
		double ex = Math.abs(right.x) * width / 2 + Math.abs(up.x) * height / 2;
		double ey = Math.abs(right.y) * width / 2 + Math.abs(up.y) * height / 2;
		double ez = Math.abs(right.z) * width / 2 + Math.abs(up.z) * height / 2;
		// Raised elements stick out of the front.
		double d = raised == null ? 0 : raised.height * width / pxWidth;
		double nx = normal.x * d, ny = normal.y * d, nz = normal.z * d;
		return new AABB(center.x - ex + Math.min(0, nx), center.y - ey + Math.min(0, ny), center.z - ez + Math.min(0, nz),
				center.x + ex + Math.max(0, nx), center.y + ey + Math.max(0, ny), center.z + ez + Math.max(0, nz));
	}
}
