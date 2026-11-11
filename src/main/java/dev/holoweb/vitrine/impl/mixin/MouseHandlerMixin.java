package dev.holoweb.vitrine.impl.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.holoweb.vitrine.impl.SetupToast;
import dev.holoweb.vitrine.impl.WorldInput;
import net.minecraft.client.MouseHandler;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse wheel scrolls the world panel under the crosshair instead of the hotbar; clicks fold the setup toast. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	//? if >=1.21.9 {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void vitrine$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (action == InputConstants.PRESS && info.button() == InputConstants.MOUSE_BUTTON_LEFT && SetupToast.click()) ci.cancel();
	}
	//?} else {
	/*@Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
	private void vitrine$button(long window, int button, int action, int mods, CallbackInfo ci) {
		if (action == InputConstants.PRESS && button == InputConstants.MOUSE_BUTTON_LEFT && SetupToast.click()) ci.cancel();
	}
	*///?}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void vitrine$scroll(long window, double dx, double dy, CallbackInfo ci) {
		if (WorldInput.scroll(dx, dy)) ci.cancel();
	}
}
