# Calendar synchronization and device validation

DayRows reads Android's CalendarProvider. Each calendar query reads
`Calendars.CALENDAR_COLOR`, names, access/visibility/sync flags and source
identities. The matrix uses the resulting `DeviceCalendar.color`; colors are
not saved in DataStore. Preferences retain selection, labels and order only.
The repository retains a previous metadata snapshot solely for debug comparisons;
queries always return fresh provider rows.

## Public API behavior

Startup/resume reads locally available events and metadata from CalendarProvider
without submitting account sync requests, waiting for adapters or asking for
account access. Provider notifications continue to update DayRows as Android's
normal account synchronization delivers changes.

**Refresh events** in the main toolbar and **Refresh calendars** in Settings
explicitly request account synchronization through one coroutine operation.
It derives distinct account name/type
pairs from readable provider calendars, including secondary/shared calendars and
multiple Google accounts. It checks visibility using AccountManager's filtered
`getAccountsByType` API, available without `GET_ACCOUNTS` on supported API 26+.
It does not request calendar write access, Internet access or OAuth credentials.
Missing account visibility is reported separately from disabled sync.
Both Reload buttons check source accounts directly in CalendarProvider
and opens Android's native account chooser when access is missing. On selection,
it continues the explicit sync and calendar discovery. Cancellation keeps the local
display and submits no sync from this action. The optional
**Allow account synchronization** button also opens the native account chooser,
limited to provider accounts still needing visibility. Choose an existing account;
repeat for additional accounts. Canceling preserves local display. The OS retains
the visibility grant; DayRows does not store a second account configuration.
A manifest `<queries>` intent for `android.content.SyncAdapter`, plus a scoped
`com.google.android.calendar` package query, exposes adapter packages to sync APIs
without `QUERY_ALL_PACKAGES` or another permission.
Calendar permission must be granted before any sync operation starts.

`ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)`
delegates work to the account's existing calendar adapter. User requests set
`SYNC_EXTRAS_EXPEDITED`. DayRows does not set `SYNC_EXTRAS_MANUAL`, which would
bypass disabled sync settings and backoff.
`READ_SYNC_SETTINGS` permits master/account/syncable checks;
`READ_SYNC_STATS` permits `isSyncActive` and `isSyncPending`. The former
`WRITE_SYNC_SETTINGS` permission is no longer needed and has been removed.
No sync settings or provider records are changed by DayRows.
Settings Reload and Developer Diagnostics share the same request-submission
function and extras builder. Normal sync account discovery reads raw provider
account columns, independent of DayRows selection and display-access filters;
diagnostics derives the same accounts from its raw before-snapshot.

One job joins existing active/pending account syncs and prevents overlapping
DayRows requests. Explicit requests have a ten-second minimum interval within the
repository/ViewModel lifetime. Opening or resuming the app never submits one.
If no
operation was requested because account visibility is missing, the throttle is
released so granting access can be followed immediately by an actual sync.

Queries/status checks run on `Dispatchers.IO`. Activity is sampled every 500 ms
for at most 30 seconds. Observed active → inactive with no pending work counts as
activity completion, never proof of a successful remote download. Idle without
an observed start, a very fast sync, offline pending work or visibility-filtered
status uses the timeout fallback. Failures are isolated per account. A hidden or
removed account may reject a request, appear disabled, or return unobservable
status. DayRows offers the native chooser only on an explicit button tap; it
never treats an empty status as successful synchronization.

During this bounded wait, provider-change notifications are coalesced. On
completion, unavailability or timeout, the ViewModel re-queries metadata and
events. Initial display can read local data immediately; it does not wait for
the network. Existing events remain visible during refresh and on query failure.
Later provider notifications still refresh the view without requesting another
sync. Navigation, selection and other local controls remain usable. Permission
revocation stops observation and clears the calendar view. DayRows never cancels
the account's sync, schedules retries, or starts a sync from a provider notification.

Public references:

- [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver)
  documents asynchronous requests, sync settings, activity and pending state.
- [Calendar color columns](https://developer.android.com/reference/android/provider/CalendarContract.CalendarColumns)
  defines `CALENDAR_COLOR` and the provider-managed color-key mapping.

Google Calendar's proprietary Reload implementation is not a documented Android
contract. It may perform additional metadata or calendar-list operations; this
is a limitation/inference, not a verified description of Google's internals.
The public API offers no documented flag guaranteeing a full Google calendar
metadata reload. No private intent, adapter-specific undocumented extra, direct
REST call or second authentication flow is used.

## Real-device test

Use a disposable test calendar with a shared subscription and note the Android
version, Google Calendar version, DayRows build, network and account sync state.
Keep Google Calendar closed throughout steps 1–6. A debug build has independent
preferences from the production app; select the test calendars in that build.

1. Open DayRows, select the shared calendar, and note its color, label and row
   order. Allow any initial sync to settle.
2. Change that calendar's color in Google Calendar web or another device using
   the same subscribing Google account. Color can be a per-account preference;
   changing the owner's color is not necessarily changing the subscriber's color.
   Also change a test event to distinguish event sync from metadata sync.
3. Tap **Refresh events** without opening Google Calendar on Android. Verify
   the waiting indicator, continued availability of local data and eventual
   updated event/color. Allow up to 30 seconds for DayRows' bounded wait; Android
   may deliver data later. Record results separately for event and color.
4. Repeat via **Settings → Refresh calendars**. Check that selections, local
   labels, row order and provider visibility are preserved. Repeat with two
   Google accounts; each eligible account should be requested only once.
5. Make another remote change and restart/resume DayRows. It should immediately
   read local data without requesting account synchronization or showing a sync
   wait/account chooser. Let Android deliver an ordinary event update and verify
   it appears through provider notifications. Explicitly reload to request remote
   metadata. Repeated toolbar taps must not submit overlapping work. Check both
   portrait and landscape.
6. Go offline and refresh. Existing rows/events should remain usable, with a
   timeout message if completion is not observable and no DayRows retry loop.
   Reconnect and refresh explicitly; later provider updates should also arrive
   automatically. Disable account sync (then master sync separately) and refresh:
   disabled accounts must be skipped while other enabled accounts still work.
   Restore the test settings afterwards. Also revoke calendar permission and
   confirm the permission screen; regrant it and confirm local display recovers.

If the event updates but the color stays stale, collect diagnostics before
opening Google Calendar. Then use Google Calendar's Reload as a comparison.
Record whether the provider-color and DayRows-color logs change only after that
action. A completed adapter operation with unchanged provider metadata is a
provider limitation, not evidence of a DayRows color cache. A timeout alone
cannot establish that limitation.

## Debug diagnostics

Enable on the test device:

```bash
adb shell setprop log.tag.DayRowsCalendars DEBUG
adb logcat -s DayRowsCalendars:D
```

Messages distinguish `requested`, `started`, `completed activity`, `timed out`,
disabled/restricted accounts and throttled attempts. Account numbers refer to
the current discovery order, not persistent identities. `calendar metadata
changed`, `provider color changed: id=… old -> new` and `DayRows colors updated`
separate provider changes from application-state updates. Logs omit account
names, calendar names, event titles and source identities. Existing discovery
logs contain provider row IDs, colors and flags only. Disable when finished:

```bash
adb shell setprop log.tag.DayRowsCalendars INFO
```

If standard sync repeatedly finishes without updating provider colors, retain
the before/after evidence and compare with Android account-level **Sync now**
and Google Calendar Reload. Investigate with the calendar adapter/provider
maintainer. The supported fallback is the owning calendar app or Android sync
settings; do not add private sync extras, permissions or OAuth without a separate
design and authorization. Remote color propagation has not been validated by
local unit tests or compilation; the device procedure remains required.

## Follow-up device report — 22 October 2026

The tester reports that Google Calendar displays the new color while DayRows
still displays the old one; invoking Reload inside Google Calendar then updates
DayRows. This is consistent with a delayed CalendarProvider metadata update,
but does not establish that DayRows' requested adapter operation ran or completed.
It remains an unresolved color-propagation test, not a successful validation.

Read-only package inspection on the connected device found two installations:
`it.stefanogozzi.dayrows.debug` was updated on 22 October and has both sync-read
permissions granted; `it.stefanogozzi.dayrows` was last updated on 20 October.
Confirm **DayRows Debug** was tested before attributing the result to the new
implementation. No matching sync diagnostics were present in the device log
buffer during this inspection. Next evidence needed: tested app variant and
the result message after its refresh, then a reproduction with diagnostics if
the updated build is confirmed. No account contents were queried or changed.

The subsequent report was **Some calendars could not be synchronized**. Device
inspection confirmed READ_CALENDAR, READ_SYNC_SETTINGS and READ_SYNC_STATS were
granted. A diagnostic startup logged both provider accounts being skipped before
requestSync. Android's ContentService source confirms that an account hidden
from the caller can make getSyncAutomatically return false, and an invisible
adapter package can likewise filter the settings/adapter results. The fix
separates missing account visibility and declares scoped adapter visibility.
Retest after choosing each existing account in DayRows' Android account chooser;
verify requested/started diagnostics before drawing conclusions about colors.
Cancel and repeated-choice cases must preserve preferences and local display.

References for the visibility correction:

- [Android AccountManager](https://developer.android.com/reference/android/accounts/AccountManager)
  documents filtered accounts and chooser-granted visibility.
- [AOSP ContentService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/content/ContentService.java)
  implements account/authority visibility checks before returning sync settings.
- [AOSP SyncManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/content/SyncManager.java)
  filters sync adapters using package visibility.
