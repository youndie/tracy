plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(wip.plugins.composeMultiplatform)
    alias(wip.plugins.composeCompiler)
}

// `-Ptracy.agent=false` builds the same app with the telemetry calls compiled to nothing and the agent
// absent — the other half of the measurement in MEASUREMENT.md.
val withAgent = providers.gradleProperty("tracy.agent").orNull != "false"

kotlin {
    jvm("desktop")
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(if (withAgent) "src/withAgent/kotlin" else "src/withoutAgent/kotlin")
            dependencies {
                implementation(wip.compose.runtime)
                implementation(wip.compose.foundation)
                implementation(wip.compose.ui)
                if (withAgent) implementation("io.github.youndie.tracy:agent")
            }
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.youndie.tracy.sample.MainKt"
    }
}
