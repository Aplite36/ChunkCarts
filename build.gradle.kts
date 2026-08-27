// Root project holds no source. Per-module configuration lives in core/ and bukkit/.
// group and version are set for every project from gradle.properties.

plugins {
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

// ───────────────────────────────────────────────────────────────────────────────────────────────
// Publishing
//
// Publishing reads jars out of a directory, not out of this build's outputs. That is deliberate.
// The forty-six jars are built by forty-six separate CI jobs on forty-six separate machines and
// collected by release.yml; there is no single machine that has built them all, and there should
// not be. Reading the directory means the artifact uploaded is byte-for-byte the one the metadata
// verifier checked and the smoke test booted.
//
// With CHUNKCARTS_DIST unset the whole block registers nothing, so an ordinary `./gradlew build`
// on a laptop is unaffected and `publishMods` is a no-op. With it set, a missing or malformed jar
// is a configuration-time failure rather than a partial release.
//
// Note on style: inside a .gradle.kts script every `Action<T>` lambda below is a *receiver*
// lambda, so a bare `version` inside one of these blocks is the publication's version property,
// not the project's. Project properties are always written `project.version` here for that reason.
// ───────────────────────────────────────────────────────────────────────────────────────────────

/** `chunkcarts-<version>+<mc>-<loader>.jar` */
val loaderJarPattern = Regex("""^chunkcarts-(.+)\+([\d.]+)-(fabric|neoforge|forge)\.jar$""")

/** `chunkcarts-<version>+bukkit.jar` */
val bukkitJarPattern = Regex("""^chunkcarts-(.+)\+bukkit\.jar$""")

data class LoaderArtifact(val file: File, val mc: String, val loader: String)

/**
 * Sorts 1.21 < 1.21.1 < 1.21.10 < 26.1. Zero-padding each component makes an ordinary string
 * compare do the right thing; without it "1.21.10" sorts before "1.21.9".
 */
val byVersion = compareBy<String> { name ->
    name.split(".").joinToString(".") { part -> part.padStart(4, '0') }
}

val projectVersion = project.version.toString()

val distDir: File? = providers.environmentVariable("CHUNKCARTS_DIST").orNull
    ?.let { rootProject.file(it) }

if (distDir != null) {
    val loaderArtifacts = mutableListOf<LoaderArtifact>()
    var bukkit: File? = null

    val jars = distDir.listFiles { file: File -> file.name.endsWith(".jar") }?.sorted()
        ?: throw GradleException("CHUNKCARTS_DIST points at $distDir, which is not a readable directory")

    for (jar in jars) {
        val loaderMatch = loaderJarPattern.matchEntire(jar.name)
        val bukkitMatch = bukkitJarPattern.matchEntire(jar.name)
        val declared = (loaderMatch ?: bukkitMatch)?.groupValues?.get(1)
            ?: throw GradleException("$distDir contains ${jar.name}, which is not a ChunkCarts artifact name")

        // A jar built at a different version than the one being released is the mislabelling that
        // release.yml's tag check exists to prevent, arriving by a different route.
        if (declared != projectVersion) {
            throw GradleException("${jar.name} was built at version $declared, but this is release $projectVersion")
        }

        if (loaderMatch != null) {
            loaderArtifacts += LoaderArtifact(jar, loaderMatch.groupValues[2], loaderMatch.groupValues[3])
        } else {
            bukkit = jar
        }
    }

    val bukkitArtifact = bukkit ?: throw GradleException("no Bukkit jar in $distDir")
    if (loaderArtifacts.isEmpty()) throw GradleException("no loader jars in $distDir")

    // Every game version any jar in this release supports. Derived from the artifacts rather than
    // written out again, so it cannot drift from what was actually built.
    val allGameVersions = loaderArtifacts.map { it.mc }.distinct().sortedWith(byVersion)

    // The Bukkit jar covers the whole range in one file, so its Modrinth entry is tagged with all
    // of them. Paper and Purpur only: `bukkit` or `spigot` would advertise a platform where
    // Paper's async chunk prefetch does not exist, and the mod would stall the main thread there.
    val bukkitLoaders = listOf("paper", "purpur")

    // gradle.properties can still say "-SNAPSHOT" here: release.yml's gate job refuses to tag-release
    // one, but a manual rehearsal dispatch (dry run, no tag) is allowed to run with one so the
    // pipeline can be exercised before the version is bumped. Either way, CHANGELOG.md headings name
    // the version the release *carries*, never the SNAPSHOT suffix, so that is what gets matched —
    // and a rehearsal is held to the same bar a real release would be, rather than skipping the check
    // and proving nothing about whether the notes are actually ready.
    val releaseVersion = projectVersion.removeSuffix("-SNAPSHOT")

    val changelogText = rootProject.file("CHANGELOG.md").takeIf { it.isFile }?.readText()
        ?: throw GradleException(
            "CHANGELOG.md is missing. Add a \"## [$releaseVersion]\" section before releasing.",
        )

    // Keep a Changelog headings look like "## [0.1.0] - 2026-07-27": DOT_MATCHES_ALL lets `.` cross
    // the date and the entries beneath, and the lookahead stops at the next "## " heading (or end of
    // file) without consuming it. Anchoring on the literal released version, rather than reading
    // whichever section happens to come first, is what keeps "## [Unreleased]" — real, unshipped
    // changes — from ever being published as this release's notes.
    val releaseNotes = Regex(
        """^## \[${Regex.escape(releaseVersion)}].*?(?=^## |\z)""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    ).find(changelogText)?.value?.trim()
        ?: throw GradleException(
            "CHANGELOG.md has no \"## [$releaseVersion]\" section. Add one before releasing.",
        )

    // 26.1 and later require Java 25; everything before it runs on 21. CurseForge wants this as
    // file metadata, and it has to agree with the per-target toolchains rather than restate them
    // from memory.
    val javaFor = { mc: String ->
        if (mc.startsWith("26.")) JavaVersion.VERSION_25 else JavaVersion.VERSION_21
    }

    publishMods {
        // One global switch, because the plugin's dryRun is extension-wide — there is no way to
        // rehearse one platform while really uploading to another. Rehearsing everything is the
        // safe reading.
        //
        // *Either* token missing rehearses *both* platforms. A release that reached one platform and
        // not the other would leave two "0.1.0"s that are not the same event, and the version that
        // silently did not ship is the one nobody notices.
        dryRun.set(
            providers.environmentVariable("CHUNKCARTS_DRY_RUN").getOrNull()?.isNotEmpty() == true ||
                providers.environmentVariable("MODRINTH_TOKEN").getOrNull().isNullOrEmpty() ||
                providers.environmentVariable("CURSEFORGE_TOKEN").getOrNull().isNullOrEmpty(),
        )

        // v0.1 ships on the beta channel. Tied to the version rather than hardcoded, so the
        // 1.0 release does not go out on beta because nobody remembered to edit this line.
        type.set(if (projectVersion.startsWith("0.")) BETA else STABLE)
        changelog.set(releaseNotes)

        val modrinthShared = modrinthOptions {
            accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
            // The immutable id rather than the `chunkcarts` slug: Modrinth accepts either here,
            // but a slug can be renamed by its owner and an id cannot, so a rename would otherwise
            // point a release at nothing.
            projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID").orElse("7Ma6i0bN"))
            // Server side only. Clients neither need it nor benefit from it, and Modrinth uses
            // this to keep it out of client-side pack tooling.
            environment.set(SERVER_ONLY)
        }

        val curseforgeShared = curseforgeOptions {
            accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
            // Numeric, unlike Modrinth: this goes straight into the upload URL path. The slug is a
            // separate property there and is only read for webhook announcements, which we do not use.
            projectId.set(providers.environmentVariable("CURSEFORGE_PROJECT_ID").orElse("1627579"))
            // CurseForge refuses a file that declares neither environment, and the plugin throws
            // before it ever calls the API. Server only, for the same reason as Modrinth above.
            client.set(false)
            server.set(true)
            // CHANGELOG.md is Markdown. The default happens to be markdown too, but a silent
            // default deciding how release notes render is worth pinning.
            changelogType.set("markdown")
        }

        for (artifact in loaderArtifacts) {
            // One version entry per artifact, because one jar supports exactly one game version.
            // A single entry tagged with all fifteen would let someone download the 1.21 jar for a
            // 26.2 server, where half the API it calls no longer exists.
            val suffix = artifact.loader.replaceFirstChar { c -> c.uppercase() } +
                artifact.mc.replace(".", "_")

            modrinth("modrinth$suffix") {
                from(modrinthShared)
                file.set(artifact.file)
                version.set("$projectVersion+${artifact.mc}-${artifact.loader}")
                displayName.set("ChunkCarts $projectVersion — ${artifact.loader} ${artifact.mc}")
                modLoaders.add(artifact.loader)
                minecraftVersions.add(artifact.mc)

                // fabric.mod.json declares this as a hard dependency, so Loader rejects the mod
                // without it. Declaring it here is what makes Modrinth's installer pull it in.
                if (artifact.loader == "fabric") {
                    requires("fabric-api")
                }
            }

            curseforge("curseforge$suffix") {
                from(curseforgeShared)
                file.set(artifact.file)
                version.set("$projectVersion+${artifact.mc}-${artifact.loader}")
                displayName.set("ChunkCarts $projectVersion — ${artifact.loader} ${artifact.mc}")
                // Resolved against CurseForge's live version list at publish time, case
                // insensitively, so `neoforge` matches their `NeoForge`. A name they do not list
                // throws rather than being skipped — all fifteen game versions were checked against
                // the live list before this block was written.
                modLoaders.add(artifact.loader)
                minecraftVersions.add(artifact.mc)
                javaVersions.add(javaFor(artifact.mc))

                // Dependencies are slugs on CurseForge, not numeric project ids.
                if (artifact.loader == "fabric") {
                    requires("fabric-api")
                }
            }
        }

        modrinth("modrinthBukkit") {
            from(modrinthShared)
            file.set(bukkitArtifact)
            version.set("$projectVersion+bukkit")
            displayName.set("ChunkCarts $projectVersion — Paper")
            modLoaders.addAll(bukkitLoaders)
            minecraftVersions.addAll(allGameVersions)
        }

        // There is deliberately no CurseForge entry for the Paper jar. Their modloader list is
        // Fabric, Flint Loader, Forge, NeoForge, Quilt, Rift and Risugami's ModLoader — read off
        // the live `game/versions` endpoint, not assumed — with no paper, bukkit or spigot tag at
        // all, because CurseForge files Bukkit plugins under a separate project class. Adding one
        // would throw `Failed to find version: paper` and take the whole release down with it.
        // CurseForge therefore carries the forty-five loader jars; the Paper jar ships on Modrinth
        // and in the GitHub release.

        // One release carrying all forty-six jars, rather than forty-six releases. The GitHub
        // release is the provenance record — "this tag produced these files" — and splitting it
        // would make that claim harder to check, not easier.
        github {
            accessToken.set(providers.environmentVariable("GITHUB_TOKEN"))
            repository.set("Aplite36/ChunkCarts")
            commitish.set(providers.environmentVariable("GITHUB_SHA").orElse("main"))
            tagName.set("v$projectVersion")
            displayName.set("ChunkCarts $projectVersion")
            file.set(bukkitArtifact)
            additionalFiles.from(loaderArtifacts.map { it.file })
        }
    }
}
