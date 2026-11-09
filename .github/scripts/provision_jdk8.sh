#!/usr/bin/env bash
#
# Install a JDK 8 for Minecraft Mavenizer and export JAVA_HOME_8_X64 for the rest of the job.
#
#   .github/scripts/provision_jdk8.sh <archive cache dir>
#
# Why this exists: ForgeGradle 7 runs Minecraft Mavenizer at configuration time, and Mavenizer's
# srg2names step runs a Java 8 tool. With no JDK 8 installed, Mavenizer gets one itself: it asks
# the foojay Disco API and downloads Temurin from GitHub releases, through a client with a
# 5-second request timeout that follows only 301/302 and saves the body of any other non-404
# response as the archive. On a full run that failed most forge jobs during configuration, either
# `HttpTimeoutException: request timed out` or `sha256 Invalid` when the saved bytes were not the
# tarball.
#
# Mavenizer checks the JAVA_HOME_<n>_X64 variables before it downloads anything, so exporting
# JAVA_HOME_8_X64 is enough to keep it off the network. Gradle's org.gradle.java.installations.*
# properties would not reach it: ForgeGradle starts Mavenizer with no JVM arguments.
#
# Not actions/setup-java: the runner image has no Temurin 8 in its tool cache, so setup-java
# downloads the same archive, and it does not check that download against a checksum. Mavenizer
# did. Here the archive is pinned, cached between runs by the workflow, and checked against the
# sha256 below whether it came from the cache or the network, before it is extracted. The pin is
# the value Adoptium publishes beside the archive (<archive>.sha256.txt), which is also what the
# Disco API lists for it.
#
# Pinned rather than latest, like the smoke test downloads: Mavenizer needs a working Java 8, not
# the newest one, and a pin cannot change underneath a build. Bump it deliberately.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 <archive cache dir>" >&2; exit 2; }

ARCHIVE="OpenJDK8U-jdk_x64_linux_hotspot_8u512b09.tar.gz"
URL="https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u512-b09/$ARCHIVE"
SHA256="fb3bfa869012419b562bb4235d0d83ec4f8c1dda62ae432ba20c6c3a7e43f7e4"

cache_dir="$1"
dest="${RUNNER_TEMP:-/tmp}/jdk8"
tarball="$cache_dir/$ARCHIVE"

verified() {
    echo "$SHA256  $tarball" | sha256sum -c --status -
}

mkdir -p "$cache_dir"
if [ -f "$tarball" ] && verified; then
    echo "Using cached $ARCHIVE"
else
    # A cached archive that fails the check is discarded and fetched again; one that was
    # fetched and fails the check stops the job.
    rm -f "$tarball"
    echo "Downloading $URL"
    curl -fsSL --retry 4 --retry-all-errors --retry-delay 5 --connect-timeout 30 -o "$tarball" "$URL"
    verified || {
        echo "::error title=JDK 8 checksum::$ARCHIVE does not match the pinned sha256 ($(sha256sum "$tarball" | cut -d' ' -f1))"
        exit 1
    }
    echo "Downloaded and verified $ARCHIVE"
fi

rm -rf "$dest"
mkdir -p "$dest"
tar -xzf "$tarball" -C "$dest" --strip-components=1

# Mavenizer probes the version itself, so a wrong JDK would fail later with a less useful message.
# Matched rather than taken as the first line, which is "Picked up JAVA_TOOL_OPTIONS" when that is
# set.
version=$("$dest/bin/java" -version 2>&1 | grep -m 1 ' version ' || true)
echo "$version"
[[ "$version" == *'"1.8.'* ]] || { echo "::error::$dest is not a Java 8: $version"; exit 1; }

echo "JAVA_HOME_8_X64=$dest" >> "${GITHUB_ENV:-/dev/null}"
echo "JAVA_HOME_8_X64=$dest"
