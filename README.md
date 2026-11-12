# Vitrine

Vitrine is a client-side Fabric library that embeds Chromium in Minecraft so other mods can build their UI with HTML, CSS and JavaScript.

- **On screen:** a page drawn over the game, either as a HUD with no focus or as a focused screen that takes the mouse and keyboard.
- **In the world:** a page on a panel placed anywhere. Players aim at it and click, text fields take the keyboard, and the page's audio is positional.
- **Java ↔ page:** messages travel as JSON. Pages can be loaded from your mod's jar through `vitrine://<modid>/...`.

On its own, Vitrine adds nothing to the game. Install it when another mod depends on it.

## For players

- **Windows x64 only for now.** On other systems the mod loads, but pages stay empty.
- **First launch:** the mod downloads the Chromium Embedded Framework (CEF) from its official CDN, about 170 MB, and checks it against a SHA-256 hash. It is installed once per game folder (about 350 MB).
- **Rendering:** frames go from Chromium to the game without leaving the GPU, on both OpenGL and Vulkan.
- **Requirements:** Fabric Loader and Fabric API. Minecraft versions 1.20.1 through 26.3 are supported.

## For mod developers

```kotlin
repositories { maven("https://api.modrinth.com/maven") }
dependencies { implementation("maven.modrinth:holoweb-vitrine:0.2.0+26.3") } // modImplementation up to 1.21.11
```

```java
ScreenView hud = Vitrine.screen("vitrine://mymod/index.html"); // serves assets/mymod/vitrine/index.html
hud.on("greet", (body, reply) -> reply.ok("{\"text\":\"Hello from Java!\"}")); // page: fetch('vitrine://mymod/greet', {method: 'POST', body})
hud.send("{\"health\":20}");                                                   // page: addEventListener('message', e => e.data)

WorldView panel = Vitrine.world("https://example.com", 1280, 720, pos, rotation, 3f); // 3 blocks wide
panel.audio(1f, 24f);
```

The public API is the `dev.holoweb.vitrine` package (`Vitrine`, `ScreenView`, `WorldView`). Everything else is internal. A complete example lives in [`examples/hello-vitrine`](https://github.com/holowebmc/vitrine/tree/master/examples/hello-vitrine).

## Building and verifying

The jar carries two native binaries, `vitrine.dll` and `vitrine_helper.exe`, built from [`native/`](native). Releases are never built on a developer's machine: [`release.yml`](.github/workflows/release.yml) compiles the natives and every jar from a tagged commit, then uploads them to Modrinth and to the [GitHub release](https://github.com/holowebmc/vitrine/releases).

To check a download, compare its SHA-512 (Modrinth shows it per file) with `SHA512SUMS` in the release, or run `gh attestation verify vitrine-<version>.jar --repo holowebmc/vitrine`.

CEF is not in the jar or in this repository. The build and the mod fetch the same official archive from `cef-builds.spotifycdn.com`, pinned by SHA-256 in [`CefRuntime.java`](src/main/java/dev/holoweb/vitrine/impl/CefRuntime.java).

To build locally (Windows x64, Visual Studio 2026, JDK 25), unpack that archive into `native/third_party/cef`, then:

```sh
cmake -S native -B native/build -G "Visual Studio 18 2026" -A x64
cmake --build native/build --config Release
./gradlew build
```

## License

MIT. Chromium and CEF ship under their own BSD licenses.
