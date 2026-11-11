package dev.holoweb.vitrine.impl.mixin;
//? if >=26.2 {

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface VulkanCommandEncoderAccessor {
	@Accessor("device")
	VulkanDevice vitrine$device();
}
//?}
