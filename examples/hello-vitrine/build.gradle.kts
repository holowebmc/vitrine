plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

version = "1.0.0"

repositories {
    mavenLocal() // dev.holoweb:vitrine, from publishToMavenLocal
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.165.2+26.3")
    implementation("dev.holoweb:vitrine:0.2.1+26.3")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

loom.runConfigs.configureEach {
    runDirectory = layout.projectDirectory.dir("run")
    // Use the engine built in this repo instead of downloading it (players get the download).
    property("vitrine.cefDir", file("../../native/build/out").absolutePath)
}
