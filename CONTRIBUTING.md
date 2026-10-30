# Contributing

## Changelog

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). It is also the
release notes: when a version is published, its `## [x.y.z]` section is what Modrinth, CurseForge
and the GitHub release show. If a change has no entry, nobody reading the release will see it.

- **Changes users can see get an entry** under `## [Unreleased]`, in the PR that makes the change.
  That includes new or changed commands, config keys and defaults, behaviour, supported game
  versions and loaders, and fixes for anything a server owner or player could have run into. Put it
  under Added, Changed, Deprecated, Removed, Fixed or Security, and write it for a server admin:
  what was wrong or missing, and what happens now.
- **CI-only changes and internal refactors do not get an entry**, unless they change what users
  get, such as a jar's metadata, what gets published, or a dependency bundled into the jar.
- When you're unsure, ask whether a server owner reading the release notes would want to know.

When a version is released, `## [Unreleased]` is renamed to that version and dated, and a new
empty `## [Unreleased]` goes above it. The release build refuses to publish unless there is a
section for the version it is releasing.
