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
include("metrics")
include("bukkit")

// The candidate target list. 1.21.2 is absent deliberately. Nothing is pruned from here
// on anyone's guess: a target that does not build gets deleted after the build says so.
val mcTargets = listOf(
    "1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8",
    "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2", "26.3",
)

// One Stonecutter branch per loader, not one flat list of <mcver>-<loader> nodes. Fabric,
// NeoForge and Forge need different Gradle plugins and genuinely different adapter source —
// nothing shared compiles against both `net.neoforged.*` and `net.minecraftforge.*` — so a branch
// gives each one its own buildscript and its own shared src/ tree. A flat list would put all three
// in one buildscript behind name checks, which is exactly the preprocessor sprawl to start without.
stonecutter {
    create("loader") {
        branch("fabric") { versions(mcTargets) }
        branch("neoforge") { versions(mcTargets) }
        branch("forge") { versions(mcTargets) }
        vcsVersion = "26.3"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
