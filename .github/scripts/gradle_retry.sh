#!/usr/bin/env bash
#
# Run a Gradle command, and run it again only if it failed while fetching something over the
# network during setup.
#
#   .github/scripts/gradle_retry.sh :loader:forge:1.21.4:build
#
# Why this exists: on the loader nodes, most of the network traffic in a cold build is not Gradle's.
# ForgeGradle 7 runs Minecraft Mavenizer, and NeoForge's ModDevGradle runs NeoForm Runtime, as
# separate JVMs at configuration time, and each downloads Minecraft, its libraries and the MCP/
# NeoForm data with its own HTTP client. Gradle's retry settings (the -D flags below) cover Gradle's
# own dependency resolution and nothing else, so a timeout inside one of those tools fails the
# configuration on the first stall. That is what turned 1-3 forge jobs red per full run on
# 2026-10-30: `java.net.http.HttpTimeoutException: request timed out` in Mavenizer's listLibraries
# step, before anything was compiled.
#
# What it will not do is retry a real failure. A second attempt happens only when both hold:
#
#   1. Every "What went wrong" block Gradle printed is a configuration or resolution failure
#      ("A problem occurred configuring", "Could not resolve", "Could not download", ...). A failing
#      compile, test, or any other task ("Execution failed for task ...") that is not itself a
#      resolution failure stops here on the first attempt.
#   2. The output carries a network exception or an HTTP 5xx/429 from a repository.
#
# Each retry is announced as a workflow warning and in the step summary, so a green job that needed
# one says so rather than looking like a clean run.
set -euo pipefail

[ $# -gt 0 ] || { echo "usage: $0 <gradle args...>" >&2; exit 2; }

MAX_ATTEMPTS="${GRADLE_RETRY_ATTEMPTS:-3}"

# Gradle's own HTTP client: wait longer for a slow repository and retry more often before giving
# up. Passed as -D on the command line rather than through GRADLE_OPTS, because command-line
# system properties are forwarded to the daemon, which is the JVM that does the resolving.
NET_PROPS=(
    -Dorg.gradle.internal.http.connectionTimeout=60000
    -Dorg.gradle.internal.http.socketTimeout=120000
    -Dorg.gradle.internal.repository.max.retries=5
    -Dorg.gradle.internal.repository.initial.backoff=1000
)

NETWORK_RE='java\.net\.(http\.Http(Connect)?TimeoutException|SocketTimeoutException|ConnectException|UnknownHostException|NoRouteToHostException|SocketException)|javax\.net\.ssl\.SSL(Handshake)?Exception|Connection reset|Read timed out|Connect timed out|Premature end of|Received status code (5[0-9][0-9]|429) from server|Could not (GET|HEAD) '"'"'https?://'

# A "What went wrong" block that is setup, not the code under test.
SETUP_RE='^(A problem occurred configuring|A problem occurred evaluating|Could not resolve|Could not download|Could not GET|Could not HEAD|Could not determine the dependencies|Plugin \[id: .*\] was not found|Error resolving plugin)'

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

# 0 if the failure in $log is worth another attempt.
retryable() {
    grep -Eq "$NETWORK_RE" "$log" || return 1

    # The line after each "* What went wrong:" header names the failure. An "Execution failed for
    # task" header is retryable only when the task failed resolving something (the next line
    # starts with "> Could not resolve/download"); anything else is the build's own failure.
    local saw_any=0 header detail
    while IFS=$'\t' read -r header detail; do
        saw_any=1
        if [[ "$header" =~ $SETUP_RE ]]; then
            continue
        fi
        if [[ "$header" == "Execution failed for task"* ]] \
            && [[ "$detail" =~ ^\>\ (Could\ not\ resolve|Could\ not\ download) ]]; then
            continue
        fi
        return 1
    done < <(awk '/^\* What went wrong:/ { getline h; getline d; sub(/^[[:space:]]+/, "", d); print h "\t" d }' "$log")

    [ "$saw_any" = 1 ]
}

attempt=1
while :; do
    status=0
    ./gradlew "${NET_PROPS[@]}" "$@" 2>&1 | tee "$log" || status=$?
    [ "$status" -eq 0 ] && exit 0

    if [ "$attempt" -ge "$MAX_ATTEMPTS" ] || ! retryable; then
        exit "$status"
    fi

    cause=$(grep -Eo "$NETWORK_RE" "$log" | head -n 1)
    wait=$((attempt * 30))
    msg="gradle $* failed during setup on attempt $attempt/$MAX_ATTEMPTS ($cause); retrying in ${wait}s"
    echo "::warning title=Gradle setup retry::$msg"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        echo "- :warning: $msg" >> "$GITHUB_STEP_SUMMARY"
    fi
    sleep "$wait"
    attempt=$((attempt + 1))
done
