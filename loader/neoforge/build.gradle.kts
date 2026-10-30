// Shared by every node under loader/neoforge/versions/. The counterpart of the Fabric branch's
// buildscript; the two differ far more than the source does.

plugins {
    java
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.142"
    // Applied for its relocator, not for its bundling — see the comment on shadowJar at the bottom
    // of this file. Same version as the Bukkit module, the other place it is applied.
    id("com.gradleup.shadow") version "9.6.1"
}

val mc = stonecutter.current.version
val neoforgeVersion = property("neoforge_version").toString()

val snakeyamlVersion = "2.6"

// The Minecraft-facing half of the adapter lives in loader/common/src and is shared with the
// Fabric branch; src/main/java/.../chunkcarts/loader is a symlink to it. It has to be a symlink
// rather than an extra srcDir — see the same comment in the Fabric buildscript.

// No Loom-style plugin-id split here. ModDevGradle compiles against NeoForge's own distribution,
// which has always been in official names, so the 26.1 unobfuscation seam is invisible to it.
// The seam that does exist is the toolchain: 21 for 1.21.x and 25 for 26.x.
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

// See the comment on the same configuration in the Forge branch: reading
// `project(":core").sourceSets` from here breaks under configure-on-demand, which the Forge branch
// cannot build without. Resolving a configuration makes Gradle configure :core on request.
val bundled = configurations.create("bundled") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(project(":core"))
    implementation(project(":metrics"))

    bundled(project(":core"))
    bundled(project(":metrics"))
    bundled("org.yaml:snakeyaml:$snakeyamlVersion")

    // Bundled for the same reason as on Fabric: the config schema in core takes a nested map, and
    // a config.yml copied off a Paper server must parse identically rather than nearly so.
    //
    // Shaded and relocated rather than Jar-in-Jar'd. The claim that used to stand here — that
    // NeoForge has no Jar-in-Jar equivalent — was wrong: MDG registers a `jarJar` task on every
    // node of this branch. Relocation is the stronger answer regardless. Jar-in-Jar only
    // reconciles the mods that opt into it, whereas relocating puts this copy of SnakeYAML
    // somewhere no other mod can reach at all.
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    inputs.property("mc", mc)
    inputs.property("neo", neoforgeVersion)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to modVersion, "mc" to mc, "neo" to neoforgeVersion)
    }

    // Same annotated config.yml the Bukkit module ships, copied from the repo root rather than
    // duplicated. Two copies would drift and the comments are half the documentation.
    from(rootProject.file("config.yml"))
}

// The thin jar: an input to shadowJar, never shipped. Left enabled rather than disabled the way
// the Bukkit module disables its own, because MDG hangs wiring off it. Its name is left at the
// Gradle default — the node's own project name, e.g. `26.2-0.1.1-SNAPSHOT-thin.jar` — so that
// build/libs holds exactly one `chunkcarts-*.jar`. CI globs for that name and hands the single
// match to the metadata verifier; a second match would break it.
tasks.jar {
    archiveClassifier = "thin"
}

// chunkcarts-<version>+<mcver>-neoforge.jar, the shipped artifact.
//
// Shadow rather than the plain `zipTree` unpack this used to be, for one reason. `zipTree` copies
// entries verbatim, so SnakeYAML landed at `org/yaml/snakeyaml/**` under its own name. NeoForge
// loads every mod into one class space and SnakeYAML is among the most commonly bundled libraries
// in the ecosystem, so two mods shipping it unrelocated means a single copy wins for both.
// ConfigFile calls `SafeConstructor(LoaderOptions)`, which only exists from 1.32 — an older winner
// is a NoSuchMethodError at config load, and the mod that loses can just as easily be the other
// one. Relocating rewrites references as well as paths, so there is nothing left to collide.
//
// core/ and metrics/ stay at their own names. Nobody else ships those packages, so there is
// nothing to collide with; they are here because this is now the task that assembles the jar.
tasks.shadowJar {
    // Explicit, and load-bearing. Left at Shadow's default this is `runtimeClasspath`, which on
    // this branch is NeoForge and the whole of Minecraft.
    configurations.set(listOf(bundled))

    relocate("org.yaml.snakeyaml", "io.github.aplite36.chunkcarts.shaded.snakeyaml")

    // Shadow drops signature files on its own. The Maven descriptors it keeps, and they still name
    // the library at the coordinates this relocation exists to get away from.
    exclude("META-INF/maven/**")

    archiveBaseName = "chunkcarts"
    archiveVersion = "${project.version}+$mc-neoforge"
    archiveClassifier = ""
}

// `assemble` only knows about `jar`, and the thin jar is not the deliverable.
tasks.build {
    dependsOn(tasks.shadowJar)
}
