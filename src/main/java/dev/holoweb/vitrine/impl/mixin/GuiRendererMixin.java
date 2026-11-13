package dev.holoweb.vitrine.impl.mixin;

//? if >=1.21.6 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.holoweb.vitrine.impl.GameBlur;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where the game's frame is captured and blurred for pages with backdrop-filter (see {@link GameBlur}). */
@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
	/** Before any GUI draw: the world alone. */
	@Inject(method = "draw", at = @At("HEAD"))
	private void vitrine$blurWorld(CallbackInfo ci) {
		GameBlur.render(false);
	}

	/** Between the draws below the page and the rest, where the vanilla menu blur would run. */
	@WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
	private void vitrine$blurGui(GameRenderer renderer, Operation<Void> vanilla) {
		if (!GameBlur.render(true)) vanilla.call(renderer);
	}
}
//?}
