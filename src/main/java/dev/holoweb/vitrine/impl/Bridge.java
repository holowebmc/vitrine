package dev.holoweb.vitrine.impl;

import dev.holoweb.vitrine.Vitrine;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Answers vitrine:// requests on the render thread.
 * <ul>
 *   <li>GET {@code vitrine://<host>/<path>} serves the files {@link Vitrine#serve} registered for the host,
 *   else {@code assets/<host>/vitrine/<path>} from the classpath.</li>
 *   <li>GET {@code vitrine://item/<namespace:item>[/<pixels>]} is that item's inventory icon as a PNG (26.2+).</li>
 *   <li>POST {@code vitrine://<modid>/<name>} calls the page's handler {@code <name>} (vitrine:// pages only).</li>
 * </ul>
 */
final class Bridge {
	private static final Map<String, String> MIME = Map.ofEntries(
			Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
			Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
			Map.entry("css", "text/css"), Map.entry("json", "application/json"),
			Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
			Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
			Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"),
			Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
			Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"),
			Map.entry("mp3", "audio/mpeg"), Map.entry("ogg", "audio/ogg"), Map.entry("wav", "audio/wav"),
			Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"),
			Map.entry("wasm", "application/wasm"), Map.entry("txt", "text/plain"));

	private static final Pattern HOST = Pattern.compile("[a-z0-9._-]+");
	private static final Map<String, Vitrine.Files> SERVED = new ConcurrentHashMap<>();

	private Bridge() {}

	static void serve(String host, Vitrine.Files files) {
		if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("Vitrine: bad host '" + host + "' (a-z 0-9 . _ -)");
		if (files == null) SERVED.remove(host);
		else SERVED.put(host, files);
	}

	/** Drains every queued request. Called once per frame. */
	static void drain() {
		Native.Request r;
		while ((r = Native.nextRequest()) != null) {
			try {
				handle(r.id(), r.view(), r.method(), r.host(), r.path(), r.body(), r.trusted());
			} catch (Exception e) {
				VitrineClient.LOG.error("Vitrine: request {} {} failed", r.method(), r.path(), e);
				text(r.id(), 500, "internal error");
			}
		}
	}

	private static void handle(long id, long view, String method, String host, String path, byte[] body, boolean trusted) throws Exception {
		if (method.equals("POST")) {
			Browser b = Browser.BY_HANDLE.get(view);
			if (!trusted || b == null) {
				text(id, 403, "bridge is only available to vitrine:// pages");
				return;
			}
			String name = path.startsWith("/") ? path.substring(1) : path;
			Vitrine.Handler handler = b.handlers.get(name);
			if (handler == null) {
				text(id, 404, "no handler '" + name + "'");
				return;
			}
			handler.handle(new String(body, StandardCharsets.UTF_8), new Reply(id));
			return;
		}

		String file = path.startsWith("/") ? path.substring(1) : path;
		//? if >=26.2 {
		if (host.equals("item")) {
			ItemIcons.serve(id, file);
			return;
		}
		//?}
		if (file.isEmpty() || file.endsWith("/")) file += "index.html";
		if (file.contains("..") || file.contains("\\") || host.isEmpty()) {
			text(id, 400, "bad path");
			return;
		}
		byte[] data;
		Vitrine.Files served = SERVED.get(host);
		if (served != null) {
			data = served.read(file);
		} else {
			// ponytail: classpath only; resource-pack overrides would go through the ResourceManager.
			try (InputStream in = Bridge.class.getClassLoader().getResourceAsStream("assets/" + host + "/vitrine/" + file)) {
				data = in == null ? null : in.readAllBytes();
			}
		}
		if (data == null) {
			text(id, 404, "not found: " + file);
			return;
		}
		String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase();
		Native.respond(id, 200, MIME.getOrDefault(ext, "application/octet-stream"), data);
	}

	private static final class Reply implements Vitrine.Reply {
		private final long id;
		private boolean sent;

		Reply(long id) {
			this.id = id;
		}

		@Override
		public void ok(String json) {
			send(200, "application/json", json);
		}

		@Override
		public void error(String message) {
			send(500, "text/plain", message);
		}

		private void send(int status, String mime, String body) {
			if (sent) throw new IllegalStateException("Vitrine: reply already sent");
			sent = true;
			Native.respond(id, status, mime, body.getBytes(StandardCharsets.UTF_8));
		}
	}

	static void text(long id, int status, String message) {
		Native.respond(id, status, "text/plain", message.getBytes(StandardCharsets.UTF_8));
	}
}
