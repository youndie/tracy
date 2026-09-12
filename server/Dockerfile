# ---------- Stage 1 : build ----------
# Pinned to amd64 on purpose: the image is built on whatever runner is free, and a native
# binary linked for the builder's architecture would fail at exec time with nothing but
# "no such file or directory" to explain it.
FROM --platform=linux/amd64 gradle:9.7.1-jdk25-noble AS build

WORKDIR /app
COPY . .

RUN gradle :server:linkReleaseExecutableLinuxX64 --no-daemon --stacktrace

# ---------- Stage 2 : runtime ----------
# distroless/cc rather than distroless/base, and the reason is not glibc: Kotlin/Native's exception
# handling imports thirteen `_Unwind_*` symbols from libgcc, which `base` does not carry. sqlx4k's
# SQLite is compiled in, so there is no database package to install either.
#
# NOTHING IS COPIED BESIDE THE BINARY, and that is load-bearing. This file used to drag
# `libcrypt.so.1` across from the builder, because a Kotlin/Native binary DECLARES it — it arrives
# with `platform.posix`, whose klib manifest names it for every Linux program — while importing not
# one symbol from it. That copy is what made the two stages a matched pair: the file is
# glibc-version-coupled, so the builder's glibc had to be no newer than the runtime's, and when it
# was not the image built and the container died with "GLIBC_2.38 not found" before any of its own
# logging ran.
#
# `sborka.kmp` links Linux executables with `-Wl,--as-needed` since 0.4.0.57, the declaration is
# gone, and so is the pairing rule. If this image ever fails with `cannot open shared object file`,
# the answer is that the convention did not apply — check `readelf -d` on the binary, not another
# COPY line.
FROM gcr.io/distroless/cc-debian13
WORKDIR /app

COPY --from=build /app/server/build/bin/linuxX64/releaseExecutable/server.kexe /app/server

EXPOSE 8080

ENTRYPOINT ["/app/server"]
