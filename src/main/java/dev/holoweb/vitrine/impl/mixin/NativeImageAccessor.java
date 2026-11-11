package dev.holoweb.vitrine.impl.mixin;
//? if <1.21.5 {
/*
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Before 1.21.5 NativeImage has no public pointer; the CPU frame path writes straight into it.
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
	@Accessor("pixels")
	long vitrine$pixels();
}
*///?}
