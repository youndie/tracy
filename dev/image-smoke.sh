#!/usr/bin/env bash
# Drives a running tracy through the paths a `scratch` image can break silently.
#
# The image carries no base system: no shell, no `ls`, and only the four glibc files `iconv` needs.
# A check that stops at a status code passes on an image that cannot encode a string — that is the
# failure this exists to catch — so this one ingests a record and reads it back, which puts a real
# body through the ingest path, SQLite, and the query path.
#
# Usage: dev/image-smoke.sh [base-url] [ingest-key]
set -euo pipefail

base=${1:-http://localhost:8080}
key=${2:-smoke-key}

fail() {
    printf 'smoke: %s\n' "$1" >&2
    exit 1
}

printf 'smoke: waiting for %s\n' "$base" >&2
for _ in $(seq 1 60); do
    curl -fsS -o /dev/null "$base/health/live" 2>/dev/null && break
    sleep 1
done
curl -fsS -o /dev/null "$base/health/live" || fail "the server never answered on $base"

# The probes and /version answer without a key — the kubelet carries none. A `version:` line also
# says the Gradle plugin's generated object was compiled in rather than merely written to disk.
for probe in /health/startup /health/ready /health/live /health; do
    curl -fsS -o /dev/null "$base$probe" || fail "$probe did not answer 200"
done
curl -fsS "$base/version" | grep -q '^version: ' || fail "/version did not report a version"
retention=$(curl -fsS "$base/health/retention")
printf '%s' "$retention" | grep -q 'databaseBytes' || fail "/health/retention reported no state"
# `walBytes` is wired through Koin, and the wiring is the half a unit test cannot reach: the number
# is computed from a file path that only the real container knows. M-137 is the reason it is
# reported at all — a write-ahead log grew to 931 MB while every size tracy published stayed small.
printf '%s' "$retention" | grep -q 'walBytes' || fail "/health/retention does not report the write-ahead log"

now=$(date +%s)000
since=$(( now - 3600000 ))
until=$(( now + 3600000 ))
line=$(printf '{"t":%s,"n":1,"l":"INFO","g":"Smoke","m":"image smoke ran"}' "$now")

curl -fsS -o /dev/null -X POST \
    -H "X-Tracy-Key: $key" \
    -H "X-Tracy-Service: image-smoke" \
    -H "X-Tracy-Instance: smoke-1" \
    -H "X-Tracy-Seq: 1" \
    -H "Content-Type: application/x-ndjson" \
    --data-binary "$line" \
    "$base/ingest" || fail "the batch was not accepted"

# Read it back through the query path, which builds its own URLs and encodes what it found.
for _ in $(seq 1 20); do
    # Both ends of the window are required — the query answers 400 with
    # `{"error":"since is required"}` otherwise, which `curl -fsS` turns into silence rather than
    # into a message.
    found=$(curl -fsS "$base/api/logs?service=image-smoke&since=$since&until=$until&limit=10" 2>/dev/null || true)
    if printf '%s' "$found" | grep -q 'image smoke ran'; then
        printf 'smoke: the record came back out of the store\n' >&2
        exit 0
    fi
    sleep 1
done

fail "the record never came back from /api/logs"
