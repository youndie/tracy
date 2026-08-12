# ---------- Stage 1 : build ----------
# Pinned to amd64 on purpose: the image is built on whatever runner is free, and a native
# binary linked for the builder's architecture would fail at exec time with nothing but
# "no such file or directory" to explain it.
FROM --platform=linux/amd64 gradle:9.7.0-jdk25-noble AS build

WORKDIR /app
COPY . .

RUN gradle :server:linkReleaseExecutableLinuxX64 --no-daemon --stacktrace

# ---------- Stage 2 : runtime ----------
# distroless/cc rather than distroless/base: the Kotlin/Native binary needs libgcc, and
# sqlx4k's SQLite is compiled in, so there is no database package to install.
#
# The two stages are a matched pair, and getting it wrong fails at exec rather than at build.
# The builder's glibc must be no newer than the runtime's, because libcrypt is copied across:
# noble is 2.39 and debian13 is newer, so this holds. The plain `gradle:9.7.0-jdk25` tag is
# Ubuntu 26.04 with glibc 2.43 and produced exactly that failure — the image built, and the
# container died with "GLIBC_2.38 not found".
FROM gcr.io/distroless/cc-debian13
WORKDIR /app

COPY --from=build /app/server/build/bin/linuxX64/releaseExecutable/server.kexe /app/server
# Kotlin/Native links against libcrypt, which distroless does not carry.
COPY --from=build /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /usr/lib/x86_64-linux-gnu/

EXPOSE 8080

ENTRYPOINT ["/app/server"]
