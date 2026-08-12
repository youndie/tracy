plugins {
    kotlin("multiplatform")
    alias(libs.plugins.pluginSerialization)
}

kotlin {
    jvm()
    jvmToolchain(21)

    listOf(
        macosArm64(),
        linuxX64(),
        linuxArm64(),
    ).forEach { target ->
        target.binaries.executable {
            entryPoint = "ru.workinprogress.tracy.server.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.di)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.sqlx4k.sqlite)
            implementation(libs.okio)
            implementation(libs.mcp.server)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.server.testHost)
            implementation(ktorLibs.client.contentNegotiation)
        }
    }
}
