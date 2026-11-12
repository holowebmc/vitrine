import com.modrinth.minotaur.ModrinthExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI

// Up to 1.21.11 Minecraft ships obfuscated: Loom remaps with Mojang's mappings. 26.x is plain.
val obfuscated = stonecutter.eval(stonecutter.current.version, "<=1.21.11")
apply(plugin = if (obfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")
apply(plugin = "maven-publish")
apply(plugin = "com.modrinth.minotaur")

version = "${property("mod.version")}+${stonecutter.current.version}"
group = property("mod.group") as String
base.archivesName = "vitrine"

// Plain renames between Minecraft versions, applied to the sources (written in the newest names).
// rename(since, new, old): `old` is used below version `since`. Different logic uses //? comments instead.
fun below(v: String) = stonecutter.eval(stonecutter.current.version, "<$v")
val preRenderpearl = below("26.2") // renderpearl renderer (GL + Vulkan) and SDL3 input
stonecutter.replacements {
    fun rename(since: String, new: String, old: String) = string(below(since)) { replace(new, old) }
    fun renameWord(since: String, new: String, old: String) =
        regex(below(since)) { replace("\\b$new\\b", old, "\\b$old\\b", new) }

    rename("26.2", "com.mojang.renderpearl.api.textures", "com.mojang.blaze3d.textures")
    rename("26.2", "com.mojang.renderpearl.backend.opengl", "com.mojang.blaze3d.opengl")
    rename("26.1", "net.minecraft.util.LightCoordsUtil;", "net.minecraft.client.renderer.LightTexture;")
    rename("26.1", "LightCoordsUtil.FULL_BRIGHT", "LightTexture.FULL_BRIGHT")
    rename("26.1", "client.keymapping.v1.KeyMappingHelper", "client.keybinding.v1.KeyBindingHelper")
    rename("26.1", "KeyMappingHelper.registerKeyMapping", "KeyBindingHelper.registerKeyBinding")
    renameWord("26.1", "GuiGraphicsExtractor", "GuiGraphics")
    rename("26.1", "gui.text(", "gui.drawString(")
    renameWord("1.21.11", "Identifier", "ResourceLocation")
    rename("1.21.11", "net.minecraft.client.renderer.rendertype.RenderTypes;", "net.minecraft.client.renderer.RenderType;")
    rename("1.21.11", "RenderTypes.text(", "RenderType.text(")
}

val java = JavaVersion.toVersion(property("java").toString())
val loom = extensions.getByType<LoomGradleExtensionAPI>()

fun mod(configuration: String) = if (obfuscated) "mod" + configuration.replaceFirstChar(Char::uppercase) else configuration

dependencies {
    "minecraft"("com.mojang:minecraft:${property("minecraft")}")
    if (obfuscated) "mappings"(loom.officialMojangMappings())
    mod("implementation")("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    mod("implementation")("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    // Dev only: -Psodium / -Piris add them to runClient to test compatibility (versions per node).
    // -Piris brings Sodium too (Iris needs it). Nodes without deps.<name> are skipped.
    val extras = if (project.hasProperty("iris")) listOf("sodium", "iris") else if (project.hasProperty("sodium")) listOf("sodium") else listOf()
    for (name in extras) findProperty("deps.$name")?.let { mod("localRuntime")("maven.modrinth:$name:$it") }
}

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
}

loom.runConfigs.configureEach {
    runDirectory = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.version}")
    // Dev: use the runtime that CMake assembles (native/build/out). -PcefDownload tests the player path
    // instead: download CEF into run/<mc>/vitrine/cef and copy the natives from the jar.
    if (!project.hasProperty("cefDownload")) property("vitrine.cefDir", rootProject.file("native/build/out").absolutePath)
    // -PcpuFrames tests the CPU fallback (frames copied and uploaded) instead of zero-copy GPU frames.
    if (project.hasProperty("cpuFrames")) property("vitrine.gpu", "false")
    property("vitrine.demo", "true") // F8/F9 demo pages; off for mods that depend on Vitrine
}

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = java
    targetCompatibility = java
    withSourcesJar() // the API's javadoc reaches IDEs through the sources
}

// Other mods depend on dev.holoweb:vitrine:<mod.version>+<mc> (one artifact per Minecraft build).
// Only dev.holoweb.vitrine is API; dev.holoweb.vitrine.impl can change in any release.
// Modrinth: .github/workflows/release.yml runs ./gradlew modrinth on a v<mod.version> tag, so the files
// there are always built by CI (players download them, and mods depend on
// maven.modrinth:holoweb-vitrine:<version>+<mc>). mod.mc_versions lists the versions this jar runs on
// (same range as mod.mc_dep).
extensions.configure<ModrinthExtension> {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = "holoweb-vitrine"
    versionNumber = project.version.toString()
    versionName = "Vitrine ${property("mod.version")} for Minecraft ${property("mod.mc_versions").toString().replace(",", ", ")}"
    versionType = "alpha"
    System.getenv("MODRINTH_CHANGELOG")?.let { changelog = it } // CHANGELOG.md's section + the CI run, from the workflow
    uploadFile.set(tasks.named(if (obfuscated) "remapJar" else "jar"))
    gameVersions.addAll(property("mod.mc_versions").toString().split(","))
    loaders.add("fabric")
    dependencies { required.project("fabric-api") }
    debugMode = project.hasProperty("modrinthDry") // -PmodrinthDry: print the upload, send nothing
}
tasks.named("modrinth") { notCompatibleWithConfigurationCache("Minotaur reads the project at execution time") }

extensions.configure<PublishingExtension> {
    publications.create<MavenPublication>("maven") {
        artifactId = "vitrine"
        from(components["java"])
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = java.majorVersion.toInt()
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000"))
    }

    named<ProcessResources>("processResources") {
        val props = mapOf(
            "version" to project.version,
            "minecraft" to project.property("mod.mc_dep"),
            "java" to java.majorVersion,
            "loader" to project.property("deps.fabric_loader"),
            // Version-specific mixins: Vulkan zero-copy and backdrop blur only on renderpearl; NativeImage pointer before 1.21.5.
            "extra_mixins" to buildList {
                if (!preRenderpearl) addAll(listOf("VulkanFeatureSetsMixin", "VulkanCommandEncoderAccessor", "GuiRendererMixin"))
                if (below("1.21.5")) add("NativeImageAccessor")
            }.joinToString("") { ", \"$it\"" },
        )
        inputs.properties(props)
        filesMatching(listOf("fabric.mod.json", "*.mixins.json")) { expand(props) }

        // Our natives ride in the jar (CefRuntime copies them next to the downloaded CEF), so they always
        // match this build. Build them first: cmake --build native/build --config Release.
        val natives = rootProject.file("native/build/out")
        val nativeFiles = listOf("vitrine.dll", "vitrine_helper.exe")
        from(natives) {
            include(nativeFiles)
            into("natives/windows-x64")
        }
        // Our license, and CEF's (BSD): vitrine.dll embeds the CEF C++ wrapper.
        from(rootProject.file("LICENSE")) { into("META-INF/licenses"); rename { "vitrine-LICENSE.txt" } }
        from(rootProject.file("native/third_party/cef/LICENSE.txt")) { into("META-INF/licenses"); rename { "CEF-LICENSE.txt" } }
        doFirst {
            val missing = nativeFiles.filterNot { natives.resolve(it).isFile }
            check(missing.isEmpty()) { "Missing $missing in $natives: build the native project first" }
        }
    }
}
