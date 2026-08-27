#!/usr/bin/env python3
"""Check that a built ChunkCarts jar would actually be *loaded*, not merely that it compiled.

This exists because of a defect that got all the way to a real server: the Forge `mods.toml`
declared its dependencies with NeoForge's `type = "required"` instead of Forge's `mandatory = true`.
All fifteen Forge targets compiled, packaged and passed every test. All fifteen would have been
rejected by FML at boot with `InvalidModFileException`, and the server would have started up
happily without the mod in it. Nothing below the mod-loading stage can catch that, so nothing did.

So: parse the metadata with a real parser, and assert the things that make a jar loadable rather
than the things that make it compile. Every check here corresponds to a way a jar can be silently
inert.

Standard library only, deliberately — no PyYAML, no toml package. This has to run on a bare CI
runner and on a developer's machine with no setup, or it will get skipped.

    python3 .github/scripts/verify_jar_metadata.py --platform forge --mc 1.21.11 \
        --version 0.1.0-SNAPSHOT loader/forge/versions/1.21.11/build/libs/chunkcarts-*.jar
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
import zipfile

CORE_CLASS = "io/github/aplite36/chunkcarts/core/"
CONFIG_FILE = "config.yml"

# SnakeYAML, before and after relocation. No jar may ship the library under its own name: Forge and
# NeoForge relocate it into the second package, Fabric carries it as a Jar-in-Jar nested archive so
# it never appears at the top level, and the Bukkit plugin gets it from the server.
SNAKEYAML_PACKAGE = "org/yaml/snakeyaml/"
SNAKEYAML_RELOCATED = "io/github/aplite36/chunkcarts/shaded/snakeyaml/"

# The two that share a class space, and so the two that must carry the relocated copy.
RELOCATING_PLATFORMS = ("forge", "neoforge")

# The class each platform actually enters the mod through. Verified present in the jar, because a
# metadata file naming a class that was never packaged is the other half of the same failure.
ENTRY_CLASS = {
    "fabric": "io/github/aplite36/chunkcarts/fabric/ChunkCartsFabric.class",
    "neoforge": "io/github/aplite36/chunkcarts/neoforge/ChunkCartsNeoForge.class",
    "forge": "io/github/aplite36/chunkcarts/forge/ChunkCartsForge.class",
    "bukkit": "io/github/aplite36/chunkcarts/bukkit/ChunkCartsPlugin.class",
}

METADATA_PATH = {
    "fabric": "fabric.mod.json",
    "neoforge": "META-INF/neoforge.mods.toml",
    "forge": "META-INF/mods.toml",
    "bukkit": "plugin.yml",
}

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def check(condition: bool, message: str) -> bool:
    if not condition:
        fail(message)
    return condition


def check_common(jar: zipfile.ZipFile, platform: str, raw: str) -> None:
    names = set(jar.namelist())

    # core/ is shaded in rather than depended on, and a jar built without it throws
    # NoClassDefFoundError on the first cart instead of failing to load. A jar like that is a
    # decoy; this is the check that we never ship one.
    check(any(n.startswith(CORE_CLASS) for n in names),
          f"no {CORE_CLASS} classes in the jar — core/ was not bundled")

    check(CONFIG_FILE in names,
          f"{CONFIG_FILE} missing from the jar — processResources did not copy the annotated config")

    entry = ENTRY_CLASS[platform]
    check(entry in names, f"entry point class {entry} is not in the jar")

    # `${version}` reaching a shipped jar means Gradle's template expansion silently did nothing.
    # It is not a compile error and the mod may even load, reporting a nonsense version forever.
    leftover = re.findall(r"\$\{[^}]*\}", raw)
    check(not leftover,
          f"unexpanded Gradle template {sorted(set(leftover))} in {METADATA_PATH[platform]}")

    check_snakeyaml(jar, platform, names)


def check_snakeyaml(jar: zipfile.ZipFile, platform: str, names: set[str]) -> None:
    """No jar may ship SnakeYAML under its own name, and moving the files is not enough.

    Forge and NeoForge load every mod into one class space, and SnakeYAML is among the most
    commonly bundled libraries in the ecosystem. Two mods shipping it unrelocated means one copy
    wins for both: `ConfigFile` calls `SafeConstructor(LoaderOptions)`, which only exists from 1.32,
    so an older winner is a NoSuchMethodError at config load — and the mod that loses can just as
    easily be the other one. Nothing else in CI looks at the bundled library at all.

    The second half is the one worth having. A relocation that renames the entries without
    rewriting the constant pools produces a jar that builds, packages and passes a path-only check,
    and then fails at runtime with NoClassDefFoundError — strictly worse than shipping it
    unrelocated, because it fails every time rather than only in company.
    """
    # `check` takes an evaluated message, so anything indexing into the offending list has to be
    # computed behind the same condition rather than inside the call.
    stray = sorted(n for n in names if SNAKEYAML_PACKAGE in n)
    if stray:
        fail(f"SnakeYAML is in the jar under its own name ({stray[0]}, {len(stray)} entries) — on "
             f"a shared class space that collides with any other mod bundling it unrelocated")

    # The rest is about the relocation, so it is asked of the platforms that relocate. On Fabric
    # the mod's own classes reference `org.yaml.snakeyaml` and should: Loader unpacks the nested
    # jar and supplies the package at runtime. The Bukkit plugin gets it from the server.
    if platform not in RELOCATING_PLATFORMS:
        return

    check(any(n.startswith(SNAKEYAML_RELOCATED) for n in names),
          f"no {SNAKEYAML_RELOCATED} classes in the jar — the relocated SnakeYAML was not "
          f"bundled, and ConfigFile throws NoClassDefFoundError on first config load")

    # Byte-level rather than by entry name: this is the check that the references moved with the
    # files. `org.yaml.snakeyaml` as a literal, so javac's synthetic `$SwitchMap$org$yaml$…` fields
    # — which are field names, not class references, and are consistent within the relocated jar —
    # do not read as a failure.
    dangling = []
    for name in sorted(names):
        if not name.endswith(".class"):
            continue
        data = jar.read(name)
        if SNAKEYAML_PACKAGE.encode() in data or b"org.yaml.snakeyaml" in data:
            dangling.append(name)
    if dangling:
        fail(f"{len(dangling)} class files still reference org.yaml.snakeyaml, starting with "
             f"{dangling[0]} — the relocation renamed entries without rewriting their constant "
             f"pools, so the jar packages and then fails at runtime")


def check_fabric(raw: str, mc: str, version: str) -> None:
    try:
        meta = json.loads(raw)
    except json.JSONDecodeError as exc:
        fail(f"fabric.mod.json is not valid JSON: {exc}")
        return

    check(meta.get("id") == "chunkcarts", f"mod id is {meta.get('id')!r}, expected 'chunkcarts'")
    check(meta.get("version") == version,
          f"declared version {meta.get('version')!r} != build version {version!r}")
    check(meta.get("schemaVersion") == 1, "fabric.mod.json schemaVersion must be 1")

    main = meta.get("entrypoints", {}).get("main") or []
    check(bool(main), "no 'main' entrypoint declared — Loader would load nothing")

    depends = meta.get("depends", {})
    for required in ("fabricloader", "fabric-api", "minecraft"):
        check(required in depends, f"depends is missing {required!r}")

    # One jar ships per game version, so the Minecraft dependency is this version exactly.
    # A floor here would let the 1.21 jar claim to run on 26.2, where half the API it calls is gone.
    check(depends.get("minecraft") == mc,
          f"minecraft dependency is {depends.get('minecraft')!r}, expected exactly {mc!r}")


def _dependency_blocks(meta: dict) -> list[dict]:
    return meta.get("dependencies", {}).get("chunkcarts", [])


def check_forge_family(raw: str, platform: str, mc: str, version: str) -> None:
    """Forge and NeoForge share a schema shape and disagree on one key. That is the whole point."""
    try:
        meta = tomllib.loads(raw)
    except tomllib.TOMLDecodeError as exc:
        fail(f"{METADATA_PATH[platform]} is not valid TOML: {exc}")
        return

    check(meta.get("modLoader") == "javafml",
          f"modLoader is {meta.get('modLoader')!r}, expected 'javafml'")

    mods = meta.get("mods", [])
    if not check(bool(mods), "no [[mods]] block — FML has nothing to load"):
        return
    check(mods[0].get("modId") == "chunkcarts",
          f"mod id is {mods[0].get('modId')!r}, expected 'chunkcarts'")
    check(mods[0].get("version") == version,
          f"declared version {mods[0].get('version')!r} != build version {version!r}")

    # README.md promises there is nothing to install on clients. On Forge that only holds if the
    # file says so: `ModContainer` reads `displayTest` out of mods.toml and falls back to
    # MATCH_VERSION when it is absent, which is the setting that asks a connecting client to have
    # the same mod. The `side = "SERVER"` keys on the dependency blocks below are a different
    # statement — they scope *this mod's own* dependencies, not what a client is expected to carry
    # — so they do not cover it. Same shape of defect as the `mandatory` check further down: valid
    # TOML, clean build, wrong behaviour at a stage no test reaches.
    #
    # Asserted on NeoForge too, where nothing currently reads the key. It is inert rather than
    # rejected there, and asserting it on both keeps one rule instead of two and holds the line if
    # NeoForge adopts the same spelling later.
    check(mods[0].get("displayTest") == "IGNORE_SERVER_VERSION",
          f"displayTest is {mods[0].get('displayTest')!r}, expected 'IGNORE_SERVER_VERSION' — "
          f"a {platform} jar without it asks a connecting client to have the mod, contradicting "
          f"the README's promise that clients do not need it")

    blocks = _dependency_blocks(meta)
    if not check(bool(blocks), "no [[dependencies.chunkcarts]] blocks — the loader is undeclared"):
        return

    # The defect this script was written for. Forge requires `mandatory` and has no `type`;
    # NeoForge requires `type` and has no `mandatory`. Getting it wrong is accepted by every
    # compiler and rejected by the mod loader, so it has to be asserted here or nowhere.
    wanted, forbidden = ("mandatory", "type") if platform == "forge" else ("type", "mandatory")
    for block in blocks:
        dep = block.get("modId", "<unnamed>")
        check(wanted in block,
              f"dependency on {dep!r} has no {wanted!r} key — {platform} rejects the whole mod file")
        check(forbidden not in block,
              f"dependency on {dep!r} uses {forbidden!r}, which is the *other* loader's spelling")

    loader_id = "forge" if platform == "forge" else "neoforge"
    declared = {block.get("modId") for block in blocks}
    check(loader_id in declared, f"no dependency on {loader_id!r} declared")
    check("minecraft" in declared, "no dependency on 'minecraft' declared")

    for block in blocks:
        if block.get("modId") == "minecraft":
            # Pinned, for the same reason as on Fabric.
            check(block.get("versionRange") == f"[{mc}]",
                  f"minecraft versionRange is {block.get('versionRange')!r}, expected '[{mc}]'")
        if block.get("modId") == loader_id:
            # An unbounded "[1,)" passes FML's check against any loader build ever released,
            # including ones that predate APIs this jar calls — the mod loads clean and then
            # crashes later with a linkage error instead of being rejected up front. A real floor
            # is what makes that FML check mean anything.
            version_range = block.get("versionRange")
            check(version_range != "[1,)",
                  f"{loader_id} versionRange is unbounded '[1,)' — any {loader_id} version ever "
                  f"released would pass FML's check, including ones too old for this jar")


def check_bukkit(raw: str, version: str) -> None:
    # Checked line-wise rather than with a YAML parser: PyYAML is not in the standard library, and
    # a check that needs `pip install` to run is a check that stops being run. plugin.yml is flat
    # enough at the top level that this is honest.
    top_level = dict(
        re.findall(r"^([A-Za-z0-9_-]+):[ \t]*(.*)$", raw, re.MULTILINE)
    )

    for required in ("name", "main", "api-version", "commands", "permissions"):
        check(required in top_level, f"plugin.yml has no top-level {required!r} key")

    check(top_level.get("name") == "ChunkCarts",
          f"plugin name is {top_level.get('name')!r}, expected 'ChunkCarts'")
    check(top_level.get("version", "").strip("'\"") == version,
          f"declared version {top_level.get('version')!r} != build version {version!r}")

    # Folia is out of scope, and this is what makes Folia refuse the jar rather than run
    # it in a configuration nobody has tested.
    check(top_level.get("folia-supported") == "false",
          "folia-supported must be explicitly false while Folia is out of scope")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True,
                        choices=("fabric", "neoforge", "forge", "bukkit"))
    parser.add_argument("--version", required=True, help="the build version, e.g. 0.1.0-SNAPSHOT")
    parser.add_argument("--mc", help="game version; required for the loader platforms")
    parser.add_argument("jar")
    args = parser.parse_args()

    if args.platform != "bukkit" and not args.mc:
        parser.error("--mc is required for the loader platforms")

    path = METADATA_PATH[args.platform]
    try:
        with zipfile.ZipFile(args.jar) as jar:
            try:
                raw = jar.read(path).decode("utf-8")
            except KeyError:
                print(f"FAIL {args.jar}\n  - {path} is not in the jar at all", file=sys.stderr)
                return 1
            check_common(jar, args.platform, raw)
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"FAIL {args.jar}\n  - not a readable jar: {exc}", file=sys.stderr)
        return 1

    if args.platform == "fabric":
        check_fabric(raw, args.mc, args.version)
    elif args.platform in ("forge", "neoforge"):
        check_forge_family(raw, args.platform, args.mc, args.version)
    else:
        check_bukkit(raw, args.version)

    if failures:
        print(f"FAIL {args.jar}", file=sys.stderr)
        for message in failures:
            print(f"  - {message}", file=sys.stderr)
        return 1

    print(f"OK   {args.jar} ({args.platform} metadata verified)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
