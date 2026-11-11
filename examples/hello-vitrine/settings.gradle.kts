// A separate build on purpose: it uses Vitrine like any other mod would, from a Maven repository.
// Publish Vitrine first (from the repo root): ./gradlew :26.3:publishToMavenLocal
// Then: ./gradlew -p examples/hello-vitrine runClient
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "hello-vitrine"
