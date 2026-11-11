package dev.holoweb.vitrine.impl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CEF runtime folder: libcef and its resources from the official CEF build (downloaded once per CEF
 * version, SHA-256 checked), plus vitrine.dll and its helper copied from the mod jar.
 */
public final class CefRuntime {
	private static final Logger LOG = LoggerFactory.getLogger("vitrine");

	// Must match native/third_party/cef, the headers vitrine.dll is built against.
	private static final String VERSION = "156.0.27";
	private static final String ARCHIVE = "cef_binary_156.0.27+ga10dae3+chromium-156.0.8078.88_windows64_minimal";
	private static final String SHA256 = "aced0fd8c927e0b6e626253697ddbd56c4c694ae21a21cd78e2879d7b899a81e";
	/** {@code -Dvitrine.cefMirror=https://host/path/} serves the same file from elsewhere. */
	private static final String CDN = System.getProperty("vitrine.cefMirror", "https://cef-builds.spotifycdn.com/");
	// ponytail: two UI languages; Chromium falls back to en-US for the rest. Add packs if pages need them.
	private static final Set<String> LOCALES = Set.of("en-US.pak", "pt-BR.pak");
	private static final String[] OURS = {"vitrine.dll", "vitrine_helper.exe"};
	private static final String COMPLETE = ".complete";

	public enum Stage { DOWNLOADING, INSTALLING }

	/** Install progress for the UI (any thread): null while there's nothing to install. */
	public static volatile Stage stage;
	/** Bytes of the archive downloaded (DOWNLOADING) or read back while extracting (INSTALLING); total may be 0. */
	public static volatile long done, total;

	private CefRuntime() {}

	/**
	 * The ready runtime folder under {@code root}, downloading CEF first if this version isn't there yet
	 * (~170 MB). Blocking: call it off the render thread.
	 */
	public static Path prepare(Path root) throws IOException, InterruptedException {
		Files.createDirectories(root);
		Path dir = root.resolve(VERSION);
		if (!Files.isRegularFile(dir.resolve(COMPLETE))) install(root, dir);
		for (String name : OURS) copyOurs(name, dir);
		removeOldVersions(root);
		return dir;
	}

	/** Engines from earlier mod versions (~350 MB each). Best effort: files in use are left for next time. */
	private static void removeOldVersions(Path root) {
		try (Stream<Path> s = Files.list(root)) {
			for (Path p : s.toList()) {
				if (!Files.isRegularFile(p.resolve(COMPLETE)) || p.getFileName().toString().equals(VERSION)) continue;
				try {
					deleteTree(p);
					LOG.info("Vitrine: removed old engine {}", p.getFileName());
				} catch (IOException e) {
					LOG.info("Vitrine: old engine {} still in use, will retry next launch", p.getFileName());
				}
			}
		} catch (IOException e) {
			LOG.warn("Vitrine: couldn't look for old engines in {}", root, e);
		}
	}

	private static void install(Path root, Path dir) throws IOException, InterruptedException {
		cleanLeftovers(root);
		Path archive = Files.createTempFile(root, "cef-", ".tar.bz2.part");
		Path tmp = null;
		try {
			stage = Stage.DOWNLOADING;
			download(archive);
			tmp = Files.createTempDirectory(root, VERSION + "-");
			done = 0;
			total = Files.size(archive);
			stage = Stage.INSTALLING;
			extract(archive, tmp);
			Files.writeString(tmp.resolve(COMPLETE), ARCHIVE);
			if (Files.exists(dir)) deleteTree(dir); // an older, unfinished attempt
			Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
			tmp = null;
			LOG.info("Vitrine: CEF {} installed in {}", VERSION, dir);
		} finally {
			Files.deleteIfExists(archive);
			if (tmp != null) deleteTree(tmp);
		}
	}

	private static void download(Path to) throws IOException, InterruptedException {
		URI uri = URI.create(CDN + URLEncoder.encode(ARCHIVE + ".tar.bz2", StandardCharsets.UTF_8));
		LOG.info("Vitrine: downloading CEF {} from {}", VERSION, uri);
		HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
		HttpResponse<InputStream> response = http.send(
				HttpRequest.newBuilder(uri).header("User-Agent", "Vitrine").build(), HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("HTTP " + response.statusCode() + " for " + uri);
		}
		total = response.headers().firstValueAsLong("content-length").orElse(0);
		done = 0;
		MessageDigest sha;
		try {
			sha = MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try (InputStream in = new DigestInputStream(response.body(), sha); OutputStream out = Files.newOutputStream(to)) {
			byte[] buf = new byte[1 << 16];
			int lastTenth = 0, n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				done += n;
				int tenth = total > 0 ? (int) (done * 10 / total) : 0;
				if (tenth > lastTenth) {
					lastTenth = tenth;
					LOG.info("Vitrine: CEF download {}%", tenth * 10);
				}
			}
		}
		String got = HexFormat.of().formatHex(sha.digest());
		if (!got.equals(SHA256)) throw new IOException("CEF archive checksum mismatch: " + got);
	}

	private static void extract(Path archive, Path to) throws IOException {
		InputStream counted = new java.io.FilterInputStream(Files.newInputStream(archive)) { // extraction progress
			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int n = super.read(b, off, len);
				if (n > 0) done += n;
				return n;
			}
		};
		try (TarArchiveInputStream tar = new TarArchiveInputStream(
				new BZip2CompressorInputStream(new BufferedInputStream(counted, 1 << 16)))) {
			ArchiveEntry entry;
			while ((entry = tar.getNextEntry()) != null) {
				String target = entry.isDirectory() ? null : target(entry.getName());
				if (target == null) continue;
				Path file = to.resolve(target).normalize();
				if (!file.startsWith(to)) throw new IOException("bad path in CEF archive: " + entry.getName());
				Files.createDirectories(file.getParent());
				Files.copy(tar, file);
			}
		}
	}

	/** Archive path -> path in the runtime folder (flattened like the CMake build), or null to skip. */
	static String target(String name) {
		String path = name.substring(name.indexOf('/') + 1); // drop the cef_binary_... folder
		if (path.startsWith("Release/")) {
			String file = path.substring("Release/".length());
			boolean runtime = file.endsWith(".dll") || file.endsWith(".bin") || file.endsWith(".json");
			return runtime && !file.contains("/") ? file : null;
		}
		if (path.startsWith("Resources/locales/")) {
			String file = path.substring("Resources/locales/".length());
			return LOCALES.contains(file) ? "locales/" + file : null;
		}
		if (path.startsWith("Resources/")) {
			String file = path.substring("Resources/".length());
			return file.contains("/") ? null : file;
		}
		return null;
	}

	/** Our natives ship in the jar so they always match this mod build; refreshed when they differ. */
	private static void copyOurs(String name, Path dir) throws IOException {
		byte[] bytes;
		try (InputStream in = CefRuntime.class.getResourceAsStream("/natives/windows-x64/" + name)) {
			if (in == null) throw new IOException("mod jar has no natives/windows-x64/" + name);
			bytes = in.readAllBytes();
		}
		Path file = dir.resolve(name);
		if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes)) return;
		Path tmp = Files.createTempFile(dir, name, ".part");
		Files.write(tmp, bytes);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); // fails if another game holds the old DLL
	}

	/** Downloads and folders left behind by a game closed mid-install. */
	private static void cleanLeftovers(Path root) throws IOException {
		try (Stream<Path> s = Files.list(root)) {
			for (Path p : s.toList()) {
				String n = p.getFileName().toString();
				if (n.startsWith("cef-") && n.endsWith(".part") || Files.isDirectory(p) && n.startsWith(VERSION + "-")) {
					deleteTree(p);
				}
			}
		}
	}

	private static void deleteTree(Path p) throws IOException {
		try (Stream<Path> s = Files.walk(p)) {
			for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
		}
	}
}
