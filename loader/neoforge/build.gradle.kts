// Shared by every node under loader/neoforge/versions/. The counterpart of the Fabric branch's
// buildscript; the two differ far more than the source does.

plugins {
    java
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.142"
}

val mc = stonecutter.current.version

val snakeyamlVersion = "2.6"

// The Minecraft-facing half of the adapter lives in loader/common/src and is shared with the
// Fabric branch; src/main/java/.../chunkcarts/loader is a symlink to it. It has to be a symlink
// rather than an extra srcDir — see the same comment in the Fabric buildscript.

// No Loom-style plugin-id split here. ModDevGradle compiles against NeoForge's own distribution,
// which has always been in official names, so the 26.1 unobfuscation seam is invisible to it.
// The seam that does exist is the toolchain: SPEC §1, 21 for 1.21.x and 25 for 26.x.
val javaTarget = if (stonecutter.eval(mc, ">=26.1")) 25 else 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaTarget)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaTarget
}

neoForge {
    version = property("neoforge_version").toString()

    // Names the mod for the dev-run classpath and for the mods.toml validation MDG does at
    // build time. Without it MDG warns that no mod owns the source set.
    mods {
        register("chunkcarts") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(project(":core"))

    // Bundled for the same reason as on Fabric: the config schema in core takes a nested map, and
    // a config.yml copied off a Paper server must parse identically rather than nearly so.
    // NeoForge has no Jar-in-Jar equivalent that unpacks plain libraries, so this one is shaded
    // into the jar below instead.
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    inputs.property("mc", mc)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to modVersion, "mc" to mc)
    }

    // Same annotated config.yml the Bukkit module ships, copied from the repo root rather than
    // duplicated. Two copies would drift and the comments are half the documentation.
    from(rootProject.file("config.yml"))
}

// chunkcarts-<version>+<mcver>-neoforge.jar per SPEC §11. core/ and SnakeYAML go in directly:
// neither carries a Minecraft reference, and NeoForge does not remap.
tasks.jar {
    from(project(":core").sourceSets["main"].output)
    from(configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("snakeyaml") }
            .map { zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
    }
    archiveBaseName = "chunkcarts"
    archiveVersion = "${project.version}+$mc-neoforge"
    archiveClassifier = ""
}
