package dev.holoweb.vitrine.impl;

//? if >=26.2 {
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.holoweb.vitrine.Vitrine;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

/**
 * The binds every page can use without a line of Java (see {@link Vitrine#bind}); what to show is the slot's
 * {@code data-vitrine}, which comes from the page: checked against the registries, never trusted.
 * <ul>
 * <li>{@code vitrine:item}: {@code <namespace:item>}, as the inventory draws it (animated models, glint).
 * <li>{@code vitrine:entity}: {@code <namespace:entity> [yaw [pitch]]} in degrees, fitted to the slot.
 * <li>{@code vitrine:player}: {@code [yaw [pitch]]}, the player as they are now (skin, armour, held items).
 * </ul>
 * All of them need a world, and show nothing for an id that does not exist.
 */
final class BuiltinBinds {
	private static final ItemStackRenderState ITEM = new ItemStackRenderState(); // one slot is drawn before the next is asked for
	private static final Map<Item, ItemStack> STACKS = new HashMap<>();
	/** One stand-in per entity type, never in the world (null: the type can't be made or drawn). Dropped with the level. */
	private static final Map<EntityType<?>, Entity> ENTITIES = new HashMap<>();
	private static Level entitiesOf;

	private BuiltinBinds() {}

	static void register() {
		Binds.register("vitrine:item", BuiltinBinds::item);
		Binds.register("vitrine:entity", BuiltinBinds::entity);
		Binds.register("vitrine:player", (slot, pose, nodes) -> {
			String[] args = slot.data().trim().split("\\s+");
			draw(Minecraft.getInstance().player, slot, number(args, 0), number(args, 1), pose, nodes);
		});
	}

	private static void item(Vitrine.Slot slot, PoseStack pose, SubmitNodeCollector nodes) {
		Minecraft mc = Minecraft.getInstance();
		Identifier id = Identifier.tryParse(slot.data().trim());
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || mc.level == null) return; // item components are only bound once in a world
		ItemStack stack = STACKS.computeIfAbsent(item, Item::getDefaultInstance);
		mc.getItemModelResolver().updateForTopItem(ITEM, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
		// As GuiItemAtlas.drawToSlot: the model fills a unit square, y up (the slot's pose has z flipped already).
		float size = Math.min(slot.width(), slot.height());
		pose.scale(size, -size, -size);
		mc.gameRenderer.lighting().setupFor(ITEM.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
		ITEM.submit(pose, nodes, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
	}

	private static void entity(Vitrine.Slot slot, PoseStack pose, SubmitNodeCollector nodes) {
		Minecraft mc = Minecraft.getInstance();
		String[] args = slot.data().trim().split("\\s+");
		Identifier id = Identifier.tryParse(args[0]);
		EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		if (type == null || mc.level == null) return;
		if (entitiesOf != mc.level) {
			ENTITIES.clear();
			entitiesOf = mc.level;
		}
		if (!ENTITIES.containsKey(type)) {
			Entity made = null;
			try {
				made = type.create(mc.level, EntitySpawnReason.LOAD);
				if (made != null) made.setId(-1 - ENTITIES.size()); // renderers ask for one; it is in no world, so any will do
			} catch (RuntimeException e) {
				VitrineClient.LOG.warn("Vitrine: can't make a '{}' to show", id, e);
			}
			ENTITIES.put(type, made);
		}
		Entity entity = ENTITIES.get(type);
		try {
			draw(entity, slot, number(args, 1), number(args, 2), pose, nodes);
		} catch (RuntimeException e) { // a stand-in is not a real entity: some renderers want more than it has
			VitrineClient.LOG.warn("Vitrine: can't draw a '{}'", id, e);
			ENTITIES.put(type, null);
		}
	}

	/** {@code entity} standing in the slot, as the inventory shows the player: facing out, turned by {@code yaw} and {@code pitch} degrees. */
	private static void draw(Entity entity, Vitrine.Slot slot, float yaw, float pitch, PoseStack pose, SubmitNodeCollector nodes) {
		if (entity == null) return;
		var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		var state = dispatcher.getRenderer(entity).createRenderState(entity, 1f);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.lightCoords = 0xF000F0; // a stand-in is at the world's origin, likely in the dark
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180;
			living.yRot = living.xRot = 0;
		}
		float w = Math.max(state.boundingBoxWidth, 0.1f), h = Math.max(state.boundingBoxHeight, 0.1f);
		float scale = 0.85f * Math.min(slot.width() / w, slot.height() / h);
		pose.scale(scale, scale, scale);
		pose.translate(0, h / 2, 0);
		pose.rotate(new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw)));
		dispatcher.submit(state, new CameraRenderState(), 0, 0, 0, pose, nodes);
	}

	/** {@code args[i]} as a number; 0 if absent or not one. */
	private static float number(String[] args, int i) {
		try {
			float f = i < args.length ? Float.parseFloat(args[i]) : 0;
			return Float.isFinite(f) ? f : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
//?}
