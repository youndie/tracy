# ---------- Stage 1 : build ----------
# Pinned to amd64 on purpose: the image is built on whatever runner is free, and a native
# binary linked for the builder's architecture would fail at exec time with nothing but
# "no such file or directory" to explain it.
FROM --platform=linux/amd64 gradle:9.7.1-jdk25-noble AS build

# The static archives the link needs: libc.a, crt1.o, libstdc++.a, libgcc.a, libgcc_eh.a. This image
# carries glibc 2.39 — the right version — and none of the archives, and the link fails with
# `unable to find library -lc`, which reads like a linker-flag problem and is not one. gcc 13 is the
# version the `libGcc` override in `server/build.gradle.kts` names; the two move together.
RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .

# `-PVERSION` FROM THE SAME LINE THE IMAGE TAG COMES FROM. Without it the build falls back to
# `version` in gradle.properties, which is a `-SNAPSHOT` on purpose — a build with no version must
# not be able to produce something that looks like a release — and the binary then reports
# `0.3.0-SNAPSHOT` from `/version` while the tag on the image it sits in says `0.3.0`. Two numbers
# for one thing, one of them calling a released image a snapshot. Read out of the file rather than
# passed as a build argument so it cannot drift from the tag: `Publish server native` greps the
# same line.
# The two cache mounts are what make a second build of a changed source tree minutes rather than
# twenty: without them every build re-downloads the Kotlin/Native toolchain (~1.8 GB into
# `~/.konan`) and every dependency, because `COPY . .` above invalidates this layer on any edit.
# They change nothing about what ends up in the image — a cold builder (a hosted CI runner) simply
# starts with both caches empty and behaves exactly as before.
RUN --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.gradle \
    gradle :server:linkReleaseExecutableLinuxX64 --no-daemon --stacktrace \
    -PVERSION="$(grep '^SERVER_VERSION=' gradle.properties | cut -d'=' -f2)"

# ---------- Stage 2 : runtime ----------
# NO BASE IMAGE. The binary is linked statically (the `staticLinuxX64` block in
# `server/build.gradle.kts`), so `distroless/cc` is not under it any more.
#
# FOUR SYSTEM FILES ARE STILL COPIED ACROSS, AND NOT ONE OF THEM IS OPTIONAL. A statically linked
# glibc is not a self-contained one: `iconv` loads its converters with `dlopen` — even UTF-8 arrives
# from a gconv module — and Ktor's charset layer on Kotlin/Native *is* glibc `iconv`, reached by
# `encodeURLParameter` on any path that builds a URL. A `scratch` image with the binary alone starts
# and answers a bare route, then fails with
# `Failed to open iconv for charset UTF-8 with error code 22` the first time it has to encode
# anything. Found on katcher (#55 there), by driving a real page rather than reading a status code.
#
# The list is what `strace -e trace=openat` recorded a working binary opening, and the copies come
# from the build stage because `dlopen` out of a static binary needs the same glibc build the binary
# was linked against — copies from the host, or from another image of the same distribution, failed
# with the same error 22. The whole gconv tree rather than one module: glibc picked `UTF-16.so` to
# convert UTF-8, which is enough to say the reachable set is not something a COPY line should predict.
#
# What this drops with the base image is the rule that used to stand here: there is no runtime image
# to pair with the builder by glibc version any more, so `GLIBC_2.38 not found` stops being possible.
#
# What `scratch` costs: no shell, no `ls`, nothing to `kubectl exec` into, no `/tmp`, and no CA
# certificates — tracy's server opens no outbound HTTPS today, and the day it does, the first TLS
# handshake is where this is remembered. The directory of the SQLite file has to exist: `WORKDIR`
# makes this one and the chart mounts a volume over it.
FROM scratch
WORKDIR /app

COPY --from=build /etc/ld.so.cache /etc/ld.so.cache
COPY --from=build /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
COPY --from=build /lib/x86_64-linux-gnu/libc.so.6 /lib/x86_64-linux-gnu/libc.so.6
COPY --from=build /usr/lib/x86_64-linux-gnu/gconv /usr/lib/x86_64-linux-gnu/gconv

COPY --from=build /app/server/build/bin/linuxX64/releaseExecutable/server.kexe /app/server

# WHY AN ALLOCATOR SETTING TRAVELS WITH THE IMAGE. glibc gives a thread its own malloc arena
# whenever the one it wants is locked, up to eight arenas per core — and it counts the *host's*
# cores, not the container's CPU quota, so a `--cpus=1` container on a 20-core runner may grow
# arenas until the limit kills it. An arena only ever returns memory from its top, so the resident
# set becomes the sum of every arena's high-water mark rather than the live heap: M-137 found 120 MB
# of the 130 MB resident set sitting in ten anonymous mappings on 64 MB boundaries.
#
# Measured on this image, same load, same limit, identical bytes ingested: 33 % less anonymous
# memory with the cap than without (74 → 49 MB at ten minutes, 82 → 54 MB at ten and a half). The
# price is lock contention in `malloc`, which this workload does not pay because it waits on SQLite
# and the disk, not on allocation — a different workload would have to re-measure.
#
# Here rather than in the chart because it is a property of running this binary under a memory
# limit, not of one deployment: `docker run` of the image and the CI smoke get it too. A chart can
# still override it.
ENV MALLOC_ARENA_MAX=2

EXPOSE 8080

ENTRYPOINT ["/app/server"]
