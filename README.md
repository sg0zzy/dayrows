# DayRows 0.1.0

**Your calendars, one simple view.**

DayRows is a native Android calendar viewer. Each calendar occupies a row, each
date occupies a column, and events appear as compact chronological text. Choose
1, 2 or 7 days; move between periods or return to today. Calendar labels stay
visible while the day columns scroll horizontally. In landscape, the title, view
modes, date navigation, Today and Settings share one compact toolbar to leave
more space for the matrix. At very large font sizes or in narrow landscape
windows, the toolbar scrolls horizontally to keep its controls readable.

DayRows is a **view**. It never creates, edits or deletes calendars or events.
It reads Android’s Calendar Provider, including Google calendars and other
calendars already synchronized by apps on the device. DayRows does not implement
synchronization, sign-in, network requests, a backend or telemetry. Locally
available events work offline. The only runtime permission is `READ_CALENDAR`;
there is no `WRITE_CALENDAR` or `INTERNET` permission. AndroidX also declares an
app-private signature permission to secure internal broadcast receivers.

## Getting started

Grant calendar read access when prompted. On the first authorized launch,
Settings opens automatically. Select calendars, optionally assign local labels,
and arrange rows with the arrows or by holding and dragging the ≡ handle.
Changes save automatically in DataStore. Tap Done to return to the matrix.
None of these preferences change the source calendar.

Tap an event to open its occurrence in an installed calendar app. Continued
entries use ↤ / ↦; `24:00` marks an exclusive midnight end. All-day events use
the provider’s UTC date semantics. Recurrences come from `CalendarContract.Instances`.
The device’s current zone determines timed events’ day boundaries, including DST.

## Build and test

Requirements: JDK 17 or newer (validated with 21), Android SDK platform 36,
build-tools 36.0.0, and internet access for the first Gradle/dependency download.
Gradle 8.14.3 is included through the wrapper. AGP 8.13.0, Kotlin 2.2.20 and the
stable Compose 2025.09.01 BOM are pinned for the installed SDK 36 toolchain.
Minimum Android version: 8.0 / API 26. Target SDK: 36.

Set your SDK path, for example:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
# Alternatively, create an untracked local.properties with sdk.dir=/absolute/sdk/path
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug build helper works from any directory and forwards additional Gradle
arguments. It builds the APK and archives a uniquely named copy and JSON manifest:

```bash
./scripts/build-debug.sh
./scripts/build-debug.sh --offline  # after dependencies have been downloaded
```

The standard APK is `app/build/outputs/apk/debug/app-debug.apk`. Archived local
builds are under `dist/local/<version>/<build-id>/`, with source content digest,
Git SHA (null until the initial commit), dirty flag, toolchain and APK checksum.
The script requires Python 3 and Git in addition to the Android build tools.
It never tags, commits, signs a release, uploads or publishes anything.

## Install and hands-on test

Enable USB debugging and connect an Android device (or start an emulator):

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.dayrows.app/.MainActivity
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If multiple devices are connected, add `-s <serial>` to each `adb` command.
Grant access, select real device calendars, test all three day modes, navigation,
labels and ordering, then force-stop/reopen to verify persistence. Verify a
recurring event, an overnight event and a multi-day all-day event. Switch system
dark/light mode, language (English/Italian), and font scale. Change an event in
the source calendar app and return to DayRows to check refresh. Deny/revoke
access to verify the permission screen. No fake data is shown by the app.

Unit coverage includes ordering, independent calendars, recurring occurrences,
all-day UTC dates, midnight and multi-day overlap, exclusive ends, DST (23/25-hour
days), month/year boundaries, empty calendars, title/label fallback, identity and
reused IDs. A debug-only `FakeCalendarRepository` is available for tests/previews;
`MainActivity` always constructs the real provider repository.

## Validation in this workspace

- `./gradlew testDebugUnitTest`: 28 tests passed, no failures or skipped tests.
- `./gradlew lintDebug`: passed with 0 errors and 9 warnings (dependency updates
  and the locale configuration attribute used only on Android 13+).
- `./scripts/build-debug.sh --console=plain`: debug APK built and archived.
- Isolated Android 36 emulator: installed/launched; permission and initial
  Settings flow; real provider discovery; selection, local label and arrow
  reorder; 1/2/7-day matrix; date navigation; recurring, overnight and multi-day
  all-day fixtures; restart persistence; horizontal scrolling; English/Italian
  and light/dark rendering checked. Test calendar fixtures were inserted only
  into this disposable emulator by host-side validation commands, not by DayRows.

## Structure and artwork

- `app/src/main/java/org/dayrows/app/data/`: provider reads, identity, DataStore.
- `app/src/main/java/org/dayrows/app/domain/`: pure date/matrix transformations.
- `app/src/main/java/org/dayrows/app/ui/`: the two Compose screens and theme.
- `app/src/main/java/org/dayrows/app/viewmodel/`: lifecycle, state and refresh.
- `app/src/main/res/values*/`: English fallback and Italian resources.
- `app/src/test/`: local unit tests; `app/src/debug/`: fake repository.
- `scripts/build-debug.sh`: debug build and archive helper.
- `icons/`: source artwork. `main_concept.png` is retained as supplied; the
  launcher assets are generated from the adaptive foreground and Play icon by
  Gradle for both script and direct builds. The script also archives the Play icon
  and feature graphic in `store-artwork/` alongside the APK, with checksums.

## Limitations

- Data freshness depends on the device’s calendar synchronization. Settings
  marks calendars whose synchronization is disabled.
- Calendar identities use account name/type, owner and remote sync ID, falling
  back to the provider’s internal calendar name. Display labels and numeric IDs
  are not sufficient identities. Preferences survive disappearance/reappearance
  when this identity is unchanged; changed account/source identities intentionally
  do not inherit old private labels. Duplicate or incomplete identities use
  session-only preferences, discarded after disappearance or a source change.
  Old reliable preferences are retained locally.
- Dragging works between visible settings rows. The up/down buttons also work
  with accessibility services and can move rows through a long list.
- Event details require a compatible calendar app. DayRows handles a missing
  handler with a message. Work-profile calendars require visibility through the
  current profile’s provider; no cross-profile integration is added.
- This is an early milestone: physical-device and vendor-specific calendar
  provider testing remains necessary. No editing, search, widgets or notifications.

## Versioning and license

`VERSION` is the canonical version. Android `versionName` is derived from it;
`versionCode = major * 1,000,000 + minor * 1,000 + patch - 999`, with minor/patch
bounded to 0…999 and the result restricted to 1…2,100,000,000. Thus 0.1.0 starts
at code 1 and later stable cores increase. Unsupported prerelease versions fail
closed; define a coordinated prerelease allocation before distributing betas.
Before 1.0, a minor version may change the settings format; patch versions aim to
preserve saved preferences. No stable release has been published yet.

Local exceptions to `VERSIONING.md` for this requested milestone: debug APKs keep
the exact requested `0.1.0` display version and code 1, so repeated local builds
are distinguished by the archive build ID, not by an auto-incremented dev version.
They are not official stable releases. Direct Gradle commands use the standard
replaceable Android output path and do not archive each invocation; use the script
for traceable, non-overwriting archives. A dirty source digest covers non-ignored
repository files, not the external SDK, Gradle cache or signing key. There is no
release/publishing workflow in this milestone.

Licensed under **GPL-3.0-or-later**. See [LICENSE](LICENSE).
