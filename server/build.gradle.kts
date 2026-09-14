plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.parity")
    // Generates `KoreBuildIdentity` — version, commit and build time as compiled-in source, because
    // Kotlin/Native has neither resources nor a manifest for `/version` to read them from. The
    // version it stamps is this project's, which is `version` in gradle.properties.
    alias(libs.plugins.koreBuild)
}

// NOT PUBLISHED: the server ships as a container image, not as an artefact, so no `sborka.publish`
// here. Explicit API is off for the same reason — nothing resolves this module as a library, so
// there is no consumer for a spelled-out surface to be spelled out for.
kotlin {
    explicitApi = null
}

// WHERE THE PLATFORM PROBE LOOKS.
//
// `localhost` and port 0: the name is what the probe resolves, and the port is bound by the test
// itself. A real service name would be a better question and needs something listening in CI, which
// this repository does not have — see `PlatformTest` for what that costs and what it still catches.
parityProbe {
    host = "localhost"
    port = 0
}

// Off with `-Ptracy.staticLink=false`. On by default because the image that gets deployed is the
// linuxX64 one, and a flag that has to be remembered at release time is a flag that is forgotten.
val staticLinuxX64 = (project.findProperty("tracy.staticLink") as String?)?.toBoolean() != false

kotlin {
    jvm()

    listOf(
        macosArm64(),
        linuxX64(),
        linuxArm64(),
    ).forEach { target ->
        target.binaries.executable {
            entryPoint = "io.github.youndie.tracy.server.main"

            // 16 KiB PAGES INSTEAD OF THE DEFAULT 256, AND THIS IS WHAT KEEPS THE PROCESS INSIDE ITS
            // MEMORY LIMIT. Kotlin/Native's `CustomAllocator` is per-thread: a thread that touches a
            // block-size class keeps a page of that class for as long as it lives, occupied or not,
            // so the resident set follows the THREAD COUNT rather than the live heap — and
            // `Dispatchers.IO` grows threads on demand under concurrency. No GC setting bounds it,
            // because these are pages and not objects.
            //
            // Measured on katcher, same shape of service, `--memory=192m --cpus=1`, fifty concurrent
            // requests rendering from SQLite: the default build was killed with `exit=137` in eight
            // runs out of eight, peaking at 252–329 MB where a limit allowed it; with this option,
            // 22–26 MB at rest and 47–62 MB under the same load, eight out of eight surviving.
            // `-Xallocator=std` was the other candidate and came out slower and, with a store on the
            // request path, LARGER — the opposite of its behaviour on a service without one.
            binaryOption("fixedBlockPageSize", "16")

            // STATIC ON linuxX64, so the runtime image needs no base image at all. glibc, libstdc++
            // and libgcc move inside the binary and the distroless/cc layer under it disappears; on
            // katcher, the same move took the image from 15 542 820 to 9 570 311 bytes to pull.
            //
            // STATIC IS NOT SELF-CONTAINED, and `server/Dockerfile` is the other half of this
            // decision — read it before touching this. glibc's `iconv` loads its converters with
            // `dlopen`, even for UTF-8, so the image still carries the shared glibc and the gconv
            // tree.
            //
            // The link needs static archives — `libc.a`, `crt1.o`, `libstdc++.a`, `libgcc.a`,
            // `libgcc_eh.a` — which `apt-get install g++` supplies and a stock `gradle:…-noble` image
            // does not. The failure there reads `unable to find library -lc`, which looks like a
            // linker-flag problem and is not one. `-Ptracy.staticLink=false` links the old way on a
            // machine without g++.
            //
            // `-Xoverride-konan-properties` IS NOT A STABLE INTERFACE: five `konan.properties` keys
            // are pinned here and JetBrains have said they may change in any patch release, so a
            // Kotlin bump can break this. It breaks loudly, as a link error, and the `Image`
            // workflow is what makes that a pull request's problem rather than a release's.
            //
            // `linkerKonanFlags` is THE STOCK VALUE WITH `-Bdynamic` REMOVED and nothing else
            // changed — its value continues onto a second line in `konan.properties`, and rewriting
            // it from memory drops `--gc-sections` and costs about 316 KB for nothing. Two of the
            // five overrides exist only because `-linker-option -static` does not yet mean static
            // upstream: KT-89362.
            if (staticLinuxX64 && target.name == "linuxX64") {
                linkerOpts("-static", "--no-dynamic-linker", "-L/usr/lib/x86_64-linux-gnu")
                freeCompilerArgs +=
                    "-Xoverride-konan-properties=" +
                    "targetSysRoot.linux_x64=/;" +
                    "crtFilesLocation.linux_x64=usr/lib/x86_64-linux-gnu;" +
                    "libGcc.linux_x64=usr/lib/gcc/x86_64-linux-gnu/13;" +
                    "linkerGccFlags=-lgcc -lgcc_eh -lc;" +
                    "linkerKonanFlags.linux_x64=-Bstatic -lstdc++ -ldl -lm -lpthread " +
                    "--defsym __cxa_demangle=Konan_cxa_demangle --gc-sections"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)

            // The stretch from SIGTERM to exit, the three probes and /version. `kore-ktor` carries
            // `kore-core`; both are named because both are imported here — `Application.kt` uses the
            // lifecycle and the module uses the routes.
            implementation(libs.kore.core)
            implementation(libs.kore.ktor)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.sqlx4k.sqlite)
            implementation(libs.okio)
            implementation(libs.mcp.server)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            // koin-ktor publishes for linuxx64 and macosarm64 — checked in Central, not assumed.
            // Without it `by inject<T>()` in routes would be JVM-only and the layer could not move.
            implementation(libs.koin.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.server.testHost)
            implementation(ktorLibs.client.contentNegotiation)
        }
    }
}
