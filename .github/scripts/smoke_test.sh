#!/usr/bin/env bash
#
# Boot a real Minecraft server with a freshly built ChunkCarts jar and fail unless the mod says it
# is enabled.
#
# This is the only part of CI that proves the jar does anything. Everything upstream of it —
# compilation, the metadata verifier, the core suite — checks a necessary condition. A jar can pass
# all three and still be inert: the Forge `mods.toml` defect built on fifteen targets, packaged
# cleanly, and was rejected by FML at boot on every one of them. The only way to know a mod loads is
# to load it.
#
#   .github/scripts/smoke_test.sh <fabric|neoforge|forge|bukkit> <mc-version> <dir-containing-the-jar>
#
# Deliberately narrow: one game version, four loaders, "did it start". It is not a functional test
# of journeys — that is the suite in core/ and the in-game runs recorded per loader. Widening this
# into a gameplay test would mean a headless client, and it would rot.
set -euo pipefail

LOADER="${1:?loader: fabric | neoforge | forge | bukkit}"
MC="${2:?minecraft version}"
JAR_DIR="${3:?directory containing the built jar}"

# Pinned rather than resolved-latest. A smoke test that silently changes what it runs against is a
# smoke test that fails for reasons unrelated to the commit under test. Bump these deliberately.
#
# Every artifact below is pinned to an exact version, so a reshuffled or silently updated build
# already fails to resolve instead of swapping itself in — the version string alone rules that out.
# The checksum beside each one covers what pinning the version does not: an artifact published
# under that exact version number that is itself compromised. Verified after each download, before
# the two installer jars are ever executed.
FABRIC_LOADER="0.19.3"
FABRIC_INSTALLER="1.1.1"
# Launcher jar for MC 1.21.11 specifically (server/jar embeds $MC in its own URL below); build.yml's
# SMOKE_MC pins every loader to that version, so this holds for how the script is actually invoked.
FABRIC_LAUNCHER_SHA256="ed1b811d75555bd18dfc2b15199d94559ee2aab035e3c92ee43d3c4addca819f"
FABRIC_API="0.141.5+1.21.11"
FABRIC_API_SHA256="2525f9b7bb5524d409404831f7af276a86f7f1e90245ae4bb7555fe088d00e0d"
NEOFORGE="21.11.44"
NEOFORGE_SHA256="b8ae291c1ebdf4d92e5f699e2323ec5493f10e7793452256b3682aebab3eb54f"
FORGE="1.21.11-61.1.11"
FORGE_SHA256="38b38b1bf0ad2aa910720cbc56b47e3deb081648ac352fb9cc25276501e5ad97"
# Paper build for 1.21.11, from https://fill.papermc.io/v3/projects/paper/versions/1.21.11/builds
# (the v2 API at api.papermc.io is deprecated and returns HTTP 410). Checksum pinned alongside it
# so a compromised or reshuffled build cannot swap the jar out from under the version number.
PAPER_BUILD="132"
PAPER_SHA256="5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"

# The adapter's last statement in start(MinecraftServer), identical on fabric/neoforge/forge and
# — modulo Bukkit's own "[ChunkCarts] " log prefix, which the pattern is unanchored against — on
# Bukkit too. The em dash is U+2014 and is not matched here — the pattern stops before it so an
# encoding difference in the runner's console cannot fail the build for the wrong reason.
MARKER='ChunkCarts .* enabled'

# Generous on purpose. Local boots take five or six seconds, but those are warm directories with an
# existing world and a populated asset cache; a cold runner generates a spawn area from nothing and
# downloads libraries. A tight timeout here would produce flaky reds that teach people to re-run
# rather than to read.
BOOT_TIMEOUT="${BOOT_TIMEOUT:-420}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERVER="$ROOT/smoke/$LOADER"
LOGS="$ROOT/smoke/logs"
mkdir -p "$SERVER" "$LOGS"

say() { printf '\n=== %s\n' "$*"; }

jar_path() {
    local found
    found=$(find "$JAR_DIR" -maxdepth 1 -name 'chunkcarts-*.jar' ! -name '*-sources.jar' | head -n1)
    [ -n "$found" ] || { echo "no chunkcarts jar in $JAR_DIR" >&2; exit 1; }
    printf '%s' "$found"
}

install_fabric() {
    # The meta service serves a ready-made launcher for a (game, loader, installer) triple, so
    # there is no installer jar to run and no network access needed at boot beyond libraries.
    curl -fsSL -o "$SERVER/fabric-server-launch.jar" \
        "https://meta.fabricmc.net/v2/versions/loader/$MC/$FABRIC_LOADER/$FABRIC_INSTALLER/server/jar"
    echo "$FABRIC_LAUNCHER_SHA256  $SERVER/fabric-server-launch.jar" | sha256sum -c - \
        || { echo "fabric-server-launch.jar does not match the pinned FABRIC_LAUNCHER_SHA256" >&2; exit 1; }

    # Fabric API is a hard dependency (fabric.mod.json declares it), so the smoke test has to supply
    # it or the mod is rejected for a reason that has nothing to do with this commit. The download
    # URL is resolved from Modrinth rather than guessed: the CDN path contains a hash. Modrinth's
    # response carries its own sha1/sha512 alongside that URL, but checking a file against a hash
    # quoted in the same response that named the file is the same claim twice, not a check — the
    # value worth verifying against is the one pinned below, computed independently ahead of time.
    local url
    url=$(curl -fsSL "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22$MC%22%5D&loaders=%5B%22fabric%22%5D" \
        | python3 -c '
import json, sys
versions = json.load(sys.stdin)
wanted = sys.argv[1]
for version in versions:
    if version["version_number"] == wanted:
        print(version["files"][0]["url"]); break
else:
    sys.exit(f"fabric-api {wanted} not published for this game version")
' "$FABRIC_API")
    curl -fsSL -o "$SERVER/mods/fabric-api.jar" --create-dirs "$url"
    echo "$FABRIC_API_SHA256  $SERVER/mods/fabric-api.jar" | sha256sum -c - \
        || { echo "fabric-api.jar $FABRIC_API does not match the pinned FABRIC_API_SHA256" >&2; exit 1; }

    LAUNCH=(java -Xmx2G -jar fabric-server-launch.jar --nogui)
}

install_paper() {
    # The fill API returns builds newest-first as a JSON array, keyed by MC version rather than by
    # build number, so the pinned build has to be found in the list rather than addressed directly.
    local url
    url=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$MC/builds" \
        | python3 -c '
import json, sys
builds = json.load(sys.stdin)
wanted = int(sys.argv[1])
for build in builds:
    if build["id"] == wanted:
        print(build["downloads"]["server:default"]["url"]); break
else:
    sys.exit(f"paper build {wanted} not published for {sys.argv[2]}")
' "$PAPER_BUILD" "$MC")

    curl -fsSL -o "$SERVER/paper.jar" "$url"
    # Checked against the constant above, not against whatever checksum the API happened to hand
    # back alongside the URL — the point of pinning is a value that lives in this file, not one
    # that travels with the download it is meant to be checking.
    echo "$PAPER_SHA256  $SERVER/paper.jar" | sha256sum -c - \
        || { echo "paper.jar for build $PAPER_BUILD does not match the pinned PAPER_SHA256" >&2; exit 1; }

    # No installer: the server jar downloaded above is the whole server. No unix_args.txt either —
    # that is a Forge-family artifact of the installer step this loader does not have.
    LAUNCH=(java -Xmx2G -jar paper.jar --nogui)
}

install_forge_family() {
    local url args_dir sha256 ver
    if [ "$LOADER" = "neoforge" ]; then
        ver="$NEOFORGE"
        url="https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
        args_dir="libraries/net/neoforged/neoforge/$ver"
        sha256="$NEOFORGE_SHA256"
    else
        ver="$FORGE"
        url="https://maven.minecraftforge.net/net/minecraftforge/forge/$ver/forge-$ver-installer.jar"
        args_dir="libraries/net/minecraftforge/forge/$ver"
        sha256="$FORGE_SHA256"
    fi

    curl -fsSL -o "$SERVER/installer.jar" "$url"
    # Checked before the installer is ever executed: this is the one artifact in the script that
    # runs as code rather than sitting on disk to be loaded, so an unverified download here would
    # be an unverified program run with the runner's own privileges.
    echo "$sha256  $SERVER/installer.jar" | sha256sum -c - \
        || { echo "installer.jar for $LOADER $ver does not match the pinned checksum" >&2; exit 1; }
    ( cd "$SERVER" && java -jar installer.jar --installServer ) > "$LOGS/$LOADER-install.log" 2>&1 \
        || { echo "installer failed; see $LOGS/$LOADER-install.log" >&2; tail -n 40 "$LOGS/$LOADER-install.log" >&2; exit 1; }

    # Both loaders boot through an argument file the installer writes. Its path embeds the loader
    # version, so assert it exists rather than discovering it missing as an unreadable Java error.
    [ -f "$SERVER/$args_dir/unix_args.txt" ] \
        || { echo "installer wrote no $args_dir/unix_args.txt" >&2; exit 1; }

    LAUNCH=(java -Xmx2G "@$args_dir/unix_args.txt" --nogui)
}

configure_server() {
    echo 'eula=true' > "$SERVER/eula.txt"

    # A headless runner has no client, no players and no reason to generate scenery. view-distance
    # and simulation-distance are as low as the server accepts, for boot time; the rest turns off
    # everything that would wait on a network round trip.
    cat > "$SERVER/server.properties" <<'PROPERTIES'
online-mode=false
enable-rcon=false
enable-query=false
white-list=false
enforce-whitelist=false
view-distance=4
simulation-distance=2
max-players=1
spawn-protection=0
sync-chunk-writes=false
level-type=minecraft\:flat
motd=ChunkCarts smoke test
PROPERTIES
}

install_mod() {
    # Bukkit is a plugin, not a mod: it loads from plugins/, and a jar dropped in mods/ would just
    # sit there unloaded and the test would pass for the wrong reason — a server that booted with
    # no ChunkCarts in it at all.
    local subdir="mods"
    [ "$LOADER" = "bukkit" ] && subdir="plugins"

    mkdir -p "$SERVER/$subdir"
    cp "$(jar_path)" "$SERVER/$subdir/"
    ls -l "$SERVER/$subdir/"
}

run_server() {
    local log="$LOGS/$LOADER-server.log"
    local fifo="$SERVER/stdin.fifo"
    rm -f "$fifo"; mkfifo "$fifo"

    # Hold the FIFO open from this shell so the server does not see EOF on stdin and shut itself
    # down the moment the first writer closes.
    exec 3<>"$fifo"

    ( cd "$SERVER" && "${LAUNCH[@]}" ) < "$fifo" > "$log" 2>&1 &
    local pid=$!

    local waited=0 found=0
    while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
        if grep -qE "$MARKER" "$log"; then found=1; break; fi
        # A dead server will never print the marker, so stop waiting the full timeout for it.
        kill -0 "$pid" 2>/dev/null || break
        sleep 2
        waited=$((waited + 2))
    done

    if [ "$found" = 1 ]; then
        say "ChunkCarts reported itself enabled after ${waited}s"
        grep -E "$MARKER" "$log"
    fi

    # Ask for a clean stop either way: a crash log from an orderly shutdown is far more readable
    # than one from SIGKILL, and on failure the log is the whole artifact.
    printf 'stop\n' >&3 || true
    local grace=0
    while kill -0 "$pid" 2>/dev/null && [ "$grace" -lt 90 ]; do sleep 2; grace=$((grace + 2)); done
    kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    exec 3>&-

    if [ "$found" != 1 ]; then
        echo >&2
        echo "FAIL: no '$MARKER' in the log after ${waited}s." >&2
        echo "The server may have started perfectly well without the mod in it — that is exactly" >&2
        echo "the failure this test exists to catch. Last 60 lines:" >&2
        tail -n 60 "$log" >&2
        return 1
    fi

    # The server starting is not on its own proof of anything: FML logs a rejected mod and carries
    # on. Assert the two lines that only appear when the world is up and nothing threw.
    grep -qE 'Done \(' "$log" \
        || { echo "FAIL: mod enabled but the server never finished starting" >&2; tail -n 60 "$log" >&2; return 1; }

    # Not a failure: a headless runner logs a couple of benign errors of its own (no terminal, no
    # authentication). Surfaced rather than asserted on, so a real stack trace is visible in the
    # summary without turning every runner quirk into a red build.
    # Printed inline rather than only pointed at. A note saying "review the log" is worth nothing
    # on a runner whose disk is thrown away seconds later, and a passing job is exactly when
    # nobody goes looking for the artifact.
    if grep -qE 'Exception|ERROR\]' "$log"; then
        say "note: $(grep -cE 'Exception|ERROR\]' "$log") error line(s) in the log, first 20:"
        grep -nE 'Exception|ERROR\]' "$log" | head -n 20 >&2
    fi
}

say "smoke test: $LOADER $MC"
configure_server
case "$LOADER" in
    fabric)          install_fabric ;;
    neoforge|forge)  install_forge_family ;;
    bukkit)          install_paper ;;
    *)               echo "unknown loader: $LOADER" >&2; exit 1 ;;
esac
install_mod
run_server
say "PASS: $LOADER $MC loads ChunkCarts and starts"
