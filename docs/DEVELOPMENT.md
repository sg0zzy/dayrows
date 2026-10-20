# DayRows development operations

[DEVELOPMENT_STANDARDS.md](../DEVELOPMENT_STANDARDS.md) identifies the policy
revision and precedence. This file owns project commands and implementation details.

## Build and signing matrix

| Target | Platform/architecture | Package | Local | Ordinary CI | Beta | Stable |
| --- | --- | --- | --- | --- | --- | --- |
| `android-debug` | Android, universal Kotlin/JVM, API 26+ | Debug-signed APK | Required | Required | Excluded | Excluded |
| `android-release-apk` | Android, universal Kotlin/JVM, API 26+ | Release APK | Low-level packaging capability | Excluded | Required, blocked | Required, blocked |
| `android-release-aab` | Android, universal Kotlin/JVM, API 26+ | Release AAB | Low-level packaging capability | Excluded | Excluded | Required, blocked |

No optional targets are declared. The existing release APK/AAB capabilities remain;
release channel completion requires every declared mandatory target and its checks.
Beta/stable are **not operational**, not successful empty matrices. Desktop,
browser-extension and web targets are not supported. Managed hosts are Linux/macOS
with Python 3.11+ and POSIX file locks; CI uses Ubuntu 24.04. `gradlew.bat` remains
available for low-level Windows Gradle work; managed Windows archival is excluded.
macOS is declared but has not been exercised in this migration.

Debug signing uses Android's generated debug key. `apksigner verify --print-certs`
verifies the final archived APK; its signer SHA-256 is recorded, with no production
identity or upgrade promise. `aapt dump badging` checks `it.stefanogozzi.dayrows`, canonical
versionName/code, minimum API 26 and target API 36. APK and artwork sizes/hashes are
recorded. Release APK signing and AAB upload signing have no configured credentials
or expected certificate inventory. Play's actual installed app-signing identity is
also unknown. There is no external signing/notarization service interaction here.

## Managed local builds

Requirements: JDK 17+ (validated with 21), Python 3.11+, Git, Android SDK platform
36 and build-tools 36.0.0. Gradle 8.14.3, AGP 8.13.0 and Kotlin 2.2.20 are pinned.
Initial Gradle/dependency downloads require internet access.

### Package IDs and coexistence

Debug and release builds install side by side on one device; they no longer share
an application ID (different signing keys alone made one replace the other fail
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

| Build type | Application ID | versionName | Label | Signature |
| --- | --- | --- | --- | --- |
| `debug` | `it.stefanogozzi.dayrows.debug` (`applicationIdSuffix = ".debug"`) | `0.1.2-debug` (`versionNameSuffix = "-debug"`) | DayRows Debug | Generated Android debug key — never the production upload keystore |
| `release` | `it.stefanogozzi.dayrows` | `0.1.2` | DayRows | Official upload key via the `DAYROWS_RELEASE_*` env contract |

versionCode stays shared (core-derived, e.g. 0.1.2 → 3); it orders updates inside a
variant only, and the two packages never uninstall or overwrite each other. DataStore
preferences live under each package's own data directory (`/data/data/<applicationId>`),
so the two variants keep independent local state. Calendar Provider access and
`READ_CALENDAR` are declared once in the main manifest and work identically in both
variants; the Settings "app details" intent uses the runtime `packageName`, so it
always opens the correct variant. No custom ContentProvider/FileProvider authorities
exist to suffix.

Install either one without uninstalling the other:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk       # DayRows Debug
adb install app/build/outputs/apk/release/app-release.apk   # release; -unsigned fails by design
```

The managed help tool archives the debug target with the `.debug` package and
`-debug` display name; see [VERSIONING.md](VERSIONING.md) for the adapter rules.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
# Or use untracked local.properties containing sdk.dir=/absolute/sdk/path.
./scripts/build-debug.sh
./scripts/build-debug.sh testDebugUnitTest lintDebug --no-daemon --console=plain
./scripts/build-debug.sh --offline
python3 -m unittest discover -s scripts/tests -v
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The helper works from any directory. Additional supported Gradle arguments are
`testDebugUnitTest`, `lintDebug`, `--offline`, `--no-daemon`, `--console=plain`,
`--rerun-tasks`, `--no-build-cache`, `--refresh-dependencies`, `--stacktrace` and
`--warning-mode=all`. Arbitrary project properties, init scripts, build/output
redirection and secret-bearing arguments are rejected by the managed interface.
Use an uncustomized Gradle user configuration; custom global properties/init
scripts and option-injection environment variables are rejected. The existing
wrapper remains available for other low-level Gradle operations.

Each invocation gets a unique direct child of `local_artifacts/` with
`build-info.json`, `DayRows-<version>-android-universal-debug.apk`, and
`store-artwork/`. A per-working-copy compilation lock coordinates managed builds
using Gradle's shared intermediate output. Do not run unmanaged/IDE builds against
the same working directory concurrently. Gradle may update ignored intermediate
outputs/caches; it never rewrites `VERSION` through this helper.

The manifest records manager/schema, source SHA/dirty state, SHA-256 source and
configuration identities, standards pin, core/channel, host/tools/options,
per-target outcome and verified package metadata, timestamps and locked completion
sequence. Git-visible inputs and ignored files in `app/src`, `icons`, `gradle` and
`scripts` participate in source identity. Symlink source inputs and submodules are
rejected until explicitly mapped. Outputs, caches, Python bytecode and credentials
are excluded. SDK binaries are environment inputs identified by digests; the
machine-local SDK path is not published. `local.properties` must contain only
`sdk.dir` and comments/blanks; its contents are checked for changes, never archived.
Source changes during compilation fail the build before success/retention.

Failure returns nonzero and retains failed/in-progress metadata with target/stage
information. Interrupted processes can leave `in_progress` state, which is never
automatically collected. Cleanup failure after successful compilation returns
nonzero as maintenance failure, leaving the successful manifest intact. Logs stay
in the terminal/Actions log, not copied from potentially sensitive private paths.

Direct `./gradlew assembleDebug`, `assembleRelease` and `bundleRelease` retain
Android's replaceable intermediate output paths. They are low-level packaging
operations, **not managed final builds or official releases**. Use the helper for
final local debug archives. Managed local release-signing/archive support is pending;
retain the existing operations until a safe signing profile is established.
Legacy `dist/local/` archives are preserved and never managed by the new collector.

## Artifact retention and recovery

Default retention is 10 successful unprotected managed builds, by durable completion
sequence. Failed, active, unknown, malformed, ambiguous and protected directories
are excluded and reported. Collection only follows successful managed builds.
`DAYROWS_ARTIFACT_LIMIT=2 ./scripts/build-debug.sh` explicitly changes that build's
limit; a negative value fails before building/deleting. Zero retains no unprotected
successes, including the just-completed build.

```bash
python3 scripts/artifacts.py clean --dry-run
python3 scripts/artifacts.py clean --limit 10 --apply
python3 scripts/artifacts.py protect <build-id>
```

The dry-run is nonmutating and lists delete/retain/skipped reasons, protection,
identity and completion sequence. Apply replans under the same root lock used by
reservation, completion and protection; it does not execute a stale dry-run list.
`protect` creates `.keep` while holding that lock. A raw `.keep` entry protects
regardless of its contents if observed before deletion; raw creation during an
active deletion cannot provide atomic protection. Use `protect` for that guarantee.
There is no unprotect operation; explicitly selecting a protected build for removal
requires matching confirmation. To recover abandoned/failed output, inspect it and
verify its process is stopped, then select it explicitly:

```bash
python3 scripts/artifacts.py remove <build-id>  # inspection only
python3 scripts/artifacts.py remove <build-id> --confirm-build-id <build-id>
```

That confirmation also permits deleting the selected protected build. Never select
a running build. Unknown directories require independent manual inspection; this
interface refuses them. Automatic cleanup never follows symlink roots/children,
uses fd-relative symlink-safe deletion, and revalidates identity/state/protection.
Do not alter `.sequence.json` or `.lock`; corruption fails closed for inspection.

## Continuous integration

[Build debug APK](../.github/workflows/build-apk.yml) runs on pushes to `main`, PRs
targeting `main`, and manual dispatch. Checkout uses `github.sha`: push SHA, PR
proposed merge SHA, or dispatch event SHA. Manifests include PR head/base and run
identity. Builds use the same helper, unit tests and lint, preceded by infrastructure
safety tests. Package upload is required for success. Temporary artifact names
include target/SHA/run/attempt; retention is 14 days. Reports and explicitly named
incomplete manifests aid diagnosis. Repository permissions are read-only, checkout
credentials are not persisted, and no production secret is used. There is no
release, tag, stable bump, signing-service call or deployment path in ordinary CI.
An SDK `local.properties` (`sdk.dir=${ANDROID_HOME}`) is written in both workflows;
it is untracked and its content digest is observed for source coherence only.

## Stable release workflow

[Stable Release](../.github/workflows/release.yml) implements the stable column of
the build matrix and drafts the GitHub Release for the authorized tag; Play
remains `disabled` with a manual follow-up, and flipping the draft public is the
publisher's manual decision (`gh release edit <tag> --draft=false` or the web
UI). It is triggered by pushing an annotated `vX.Y.Z` tag, which is the publisher
authorization for that release build. The pipeline has four jobs:

1. `tag-guard` (read-only): validates the tag with
   `scripts/release_verify.py tag` — canonical `vMAJOR.MINOR.PATCH`, equality
   with the authoritative `VERSION`, peeled commit equals the workflow SHA,
   annotated tag with subject `DayRows <version>`, the tagged commit is the tip
   of remote `main`, and the reservation high-water check over the complete
   remote official state (`gh api` matching tags plus published releases;
   nonofficial tag names are ignored, unparseable official records block).
   Requires the full history fetch of the exact tag object.
2. `build` (read-only permissions): unit tests, lint, `assembleRelease` and
   `bundleRelease` with the opt-in `DAYROWS_RELEASE_*` env credential contract
   from `app/build.gradle.kts`. Repository secrets:
   `ANDROID_KEYSTORE_BASE64` (base64 of the `.jks` upload keystore),
   `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_PASSWORD`. The keystore is restored from base64 into a fresh
   `mktemp` directory **under `RUNNER_TEMP`** — outside the workspace, so no
   artifact upload can include it — exported to Gradle via `DAYROWS_RELEASE_KEYSTORE`,
   and shredded (`shred -u`) before the job ends on any outcome. Missing material
   produces an unsigned package that the verification gate rejects — signing
   failures never fall back to the debug signer — and a partially configured
   credential set fails the Gradle all-or-none check. Ordinary CI
   ([build-apk.yml](../.github/workflows/build-apk.yml), push/PR/dispatch) uses
   no secrets at all, so untrusted pull-request workflows never receive signing
   credentials. `scripts/verify_release.py apk|aab` verify decoded identity
   (`package`, both versions, min/target SDK), signature validity, zipalign and
   the signer fingerprint against `vars.DAYROWS_UPLOAD_CERT_SHA256` when that
   variable is configured. Final staged bytes are
   `DayRows-<version>-android-universal-{release.apk,play.aab}`,
   `dist/DayRows-<version>-android-universal-symbols.zip` (the native debug
   symbols package: every `.so` shipped in the bundle, with `<ABI>/<library>.so`
   at the zip **root** and **nothing else** — Play's validator accepts only
   Android ABI directories; stray files like `MANIFEST.txt` or a `lib/` wrapper
   are rejected), `dist/DayRows-<version>-android-universal-mapping.txt`
   (the R8/Proguard mapping, uploaded to Play's separate deobfuscation-file
   slot; never embedded in the symbols zip), and
   `dist/SHA256SUMS.txt` (generated and transport-verified). Verified staging
   uploads as a 14-day workflow artifact named
   `<tag>-release`; failed runs upload `<tag>-incomplete`.
3. `notes` (read-only): resolves the previous successfully published stable tag
   as the notes baseline (`notes-baseline`; root commit fallback records the
   initial-reachable-history baseline) and generates notes for
   `previous-stable..tag` from first-parent Git history with a compare link
   (RELEASE.md notes rules; beta tags are never used as the stable baseline).
4. `release` (contents: write, the only write-scoped job): downloads both
   artifacts, decodes them against the tag's `VERSION`, re-verifies signatures
   and all transport checksums (independent verification of artifact bytes).
   `scripts/release_publish.py stage` refuses to run without the remote tag,
   then creates/updates a **draft** release at that tag with the checksummed
   assets, `SHA256SUMS.txt` and the assembled notes body (staging retries may
   replace unpublished draft assets after hash checks; published assets are
   immutable and a retry with differing bytes fails as an incident instead of
   replacing them). The workflow leaves the release in draft; publication keeps
   the tag, draft and staging evidence intact either way.

The AAB asset is downloaded and uploaded to Play Console
manually and that acceptance is a separate destination state. Two more assets
feed Play's deobfuscation pipeline: the `symbols.zip` (native `.so` set with
`<ABI>/` directories at the zip root — no `lib/` wrapper, no manifest) goes to
the release's "Debug symbols" step, and the `mapping.txt` file goes to the
**deobfuscation file** slot — Play rejects symbols archives containing any
extra file ("unexpected file: MANIFEST.txt") or any non-ABI directory
("invalid directory lib"/"invalid directory symbols": only Android ABIs are
supported). With them uploaded, Android Vitals shows
retraced Java/Kotlin stack traces and attributed native crash/ANR frames. If a
future build disables minification, the mapping asset simply vanishes and only
native frames are attributed. The workflow does not
implement beta allocation (`-dev.N`), durable release/ledger records, or the
Play delivery destination; those remain explicit gaps, and the tag workflow's
`notes-baseline` resolver covers the notes-side requirements only.

## Official releases and distribution

The GitHub inventory inspected during adoption has no tags/releases. Play and any
out-of-band allocations remain unknown, so no production code high-water is assumed.
The preserved debug adapter is not suitable for coordinated beta→stable allocation.
No official update edges (including cross-GitHub/Play) are promised yet.

| Destination | Platform/architecture | Channel | Artifacts | Mode |
| --- | --- | --- | --- | --- |
| GitHub Releases, `sg0zzy/dayrows` | Android/universal | Beta | Release APK, manifest, SHA-256 checksums | Disabled pending implementation |
| GitHub Releases, `sg0zzy/dayrows` | Android/universal | Stable | Release APK + AAB, SHA-256 checksums, verified notes | Draft staged `automatic`ally after an authorized annotated `vX.Y.Z` tag push; visibility flip is `manual` ([Stable Release](../.github/workflows/release.yml)) |
| Google Play, `it.stefanogozzi.dayrows` | Android/universal | Beta | None enabled | Disabled |
| Google Play, `it.stefanogozzi.dayrows` | Android/universal | Stable | Verified upload-signed AAB + native debug-symbols zip when configured | `manual`: the AAB draft asset is staged by the GitHub pipeline, the human uploads it and its symbols in Play Console and records acceptance |

All other destinations default to disabled. Credentials/environment references and
automated retry identities are intentionally unconfigured while disabled; no tool
attempts delivery. GitHub completion will require verified mandatory assets at the
immutable tag and manifest-hash identity; Play completion must eventually specify
acceptance/rollout evidence keyed by app ID, code and artifact hash. A debug upload
or existence of an AAB establishes neither result.

Before enabling releases, the publisher must inventory actual APK/upload/Play
app-signing certificates, package code history (including failed/submitted builds),
and intended update edges. Then implement shared durable official version/code
reservations, trusted manual beta dispatch, explicit stable preparation with dry-run,
annotated immutable tagged builds, signature/checksum/aggregate staging gates,
reviewed notes since the previous successfully published stable, and recovery that
never reuses abandoned identities or replaces published bytes. For the first stable,
notes must cover initial reachable history. These are central requirements; they
are not provided by the current debug workflow. Missing signing must fail the future
release target, never fall back to debug signing.

Retain the README's project publication checklist for legal documents, source,
artwork, contact/privacy hosting and store setup. None of those actions is performed
by this migration. Do not create a production key to fill a missing inventory.

## Architecture and data recovery

Kotlin/Compose and native Calendar Provider APIs already match applicable preferences.
The app has no backend, account requirement, network permission, telemetry or
synchronization. Calendar events remain in the device/provider; their backup is
the user's calendar-provider procedure, not a DayRows feature. DayRows only reads
those events. Pure domain rules are independently unit-tested.

DataStore stores local calendar identities, labels, selection and row order under
`configuration_v1`. Those private preferences are not a disposable cache. Android
backup is disabled, and no supported export/restore exists: this remains an
ARCHITECTURE MUST gap. Do not describe synchronization as backup, enable platform
backup, change settings formats or remove preferences during housekeeping. A future
recovery feature needs explicit privacy design and representative restore tests.
