// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Copy the supplied artwork unchanged; Gradle tracks both files as build inputs.
abstract class PrepareLauncherIcons : DefaultTask() {
    @get:InputFile
    abstract val adaptiveForeground: RegularFileProperty
    @get:InputFile
    abstract val playIcon: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        val foreground = destination.resolve("mipmap-xxxhdpi/ic_launcher_foreground.png")
        val legacy = destination.resolve("mipmap-nodpi/ic_launcher.png")
        foreground.parentFile.mkdirs()
        legacy.parentFile.mkdirs()
        adaptiveForeground.get().asFile.copyTo(foreground)
        playIcon.get().asFile.copyTo(legacy)
    }
}
val prepareLauncherIcons = tasks.register<PrepareLauncherIcons>("prepareLauncherIcons") {
    adaptiveForeground.set(rootProject.layout.projectDirectory.file("icons/dayrows-adaptive-foreground-432.png"))
    playIcon.set(rootProject.layout.projectDirectory.file("icons/dayrows-play-icon-512.png"))
    outputDirectory.set(layout.buildDirectory.dir("generated/launcherIcons/res"))
}
// Package the canonical legal files so Settings can show them without a network connection.
abstract class PrepareLegalDocuments : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val legal = root.resolve("legal").also { it.mkdirs() }
        documents.files.forEach { it.copyTo(legal.resolve(it.name)) }
    }
}
val prepareLegalDocuments = tasks.register<PrepareLegalDocuments>("prepareLegalDocuments") {
    documents.from(rootProject.files("PRIVACY.md", "TERMS.md", "LICENSE"))
    outputDirectory.set(layout.buildDirectory.dir("generated/legalAssets"))
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(prepareLauncherIcons) { it.outputDirectory }
        variant.sources.assets?.addGeneratedSourceDirectory(prepareLegalDocuments) { it.outputDirectory }
    }
}

val appVersion = rootProject.file("VERSION").readText().trim()
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(appVersion)) { "VERSION must be a canonical X.Y.Z core; see VERSIONING.md" }
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
