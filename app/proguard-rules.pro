# SPDX-License-Identifier: GPL-3.0-or-later
# DayRows R8 keep rules. The app is plain Kotlin/Compose with direct Android
# framework/Calendar-Provider calls and DataStore preferences; everything here
# protects reflective or framework-bound entry points that R8 cannot infer.

# Compose runtime/compiler already ships consumer rules; the entries below are
# belt-and-braces for the app's own reflective surfaces.

# DataStore: the preferences framework touches generated serializers by name in
# some configurations, and crash-report deobfuscation of DataStore internals
# benefits from stable names.
-keep class androidx.datastore.** { *; }

# Kotlinx coroutines internal debug probes referenced via reflection in
# kotlinx-coroutines-core.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Compose + Material ship their own rules; keep the app entry activity, which is
# declared in the manifest by name and resolved reflectively.
-keep class it.stefanogozzi.dayrows.MainActivity { *; }

# Preserve source-file metadata and line numbers mapped by mapping.txt; without
# them Play's retrace cannot localize deobfuscated frames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Enum values are looked up reflectively by some AndroidX helpers (valueOf,
# values) — R8 keeps them by default, but assert it explicitly:
-keepclassmembers enum it.stefanogozzi.dayrows.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}