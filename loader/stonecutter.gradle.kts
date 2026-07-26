plugins {
    id("dev.kikugie.stonecutter")
}

// The version whose source tree the IDE and the shared src/ link point at. 26.2 is the newest
// target, which is where the adapter is written first (SPEC §12).
stonecutter active "26.2"

// One target at a time. Loom decompiles and remaps a full Minecraft jar per version; running
// fifteen of those concurrently is what turns a slow build into a swapping one.
stonecutter tasks {
    order("build")
}

// `./gradlew :loader:buildAll` is the §12 measurement: build every candidate, read the
// failures, and only then collapse the list. There is no built-in equivalent — `:loader:fabric`
// is the branch container project and builds nothing itself, so `loader:fabric:build` silently
// resolves to `buildEnvironment` instead.
tasks.register("buildAll") {
    group = "chunkcarts"
    description = "Builds every loader target in the Stonecutter tree."
    dependsOn(stonecutter.tasks.named("build").map { it.values })
}
