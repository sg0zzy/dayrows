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

## Official releases and distribution

The GitHub inventory inspected during adoption has no tags/releases. Play and any
out-of-band allocations remain unknown, so no production code high-water is assumed.
The preserved debug adapter is not suitable for coordinated beta→stable allocation.
No official update edges (including cross-GitHub/Play) are promised yet.

| Destination | Platform/architecture | Channel | Artifacts | Mode |
| --- | --- | --- | --- | --- |
| GitHub Releases, `sg0zzy/dayrows` | Android/universal | Beta | Release APK, manifest, SHA-256 checksums | Disabled pending implementation |
| GitHub Releases, `sg0zzy/dayrows` | Android/universal | Stable | Release APK + AAB, manifest, SHA-256 checksums | Disabled pending implementation |
| Google Play, `it.stefanogozzi.dayrows` | Android/universal | Beta | None enabled | Disabled |
| Google Play, `it.stefanogozzi.dayrows` | Android/universal | Stable | Verified upload-signed AAB when configured | Disabled |

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
