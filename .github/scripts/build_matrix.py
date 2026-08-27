#!/usr/bin/env python3
"""Emit the GitHub Actions job matrix for the loader targets.

Two scopes:

  full    every candidate target, on every loader. This is what runs on a push to main and on a
          release tag, because every jar that gets published has to be built by CI.

  subset  one target per *known branch point in the source*, per loader. ~20 jobs. This is what
          runs on a pull request, so feedback arrives in minutes instead of tens of minutes.

The subset is not a sample of convenient versions; each entry below is a version where the adapter
source genuinely differs from its neighbour, with the reason recorded. Keep the reasons accurate —
they are the only thing that makes the subset defensible, and when a seam moves the list has to
move with it.

**The subset is not a substitute for the full matrix, and cannot be made into one.** The Forge port
found that 1.21.6, 1.21.7 and 1.21.8 broke while every version around them compiled — a seam nobody
predicted, in the middle of the range. A future edit can do that again on any target. That is
precisely why the full matrix still gates main.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import NoReturn

LOADERS = ("fabric", "neoforge", "forge")

SETTINGS = Path(__file__).resolve().parents[2] / "settings.gradle.kts"

# `val mcTargets = listOf("1.21", "1.21.1", ...)`. The body cannot contain a `)` of its own, so
# stopping at the first one is enough, and it keeps this on the standard library.
_MC_TARGETS = re.compile(r"\bval\s+mcTargets\s*=\s*listOf\s*\(([^)]*)\)")
_KOTLIN_LINE_COMMENT = re.compile(r"//[^\n]*")
_QUOTED = re.compile(r'"([^"]*)"')
_VERSION = re.compile(r"\d+(?:\.\d+)+")


def _fail(message: str) -> NoReturn:
    """Die with an annotation. Nothing here is worth guessing about.

    A target list that parses to something wrong is worse than one that is written out twice:
    the duplicate at least fails by name when the matrix builds a node that does not exist,
    whereas a silently empty or truncated list would produce a smaller matrix, a smaller expected
    jar count to match it, and a release that looks complete.
    """
    print(f"::error::build_matrix.py: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_targets() -> list[str]:
    """Read the candidate target list out of settings.gradle.kts.

    settings.gradle.kts is the single source: it is the list that creates the Stonecutter nodes,
    so it is the list that decides what can be built at all. Restating it here used to be silent
    drift in one direction — a target added there and not here was never compiled, never
    metadata-verified, never smoke-tested and never published, and the jar count check could not
    notice because the count was derived from this copy.
    """
    try:
        source = SETTINGS.read_text(encoding="utf-8")
    except OSError as error:
        _fail(f"cannot read {SETTINGS}: {error}")

    match = _MC_TARGETS.search(source)
    if match is None:
        _fail(
            f"no `val mcTargets = listOf(...)` block in {SETTINGS}. "
            "If that declaration was renamed or reformatted, update this parser to match."
        )

    # A pruned target is likely to be commented out rather than deleted, and a commented-out
    # target is not a target.
    body = _KOTLIN_LINE_COMMENT.sub("", match.group(1))
    targets = _QUOTED.findall(body)

    if not targets:
        _fail(f"the mcTargets block in {SETTINGS} parsed to zero targets")

    bad = [target for target in targets if not _VERSION.fullmatch(target)]
    if bad:
        _fail(f"mcTargets holds entries that are not version numbers: {bad}")

    duplicates = sorted({target for target in targets if targets.count(target) > 1})
    if duplicates:
        _fail(f"mcTargets lists the same target more than once: {duplicates}")

    return targets


# The candidate target list, parsed from settings.gradle.kts. 1.21.2 is absent there deliberately.
ALL_TARGETS = load_targets()

# Branch points in the shared loader/common tree. These apply to all three loaders, because all
# three compile the same shared source.
COMMON_SEAMS = {
    "1.21": "oldest generation: no Profiler, no SerializableChunkData, no TICKET_TYPE registry",
    "1.21.3": "SerializableChunkData exists but no getChunkStatusFromTag; MinecartBehavior appears",
    "1.21.5": "no TicketType.FLAG_* constants yet",
    "1.21.9": "ResourceLocation not Identifier; minecraft subpackage moves; named permission atoms",
    "1.21.11": "the modern tree — the renames that read as '26.x' actually shipped here",
    "26.1": "first unobfuscated release, and the Java 25 toolchain seam",
}

# Branch points that exist only in one loader's own adapter, on top of the shared ones.
LOADER_SEAMS = {
    "fabric": {
        # 26.1 is already in COMMON_SEAMS and is also where Loom's plugin id changes
        # (fabric-loom-remap -> fabric-loom); no Fabric-only seam beyond that.
    },
    "neoforge": {
        "1.21.5": "ticket registry exists and is frozen, so registration moves to RegisterEvent",
        "1.21.6": "AttachmentType.Builder.serialize takes a MapCodec here, a plain Codec below",
    },
    "forge": {
        "1.21.5": "ticket registry needs RegisterEvent, but still on EventBus 6 to reach it",
        "1.21.6": "EventBus 7: static per-event buses, BusGroup for mod events",
        "1.21.9": "ServerTickEvent.Post becomes a record exposing server(), not getServer()",
        "26.1": "EntityInteractSpecific, and ModList stops being a singleton",
    },
}


# A seam on a target that no longer exists means the subset would build a Stonecutter node that is
# not there. Say so here rather than letting `ALL_TARGETS.index` raise further down, where the
# message would not name the file that has to change.
_stale_seams = sorted(
    {target for seams in (COMMON_SEAMS, *LOADER_SEAMS.values()) for target in seams}
    - set(ALL_TARGETS)
)
if _stale_seams:
    _fail(
        f"seams named here are no longer in mcTargets: {_stale_seams}. "
        f"A target pruned from {SETTINGS.name} has to be pruned from the seam lists too."
    )


def expected_counts() -> dict[str, int]:
    """What a full release ships, derived from the same list the matrix is built from.

    The jar count check in release.yml exists because download-artifact is happy to hand back a
    partial set. It only means anything if the number it compares against comes from here.
    """
    loader_jars = len(ALL_TARGETS) * len(LOADERS)
    return {
        "TARGET_COUNT": len(ALL_TARGETS),
        "LOADER_COUNT": len(LOADERS),
        "LOADER_JARS": loader_jars,
        # Bukkit/Paper is one jar across all versions, built outside the loader matrix.
        "EXPECTED_JARS": loader_jars + 1,
    }


def subset_for(loader: str) -> dict[str, str]:
    reasons = dict(COMMON_SEAMS)
    for target, reason in LOADER_SEAMS[loader].items():
        reasons[target] = (reasons[target] + "; " + reason) if target in reasons else reason
    return reasons


def build(scope: str) -> list[dict[str, str]]:
    include: list[dict[str, str]] = []
    for loader in LOADERS:
        if scope == "full":
            targets = {target: "full matrix" for target in ALL_TARGETS}
        else:
            targets = subset_for(loader)
        for target in sorted(targets, key=ALL_TARGETS.index):
            include.append({
                "loader": loader,
                "mc": target,
                # 26.1 and later require Java 25; 1.21.x builds on 21.
                "java": "25" if target.startswith("26.") else "21",
                "why": targets[target],
            })
    return include


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("full", "subset"))
    parser.add_argument("--pretty", action="store_true", help="human-readable, for reading by hand")
    parser.add_argument(
        "--counts",
        action="store_true",
        help="emit KEY=value jar counts for a full release instead of a matrix, for $GITHUB_ENV",
    )
    args = parser.parse_args()

    if args.counts:
        if args.scope:
            parser.error("--counts and --scope are separate modes; pass one of them")
        for key, value in expected_counts().items():
            print(f"{key}={value}")
        return

    if args.scope is None:
        parser.error("one of --scope or --counts is required")

    include = build(args.scope)
    if args.pretty:
        for entry in include:
            print(f"{entry['loader']:9} {entry['mc']:8} java {entry['java']}  {entry['why']}")
        print(f"\n{len(include)} jobs")
    else:
        print(json.dumps({"include": include}, separators=(",", ":")))


if __name__ == "__main__":
    main()
