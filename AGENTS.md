# DayRows agent instructions

Read [DEVELOPMENT_STANDARDS.md](DEVELOPMENT_STANDARDS.md) before relevant work.
Consult and verify the pinned central policies for the area being changed using
`python3 scripts/check_standards.py <standards-checkout>`. Central standards take
precedence over conflicting local conventions. Use the exact adopted revision;
follow the adoption document's verified-cache procedure if central access fails.
Do not guess missing policies or silently update the pin.

Project implementation details:

- `VERSION` is authoritative. [VERSIONING.md](VERSIONING.md) explains the Android
  adapter and existing test-install compatibility. Do not change IDs/codes or
  signing identities incidentally.
- Use `./scripts/build-debug.sh` for managed debug builds. Run
  `python3 -m unittest discover -s scripts/tests -v`,
  `./gradlew testDebugUnitTest` and `./gradlew lintDebug` for relevant checks.
  [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) owns prerequisites and operations.
- Run `python3 scripts/artifacts.py clean --dry-run` before manual cleanup;
  use `protect <build-id>` for race-free `.keep` protection. Leave legacy
  `dist/local/` archives alone. Follow central ARTIFACTS before changing deletion.
- Keep Kotlin/Compose and Calendar Provider integration. The app only reads
  calendars; preserve permission, offline/privacy, identity and saved-preference
  behavior. Do not add frameworks or rewrite functionality for standardization.
- Preserve English/Italian resources, legal documents packaged by Gradle, launcher
  generation, store artwork and both APK/AAB packaging capabilities.
- Official release signing/history and settings recovery remain explicit gaps in
  the adoption record. Never treat debug packages as official releases or a
  reinstall as proof of data-preserving updates.

Follow pinned SECURITY for operational authority. Infrastructure work permits
local reviewable edits and validation; commits/tags, pushes, publication and
external delivery require authorization in task scope. No standing publication
policy is adopted. Never expose or replace signing credentials. Record evidence
and remaining MUST gaps in [docs/STANDARDS_MIGRATION.md](docs/STANDARDS_MIGRATION.md).
