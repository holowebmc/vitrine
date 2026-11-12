package dev.holoweb.vitrine.impl;

//? if >=26.2 {
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector4f;

/**
 * {@code vitrine://item/<namespace:item>[/<pixels>]}: an item as the inventory draws it, as a PNG any page
 * can put in an {@code <img>}. Drawn once by the game's own item renderer (so blocks and modded items look
 * like they do in a slot), the way {@code GuiItemAtlas} fills its slots, then read back.
 */
final class ItemIcons {
	private static final int DEFAULT_SIZE = 64, MAX_SIZE = 512;
	// ponytail: never invalidated; clear it on resource reload if packs changing item models mid-game matters.
	private static final Map<String, byte[]> CACHE = new HashMap<>();
	private static final PoseStack POSE = new PoseStack();
	private static final Projection PROJECTION = new Projection();
	private static final SubmitNodeStorage NODES = new SubmitNodeStorage();
	private static ProjectionMatrixBuffer projectionBuffer;

	private ItemIcons() {}

	/** Answers request {@code id}; {@code path} is {@code minecraft:stone} or {@code minecraft:stone/128}. */
	static void serve(long id, String path) {
		byte[] cached = CACHE.get(path);
		if (cached != null) {
			Native.respond(id, 200, "image/png", cached);
			return;
		}
		if (Minecraft.getInstance().level == null) { // item components are only bound once in a world
			Bridge.text(id, 503, "items need a world");
			return;
		}
		String name = path;
		int size = DEFAULT_SIZE;
		int slash = path.lastIndexOf('/');
		if (slash >= 0 && path.substring(slash + 1).matches("\\d{1,4}")) {
			name = path.substring(0, slash);
			size = Math.clamp(Integer.parseInt(path.substring(slash + 1)), 1, MAX_SIZE);
		}
		Identifier key = Identifier.tryParse(name);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		if (item == null) {
			Bridge.text(id, 404, "no item '" + name + "'");
			return;
		}
		render(item.getDefaultInstance(), size, png -> {
			if (png == null) {
				Bridge.text(id, 500, "could not draw '" + path + "'");
				return;
			}
			CACHE.put(path, png);
			Native.respond(id, 200, "image/png", png);
		});
	}

	/** Draws {@code stack} filling a {@code size}² texture; {@code done} gets the PNG (null on failure) once the GPU hands the pixels back. */
	private static void render(ItemStack stack, int size, Consumer<byte[]> done) {
		Minecraft mc = Minecraft.getInstance();
		ItemStackRenderState state = new ItemStackRenderState();
		mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);

		GpuDevice device = RenderSystem.getDevice();
		GpuTexture color = device.createTexture("Vitrine item", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
		GpuTexture depth = device.createTexture("Vitrine item depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, size, size, 1, 1);
		GpuTextureView colorView = device.createTextureView(color), depthView = device.createTextureView(depth);
		GpuBuffer pixels = device.createBuffer(() -> "Vitrine item pixels", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) size * size * 4);
		Runnable free = () -> {
			pixels.close();
			colorView.close();
			depthView.close();
			color.close();
			depth.close();
		};
		try {
			device.createCommandEncoder().clearColorAndDepthTextures(color, new Vector4f(0f), depth, 0);

			// Same setup as GuiItemAtlas.drawToSlot, with the one slot being the whole texture.
			POSE.pushPose();
			POSE.translate(size / 2f, size / 2f, 0);
			POSE.scale(size, -size, size);
			if (projectionBuffer == null) projectionBuffer = new ProjectionMatrixBuffer("Vitrine items");
			PROJECTION.setupOrtho(-1000, 1000, size, size, true);
			RenderSystem.backupProjectionMatrix();
			RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(PROJECTION), ProjectionType.ORTHOGRAPHIC);
			mc.gameRenderer.lighting().setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
			try {
				state.submit(POSE, NODES, 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
				try (var frame = mc.gameRenderer.featureRenderDispatcher().prepareFrame(NODES);
						RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Vitrine item", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, frame);
				}
			} finally {
				RenderSystem.restoreProjectionMatrix();
				POSE.popPose();
			}

			device.createCommandEncoder().copyTextureToBuffer(color, pixels, 0, () -> {
				byte[] png = null;
				try (GpuBufferSlice.MappedView view = pixels.map(true, false)) {
					png = png(view.data(), size);
				} catch (Exception e) {
					VitrineClient.LOG.error("Vitrine: item icon readback failed", e);
				}
				free.run();
				done.accept(png);
			}, 0);
		} catch (RuntimeException e) {
			free.run();
			throw e;
		}
	}

	/** RGBA rows, bottom-up (render targets are), to PNG. */
	private static byte[] png(ByteBuffer rgba, int size) throws java.io.IOException {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = ((size - 1 - y) * size + x) * 4;
				image.setRGB(x, y, (rgba.get(i + 3) & 0xFF) << 24 | (rgba.get(i) & 0xFF) << 16 | (rgba.get(i + 1) & 0xFF) << 8 | rgba.get(i + 2) & 0xFF);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
//?}
