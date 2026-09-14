#!/usr/bin/env bash
# Where the resident set of tracy settles under a fixed ingest rate — M-137.
#
# The 14.09 runs answered "128Mi is killed, 256Mi survives five minutes" and could not answer where
# the curve stops, because five minutes is not long enough for it to stop and because `--vus` lets
# the input drop with the server. This one holds the rate (see dev/soak-load.js) and samples the
# axis that separates the candidate causes instead of one number that cannot:
#
#   anon    — the heap: SQLite page caches, sqlx statement caches, the Kotlin/Native allocator;
#   file    — the binary and the gconv modules, flat by construction;
#   shmem   — the WAL index, which grows with the WAL rather than with the heap;
#   wal     — the `-wal` file itself, the direct reading of whether checkpoints keep up;
#   cgroup  — what the kernel counts against `--memory`, which includes page cache and is what the
#             OOM killer acts on. VmRSS alone would miss it;
#   comm    — the names of the threads, because on Kotlin/Native the allocator keeps a page per size
#             class *per thread* (M-133), so "how many threads, and whose" is a candidate cause of
#             its own, and a count alone cannot say whose.
#
# The port is published on loopback only, so that the samples cost a `curl` rather than a container
# per sample. This is a stand with an empty database; nothing here is reachable off the box.
#
# Usage: soak.sh <image> <memory> <rate/s> <duration> <label> [outdir]
# Extra server environment (the pool knobs, for instance) goes in EXTRA:
#   EXTRA="-e TRACY_DB_MAX_CONNECTIONS=4" soak.sh ...
set -uo pipefail

image=$1; mem=$2; rate=$3; dur=$4; label=$5
out=${6:-/root/tracy-stand}
name=tracy-soak
key=stand-key
port=18080
sample_every=10
idle_tail=180

data=$out/data-$label
rm -rf "$data"; mkdir -p "$data"
samples=$out/samples-$label.tsv
comms=$out/comms-$label.txt
: > "$samples"; : > "$comms"

docker rm -f $name >/dev/null 2>&1
# shellcheck disable=SC2086  # EXTRA is a list of docker flags on purpose.
docker run -d --name $name --network tracy-net \
  --memory="$mem" --memory-swap="$mem" --cpus=1 \
  -p 127.0.0.1:$port:8080 \
  -v "$data":/data \
  ${EXTRA:-} \
  -e TRACY_INGEST_KEY=$key -e TRACY_DB_PATH=/data/tracy.db "$image" >/dev/null || exit 1

for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:$port/health/ready 2>/dev/null)
    [ "$code" = "200" ] && break
    sleep 1
done
[ "$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:$port/health/ready)" = "200" ] || {
    echo "soak: the server never became ready" >&2; docker logs $name 2>&1 | tail -20 >&2; exit 1; }

pid=$(docker inspect -f '{{.State.Pid}}' $name)
cg=/sys/fs/cgroup$(awk -F: '/^0::/{print $3}' /proc/$pid/cgroup)

field() { awk -v k="$2" '$1 == k":" {print $2; exit}' "$1" 2>/dev/null; }
cgstat() { awk -v k="$2" '$1 == k {print int($2/1024); exit}' "$1" 2>/dev/null; }
size() { stat -c %s "$1" 2>/dev/null || echo 0; }

printf 'elapsed\tthreads\trss_kb\tanon_kb\tfile_kb\tshmem_kb\tcg_kb\tcg_anon_kb\tcg_file_kb\tcg_slab_kb\tdb_kb\twal_kb\tshm_kb\tdb_pages\n' >> "$samples"

start=$(date +%s)
(
    while [ -r /proc/$pid/status ]; do
        now=$(( $(date +%s) - start ))
        rollup=/proc/$pid/smaps_rollup
        rss=$(field /proc/$pid/status VmRSS)
        threads=$(field /proc/$pid/status Threads)
        anon=$(field $rollup Anonymous)
        shmem=$(field $rollup Shmem)
        rss_r=$(field $rollup Rss)
        filekb=$(( ${rss_r:-0} - ${anon:-0} - ${shmem:-0} ))
        cur=$(( $(cat $cg/memory.current 2>/dev/null || echo 0) / 1024 ))
        pages=$(curl -s --max-time 5 http://127.0.0.1:$port/health/retention | tr ',' '\n' | sed -n 's/.*"databaseBytes"[: ]*\([0-9]*\).*/\1/p' | head -1)
        printf '%s\t%s\n' "$now" \
            "$(cat /proc/$pid/task/*/comm 2>/dev/null | sed 's/[0-9]\+$//' | sort | uniq -c | sort -rn | awk '{printf "%s=%s ", $2, $1}')" >> "$comms"
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$now" "${threads:-NA}" "${rss:-NA}" "${anon:-NA}" "$filekb" "${shmem:-0}" \
            "$cur" "$(cgstat $cg/memory.stat anon)" "$(cgstat $cg/memory.stat file)" "$(cgstat $cg/memory.stat slab)" \
            "$(( $(size "$data/tracy.db") / 1024 ))" "$(( $(size "$data/tracy.db-wal") / 1024 ))" \
            "$(( $(size "$data/tracy.db-shm") / 1024 ))" "${pages:-NA}" >> "$samples"
        sleep $sample_every
    done
) &
sampler=$!

docker run --rm --network tracy-net --cpus=1 -v "$out":/w \
  -e BASE=http://$name:8080 -e KEY=$key -e RATE="$rate" -e DURATION="$dur" \
  grafana/k6:latest run --quiet /w/soak-load.js > "$out/k6-$label.txt" 2>&1

# The load stops and the sampler keeps going: what a process gives back when the writers leave is
# the difference between a cache that is holding pages and a heap that only grows.
echo "soak: load finished, $idle_tail s of idle sampling" >&2
sleep $idle_tail
kill $sampler 2>/dev/null; wait $sampler 2>/dev/null

alive=$(docker inspect -f '{{.State.Running}}' $name)
state=$(docker inspect -f 'oom={{.State.OOMKilled}}/exit={{.State.ExitCode}}' $name)
docker logs $name > "$out/log-$label.txt" 2>&1

printf '\n== %s  image=%s mem=%s rate=%s/s duration=%s extra=%s ==\n' "$label" "$image" "$mem" "$rate" "$dur" "${EXTRA:-none}"
printf 'alive=%s %s\n' "$alive" "$state"
grep -E 'dropped_iterations|http_req_failed|http_reqs|p\(95\)' "$out/k6-$label.txt" | head -6
echo "samples: $samples"
tail -1 "$samples"
