pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "TransitKompakt"

// Mudita Mindful Design, vendored from github.com/mudita/MMD (Apache-2.0).
// Upstream is a Kotlin Multiplatform library with an androidMain-only source set
// and no published Maven artifact; here it is built as a plain Android library so
// the app needs no KMP / compose-multiplatform / maven-publish plugins.
include(":mmd-core")
include(":app")
