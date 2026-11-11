package dev.holoweb.hello;

import com.mojang.blaze3d.platform.InputConstants;
import dev.holoweb.vitrine.ScreenView;
import dev.holoweb.vitrine.Vitrine;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * A HUD card made with Vitrine: shows the player's health, and H opens it to say hello to Java.
 * The page is assets/hello_vitrine/vitrine/index.html, served as vitrine://hello_vitrine/index.html.
 */
public final class HelloVitrine implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Fine this early: the page starts loading as soon as Vitrine's engine is up.
		ScreenView hud = Vitrine.screen("vitrine://hello_vitrine/index.html");

		// Page -> Java: fetch('vitrine://hello_vitrine/greet', {method: 'POST', body}) resolves with this reply.
		// body is the JSON the page sent; it goes back as a value, not inside a string.
		hud.on("greet", (body, reply) -> reply.ok("{\"text\":\"Hello from Java!\",\"got\":" + body + "}"));
		hud.on("close", (body, reply) -> {
			hud.focus(false);
			reply.ok("{}");
		});

		KeyMapping open = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.hello_vitrine.open", InputConstants.KEY_H, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (open.consumeClick()) hud.focus(true);
			// Java -> page, twice a second: addEventListener('message', e => e.data).
			if (mc.player != null && mc.level.getGameTime() % 10 == 0) {
				hud.send(String.format(Locale.ROOT, "{\"health\":%.1f,\"max\":%.1f}",
						mc.player.getHealth(), mc.player.getMaxHealth()));
			}
		});
	}
}
