// Shared by every node under loader/forge/versions/. The third loader branch, and a separate
// adapter rather than a NeoForge variant: the package roots differ outright
// (net.minecraftforge.* against net.neoforged.*), so nothing in the loader-specific half compiles
// against both.

plugins {
    java
    id("dev.kikugie.stonecutter")
    // ForgeGradle *7*, not 6. Forge's current MDK moved to it and the old
    // net.minecraftforge.gradle:ForgeGradle 6.x maven artifact is the migration reference now.
    // Two separate coordinates confuse this: the plugin marker on the plugin portal resolves
    // net.minecraftforge:forgegradle, which is a different artifact from the 6.x one.
    // Pinned rather than the MDK's own "[7.0.29,8.0)" range, because a version matrix that
    // silently changes its build plugin is not a matrix.
    id("net.minecraftforge.gradle") version "7.0.31"
}

val mc = stonecutter.current.version

val forgeVersion = property("forge_version").toString()

// Forge's loaderVersion range may only use the *major* of the Forge version as a bound — an FML
// rule, not a convention — so it is derived here rather than carried as a second property that
// could disagree with forge_version.
//
// Hoisted out of the processResources block below on purpose: inside a task configuration block
// `property(...)` resolves against the *task*, not the project, and fails with "Could not get
// unknown property 'forge_version' for task ':…:processResources'". Same trap as `the<T>()` in the
// Fabric branch's dependencies block.
val forgeLoaderMajor = forgeVersion.substringBefore('.')

val snakeyamlVersion = "2.6"

// The Minecraft-facing half of the adapter lives in loader/common/src and is shared with the
// Fabric and NeoForge branches; src/main/java/.../chunkcarts/loader is a symlink to it. It has to
// be a symlink rather than an extra srcDir — see the comment in the Fabric buildscript.

// Java 21 for 1.21.x, 25 for 26.x. Note the MDK hard-codes 21 and says so in a comment —
// its own example has not crossed this seam yet, so the number is ours, not theirs.
val javaTarget = if (stonecutter.eval(mc, ">=26.1")) 25 else 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaTarget)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaTarget
    options.encoding = "UTF-8"
}

minecraft {
    // Mojang's official names, the same choice the Fabric branch makes, so one source tree spans
    // every target. Unlike Loom, ForgeGradle expresses the channel as a call rather than as a
    // plugin id.
    mappings("official", mc)
}

repositories {
    // ForgeGradle synthesises a repository for the mavenized Minecraft artifacts; the other two
    // are Forge's own maven and the Minecraft library maven. All three come from the MDK, and
    // omitting any of them fails resolution rather than degrading.
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

// Everything that gets shaded into the mod jar, declared as a configuration rather than read off
// the other project's source sets. `project(":core").sourceSets` is the obvious way to write this
// and it is what the Fabric and NeoForge branches used to do, but it fails outright under
// configure-on-demand with "Extension with name 'sourceSets' does not exist" — reaching across
// projects only works if the other project happens to have been configured already. Going through
// dependency resolution instead makes Gradle configure :core because it was asked for.
// Not transitive: this is a list of things to unzip into the jar, not a dependency graph.
val bundled = configurations.create("bundled") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // FG7 requests the "userdev" classifier of this artifact and sets the rest up itself.
    implementation(minecraft.dependency("net.minecraftforge:forge:$mc-$forgeVersion"))

    // Deliberately no eventbus-validator annotation processor, though the MDK ships one. It
    // validates annotation-based @SubscribeEvent listeners, and this adapter has none — every
    // listener is an explicit Consumer handed to a typed bus, where a wrong event type is already
    // a compile error. Adding it would pin a fourth version that has to track each target's own
    // EventBus build (measured: 6.2.8 through 7.0.5 across the fifteen) for no benefit.

    implementation(project(":core"))
    implementation(project(":metrics"))

    // Bundled for the same reason as on the other two loaders: the config schema in core takes a
    // nested map, and a config.yml copied off a Paper server must parse identically rather than
    // nearly so. Shaded into the jar below rather than going through Forge's jarJar plugin, which
    // would take over the jar task and the artifact name with it.
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")

    bundled(project(":core"))
    bundled(project(":metrics"))
    bundled("org.yaml:snakeyaml:$snakeyamlVersion")
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    inputs.property("mc", mc)
    inputs.property("loaderMajor", forgeLoaderMajor)
    filesMatching("META-INF/mods.toml") {
        expand("version" to modVersion, "mc" to mc, "loader" to forgeLoaderMajor)
    }

    // Same annotated config.yml the Bukkit module ships, copied from the repo root rather than
    // duplicated. Two copies would drift and the comments are half the documentation.
    from(rootProject.file("config.yml"))
}

// chunkcarts-<version>+<mcver>-forge.jar. core/ and SnakeYAML go in directly:
// neither carries a Minecraft reference, so there is nothing for FG7 to remap.
tasks.jar {
    from(bundled.elements.map { artifacts -> artifacts.map { zipTree(it.asFile) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**",
                "META-INF/MANIFEST.MF")
    }
    archiveBaseName = "chunkcarts"
    archiveVersion = "${project.version}+$mc-forge"
    archiveClassifier = ""
}
