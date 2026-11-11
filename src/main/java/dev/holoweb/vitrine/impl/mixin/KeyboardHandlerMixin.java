package dev.holoweb.vitrine.impl.mixin;

import dev.holoweb.vitrine.impl.Keys;
import dev.holoweb.vitrine.impl.WorldInput;
import net.minecraft.client.KeyboardHandler;
//? if >=1.21.9
import net.minecraft.client.input.KeyEvent;
//? if >=26.1
import net.minecraft.client.input.PreeditEvent;
//? if >=1.21.9 <26.2
/*import net.minecraft.client.input.CharacterEvent;*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a world panel's text field has the keyboard, keys and text go to it and nowhere else. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	//? if >=1.21.9 {
	private void vitrine$key(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.key(action, event.key(), Keys.scancode(event), event.modifiers());
		ci.cancel();
	}
	//?} else {
	/*private void vitrine$key(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.key(action, key, Keys.glfwScancode(scancode), modifiers);
		ci.cancel();
	}
	*///?}

	//? if >=26.2 {
	@Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
	private void vitrine$text(long window, String text, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.text(text);
		ci.cancel();
	}

	@Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
	private void vitrine$preedit(long window, PreeditEvent event, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.preedit(event);
		ci.cancel();
	}
	//?} else if >=1.21.9 {
	/*@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void vitrine$text(long window, CharacterEvent event, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.text(event.codepointAsString());
		ci.cancel();
	}
	*///?} else {
	/*@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void vitrine$text(long window, int codepoint, int modifiers, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.text(Character.toString(codepoint));
		ci.cancel();
	}
	*///?}

	//? if >=26.1 <26.2 {
	/*@Inject(method = "preeditCallback", at = @At("HEAD"), cancellable = true)
	private void vitrine$preedit(long window, PreeditEvent event, CallbackInfo ci) {
		if (!WorldInput.typing()) return;
		WorldInput.preedit(event);
		ci.cancel();
	}
	*///?}
}
