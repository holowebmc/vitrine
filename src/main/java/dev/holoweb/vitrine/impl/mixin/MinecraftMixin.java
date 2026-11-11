package dev.holoweb.vitrine.impl.mixin;

import dev.holoweb.vitrine.impl.Pages;
import dev.holoweb.vitrine.impl.WorldInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/** Per-frame hook (menus included), before anything is rendered. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void vitrine$frame(boolean advanceGameTime, CallbackInfo ci) {
		Pages.frame();
	}

	// While the crosshair is on a world panel, its buttons belong to the page, not to the world.

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void vitrine$attack(CallbackInfoReturnable<Boolean> cir) {
		if (WorldInput.capturesMouse()) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void vitrine$continueAttack(boolean down, CallbackInfo ci) {
		if (WorldInput.capturesMouse()) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void vitrine$use(CallbackInfo ci) {
		if (WorldInput.capturesMouse()) ci.cancel();
	}

	//? if >=26.1 {
	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	//?} else
	/*@Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)*/
	private void vitrine$pick(CallbackInfo ci) {
		if (WorldInput.capturesMouse()) ci.cancel();
	}
}
