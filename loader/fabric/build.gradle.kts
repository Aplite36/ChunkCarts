// Shared by every node under loader/fabric/versions/. Stonecutter points each node's source
// root at loader/fabric/src, so this file and that tree are the whole Fabric adapter.

plugins {
    java
    id("dev.kikugie.stonecutter")
    // Both Loom flavours go on the classpath unapplied; which one applies depends on the
    // target version. See the comment on `unobfuscated` below.
    id("net.fabricmc.fabric-loom") version "1.17.17" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.17.17" apply false
}

val mc = stonecutter.current.version

// 26.1 is the first fully unobfuscated release. Verified, not assumed: Mojang's version
// manifest lists `server_mappings` for 1.21.11 and does not for 26.1 or 26.2.
//
// Loom expresses this as two plugin ids, not as a flag:
//   net.fabricmc.fabric-loom        -> LoomNoRemapGradlePlugin, wants no mappings at all
//   net.fabricmc.fabric-loom-remap  -> LoomGradlePlugin, requires a mappings dependency
// (`fabric-loom` is the deprecated alias of the second.) Applying the remapping flavour to a
// 26.x target fails configuration with "Configuration 'mappings' has no dependencies".
val unobfuscated = stonecutter.eval(mc, ">=26.1")

apply(plugin = if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")

// SPEC §1: loader subprojects target 21 for 1.21.x and 25 for 26.x, because 26.1+ requires 25.
// Confirmed against the version manifest: 1.21.11 asks for java-runtime-delta (21), 26.x for
// java-runtime-epsilon (25).
val javaTarget = if (unobfuscated) 25 else 21

// Hoisted: inside a `dependencies { }` block `the<T>()` resolves against the DependencyHandler,
// which has no Loom extension, and the error names the type rather than the receiver.
val loom = project.extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaTarget)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaTarget
}

dependencies {
    "minecraft"("com.mojang:minecraft:$mc")
    if (!unobfuscated) {
        // Mojang's official names, not Yarn: they are the same names 26.x ships unobfuscated,
        // so one source tree spans the seam. Yarn has no 26.x build at all (meta.fabricmc.net
        // returns an empty list for 26.2) and its names differ regardless.
        "mappings"(loom.officialMojangMappings())
    }

    // The no-remap flavour creates no remap configurations at all, so `modImplementation` does
    // not exist there — on an unobfuscated target a mod jar is already in the names we compile
    // against and is an ordinary dependency.
    val modDependency = if (unobfuscated) "implementation" else "modImplementation"
    modDependency("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modDependency("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    implementation(project(":core"))
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    inputs.property("mc", mc)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion, "mc" to mc)
    }

    // Same annotated config.yml the Bukkit module ships, copied from the repo root rather than
    // duplicated. Two copies would drift and the comments are half the documentation.
    from(rootProject.file("config.yml"))
}

// core/ is a plain Java library, not a mod, so Jar-in-Jar does not apply. Its classes go
// straight into the mod jar; they carry no Minecraft references, so remapping is a no-op.
tasks.jar {
    from(project(":core").sourceSets["main"].output)
}

// chunkcarts-<version>+<mcver>-fabric.jar per SPEC §11. The shipped artifact is remapJar on an
// obfuscated target and plain jar on an unobfuscated one — the no-remap flavour registers no
// remapJar at all, so naming it unconditionally fails on 26.x.
// Typed as org.gradle.jvm.tasks.Jar, not the bundling Jar the Kotlin DSL imports by default:
// Loom's RemapJarTask extends the former and is not a subclass of the latter.
tasks.named<org.gradle.jvm.tasks.Jar>(if (unobfuscated) "jar" else "remapJar") {
    archiveBaseName = "chunkcarts"
    archiveVersion = "${project.version}+$mc-fabric"
    archiveClassifier = ""
}
