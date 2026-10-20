# Development standards adoption

| Field | Adopted value |
| --- | --- |
| Standards origin | https://github.com/sg0zzy/dev-standards |
| Edition | 1.0.0 |
| Immutable commit | `e3111c4e9dc652f833a5f2ba5232c292646e7969` |
| Stable tag | None exists at adoption; exact default-branch commit used |
| Verified local snapshot | No vendored copy; use a verified detached Git checkout as described below |
| Snapshot inventory/digest verification | [docs/standards-lock.json](docs/standards-lock.json), checked with [scripts/check_standards.py](scripts/check_standards.py) |
| Adoption date and approval | 2026-10-16; developer's “Adopt Centralized Development Standards” migration request |
| Scope/status | **Partial adoption**, Android only; local builds and ordinary CI implemented; official release infrastructure and data recovery have gaps below |
| Configuration/native mapping | This document, [development operations](docs/DEVELOPMENT.md), [version adapter](VERSIONING.md), Gradle and workflow metadata |
| Compliance evidence | [Migration report](docs/STANDARDS_MIGRATION.md) |

## Authority and applicable documents

Precedence is: explicit user instructions and non-overridable security/platform
requirements; the pinned central standards; documented project exceptions allowed
by those standards; local policies; existing implementation/historical practices.
Central MUST/MUST NOT requirements are binding. SHOULD requirements are defaults
with justified departures recorded below. A documented MUST gap is not compliance.
Conflicting local policy must be corrected; functional/data compatibility conflicts
must be reported and preserved pending a safe resolution.

Read the [pinned central README](https://github.com/sg0zzy/dev-standards/blob/e3111c4e9dc652f833a5f2ba5232c292646e7969/README.md)
and applicable documents at that revision: `ADOPTION.md`, `VERSIONING.md`,
`BUILD.md`, `ARTIFACTS.md`, `RELEASE.md`, `ANDROID.md`, `DISTRIBUTION.md`,
`SECURITY.md`, `ARCHITECTURE.md`, `CONFIGURATION.md` and `COMPLIANCE.md`.
Central `AGENTS.md` was reviewed; it governs maintenance of the central repository.
This declaration follows its `templates/DEVELOPMENT_STANDARDS.md`; local agent
instructions follow `templates/AGENTS.project.md`. The inventory also covers all
examples and the central review report.

## Retrieve and verify policies

Use an external directory so the checkout never becomes an application input:

```bash
git clone https://github.com/sg0zzy/dev-standards.git /tmp/dayrows-dev-standards
git -C /tmp/dayrows-dev-standards checkout --detach e3111c4e9dc652f833a5f2ba5232c292646e7969
python3 scripts/check_standards.py /tmp/dayrows-dev-standards
```

The checker verifies HEAD, the Git object contents and checkout bytes against the
recorded SHA-256 inventory. Cache a verified checkout for offline policy work.
If the remote is unavailable, use that verified cache. If neither source can be
verified, report the missing source and stop policy-dependent release/deletion
work; safe independent inspection can continue. Never substitute `main`, another
revision, or reconstructed rules. Normal builds/tests have no central network or
executable dependency.

To update adoption, explicitly select a tag/immutable commit, compare all policies
and examples with this pin, assess identity/signing/retention/data impacts, implement
changed obligations, regenerate the inventory and evidence, and update this record
and local mappings together. Revalidate the exact new checkout. Preserve historical
reservations/artifacts and record unresolved MUST gaps. No automatic pin updates.

## Project mapping

| Policy concept | Native implementation and authoritative location |
| --- | --- |
| Architecture and privacy | Native Kotlin/Compose, direct Calendar Provider, DataStore; [README](README.md) describes offline behavior and structure; [PRIVACY.md](PRIVACY.md) describes sensitive local data |
| Data recovery and sync | Source calendars belong to external providers; DayRows has no synchronization. Private local labels/order/preferences have no supported export/restore; gap R04 below |
| Application version/development core | Root `VERSION`; Gradle derives package fields. Until a release allocator exists, managed test builds use that same core without rewriting it; [VERSIONING.md](VERSIONING.md) |
| Compatibility surface | User preferences and calendar identities; before 1.0 minor changes may change settings format, patches aim to preserve preferences; no data migration in this adoption |
| Build matrix and signing | [Development operations](docs/DEVELOPMENT.md#build-and-signing-matrix); Android debug required locally/in CI; release APK/AAB retained as packaging capabilities, official pipelines blocked |
| Local prerequisites/source identity | [Development operations](docs/DEVELOPMENT.md#managed-local-builds); repository-owned Python helper, source digest and change detection |
| Storage/retention/protection | `scripts/artifacts.py`; root `local_artifacts/`; configurable nonnegative limit default 10; locking clean/protect/remove interface |
| CI and validation | `.github/workflows/build-apk.yml`; main push/PR and manual; exact event SHA, PR proposed merge with head/base recorded; required infrastructure tests, Android unit tests, lint, package verification; 14-day artifacts |
| Beta/stable coordination, preparation, notes/recovery | Not implemented; [release readiness](docs/DEVELOPMENT.md#official-releases-and-distribution) and gaps below; no allocator/tag/release operation is provided |
| Android code/signing/update history | Existing production application ID and debug code adapter preserved; debug signer verified and recorded. Production identities, consumed codes and compatible update paths are not established |
| Distribution/approval/retry | Explicit disabled beta/stable destinations in [development operations](docs/DEVELOPMENT.md#official-releases-and-distribution); no remote delivery tool or standing authority |

## Overrides, recommendations and gaps

| Type | Policy/scope | Decision, consequences and remediation | Owner |
| --- | --- | --- | --- |
| Default | ARTIFACTS local retention | 10 successful unprotected builds; `DAYROWS_ARTIFACT_LIMIT` is an explicit per-build override; manual clean has `--limit`. Zero deletes even the just-completed unprotected build | Developer |
| SHOULD departure | VERSIONING local label | Display core and debug versionCode mapping remain local display-only. Canonical application ID `it.stefanogozzi.dayrows` is adopted for debug and release builds; the former `org.dayrows.app` test install is a separate legacy app with no update edge from it. Different debug keys can block updates; use disposable devices. Do not interpret the display core as a stable release | Maintainer |
| SHOULD departure | ADOPTION verified snapshot | Retrieve/cache a digest-verified Git checkout instead of vendoring policy copies, avoiding duplicated policy text; offline policy work requires that cache | Maintainer |
| MUST gap | RELEASE, VERSIONING official stream | No durable shared official reservation/code ledger, beta workflow, stable preparation/tag build, reviewed notes generation or aggregate publication/recovery gates. Implement after public code/signing history inventory; no release is claimed ready | Maintainer/publisher |
| MUST gap | ANDROID production signing/update domains | Actual production/upload/app-signing fingerprints and historical allocated/submitted codes are unknown. No official update edge is promised. Inventory without replacing keys; implement coordinated allocation and test update/data paths before distributing | Publisher |
| MUST gap | ARCHITECTURE valuable local settings, R04 | Existing labels/order/preferences lack supported export and validated restore. Android backup is disabled for privacy. Preserve behavior; design an explicit private recovery path in separate application work | Maintainer |
| Limitation | Tool/dependency provenance | Direct dependency versions/toolchains pinned; no transitive dependency lockfiles or byte-reproducibility claim. Manifest identifies declared tools and source/config digests; external Gradle configuration must be absent as documented | Maintainer |
| Scope exclusion | Desktop/browser/web and external sync | No such application targets or sync implementation; no preferred frameworks or distribution targets added | Project |

## Authorized automation

This adoption authorizes local implementation/validation only. Existing manual
build and new ordinary CI produce test-only temporary Actions artifacts. There is
no standing authority to create commits/tags, publish GitHub Releases, sign with
production credentials, upload to stores or deploy. Future developer authorization
must identify the operation/channel/destination and checks; configuration or a token
alone is not authorization. No credentials are requested or changed by this adoption.

## Acceptance evidence and migration history

Initial adoption replaces the local generic versioning contract with this central
pin and project implementation mappings. See [migration evidence](docs/STANDARDS_MIGRATION.md)
for case results, source baseline, changed-file identity and pending remote/device
validation. This is partial adoption; unexecuted release cases and mandatory gaps
must not be presented as compliant.
