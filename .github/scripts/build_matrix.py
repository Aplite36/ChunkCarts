#!/usr/bin/env python3
"""Emit the GitHub Actions job matrix for the loader targets.

Two scopes:

  full    every target in SPEC §12's candidate list, on every loader. 45 jobs. This is what runs
          on a push to main and on a release tag, because §13 requires that CI build every jar
          that gets published.

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

LOADERS = ("fabric", "neoforge", "forge")

# SPEC §12's candidate list, verbatim. 1.21.2 is absent deliberately.
ALL_TARGETS = [
    "1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8",
    "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2",
]

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
                # 26.1 and later require Java 25; 1.21.x builds on 21. SPEC §1.
                "java": "25" if target.startswith("26.") else "21",
                "why": targets[target],
            })
    return include


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", required=True, choices=("full", "subset"))
    parser.add_argument("--pretty", action="store_true", help="human-readable, for reading by hand")
    args = parser.parse_args()

    include = build(args.scope)
    if args.pretty:
        for entry in include:
            print(f"{entry['loader']:9} {entry['mc']:8} java {entry['java']}  {entry['why']}")
        print(f"\n{len(include)} jobs")
    else:
        print(json.dumps({"include": include}, separators=(",", ":")))


if __name__ == "__main__":
    main()
