plugins {
    id("dev.kikugie.stonecutter")
}

// The version whose source tree the IDE and the shared src/ link point at. 26.3 is the newest
// target, which is where the adapter is written first.
stonecutter active "26.3"

// Deliberately no `stonecutter tasks { order("build") }`, and no `dependsOn` on Stonecutter's own
// task collection. Both reach across every node in the tree to realise its `build` task, which
// configures all forty-five projects on any invocation — and ForgeGradle does its Minecraft setup
// at *configuration* time, so `:loader:forge:1.21.11:build` was measured downloading and patching
// Minecraft for 1.21, 1.21.1, 1.21.10, 1.21.3 … in turn before it ever reached the compiler. That
// is what makes configure-on-demand load-bearing here rather than an optimisation; with it, and
// with nothing realising sibling tasks, the same command configures exactly one project.

// `./gradlew :loader:buildAll` is how the target list gets measured: build every candidate, read
// the failures, and only then collapse the list. There is no built-in equivalent — `:loader:fabric`
// is the branch container project and builds nothing itself, so `loader:fabric:build` silently
// resolves to `buildEnvironment` instead.
tasks.register("buildAll") {
    group = "chunkcarts"
    description = "Builds every loader target in the Stonecutter tree. Slow — read the note below."

    // Task *paths*, as strings, deliberately. A string is resolved when the task graph is built, so
    // registering this costs nothing; asking Stonecutter or `project(...)` for the task objects
    // would configure the whole tree just to learn they exist, which is the fan-out above. The node
    // list comes off disk for the same reason: `versions/` is the source of truth Stonecutter reads.
    for (branch in listOf("fabric", "neoforge", "forge")) {
        val nodes = layout.projectDirectory.dir(branch).dir("versions").asFile
                .listFiles { f: java.io.File -> f.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        for (node in nodes) {
            dependsOn(":loader:$branch:$node:build")
        }
    }

    doFirst {
        logger.lifecycle("Building every loader target. On an 8 GB machine run this with "
                + "--no-parallel --max-workers=1, or one target at a time by hand: each node keeps "
                + "a Minecraft toolchain resident, and ForgeGradle also provisions its own JDKs.")
    }
}
