package dev.holoweb.vitrine.impl.mixin;
//? if <1.21.6 {
/*
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.holoweb.vitrine.impl.Offscreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Before 1.21.6 vanilla render types draw into the main target: while Offscreen draws, it is the main target.
@Mixin(Minecraft.class)
abstract class MainTargetMixin {
	@Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void vitrine$offscreen(CallbackInfoReturnable<RenderTarget> cir) {
		if (Offscreen.drawing != null) cir.setReturnValue(Offscreen.drawing);
	}
}
*///?}
