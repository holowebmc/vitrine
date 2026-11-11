package dev.holoweb.vitrine.impl.mixin;
//? if >=26.2 {

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Asks Minecraft's Vulkan device for VK_KHR_external_memory_win32 when the GPU has it, so pages can hand
 * frames over as shared D3D11 textures. Optional: without it Vitrine falls back to CPU frames.
 */
@Mixin(value = VulkanFeatureSets.class, remap = false)
abstract class VulkanFeatureSetsMixin {
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void vitrine$externalMemory(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		// A fresh mutable set on every call; FeatureSet is a record, so equal instances dedupe.
		cir.getReturnValue().add(new FeatureSet("vitrine_external_memory_win32", Set.of("VK_KHR_external_memory_win32"), Set.of()));
	}
}
//?}
