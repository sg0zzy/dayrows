# DayRows

**Your calendars, one simple view.**

Native Android · Android 8.0+ (API 26) · Application ID: `it.stefanogozzi.dayrows` ·
GPL-3.0-or-later

[Privacy policy / Informativa sulla privacy](PRIVACY.md) ·
[Terms of use / Condizioni d’uso](TERMS.md) · [GNU GPLv3](LICENSE) ·
[Source project](https://github.com/sg0zzy/dayrows)

DayRows is a native Android calendar viewer. Each calendar occupies a row, each
date occupies a column, and events appear as compact chronological text. Choose
1, 2 or 7 days; move between periods or return to today. Calendar labels stay
visible while the day columns scroll horizontally. In landscape, the title, view
modes, date navigation, Today and Settings share one compact toolbar to leave
more space for the matrix. At very large font sizes or in narrow landscape
windows, the toolbar scrolls horizontally to keep its controls readable.
Day headers use a single compact line, such as “Fri 16 Oct” or “ven 16 ott”.

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

English is the fallback language; Italian translations are included. Android
selects the device language unless you set a per-app language override (Android
13+). Calendar names and event titles retain their source text.

Privacy, terms, and the full GPLv3 are available offline at the end of Settings.
The permission explanation also provides the privacy policy before you grant
calendar access. English and Italian privacy/terms are included in the same files.

Tap an event to open its occurrence in an installed calendar app. Continued
entries use ↤ / ↦; `24:00` marks an exclusive midnight end. All-day events use
the provider’s UTC date semantics. Recurrences come from `CalendarContract.Instances`.
The device’s current zone determines timed events’ day boundaries, including DST.

## Development and builds

[DEVELOPMENT_STANDARDS.md](DEVELOPMENT_STANDARDS.md) records the pinned central
policy and partial adoption status. [Development operations](docs/DEVELOPMENT.md)
contains toolchain prerequisites, build/signing matrices, source metadata,
retention/protection commands, CI behavior and release readiness.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./scripts/build-debug.sh testDebugUnitTest lintDebug --no-daemon --console=plain
python3 -m unittest discover -s scripts/tests -v
```

Managed debug APKs, verified manifests and store artwork are archived under
`local_artifacts/<unique-build-id>/`. The default retains the newest 10 successful
unprotected builds. Use `python3 scripts/artifacts.py protect <build-id>` to keep
one. Existing `dist/local/` archives are preserved outside automatic cleanup.

The [Build debug APK workflow](.github/workflows/build-apk.yml) runs on main pushes,
PRs to main and manual dispatch. It runs infrastructure tests, Android unit tests
and lint, then uploads verified test-only packages and reports as temporary Actions
artifacts for 14 days. Production signing and official beta/stable publication are
not configured; see the [migration report](docs/STANDARDS_MIGRATION.md) for gaps.

## Install and hands-on test

Enable USB debugging and connect an Android device (or start an emulator):

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n it.stefanogozzi.dayrows.debug/.MainActivity
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The debug build installs as the separate package `it.stefanogozzi.dayrows.debug`
(label "DayRows Debug"), so it can be installed beside a production
`it.stefanogozzi.dayrows` release without uninstalling either.

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

## Earlier application validation

- `./gradlew testDebugUnitTest`: 28 tests passed, no failures or skipped tests.
- `./gradlew lintDebug`: passed with 0 errors. Existing warnings concern dependency
  updates, newer-platform attributes, and launcher icon recommendations.
- The previous build helper produced a debug APK and archive; current infrastructure
  validation is recorded in [the migration report](docs/STANDARDS_MIGRATION.md).
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
- `.github/workflows/build-apk.yml`: automatic/manual debug APK checks and artifact upload.
- `.github/workflows/play-upload.yml`: manually upload verified release AAB,
  native symbols and R8 mapping to a Google Play draft; [setup and usage](docs/GOOGLE_PLAY_UPLOAD.md).
- `icons/`: source artwork. `main_concept.png` is retained as supplied; the
  launcher assets are generated from the adaptive foreground and Play icon by
  Gradle for both script and direct builds. The script also archives the Play icon
  and feature graphic in `store-artwork/` alongside the APK, with checksums.

## Limitations

Debug builds include **Settings → Developer Diagnostics** for raw CalendarProvider
snapshots, sync tracing, color/calendar comparisons and redacted clipboard reports.
See [Developer Diagnostics](docs/DEVELOPER_DIAGNOSTICS.md) for the Google Calendar
Reload investigation and extension guide. Release builds exclude the implementation.

- Data freshness depends on the device’s calendar synchronization. The main view’s
  *Refresh events* button (in portrait and landscape), opening the app, retrying
  after an error and *Refresh calendars* ask the owning account
  apps to synchronize now through the platform sync framework; DayRows itself
  still performs no synchronization and no network request, and the system
  decides when a requested sync actually runs. Settings re-reads the calendars
  registered on the device when *Refresh calendars* is tapped or the screen is
  opened, and marks calendars whose synchronization is disabled. A calendar the
  account has not yet synchronized is not registered and appears only after
  that app has synchronized it.
  DayRows waits up to 30 seconds for observed sync activity to finish before
  reloading events and metadata, including colors. Startup/resume only reads
  local CalendarProvider data; account synchronization is requested by the Reload
  buttons. Explicit refresh has a ten-second minimum interval.
  Disabled synchronization is respected and
  unavailable/unfinished syncs leave local data usable. If account access is
  missing, *Allow account synchronization* opens Android’s account chooser for
  an existing calendar account; repeat for each account. No OAuth is required.
  Adapter completion does
  not prove that Google's adapter fetched calendar colors. See the device test
  and diagnostic procedure in [calendar synchronization](docs/CALENDAR_SYNC.md).
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

## Publication documents and release preparation

| Document | Purpose |
| --- | --- |
| [PRIVACY.md](PRIVACY.md) | English/Italian privacy policy covering calendar access, stored account identities, retention/deletion, permission choices, and event-opening requests. |
| [TERMS.md](TERMS.md) | English/Italian usage information, provider limitations, and warranty terms that preserve GPL rights and mandatory consumer rights. |
| [LICENSE](LICENSE) | Full, unmodified GNU GPL version 3 text. The project grants version 3 or any later version. |

Gradle packages the canonical privacy, terms, and license files into every build;
Settings opens these local copies in scrollable dialogs. There is no third app
screen, consent gate, or network dependency for reading the documents.

The repository currently identifies the privacy/support contact as the DayRows
project issue tracker: https://github.com/sg0zzy/dayrows/issues. It must be public
and usable when the app is distributed, or the policy must name another active
contact mechanism. Never post private calendars or account addresses in public
issues. No personal contact email is added to these documents.

Before submitting a release:

1. Publish the privacy policy at a stable, publicly accessible HTTPS URL, reachable
   without sign-in or geographic restrictions. A rendered web page is suitable;
   do not use a private repository link or PDF. Publish these documents and make
   the stated contact mechanism available before entering that URL in Play Console.
2. Verify that the publisher/store listing identifies DayRows consistently with
   the policy. Complete Play Console's Data safety form for the actual shipped
   binary and its dependencies. The current app processes calendar data only on
   the device; it has no developer-operated data collection or telemetry. Opening
   an event is an explicit user-initiated transfer to another calendar app, as
   disclosed in the privacy policy. On-device access is still sensitive access
   even where it is outside Play's definition of off-device collection.
3. Make the corresponding source, build instructions, GPL license, and required
   notices available for the exact distributed version. Preserve third-party
   licenses/notices and verify rights to all distributed artwork. The project
   GPL does not relicense third-party components or the GNU license document.
4. Prepare a signed **release** Android App Bundle using your own protected upload
   key and Play App Signing as appropriate. Release signing is not configured in
   this milestone; debug APKs are for hands-on testing. After setting up signing
   outside tracked files, `./gradlew bundleRelease` produces
   `app/build/outputs/bundle/release/app-release.aab`. Never commit signing keys,
   passwords, or generated packages.
5. Check current target API and store requirements, complete the content rating,
   target audience, ads (none), app access (no account required), and permission
   declarations, and test the signed build on physical devices. Recheck version
   code allocation against any already distributed release before submission.

Preparing these files does not publish the app or establish store approval.
Public hosting, store configuration, source distribution, and release signing
remain publisher actions; no release tag, upload, or publication is automatic.

References: [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311),
[Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469),
and [GNU licensing guidance](https://www.gnu.org/licenses/gpl-howto.html).

## Versioning and license

`VERSION` is authoritative; Android fields and existing test-build compatibility
are documented in [VERSIONING.md](VERSIONING.md). Central policy, recommendation
departures and unresolved release/data-recovery requirements are recorded in
[DEVELOPMENT_STANDARDS.md](DEVELOPMENT_STANDARDS.md).

Copyright © 2026 DayRows contributors. DayRows is free software: you may
redistribute and modify it under the GNU General Public License, version 3 or,
at your option, any later version. It is distributed without warranty, including
implied warranties of merchantability or fitness, to the extent permitted by law.
See [LICENSE](LICENSE) for the full terms. Third-party components retain their
respective licenses; contributors retain their copyrights.
