// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val appVersion = rootProject.file("VERSION").readText().trim()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(appVersion)) { "VERSION must be a stable X.Y.Z core for milestone builds" }
val versionParts = appVersion.split('.').map(String::toLong)
require(versionParts[0] <= 2100 && versionParts[1] <= 999 && versionParts[2] <= 999)
// Baseline 0.1.0 = 1. Each core reserves 1,000 patch values; no pre-release distribution yet.
val androidCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2] - 999
require(androidCode in 1..2_100_000_000) { "Version is outside the Android code allocation" }

android {
    namespace = "org.dayrows.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.dayrows.app"
        minSdk = 26
        targetSdk = 36
        versionCode = androidCode.toInt()
        versionName = appVersion
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
