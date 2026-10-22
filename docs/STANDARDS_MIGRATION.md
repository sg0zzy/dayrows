# Standards migration evidence — 2026-10-16

**Partial adoption.** Adopted dev-standards edition 1.0.0 at immutable commit
`e3111c4e9dc652f833a5f2ba5232c292646e7969` from
https://github.com/sg0zzy/dev-standards. No standards tags existed; `main` was
resolved once and the full commit pinned. All policy documents, templates and
examples were read. No central content was modified or vendored.
On 2026-10-20 a stable release workflow was implemented under the developer's
release-infrastructure request (`release.yml`, `scripts/release_verify.py`,
`scripts/release_publish.py`, `scripts/tests/test_release_gates.py`); GitHub
Releases stable is now `automatic`-after-authorized-tag and Play remains manual.
See the 2026-10-20 entry below.

Application source baseline: `fc102f0d3d7274040a15ded96f537270423bccbc`, plus this
uncommitted migration. Initial Git status was clean. GitHub tags/releases inventory
was empty; this does not establish Play/out-of-band submission history. No commits,
tags, pushes, remote workflow runs, releases or deployments were made. Signing
credentials and application/data behavior were not changed.

## Implemented changes

| Files | Result |
| --- | --- |
| `DEVELOPMENT_STANDARDS.md` | Template-based adoption record: exact pin, authority hierarchy, native configuration mapping, verified retrieval/offline procedure, departures, gaps, update procedure and authorization boundaries |
| `AGENTS.md` | Added concise project instructions; none existed previously and no nested agent instructions were found. Requires the adoption record and pinned applicable policies, preserves Android/privacy/data/artwork/legal behavior, and links operational commands and gaps |
| `VERSIONING.md` | Replaced the independent generic contract with pinned central links and the actual Android adapter, version source and compatibility constraints |
| `README.md` | Removed duplicated version/build policy and stale manual-only CI instructions; linked project operations; retained installation, prior device evidence, legal/store checklist, app behavior and licensing |
| `docs/DEVELOPMENT.md` | Project-specific build/signing and distribution matrices, tool requirements, managed commands, metadata scope, retention/protection/recovery, CI revision policy, release prerequisites and architecture/data review |
| `docs/standards-lock.json`, `scripts/check_standards.py` | SHA-256 inventory of 20 central policy/template/example files; verify exact Git commit, Git object bytes and checkout bytes. Builds do not call this tool or depend on central availability |
| `scripts/build-debug.sh`, `scripts/build_debug.py` | Existing shell entry point preserved; repository-owned Python implementation adds unique direct-child archives, coherent source/config/SDK identity, explicit state, package/signature verification and successful-build-only retention |
| `scripts/artifacts.py` | Local artifact manager: default retention 10, override/zero validation, locked completion sequence, `.keep` protection, nonmutating dry-run, replanning apply, explicitly confirmed recovery removal and fd-relative symlink-safe deletion |
| `scripts/tests/test_infrastructure.py` | 19 isolated tests for deletion safety, race/failure paths, source identity, adapter boundaries and argument rejection |
| `.github/workflows/build-apk.yml` | Added main push/PR triggers alongside existing dispatch; exact event SHA, PR head/base metadata, infrastructure checks, SHA/run/attempt artifact names, required package upload and incomplete diagnostics. Read-only permissions/no production secrets/no release behavior preserved |
| `app/build.gradle.kts` | Canonical numeric core syntax rejects leading zeroes; existing package ID, versionName/code mapping and packaging targets preserved |
| `.gitignore` | Added `/local_artifacts/` and Python bytecode directories |

No standalone files were removed. The obsolete inline Python build implementation
was replaced rather than kept alongside a second implementation. Legacy
`dist/local/` artifacts remain untouched and outside the collector. Nothing was
identified as an unused packaging target or deleted speculatively.

Eliminated redundant local rules include the generic SemVer/release contract,
local counter/tag/publication/changelog policies, cross-platform adapter advice,
and generic coding-agent versioning rules. Their central owners now govern them.
Project commands, Android formula, installed test compatibility, artwork and legal
packaging, source identity/privacy constraints and operational instructions remain.
Version/date applicability in PRIVACY.md and TERMS.md is retained as legal-document
scope, not a second editable package-version source; publisher review is required
when those documents apply to a future release.

## Retained decisions and departures

`VERSION` is `0.1.1` (publisher release decision, 2026-10-19; no tag yet); local
display/core-only versioning remains a documented SHOULD departure. Canonical
application ID `it.stefanogozzi.dayrows` is aligned across `namespace`,
`applicationId` and source packages, shared by debug builds; the former
`org.dayrows.app` test install is a separate legacy app with no update edge, so
nothing here promises production update compatibility. Different generated debug keys
can block replacement;
use disposable test devices without valuable preferences. Uninstalling is not proof
of a successful update and can destroy local settings.

The policy source is a verified detached Git checkout/cache rather than a vendored
copy, as permitted by ADOPTION. Default retention 10 is used; explicit nonnegative
per-build/manual overrides are supported. No architecture preference required a
rewrite: Kotlin/Compose/direct Android APIs already fit. No account, backend, sync,
new platform, shared runtime/build dependency or distribution destination was added.

## Validation performed

Commands executed locally on Linux, Java 21, Python 3.11+ and Android SDK 36:

| Check | Observed result/evidence |
| --- | --- |
| Standards revision and document inventory | Remote Git HEAD and API commit agreed; detached checkout resolved exact SHA. `python3 scripts/check_standards.py /tmp/dayrows-dev-standards-e3111c4` verified all 20 inventoried files from local Git objects and checkout bytes; checker performs no network access |
| Infrastructure safety tests | `python3 -m unittest discover -s scripts/tests -v`: **19 passed**. Deletion/failure/concurrency tests use disposable storage; compiler/source-change faults are simulated and labeled as such |
| Managed Android build and checks | `ANDROID_HOME=… ./scripts/build-debug.sh testDebugUnitTest lintDebug --rerun-tasks --no-daemon --console=plain`: passed, 55 tasks executed; **28 unit tests, zero failed/skipped**; lint **zero errors, 11 warnings** |
| Repeat managed build | `ANDROID_HOME=… ./scripts/build-debug.sh --offline --no-daemon --console=plain`: passed; same dirty source digest, distinct ID, prior archive preserved |
| Low-level release packaging | `ANDROID_HOME=… ./gradlew assembleRelease bundleRelease --no-daemon --console=plain`: passed; unsigned APK and AAB generated. APK signature verification correctly rejected the unsigned output; AAB has no JAR signature entries. These are packaging diagnostics, not official/approved releases |
| Package integrity | `aapt` checked package ID/version/minimum/target SDK; `apksigner` verified debug signatures and recorded certificate SHA-256. All archived artifact sizes/SHA-256 hashes independently rechecked |
| Workflow/shell | YAML parsed; actionlint **1.7.12** and ShellCheck **0.11.0** passed. Existing full Action commit pins resolve through the official repositories. Validation tools downloaded outside the repository; no new project dependency |
| Documentation and housekeeping | Relative Markdown files/anchors validated; Python compilation passed; `git diff --check` passed; ignore rules cover new output/bytecode; `VERSION`, Git HEAD and tag list unchanged |

The SDK emitted its existing command-line XML-version warning; packaging also
reported native libraries it could not strip. Neither caused build failure. No
physical-device/emulator upgrade, fresh macOS build, GitHub workflow execution,
store submission or publication was performed during this migration.

Real local evidence directories:

- `local_artifacts/20261016T163204.597378Z-491c0f08898a4b6c84f073202c0ef536/`: initial managed dirty build, completion sequence 1.
- `local_artifacts/20261016T163426.109664Z-e4e63ce38edf46ec8ff3a80c851ea8e3/`: fresh unit/lint/build run, sequence 2.
- `local_artifacts/20261016T163521.433582Z-a9a764360e0f4a0190b5bf774ac2fc9e/`: offline repeat, sequence 3.
- `local_artifacts/20261016T163852.650401Z-cebf989b919f4f37ba458498d88d938d/`: final implementation build after nonregular-state-file hardening and 19 passing infrastructure tests, sequence 4.

Each contains `build-info.json`, verified debug APK and store artwork. Builds at sequences 2 and 3 have matching
source digests:
`e84ce94f0f484cbbdd2b5b8b33c4da3ecd26a130f4726726cb3afc6adcf2315b`.
Their configuration digest is
`199e4d768abcdd45810ec583c92ed7f3c20e3338183ab512156d3d7c88ec74cc`.
The final build configuration digest is
`a7f880460ad0b0c25893ad1b28d5b0159d4703d741b42d7bf900cc8c2c1a44bd`.
The report itself was finalized after these builds; implementations/configuration
were unchanged after the final successful validation. These ignored outputs remain
subject to documented retention; protect them if persistent evidence is needed.

## Acceptance cases and compliance by area

All rows below use the application baseline plus uncommitted migration, standards
SHA above, and configuration/build manifests above. Evidence is the named commands,
test fixtures, inspected source/configuration and local reports. Pending/gap owner
is the maintainer except production history/signing/store facts, owned by publisher.

| Central cases/area | Result and evidence | Limits/gap |
| --- | --- | --- |
| L01–L03 local identity | Passed real dirty/repeat builds and fixture source tests; unique IDs, source/config identity and unchanged VERSION | Managed Android debug scope only; direct Gradle packaging uses intermediates |
| L04–L05 retention/protection | Passed fixtures with >10 successful directories, protected `.keep` excluded from count, newest completion order retained | No real legacy artifacts deleted |
| L06 mandatory failure | Passed simulated compiler failure; old successes preserved even with limit zero; failed target/stage retained | Compiler fault injection, not a deliberate real Android compilation error |
| L07 concurrency | Passed 20 cross-process completions and concurrent collectors, with a simulated active directory retained | POSIX host fixture; no Windows manager claim |
| L08–L09 unsafe paths/dry-run/revalidation | Passed symlink root/child/manifest, internal symlink, traversal, dry-run nonmutation, protection after planning and confirmed-removal fixtures | Root uncertainty fails closed; arbitrary out-of-band edits cannot be atomic protection |
| L10–L12 completion/overrides/interruption | Passed reverse-completion/mtime fixture, duplicate/invalid/regressed sequence rejection, limits 2/0/negative and in-progress retention | Interrupted state simulated; never implicitly recovered/deleted |
| L13 source changes | Passed simulated build-input mutation; real builds verify pre/post compile and archive digests | Uses change detection, not a private compilation snapshot |
| L14 multi-target failure | No multi-target managed local matrix; one mandatory debug target has the failure gate above | Future multi-target implementation needs its own aggregate tests |
| C01–C04 ordinary CI | Configuration/source review passed: exact SHA, PR proposed merge/head/base, required checks/build/upload, read-only token and no publication path | Actual GitHub push/PR/artifact runs pending; not claimed live-validated |
| C05 optional targets | Not applicable: no optional targets | Do not fabricate optional successes |
| V01–V03 official ordering | **Gap**: no official SemVer comparator/high-water allocator/reservation ledger | Existing debug/core mapping and syntax/bounds tested; official precedence examples not implemented/executed |
| B01–B07 beta | **Gap/not executed**: no trusted Beta Release dispatch, official code/tag reservation, signed packaging/staging or prerelease publication/recovery | Kept disabled; do not distribute debug artifacts as beta releases |
| S01–S10 stable | **Gap/not executed**: no stable dry-run/preparation transaction, annotated tag build, notes baseline/review or immutable aggregate publication/recovery pipeline | No completed release can be produced by current CI; manually published output is not validated by it |
| A01–A07 official Android update/code/signers | **Gap/not executed**: production/submitted history, actual allowed signer identities, monotonic ledger and update proofs unknown | Existing test identity/code preserved; production ordering not inferred from arithmetic |
| A08 signing boundary | Debug build works without production secrets; debug final signature verified; unsigned release output identified | No operational official signing gate exists; low-level unsigned packaging is not a completed release target |
| D01–D04 distribution | Explicit disabled beta/stable GitHub/Play entries; no delivery implementation or implicit activation | Mode/receipt/idempotency/rejection cases not executed while disabled; no external success claimed |
| D05 local/CI separation | Source/workflow review: no upload/deploy command in local tooling; ordinary CI only temporary Actions uploads | No real CI mutation audit until workflow execution |
| Q01–Q02 security/authority | Static checks and task-operation audit passed for current scope; no production credential/write token in ordinary build; no forbidden remote mutation | Production signing/publishing boundary tests pending implementation |
| P01 adoption resolution | Exact pin and local Git object/file hash verification passed; missing-source behavior documented | Offline work requires a previously verified checkout; no central runtime dependency |
| P02 migration | Initial adoption; checklist preceded edits; identity/artifact preservation, mappings and evidence reviewed together | Partial, not a claim all central MUST requirements are satisfied |
| P03 configuration | Native metadata mapping documented; unknown managed arguments and invalid retention rejected | No release/delivery parser/operation exists; release validation remains a gap |
| R01–R02, R05, R09, R11–R12 architecture | Source/design review: native Kotlin/Compose, pure domain rules, existing storage/permission/integration behavior preserved; no speculative frameworks or service added | No new framework or architecture migration required |
| R03, R06 offline/privacy | Static review: Calendar Provider reads, only READ_CALENDAR, no INTERNET/WRITE_CALENDAR; existing permission-denial handling retained | Prior device evidence remains historical; no fresh device session in this task |
| R04 valuable settings recovery | **Gap**: DataStore labels/order/selection lack supported export plus validated restore; Android backup disabled | Preserve current privacy/data behavior; separate recovery feature and tests needed |
| R07, R10 synchronization/P2P | Out of scope: no DayRows sync/P2P implementation or requirement | Provider synchronization is external; not claimed as backup |
| R08 migration recovery | No settings/schema migration introduced | Existing corrupt/incompatible-settings recovery not validated; do not infer a guarantee |

## Remaining gaps and recommended next steps

These are recommendations, **not implemented or authorized remote operations**:

1. Publisher: establish public production/upload/Play app-signing certificate
   inventory, all allocated/submitted/distributed code history and intended update
   edges. Do not generate/rotate keys, rename the application or seed from zero
   based only on empty GitHub history.
2. Maintainer: implement common durable official version/code reservations and the
   beta/stable pipelines required by the pin, with independent signing/publication
   permissions, staged aggregate asset gates, complete reviewed release notes and
   recovery/idempotency tests. Keep stores disabled until separately configured and
   authorized. Add managed release archival once signing profiles are known.
3. Maintainer: design a private preferences export/backup/restore path and validate
   representative restoration without changing provider calendars or exposing
   account identities. This is application work beyond housekeeping.
4. After review/authorization, exercise actual push/PR CI, trusted beta/stable
   workflows in a test repository, and promised signed device updates/data
   preservation. Verify host controls/tag protection and publisher store setup.
   Reassess transitive dependency locks and macOS managed-build validation.

No unresolved convention conflict was silently ignored. Mandatory official release,
signing/history and valuable-settings recovery gaps prevent full compliance and
prevent claiming the repository is ready for official beta/stable publication.
Local managed debug development is validated; CI is configured and statically
validated, with live execution pending.

## Stable release pipeline addendum — 2026-10-20

Scope: the developer requested a release-producing GitHub Actions workflow that
prepares store-uploadable output at a tag, generates GitHub release notes and
publishes the GitHub Release; Play upload stays manual for now. Authorization
basis: this explicit task instruction; per SECURITY.md the annotated stable tag
push itself is the documented per-release act, and the workflow performs no other
remote operation. Nothing in this addendum was executed against the remote:
no commit, tag, push, release or secret write occurred while implementing it.

Changes:

| Files | Result |
| --- | --- |
| `.github/workflows/release.yml` (new) | Stable Release: annotated `vX.Y.Z` tag push trigger; tag/version/annotation/high-water/branch-tip gates; signed APK+AAB packaging with unit tests and lint; identity/signature/alignment/SDK verification and `SHA256SUMS.txt` staging; notes since the previous published stable; write-scoped publish job (draft staging, aggregate gate, publish) |
| `.github/workflows/build-apk.yml` | Added the standard SDK `local.properties` write for the managed helper; behavior otherwise unchanged |
| `scripts/release_verify.py` (new) | Tag-classification/SemVer-precedence/reservation gate and notes baseline/generation tools used by the workflow; official stream semantics per pinned VERSIONING/RELEASE |
| `scripts/release_publish.py` (new) | Idempotent draft staging (checksum-gated asset upload, draft-staging replacement allowed, published-asset replacement blocked) and publish reconciliation via `gh` |
| `scripts/tests/test_release_gates.py` (new) | 26 tests: VERSIONING.md precedence examples, malformed/nonofficial tag classification, high-water/baseline/inversion branches, stage tamper/missing/malformed records, note bodies, fixture-repo tag-gate failures/passes |
| `docs/DEVELOPMENT.md` | Stable release workflow documentation and destination matrix updates (GitHub stable automatic-after-tag; Play manual staging handoff) |

Validation performed locally (no remote writes):

| Check | Observed result/evidence |
| --- | --- |
| Standards re-verification | `scripts/check_standards.py` against a fresh verified detached checkout of pin `e3111c4…`: 20 files verified, exit 0 |
| Infrastructure + new tests | `python3 -m unittest discover -s scripts/tests -v`: 45 tests OK (19 prior + 26 release-gate) |
| Workflow syntax | Both YAML files parse; every inline `run` script passes `bash -n`; shellcheck-style manual review |
| Action pins | checkout v7.0.1, setup-java v6.1.0, upload-artifact v7.0.2, download-artifact v8.0.2 SHAs resolved against the official repositories (`git ls-remote` of the tags) |
| Tag-gate fixture | Annotated/lightweight/wrong-subject/SHA-mismatch/version-mismatch/noncanonical tags in a throwaway repo fail closed; correct annotated tag passes; `ls-remote` branch-tip containment rejects a tag not on the remote main tip; live high-water ran read-only against `sg0zzy/dayrows` (0 official records) |
| Notes/baseline fixture | `notes-baseline` fallback/inversion/stable-selection branches; `notes` generation from root and tag baselines with compare links |
| Staging/publish fixture | Stage→publish→idempotent-republish cycle against a scripted fake `gh`; checksum-tampered, changed-published-byte, missing and malformed record inputs fail closed; draft restage replaces unpublished staging assets |

Compliance status after this addendum: the stable-tag publication pipeline
central cases S01 (tag syntax/annotation/version checks), S04 (tag-source
mandatory build + verification before publication gate) and S05 (notes since the
previous published stable with root fallback), plus the aggregate-gate security
requirements (build/publish permission separation, draft staging, immutable
published assets), are implemented and locally validated. Still open (unchanged
or newly explicit):

- S02/S03 stable preparation dry-run transaction remains absent: the publisher
  creates the annotated tag manually (tag+push is the explicit authorization).
- Beta stream (`-dev.N` allocation), durable release records and cross-version
  publication ordering between concurrent beta/stable runs are not implemented;
  the `stable-release` concurrency group serializes only stable runs.
- B3/B4 remain publisher prerequisites: without an upload keystore/secret set and
  Play history inventory, the release build produces an unsigned package and the
  verification gate fails the workflow by design (never a debug-signed fallback).
- Real run/live remote execution of the workflow has not occurred; the
  first execution must be on the actual authorized tag push.

Configuration revision: `docs/standards-lock.json` pin unchanged
(`e3111c4e9dc652f833a5f2ba5232c292646e7969`); no central policy text was altered.

## Signing hardening and draft-only publication — 2026-10-20 (second pass)

Developer instruction: configure the release pipeline to sign production
artifacts securely with the four provided `ANDROID_*` secrets, keep Play
submission manual, and stop automatic GitHub Release publication (commit/tag
creation and release publication are operator acts, not workflow behavior).

| Check | Observed result/evidence |
| --- | --- |
| Keystore custody | `ANDROID_KEYSTORE_BASE64` is restored with `mktemp -d $RUNNER_TEMP/…` under the runner temp (outside the tree, so artifact scans of `dist/` cannot reach it), exported to Gradle only as the `DAYROWS_RELEASE_KEYSTORE` **path** via `GITHUB_ENV`, and `shred -u` + `rmdir` in an `if: always()` step. No password/key material is ever written to disk, echoed, cached or packaged; `.gitignore` already rejects `*.jks`/`*.keystore` |
| Ordinary CI isolation | `build-apk.yml` (push/PR/dispatch) contains zero `secrets.` references; `release.yml` has no `pull_request` trigger — untrusted PR workflows cannot receive signing credentials |
| No automatic publication | The workflow only stages a **draft** (`gh release create --draft` via `release_publish.py stage`); the flip command is printed for the human step. `release_publish.py publish` remains a separate operator command |
| Staging preconditions | `stage` now refuses to run when the remote tag does not exist (`gh api repos/…/git/ref/tags/…`, annotated refs peeled to the commit) and attaches `SHA256SUMS.txt` as a release asset |
| Tests | `python3 -m unittest discover -s scripts/tests -v`: 49 tests OK (4 new: annotated-tag peel, lightweight ref, missing tag rejection with push hint, malformed ref rejection) |
| Fake-API rehearsal | Missing-tag staging fails; present-tag staging creates a draft with exactly `DayRows-…-release.apk`, `DayRows-…-play.aab`, `SHA256SUMS.txt`; interrupted-staging rerun is idempotent; manual `publish` is idempotent; non-stable tag is refused |
| Workflow syntax | Both YAML files re-parsed; every inline script passes `bash -n`; the release job's `DAYROWS_RELEASE_TAG` was previously undefined in that job and is now set at job level (bug fix) |

Configuration revision: standards pin unchanged; no secret values were requested,
read or recorded at any point during this work.

Missing configuration remaining (not exposed, none of it blocks the workflow):
`vars.DAYROWS_UPLOAD_CERT_SHA256` is still unconfigured, so signer-fingerprint
pinning is advisory-only until the Play App Signing/upload certificate inventory
(B3) is complete. Play upload stays manual (B4/B5 publisher steps).

## Main calendar refresh — 2026-10-22

The main calendar toolbar now exposes *Refresh events* in portrait and landscape,
using the existing `CalendarViewModel.syncNow` account-sync request and provider
observer. English/Italian feedback explains that updates arrive after the owning
calendar account syncs; it does not claim that the remote download has completed.
Resume-time sync requests and settings discovery retain their existing behavior.

The exact standards checkout at `e3111c4e9dc652f833a5f2ba5232c292646e7969`
passed `python3 scripts/check_standards.py /tmp/dayrows-dev-standards` (20 files).
Applicable ARCHITECTURE R02/R03/R06/R11 and SECURITY Q02 source review confirms
native Compose integration, unchanged permissions and preferences, no calendar
writes or direct network access, and local-only implementation/validation.
`python3 -m unittest discover -s scripts/tests -v` and
`./gradlew testDebugUnitTest lintDebug --console=plain` passed. No new dependency,
identity, version, signing or release changes were made.

Device validation remains pending: edit an event in a shared remote calendar,
tap the toolbar refresh without opening another calendar app, and verify that
the account provider downloads it and the observer updates DayRows. Repeat for
portrait/landscape and offline operation. Platform/provider scheduling can delay
the result. No end-to-end remote synchronization success is claimed. Existing
MUST gaps, including settings recovery (R04) and official signing/history and
release coordination, remain unresolved by this UI change.

## Bounded calendar metadata synchronization — 2026-10-22

The follow-up request covers asynchronous account sync, startup throttling,
metadata/color reloads, offline fallback and diagnostics. The same immutable
standards checkout was reverified with `scripts/check_standards.py` (20 files).
ARCHITECTURE R02/R03/R06/R07/R11 and SECURITY Q02 were applied: native APIs and
preferences are preserved; DayRows delegates synchronization/conflict handling
to existing account adapters, does not write provider rows, and does not claim
sync as backup. Implementation and validation remain local; no publication,
version, package identity or signing changes are authorized or performed.

The sync coordinator isolates account failures, joins pending/active syncs,
coalesces overlapping callers, throttles startup/manual attempts, observes
activity on IO with a 30-second bound, and does not retry or cancel account work.
The final query reloads both metadata and events; local data remains visible on
sync/query failures. The existing permission-revocation path still clears the
calendar view. Only `READ_SYNC_SETTINGS` and `READ_SYNC_STATS` are added;
`WRITE_SYNC_SETTINGS` is removed by avoiding settings-bypass flags. No account
enumeration permission, network permission, dependency or OAuth integration is
added. English/Italian UI and packaged privacy disclosures cover the behavior.

Validation: 40 Android unit tests passed, including eight new virtual-time cases
for multiple accounts, disabled/restricted accounts, unobservable/offline status,
pending work, overlap/throttling and cancellation. Android lint passed; the 57
Python infrastructure tests passed. The managed debug build runs these Android
checks again and records package/source verification in its `build-info.json`.
See [the device validation procedure](CALENDAR_SYNC.md) for remote color tests,
startup/manual/offline/disabled-account cases and opt-in diagnostics. Real Google
account color propagation and Google Calendar's internal Reload behavior remain
unverified: adapter activity ending is not proof of metadata download, and a
timeout is not proof of a provider limitation. No direct REST/private-API
fallback is introduced. Existing MUST gaps for settings recovery (R04), official
signing/update history and release coordination remain outside this change.

Follow-up device evidence (22 October): the tester reports color propagation
only after Google Calendar Reload, even when Google Calendar already displays
the new color. Read-only package inspection found the newer debug app with
sync-read permissions granted alongside the older production app. The tested
variant and DayRows sync outcome still require confirmation; the available
device log buffer contained no matching sync diagnostics. The color issue
remains unresolved. No additional permissions, authentication, sync flags or
provider writes were introduced on this evidence alone. The standards pin
was verified again (20 files); existing MUST gaps remain unchanged.

## Account and adapter visibility correction — 22 October 2026

The tester confirmed the generic unavailable-sync message. Read-only device
inspection found READ_CALENDAR, READ_SYNC_SETTINGS and READ_SYNC_STATS already
granted to the updated debug app. A diagnostic launch reproduced both provider
accounts being skipped before requestSync, disproving the assumption that the
color test had necessarily exercised an adapter sync. The inspected AOSP
ContentService/SyncManager implementations filter sync settings and adapters
according to account and package visibility; false settings do not alone prove
the user disabled synchronization.

The correction adds scoped manifest visibility for services handling
`android.content.SyncAdapter` (no new permission), checks visible accounts with
AccountManager's API-26+ filtered query, and reports missing account access
separately. An explicit button opens the native chooser restricted to provider
accounts with adapters still needing access. It neither opens automatically nor
selects an account for the user. Successful selection retries through the shared
sync operation; cancellation leaves local data and preferences intact. Local
accounts with no adapter are excluded from the chooser. No GET_ACCOUNTS,
QUERY_ALL_PACKAGES, Google OAuth, network or calendar-write permission is added.
English/Italian explanations and the packaged privacy policy were updated.

Validation: 42 Android unit tests and lint passed, including two new tests for
missing account visibility, immediate retry after granting access, and continued
sync for other visible accounts. The final managed debug build repeats Android
checks and verifies the package; its build-info.json records source identity.
The native chooser grant must be made by the tester; remote color propagation
after that grant remains pending. Neither the earlier skip nor this correction
establishes that Google Calendar's private Reload behavior is reproducible with
the public sync API. Existing recovery/signing/history/release MUST gaps remain.

Final local evidence: managed build
`20261022T125826.149417Z-d1b04a1573014fbba73373947241fd39` succeeded with
42 Android unit tests, lint and verified debug packaging; 57 infrastructure
tests passed. `adb install -r` updated the existing debug installation without
uninstalling or clearing its data. The account chooser was not used to grant
access on behalf of the tester. End-to-end sync/color validation remains pending
that explicit account choice. The temporary diagnostic log property was restored
to its original default after inspection.

## Debug-only Developer Diagnostics — 22 October 2026

User scope: a reusable debug-only diagnostic interface for provider snapshots,
sync tracing, comparisons and redacted clipboard export, without changing
production synchronization, versions or Git history. The pinned checkout passed
`python3 scripts/check_standards.py /tmp/dayrows-dev-standards` (20 files).
ARCHITECTURE R02/R03/R06/R11 and SECURITY Q02 apply: native Kotlin/Compose,
existing platform permissions, no telemetry/network integration, local validation
only, and no publication/signing-key access or replacement.

`src/debug` owns the module registry, raw CalendarProvider probe, bounded session,
comparison/export logic, UI, English/Italian resources and calendar diagnostic
logging. Settings is guarded by BuildConfig.DEBUG; release source stubs expose
no actions and retain no diagnostic metadata. Existing sync behavior is unchanged.
The raw probe reads metadata only on explicit actions, independently of the app
repository/preferences, preserves raw nulls/flags, and shows application colors
captured at query start. Five snapshots and 200 bounded trace messages remain in
memory. On stop/disposal, diagnostic polling is canceled without canceling the
owning account adapter. No diagnostic observers, services or retries are added.

Clipboard export uses an allowlist of numeric IDs/colors/flags, changed-field
names and sanitized status/exception classes. Account names/types, calendar
names, identities, event contents and exception messages are omitted. Raw names
are visible only inside the debug interface. Packaged privacy disclosures were
updated in both languages. Diagnostic logs are disabled in release at the source
set boundary, independently of system log properties.

Validation: 52 debug unit tests (ten new diagnostics tests), debug lint, 41 release
unit tests, release lint, release APK/AAB packaging, and 57 Python infrastructure
tests passed. An existing fake-repository test was moved from shared tests to
`src/testDebug` because its fixture was already debug-only; its coverage remains.
Release builds were run with all DAYROWS_RELEASE_* credential variables excluded,
producing unsigned validation packages, not official releases. Actual APK/AAB
DEX/resource checks found no diagnostic module/session/probe classes, diagnostic
resources or DayRowsCalendars tag. Unminified release outputs likewise excluded
the debug modules/fixture; the release logger contains no android.util.Log calls.

See [Developer Diagnostics](DEVELOPER_DIAGNOSTICS.md) for the before/public-sync/
Google-Reload procedure and module extension instructions. Google metadata
propagation remains an unverified device investigation, not a claimed fix.
Existing MUST gaps for settings recovery (R04), official identity/signing/history
and release coordination remain unresolved. No version bump, commit or tag was
created and no production synchronization policy was changed.

## Debug request delivery audit — 22 October 2026

User scope: investigate absent SyncManager history, instrument the diagnostic
request path and document exact ADB verification, preserving production sync and
release behavior. The immutable standards checkout passed the repository checker
(20 files at the adopted pin). All implementation/test/resource changes for this
follow-up are under `src/debug` or `src/testDebug`; no main/release synchronization
code, manifest permissions, version fields or Git history were changed.

Before editing, inspection verified the diagnostic button → snapshot → probe →
coordinator → `ContentResolver.requestSync(Account(name, type),
CalendarContract.AUTHORITY, extras)` path. Eligibility/throttle/join gates can
prevent the actual call. Only the existing public expedited extra is sent;
SYNC_EXTRAS_MANUAL is absent, so inspected AOSP source classifies the request as
OTHER rather than USER. ContentService can silently return for account/authority
access restrictions; a void return cannot establish scheduler receipt.

Connected Pixel evidence: Android 17 / SDK 37; the three declared calendar/sync
read permissions were granted. Available content history contained 84 USER and
7 FEED calendar rows, no OTHER calendar row and no row at 16:55; no DayRows Debug
package attribution appeared. This substantiates the reported absence without
claiming a particular rejection cause. No raw account names/URLs were emitted in
inspection results. The instrumented debug APK was installed with `adb install
-r`, preserving app data. An attempted UI reproduction found keyguard showing,
input restricted, and the phone dozing; no diagnostic button press or API call
was claimed from that attempt. An unlocked-device reproduction remains pending.

The new audit separates handler entry, target/preflight/settings checks, exact
API enter/return/throw boundaries, changed pending/active observations and
activity ending. Run IDs, package/UID/PID, device/target SDK, standard authority,
provider row IDs, Google-type equality, exact visible account matches, permissions
and actual Boolean extras are logged. Account names/custom types and exception
messages are excluded; failures retain bounded stack locations, cause classes
and allowlisted permission mentions. The bounded journal is mirrored to the
debug-only DayRowsSync Logcat tag without changing system properties. Existing
work, skipped requests, unknown delivery, timeout and remote-success uncertainty
are stated explicitly; no undocumented extras or new authentication is introduced.

Validation: 60 debug unit tests (eight new audit regressions), debug lint and
managed debug packaging passed; 41 release tests, release lint and unsigned
release APK/AAB packaging passed; 57 Python infrastructure tests passed. Release
DEX was unchanged from the prior validated release and excluded the new audit
class/tag/API marker. The native public request is tested through the audit
closure boundary; actual Pixel execution requires the foreground/unlocked test
in [the request audit guide](SYNC_REQUEST_AUDIT.md). Source/public API inspection
does not substitute for system-side pending/history evidence.

ARCHITECTURE R06 privacy and SECURITY Q02 local authority remain satisfied within
this work's scope. Existing MUST gaps for settings recovery, official signing
and history, and release coordination remain unresolved. No version bump,
commit, tag, production signing credential access or publication was performed.

### Calendar account/adapter visibility recovery (2026-10-22)

The supplied 17:26 trace establishes zero public sync calls: both provider-derived
Google accounts were invisible, and adapter discovery returned zero matches.
The before/after dumps show no calendar execution in that interval. Account-access
recovery now precedes adapter rejection; the native chooser no longer depends on
adapter discovery, and debug diagnostics expose the same user-driven recovery.
Add only a scoped Google Calendar package-visibility query alongside the generic
SyncAdapter query. Permissions, extras, user sync settings and preferences remain
unchanged. See [request audit](SYNC_REQUEST_AUDIT.md) for the reproduction and
remaining distinction between API return, scheduled work and metadata retrieval.

Verified the exact central pin with `check_standards.py`. Managed debug packaging,
62 debug tests and debug lint passed; 42 release tests, release lint and unsigned
release APK packaging passed; 57 infrastructure tests passed. Release DEX contains
none of the diagnostic module/audit/log markers. Installed the archived debug APK
with `adb install -r`; the device package dump confirms the explicit visibility
query. The Pixel is locked, so account selection, adapter visibility after the
update, system scheduling and remote color/new-calendar retrieval remain device
validation steps. No version bump or commit. Previously recorded MUST gaps remain.

### Settings Reload and native account authorization (2026-10-22)

Following successful device synchronization from diagnostics, Settings Reload now
checks raw CalendarProvider accounts on IO and opens Android's native chooser
when account access is missing. Successful selection continues the explicit sync
and discovery; cancellation refreshes only local data. Multiple accounts retain
the existing select-one/repeat recovery, with no automatic prompt/retry loop.
Guard concurrent chooser launches and suppress resume sync during authorization.
Normal synchronization now discovers accounts from the same unfiltered provider
rows as diagnostics; both share one extras builder and public request-submission
function. No permission, flag, identity, version or preference-format change.

Exact pinned standards verified. Managed debug packaging, 62 debug tests and debug
lint passed; 42 release tests, release lint and unsigned release APK packaging
passed; 57 infrastructure tests passed. Diagnostic probe/audit/log markers remain
absent from release DEX. Installed the final archived debug update with
`adb install -r`; Settings-button and chooser interaction are left for the user's
requested real-device test. No commits or publication. Previously recorded MUST
gaps remain unresolved.

### Explicit Reload only; lightweight startup/resume (2026-10-22)

At the user's request, opening/resuming DayRows and granting calendar permission
now refresh only local CalendarProvider data. Removed the ViewModel startup-sync
entry point; automatic lifecycle callbacks and error Retry no longer request
account synchronization. Provider notifications retain ordinary event updates.
Both main-page and Settings Reload explicitly request the existing bounded sync
and offer native account authorization when needed. Diagnostics keep their
explicit Request Sync action. No new flags, permissions, version or preference
changes. Updated EN/IT privacy and diagnostic text and current behavior/test docs.

Verified the exact central pin. Managed debug build, 62 debug tests and debug lint
passed; 42 release tests, release lint and unsigned release APK packaging passed;
57 infrastructure tests passed. Source call-path inspection confirms that startup
contains no account-sync call. Installed the final archived debug update with
`adb install -r`; lifecycle/Reload interaction remains the user's device test.
No version bump, commits or publication; previously recorded MUST gaps remain.

### Authorized development bump and source push (2026-10-22)

The user explicitly requested a bump, commit and push of this calendar sync and
debug diagnostics work. Advance the authoritative VERSION from 0.1.2 to 0.1.3
(existing Android core mapping: versionCode 4), without changing package IDs or
signers. This is a source/development bump and branch push, not a stable tag,
official signing or release-publication operation.

Verified the pinned standards and remote main baseline before preparation.
Managed 0.1.3 debug packaging, 62 debug tests and debug lint passed; 42 release
tests, release lint and unsigned release APK/AAB packaging passed; 57 infrastructure
tests passed. Release APK excludes diagnostic log/probe/audit markers. Existing
settings recovery and official release-history/signing gaps remain unresolved.
