plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    implementation(project(":core"))
    implementation(project(":metrics"))

    // Compiled against the 1.21 API floor. Bukkit is forward-compatible at runtime but
    // not backward, so the floor is what makes one jar cover 1.21 -> 26.3.
    // Do not raise this to chase a newer API without re-reading that constraint.
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")

    // The tests run against the same API floor the plugin compiles against. Mockito is here
    // because the command and the listener talk to ChunkCartsPlugin, CartTracker and the engine,
    // all final classes, and to Bukkit interfaces with hundreds of methods; there is no server in
    // a unit test to hand them real ones. Only what the code under test calls gets stubbed.
    testImplementation("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    mockitoAgent("org.mockito:mockito-core:5.24.0") { isTransitive = false }
}

tasks.test {
    useJUnitPlatform()
    // Mockito's inline mock maker (needed for the final classes) attaches itself as an agent at
    // runtime, which the JDK warns about now and is going to refuse. Loading it as an explicit
    // agent is the setup Mockito's own documentation gives for that.
    // A provider, so the agent jar is resolved when the tests run rather than whenever the build
    // is configured.
    val agent = mockitoAgent
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${agent.singleFile}") })
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }

    // The annotated config.yml is shared with the loaders, so it lives at the repo root and is
    // copied in rather than duplicated here. Two copies would drift, and the comments are half
    // the documentation this plugin has.
    from(rootProject.file("config.yml"))
}

// The thin jar has no core/ classes in it, so dropping it on a server fails at runtime with
// NoClassDefFoundError. Only ever ship the shadowed one; don't produce a decoy.
tasks.jar {
    enabled = false
}

// chunkcarts-<version>+bukkit.jar
tasks.shadowJar {
    archiveBaseName = "chunkcarts"
    archiveVersion = "$version+bukkit"
    archiveClassifier = ""
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
