plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":core"))

    // Compiled against the 1.21 API floor. Bukkit is forward-compatible at runtime but
    // not backward, so the floor is what makes one jar cover 1.21 -> 26.2 (SPEC §1).
    // Do not raise this to chase a newer API without re-reading that constraint.
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

// The thin jar has no core/ classes in it, so dropping it on a server fails at runtime with
// NoClassDefFoundError. Only ever ship the shadowed one; don't produce a decoy.
tasks.jar {
    enabled = false
}

// chunkcarts-<version>+bukkit.jar per SPEC §11.
tasks.shadowJar {
    archiveBaseName = "chunkcarts"
    archiveVersion = "$version+bukkit"
    archiveClassifier = ""
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
