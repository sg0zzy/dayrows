# DayRows artwork

These source files are used unchanged:

- `dayrows-adaptive-foreground-432.png`: transparent adaptive launcher foreground,
  packaged at xxxhdpi (108 dp) over a white background.
- `dayrows-play-icon-512.png`: base launcher icon and Play listing icon.
- `dayrows-feature-1024x500.png`: store listing feature graphic.
- `main_concept.png`: original design reference.

Gradle's `prepareLauncherIcons` task copies the launcher PNGs to generated Android
resources under `app/build/generated/launcherIcons/res/`. It runs automatically for
both `./gradlew assembleDebug` and `./scripts/build-debug.sh`, and reruns when either
launcher source changes. Adaptive launcher XML is in
`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.

The debug build script archives the Play icon and feature graphic in
`store-artwork/` alongside the APK, recording their checksums and the launcher
input checksums in `build-manifest.json`. The feature graphic and design reference
are not app resources. To update the launcher, replace the corresponding source
PNGs here with the same dimensions and rebuild.
