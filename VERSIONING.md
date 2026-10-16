# DayRows version implementation

Policy authority is [DEVELOPMENT_STANDARDS.md](DEVELOPMENT_STANDARDS.md), adopting
[central VERSIONING.md](https://github.com/sg0zzy/dev-standards/blob/e3111c4e9dc652f833a5f2ba5232c292646e7969/VERSIONING.md)
and [ANDROID.md](https://github.com/sg0zzy/dev-standards/blob/e3111c4e9dc652f833a5f2ba5232c292646e7969/ANDROID.md).
This file describes the existing project adapter, not a separate policy contract.

`VERSION` is the only editable application version source. Gradle and the managed
build verifier read it; the README title is unversioned. Current packaging accepts
canonical `X.Y.Z` cores only. The development core is currently that same value.
Local/CI test-only manifests carry the channel and unique build identity without
modifying `VERSION`; see the documented local-display recommendation departure.

`app/build.gradle.kts` derives `versionName` from `VERSION` and preserves:

```text
versionCode = major * 1,000,000 + minor * 1,000 + patch - 999
0 <= major <= 2100; 0 <= minor, patch <= 999
1 <= resulting code <= 2,100,000,000
0.1.0 => 1; 0.1.1 => 2; 0.2.0 => 1001; 1.0.0 => 999001
```

This is the historical **debug/core-only** mapping, not a beta/stable allocator.
Prerelease strings fail closed. Local builds do not consume production reservations.
Do not introduce beta slots, seed a production counter or renumber existing
packages until all allocated/submitted/distributed codes and real signing identities
have been inventoried. Production update ordering and release automation remain
explicit adoption gaps. No tags/releases existed in the inspected GitHub inventory;
that observation does not establish Play submission history.

Compatibility concerns are settings persistence, calendar identity and Android
updates. Before 1.0 a minor version may change settings format; patches aim to
preserve preferences. Any actual migration still needs central data-recovery
safeguards. This adoption changes neither settings nor package identity/signing.
See [release readiness](docs/DEVELOPMENT.md#official-releases-and-distribution).
