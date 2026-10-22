# Verifying a diagnostic sync request

## Findings before instrumentation

The diagnostic button calls `CalendarDiagnosticsSession.requestSync`, which first
reads CalendarProvider and then invokes `CalendarProviderProbe.sync`. Its shared
coordinator can skip the actual request because there are no complete accounts,
the account/adapter is not visible, settings disable sync, an attempt is throttled,
or existing active/pending work is being joined. A button press therefore never
proved that `ContentResolver.requestSync` was reached.

The existing call constructs `Account(providerAccountName, providerAccountType)`
and uses `CalendarContract.AUTHORITY` (`com.android.calendar`). Its only extra is
`SYNC_EXTRAS_EXPEDITED=true`. Despite the coordinator parameter named `manual`,
it does **not** set `SYNC_EXTRAS_MANUAL`. AOSP classifies this authority-specific
non-forced request without a feed extra as `OTHER`; Google's `USER` history rows
are not the expected source classification for DayRows' current request. No flags
or permissions are changed by this audit.

On the connected Pixel 8a, inspection found Android 17 / SDK 37 with calendar,
sync-settings and sync-stats read permissions granted to DayRows Debug. The
available content dump had no calendar history row at 16:55, no `OTHER` calendar
history rows, and no DayRows Debug package attribution. This confirms the
observed absence, not the cause: history records executed activity, not receipts
for every API call. Pending, deduplicated, deferred or filtered requests need not
produce a new history row.

The previous `requested` message was emitted after a void method returned. It
was not a scheduling acknowledgement. Exceptions from the request were caught
per account and reduced to an unavailable result plus a class-name message;
status errors could become unknown observations until timeout. Existing work
could also end with FINISHED even though no new request was submitted. These
paths now have explicit debug-only evidence without changing release behavior.

## Confirmed 17:26 reproduction and recovery fix

The supplied 17:26:35 Logcat run (`run=13346-1`) proves that neither request
reached the public API: both accounts had `adapterMatches=0`,
`visibleTypeAccounts=0`, `exactVisibleMatches=0`, followed by `apiCalls=0`.
Before (17:25:58) and after (17:27:14) content dumps have identical calendar
counters and last-success timestamps, with only unrelated Gmail/WhatsApp work.
This is a preflight failure, not evidence of a request being lost by SyncManager.

AOSP SyncManager filters adapter discovery by package visibility; ContentService
also checks account and adapter-package access before scheduling. The installed
manifest contained the SyncAdapter intent query, but that did not expose Google's
adapter on this device. Add a scoped `com.google.android.calendar` package query
alongside the generic intent query; this is package visibility, not a permission
or Google API integration. Keep the existing extras and sync settings policy.

Missing account access now takes priority over an empty adapter list. The normal
account chooser no longer filters its candidates through adapter discovery.
Diagnostics show **Allow account sync** after ACCESS_REQUIRED; it opens the native
chooser using accounts from the raw snapshot, with no adapter prerequisite.
Select one account, return and press **Request Sync** again. Repeat for each
account. Selection is explicit; diagnostic requests never launch a chooser or
retry automatically. No sign-in, OAuth or additional permission is introduced.

Re-run the capture below after updating the APK. First expect account recovery;
after granting access expect positive exact-account/adapter matches, SETTINGS,
API_CALL_ENTER and API_CALL_RETURN. Only observed pending/active transitions or
system history establish scheduling/execution. Metadata refreshing through the
standard adapter still requires the real-device color/new-calendar test; fixing
preflight alone does not prove Google's adapter downloads those changes.

## New trace

Open **DayRows Debug → Settings → Developer Diagnostics**. The bounded on-screen
journal and its redacted clipboard report are also emitted at INFO under
`DayRowsSync`; no log property change is required. Everything is in `src/debug`.
There is no diagnostic receiver, service or monitoring while the module is closed.

| Marker | Evidence and limit |
| --- | --- |
| `REQUEST_SYNC_ACTION` | The button handler entered; the API has not yet been called. A failed before-snapshot can stop here. |
| `RUN_START`, `APP_CONTEXT` | Run ID, package, caller UID/PID, device SDK, target SDK, version, and standard authority. |
| `PERMISSIONS` | Actual app permission checks. `writeSyncSettings=false` is expected: no settings-bypass or periodic request is made. |
| `TARGET` | Distinct account index, matching provider row IDs, whether its type is exactly `com.google`, and edge-whitespace flags. On-device snapshots display the raw account name/type for local comparison; exports do not. |
| `PREFLIGHT` | Matching visible adapter count, visible accounts of the target type, and exact name/type account-match count. |
| `SKIP_API` | Explicit preflight reason. No new request is submitted on this path. |
| `ACCOUNT_OBJECT`, `API_ARGUMENTS` | The constructed Account matches the provider name/type; actual authority and Boolean extras are recorded. No account name or custom account type is logged. |
| `API_CALL_ENTER` | Immediately precedes the actual public API invocation. |
| `API_CALL_RETURN` | The void API returned without throwing. Scheduling and delivery remain **unconfirmed**. |
| `API_CALL_THROW`, `PREFLIGHT_THROW`, `STATUS_THROW` | Exception class, bounded stack locations, cause classes and allowlisted permission mentions. Exception messages are omitted because they can contain account data. |
| `OBSERVED` | Initial/changed account-level active/pending flags, with before/after-call phase. Unchanged polling values are not repeated. |
| `PENDING_OBSERVED` | Queued work observed after the API returned; it is account-level evidence, not proof the work belongs to this request. |
| `ACTIVE_OBSERVED`, `ACTIVITY_ENDED` | Account activity started/ended after the API returned; remote outcome and request ownership remain unknown. |
| `RUN_RESULT`, `RUN_EVIDENCE` | Separate API enter/return/failure counts and post-call scheduling/activity evidence. |
| `NO_API_CALL` | No new public call occurred, even if an existing sync finished. |
| `DELIVERY_UNCONFIRMED` | A call returned but no pending/active work was observed. Check system state; do not report this as successful scheduling. |

Runs use `run=<pid>-<counter>`. Account indices refer to distinct accounts in the
fresh before-request snapshot, in provider-ID order. Correlate them locally with
the raw snapshot/provider IDs; they are not persistent account identifiers.

## Exact ADB procedure

Keep Google Calendar closed and leave the diagnostic dialog foreground for the
whole 30-second window. Normal DayRows resume reads only CalendarProvider;
the `DayRowsSync` run markers identify the diagnostic action. First verify
the installed variant and device time (Logcat/system dumps may use local time;
the journal embeds UTC timestamps):

```bash
adb shell date '+%Y-%m-%dT%H:%M:%S%z'
adb shell am get-current-user
adb shell dumpsys package it.stefanogozzi.dayrows.debug > /tmp/dayrows-package.txt
adb shell dumpsys content > /tmp/dayrows-content-before.txt
```

In a second terminal, start capture **before tapping Request Sync**:

```bash
adb logcat -v threadtime -s DayRowsSync:I ContentService:V SyncManager:V '*:S'
```

No buffer clearing or system-property changes are needed. System tags may be
silent on a stock Pixel; they are supplementary, not an acknowledgement channel.
Tap the enabled diagnostic **Request Sync** button once. While it is monitoring,
use the first terminal to capture pending/active work, then capture again after
the window finishes:

```bash
adb shell dumpsys content > /tmp/dayrows-content-during.txt
adb shell dumpsys jobscheduler > /tmp/dayrows-jobs-during.txt
adb shell dumpsys content > /tmp/dayrows-content-after.txt
```

Run the last command after completion/timeout, not immediately after the first
two. Inspect the snapshots locally:

```bash
rg -n 'Active Syncs|Pending Syncs|Recent Sync History|com.android.calendar|it.stefanogozzi.dayrows.debug|OTHER' /tmp/dayrows-content-before.txt /tmp/dayrows-content-during.txt /tmp/dayrows-content-after.txt
rg -n 'com.android.calendar|SyncJobService|it.stefanogozzi.dayrows.debug' /tmp/dayrows-jobs-during.txt
```

Compare the exact account (visible only in local dumps/screen), authority, time,
source and reason/calling UID/package when the device prints them. Use the UID
from `RUN_START` as well as the package name: SyncManager can render the request
reason as a UID or package. A scheduled job is normally owned by the **sync
adapter's package**, not by DayRows; searching only for a DayRows-owned job is
insufficient. Do not filter exclusively for `USER`: look for `OTHER` and pending
work. The public API returns no request/job ID to correlate conclusively.

Interpret the result in order:

1. No handler marker: verify the new debug APK, enabled button and Logcat filter.
2. Handler but no `API_CALL_ENTER`: inspect snapshot errors, `SKIP_API`,
   coordinator throttling/join messages and `NO_API_CALL`. If exact visible
   matches are zero, use the existing Settings account-access chooser; adding
   `READ_CALENDAR` again cannot grant account visibility.
3. `API_CALL_THROW`: inspect its class/stack/permission details. This is not a
   successful request return. Remaining accounts are still attempted.
4. `API_CALL_RETURN` with pending work/reason matching DayRows: scheduling has
   supporting system evidence, although history may remain empty until execution.
5. `API_CALL_RETURN` with no observable/system evidence: delivery is unconfirmed.
   ContentService can return early for account/authority access restrictions;
   scheduler deduplication, backoff, standby quotas or other deferral can also
   explain no new history. A snapshot of history alone cannot distinguish these.
6. A matching started/history entry proves execution. Ended activity and an idle
   API observation alone do not prove remote success or a metadata download.

Finally run Google Calendar's manual Refresh and capture another `dumpsys content`
file for comparison. Compare scopes/source/reason; do not copy its feed or other
private extras into DayRows. The dumps can contain account names, calendar IDs
and URLs: keep them local. The diagnostic clipboard export is the redacted form.

## Public API limitations and sources

- [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver)
  defines the asynchronous, void request API and public sync flags.
- [AOSP ContentService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/content/ContentService.java)
  checks account/authority access before scheduling and can return early without
  notifying the app that no operation was scheduled.
- [AOSP SyncManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/content/SyncManager.java)
  classifies sources and schedules/deduplicates operations. These inspected AOSP
  paths explain possibilities, not a claim of exact source parity with the Pixel
  build or proof of the particular rejection at 16:55.

No undocumented Google extras, new permissions, production sync changes, version
bump or commits are part of this instrumentation.
