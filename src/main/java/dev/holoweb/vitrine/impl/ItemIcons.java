package dev.holoweb.vitrine.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code vitrine://item/<namespace:item>[/<pixels>]}: an item as the inventory draws it, as a PNG any page
 * can put in an {@code <img>}. Drawn once by the game's own item renderer (so blocks and modded items look
 * like they do in a slot), the way the GUI item atlas fills its slots, then read back.
 */
final class ItemIcons {
	private static final int DEFAULT_SIZE = 64, MAX_SIZE = 512;
	// ponytail: never invalidated; clear it on resource reload if packs changing item models mid-game matters.
	private static final Map<String, byte[]> CACHE = new HashMap<>();
	private static final PoseStack POSE = new PoseStack();

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
			size = Math.max(1, Math.min(Integer.parseInt(path.substring(slash + 1)), MAX_SIZE));
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

	/** Draws {@code stack} filling a {@code size}² target; {@code done} gets the PNG (null on failure) once the GPU hands the pixels back. */
	private static void render(ItemStack stack, int size, Consumer<byte[]> done) {
		Offscreen target = new Offscreen("Vitrine item", size, size, false);
		try {
			// As the GUI item atlas draws a slot, with the one slot being the whole target.
			target.draw(0, 0, size, size, nodes -> {
				POSE.pushPose();
				POSE.translate(size / 2f, size / 2f, 0);
				POSE.scale(size, -size, size);
				try {
					BuiltinBinds.item(stack, POSE, nodes);
				} finally {
					POSE.popPose();
				}
			});
			target.read(rgba -> {
				byte[] png = null;
				try {
					if (rgba != null) png = png(rgba, size);
				} catch (Exception e) {
					VitrineClient.LOG.error("Vitrine: item icon not encoded", e);
				}
				target.free();
				done.accept(png);
			});
		} catch (RuntimeException e) {
			target.free();
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
