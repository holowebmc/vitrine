pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// One subproject per Minecraft version; sources are shared and switched with //? comments.
stonecutter {
    create(rootProject) {
        versions("1.20.1", "1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.11", "26.1.2", "26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "vitrine"
