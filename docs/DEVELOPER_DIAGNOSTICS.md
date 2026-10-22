# Developer Diagnostics

For missing SyncManager history, use the [request audit and exact ADB procedure](SYNC_REQUEST_AUDIT.md).

Debug builds expose **Settings → Developer Diagnostics** (Italian:
**Diagnostica sviluppatore**). Release builds contain only an empty variant
integration stub: no diagnostic screen, probe, actions or resources. The
Settings entry is also guarded by `BuildConfig.DEBUG`. Existing calendar debug
logging and its metadata comparison state now live in `src/debug`; the release
implementations are no-ops even when the Android log property is enabled.

## CalendarProvider module

- **Read Provider** and **Refresh Snapshot** both make a new raw query to
  `CalendarContract.Calendars.CONTENT_URI` on IO, without requesting sync.
  They bypass the application repository, identity resolver, preferences and
  diagnostic snapshots. There are no application filters; raw nulls, deleted
  rows and visibility/sync flags are retained. No events are queried.
- Each row displays its provider ID, account name/type, display name, ARGB color
  and swatch, visibility, sync-events, access level and deleted flag. The DayRows
  color is captured from application state at the start of each query, so the
  provider and application observations can be distinguished. An absent row in
  DayRows can also be caused by its normal access/deletion filtering.
- **Request Sync** takes a fresh snapshot, derives distinct accounts, checks
  permissions, adapter/account visibility and sync settings, then calls the
  public sync API using the existing expedited-request semantics. It joins
  active/pending adapter work and observes activity for at most 30 seconds. It
  takes a second snapshot after the result, including timeout/unavailability.
  Requested, started, activity-ended, errors, missing access, disabled settings
  and timeout are separate trace entries. Activity ending never claims remote
  download success. A very fast or unobservable operation can use the timeout
  path. Repeated status errors are recorded once per account per attempt.
- **Compare Snapshots** compares the selected Before/After pair and highlights
  added, removed and modified rows, including old/new colors. Null and zero/false
  are distinct. Provider ID is the comparison key; a reused ID with different
  metadata is marked modified.
- **Copy redacted results** copies the retained snapshots, consecutive snapshot
  comparisons, selected comparison and timestamped trace. The allowlist excludes
  account names/types, calendar names, event contents, identities and exception
  messages. Exported evidence includes numeric provider IDs, colors, flags,
  changed-field names and exception classes. The clipboard preview is marked
  sensitive. Clipboard storage is managed by Android after the explicit copy.

The module retains only five snapshots and 200 trace entries (300 characters per
entry) in memory. Nothing is saved to disk or uploaded. Timestamps use UTC.
Closing the dialog drops the session. Leaving the foreground stops its operation
and polling, but keeps snapshots while the dialog remains composed; returning
does not automatically read or sync. Rotation/process recreation may discard the
session. Stop/close never cancels the account's adapter work, which Android owns.
There is no diagnostic ContentObserver, worker, service or automatic retry.
Normal app resume only reads CalendarProvider; it submits no additional sync
request when returning from Google Calendar or the account chooser.

## Investigating colors and newly created calendars

Use the debug app and a disposable test calendar; release and debug preferences
are independent. Grant calendar permission and, when needed, existing account
visibility using DayRows' existing account chooser before opening diagnostics.

1. Open Developer Diagnostics and tap **Read Provider**. Note snapshot #1 and
   the provider/application colors. Change a shared calendar's color remotely
   using the same subscribing account, and optionally create a calendar there.
2. Tap **Request Sync**. It records a fresh before snapshot and an after snapshot
   once activity ends or the wait times out. Keep the screen foreground until
   the operation settles. Inspect permission/eligibility and request/start/end
   entries before assuming that a sync ran.
3. Select the snapshots immediately before/after this request and tap **Compare
   Snapshots**. Check color changes and added calendars. A request without an
   observed start/end is inconclusive; failed eligibility is not an adapter test.
4. Open Google Calendar, invoke its manual Reload, then return to the still-open
   diagnostic dialog. Tap **Refresh Snapshot**; this does not request sync.
5. Compare the post-public-sync snapshot with this newest snapshot. A color or
   calendar appearing in the provider only after Google Reload points to the
   owning adapter/provider metadata path. If the provider already has the new
   metadata while DayRows state stays old, investigate application refresh and
   normal provider filtering. This distinguishes observations without claiming
   Google's private Reload is equivalent to a standard sync request.
6. Copy the redacted report. Repeat offline, with disabled account/master sync,
   denied calendar permission and missing account visibility. Existing snapshots
   must remain after errors. Stop/close during a wait and verify no further
   diagnostic reads/monitoring or automatic retries. Reopen to start a new session.

Snapshots contain metadata only; event synchronization remains a separate test
through DayRows' normal calendar view. No claim about successful remote color
propagation follows from unit tests, compilation or adapter activity alone.

## Extending the feature

`src/debug/.../diagnostics/DeveloperDiagnostics.kt` has a small `DiagnosticModule`
registry of title/content pairs. Add another module there for database/cache,
background tasks, performance or permissions. Keep its probe/state in debug
sources, cancel operations on stop/disposal, and use a bounded in-memory journal.
Journal messages must be static labels, numeric/boolean state or exception class
names; never pass raw provider values or exception messages. Export via an
explicit field allowlist rather than dumping objects. Future modules do not
require changes to release UI or production behavior.

Debug-specific unit tests are in `src/testDebug`, so release tests do not depend
on diagnostic classes or the debug-only fake calendar repository. Verify both
variant compilation and actual release resource/class exclusion when extending
the registry; do not rely solely on hiding a settings button.
