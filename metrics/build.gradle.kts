plugins {
    `java-library`
}

// 21, matching core, and deliberately not 25. The 26.x loader targets run on 25 and load 21
// bytecode without complaint; compiling this at 25 would make it unusable from bukkit.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// No Minecraft dependency and no bStats dependency either — the five classes under
// metrics/bstats are vendored, so this module is plain Java and the JDK. See the licence header
// on those files for why they are vendored rather than resolved.
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
