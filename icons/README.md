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

The app bar title uses the long lockup `dayrows-feature-1024x500_long.png`
(1013x333). It is not packaged as-is: a 993 KiB bitmap in a 56 dp slot would be
waste, so it is downscaled once into density buckets and stored as WebP at
`app/src/main/res/drawable-{h,xh,xxh,xxxh}dpi/title_banner.webp` (39 KiB total).
The files are committed rather than generated because nothing else derives from
them. Regenerate after replacing the source, keeping the 56 dp display height so
the widths stay 1013:333:

```sh
python3 - <<'PY'
from PIL import Image; import pathlib
src = Image.open('icons/dayrows-feature-1024x500_long.png').convert('RGB')
for bucket, scale in (("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
    h = round(56 * scale); w = round(h * src.width / src.height)
    src.resize((w, h), Image.LANCZOS).save(
        pathlib.Path(f"app/src/main/res/drawable-{bucket}/title_banner.webp"),
        quality=90, method=6)
PY
```

The debug build script archives the Play icon and feature graphic in
`store-artwork/` alongside the APK, recording their checksums and the launcher
input checksums in `build-manifest.json`. The feature graphic and design reference
are not app resources; only the long lockup is, through the derived WebP above.
To update the launcher, replace the corresponding source
PNGs here with the same dimensions and rebuild.
