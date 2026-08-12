import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.pluginSerialization) apply false
    alias(libs.plugins.ktlintPlugin)
}

// Read here: the `libs` accessor is not visible inside subprojects { }.
val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    version = libVersion()
    group = "ru.workinprogress.tracy"

    configure<KtlintExtension> {
        version.set(ktlintVersion)
    }
}

fun Project.libVersion(): String = findProperty("VERSION")?.toString() ?: ("0.1." + (findProperty("BUILD_NUMBER") ?: "snapshot"))
