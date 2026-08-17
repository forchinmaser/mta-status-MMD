plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Mudita Mindful Design (MMD) — vendored from github.com/mudita/MMD, Apache-2.0.
 * See LICENSE in this folder. Sources are upstream's androidMain set, unmodified,
 * relocated to src/main/java so this builds as a normal Android library.
 *
 * Namespace stays com.mudita.mmd so imports and R references are unchanged; if
 * MMD is ever published to Maven Central, delete this module and swap in
 * implementation("com.mudita:MMD:<version>") with no source changes.
 */
android {
    namespace = "com.mudita.mmd"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Several MMD components (TopAppBarMMD, CardMMD(onClick), FilterChipMMD) are
        // built on Material 3 APIs that are still marked experimental upstream.
        // Opting in once here avoids scattering @OptIn across every call site.
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(bom)
    api("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
