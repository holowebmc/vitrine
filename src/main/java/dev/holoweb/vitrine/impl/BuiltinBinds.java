package dev.holoweb.vitrine.impl;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.holoweb.vitrine.Vitrine;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else
/*import net.minecraft.client.renderer.MultiBufferSource;*/
//? if >=1.21.5 {
import net.minecraft.client.renderer.item.ItemStackRenderState;
//?} else if >=1.21.2
/*import net.minecraft.world.item.BlockItem;*/
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EntitySpawnReason;
//?} else
/*import net.minecraft.world.entity.LivingEntity;*/

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
	//? if >=1.21.5
	private static final ItemStackRenderState ITEM = new ItemStackRenderState(); // one item is drawn before the next is asked for
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

	/** {@code stack} as the GUI draws it in a slot: the model fills a unit square around the origin, y up. */
	//? if >=1.21.9 {
	static void item(ItemStack stack, PoseStack pose, SubmitNodeCollector nodes) {
	//?} else
	/*static void item(ItemStack stack, PoseStack pose, MultiBufferSource nodes) {*/
		Minecraft mc = Minecraft.getInstance();
		//? if >=1.21.5 {
		mc.getItemModelResolver().updateForTopItem(ITEM, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
		boolean blockLight = ITEM.usesBlockLight();
		//?} else if >=1.21.2 {
		/*// ponytail: the one jar runs on 1.21.2-1.21.4, which share no way to ask a model; block items light as blocks (a few flat ones, saplings, come out darker).
		boolean blockLight = stack.getItem() instanceof BlockItem;
		*///?} else
		/*boolean blockLight = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0).usesBlockLight();*/
		//? if >=1.21.6 {
		mc.gameRenderer.lighting().setupFor(blockLight ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
		//?} else {
		/*if (blockLight) Lighting.setupFor3DItems();
		else Lighting.setupForFlatItems();
		*///?}
		//? if >=1.21.9 {
		ITEM.submit(pose, nodes, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
		//?} else if >=1.21.5 {
		/*ITEM.render(pose, nodes, 0xF000F0, OverlayTexture.NO_OVERLAY);
		*///?} else
		/*mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI, 0xF000F0, OverlayTexture.NO_OVERLAY, pose, nodes, mc.level, 0);*/
	}

	//? if >=1.21.9 {
	private static void item(Vitrine.Slot slot, PoseStack pose, SubmitNodeCollector nodes) {
	//?} else
	/*private static void item(Vitrine.Slot slot, PoseStack pose, MultiBufferSource nodes) {*/
		Identifier id = Identifier.tryParse(slot.data().trim());
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || Minecraft.getInstance().level == null) return; // item components are only bound once in a world
		float size = Math.min(slot.width(), slot.height());
		pose.scale(size, -size, -size); // y up; the slot's pose has z flipped already
		item(STACKS.computeIfAbsent(item, Item::getDefaultInstance), pose, nodes);
	}

	//? if >=1.21.9 {
	private static void entity(Vitrine.Slot slot, PoseStack pose, SubmitNodeCollector nodes) {
	//?} else
	/*private static void entity(Vitrine.Slot slot, PoseStack pose, MultiBufferSource nodes) {*/
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
				//? if >=1.21.2 {
				made = type.create(mc.level, EntitySpawnReason.LOAD);
				//?} else
				/*made = type.create(mc.level);*/
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
	//? if >=1.21.9 {
	private static void draw(Entity entity, Vitrine.Slot slot, float yaw, float pitch, PoseStack pose, SubmitNodeCollector nodes) {
	//?} else
	/*private static void draw(Entity entity, Vitrine.Slot slot, float yaw, float pitch, PoseStack pose, MultiBufferSource nodes) {*/
		if (entity == null) return;
		var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		//? if >=1.21.2 {
		var state = dispatcher.getRenderer(entity).createRenderState(entity, 1f);
		//? if >=1.21.9 {
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.lightCoords = 0xF000F0; // a stand-in is at the world's origin, likely in the dark
		//?}
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180;
			living.yRot = living.xRot = 0;
		}
		float w = Math.max(state.boundingBoxWidth, 0.1f), h = Math.max(state.boundingBoxHeight, 0.1f);
		//?} else
		/*float w = Math.max(entity.getBbWidth(), 0.1f), h = Math.max(entity.getBbHeight(), 0.1f);*/
		float scale = 0.85f * Math.min(slot.width() / w, slot.height() / h);
		pose.scale(scale, scale, scale);
		pose.translate(0, h / 2, 0);
		pose.rotate(new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw)));
		//? if >=26.1 {
		dispatcher.submit(state, new net.minecraft.client.renderer.state.level.CameraRenderState(), 0, 0, 0, pose, nodes);
		//?} else if >=1.21.9 {
		/*dispatcher.submit(state, new net.minecraft.client.renderer.state.CameraRenderState(), 0, 0, 0, pose, nodes);
		*///?} else if >=1.21.2 {
		/*// The renderer itself: the dispatcher would add a shadow. Full bright: a stand-in is at the world's origin, likely in the dark.
		((net.minecraft.client.renderer.entity.EntityRenderer) dispatcher.getRenderer(entity)).render(state, pose, nodes, 0xF000F0);
		*///?} else {
		/*var renderer = dispatcher.getRenderer(entity);
		if (!(entity instanceof LivingEntity living)) {
			renderer.render(entity, 0, 1, pose, nodes, 0xF000F0);
			return;
		}
		// Facing out, head straight, then back as it was (the player is a real entity).
		float body = living.yBodyRot, bodyO = living.yBodyRotO, head = living.yHeadRot, headO = living.yHeadRotO, xRot = living.getXRot(), xRotO = living.xRotO;
		living.yBodyRot = living.yBodyRotO = living.yHeadRot = living.yHeadRotO = 180;
		living.setXRot(0);
		living.xRotO = 0;
		try {
			renderer.render(entity, 180, 1, pose, nodes, 0xF000F0);
		} finally {
			living.yBodyRot = body;
			living.yBodyRotO = bodyO;
			living.yHeadRot = head;
			living.yHeadRotO = headO;
			living.setXRot(xRot);
			living.xRotO = xRotO;
		}
		*///?}
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
