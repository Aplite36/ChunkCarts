rootProject.name = "ChunkCarts"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.minecraftforge.net/")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
}

include("core")
include("bukkit")

// The §12 candidate list, verbatim. 1.21.2 is absent deliberately. Nothing is pruned from here
// on anyone's guess: a target that does not build gets deleted after the build says so.
val mcTargets = listOf(
    "1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8",
    "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2",
)

// One Stonecutter branch per loader, not one flat list of <mcver>-<loader> nodes. Fabric,
// NeoForge and Forge need different Gradle plugins and — per SPEC §12 — genuinely different
// adapter source, so a branch gives each one its own buildscript and its own shared src/ tree.
// A flat list would put all three in one buildscript behind name checks, which is the
// preprocessor sprawl SPEC §1 says to start without.
stonecutter {
    create("loader") {
        branch("fabric") { versions(mcTargets) }
        branch("neoforge") { versions(mcTargets) }
        vcsVersion = "26.2"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
