# Manual Google Play upload

**Upload to Google Play** (`play-upload.yml`) is a separate, manually dispatched
workflow. It consumes an existing successful Stable Release build, including a
GitHub draft release, without rebuilding or changing the app version. Dispatch
authorizes upload of that tag's verified files to the chosen Play track. It never
runs on push, tags or release publication, and never starts a rollout.

The workflow uploads the signed AAB and creates a **draft** release on the selected
track (default `internal`), then commits the Google Play edit with
`changesNotSentForReview=true`. Committing an edit persists it; it is not
publication. Review, acceptance and rollout stay manual in Play Console, and
GitHub release publication stays independent. The track choice is `internal`,
`alpha` or `beta` only: `production` is deliberately not offered, because a
production draft sits one console click from rollout.

## Why nothing else is uploaded

An Android App Bundle is self-describing, and a bundle is the only artifact this
workflow uploads:

- **R8/ProGuard mapping.** AGP embeds it at
  `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`. Google Play
  reads it from the bundle to retrace Java/Kotlin crash stack traces. For v0.1.5
  that embedded entry is byte-identical to the published `mapping.txt`
  (SHA-256 `65e6f31f…128a`).
- **Native debug symbols** exist only if
  `android.buildTypes.release.ndk.debugSymbolLevel` is set; AGP then packs them
  into the bundle and Play reads them from there. DayRows has no NDK build: all
  eight shipped `.so` files are stripped prebuilt libraries, so there is nothing
  to symbolicate. `release_symbols.py` packages those bytes purely as provenance.

`scripts/play_upload.py prepare` proves both statements from the bundle bytes and
stops before uploading if either stops being true: the embedded mapping must match
the release `mapping.txt`, and unstripped libraries without symbols embedded in the
AAB are rejected with instructions to set `debugSymbolLevel`. Uploading symbols or
mapping separately is not possible for a bundle anyway — the legacy
`edits.deobfuscationfiles.upload` endpoint attaches a file to an **APK** artifact,
and Google rejects a bundle versionCode there with HTTP 400 (this was the failure
behind the 2026-10-25 incident, see [Failure history](#failure-history)).

## One-time setup

1. Use a Google Cloud project with the Google Play Android Developer API enabled.
   Create a dedicated service account, then invite its email in Play Console
   **Users and permissions**. Limit access to `it.stefanogozzi.dayrows` and the
   release permissions needed for the selected track. No billing, financial or
   administrator access is needed for this uploader. For internal testing, prefer
   testing-track rights; grant production release rights only when needed.
2. In GitHub repository **Settings → Environments**, create `google-play` and
   restrict deployment branches to `main`. Configure required reviewers if desired
   and supported by the repository plan. Only reviewed default-branch uploader
   code may receive the credential; the selected release tag is data, not code
   executed by this workflow.
3. Store the service-account JSON as environment secret
   **`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`**. Use the GitHub UI or read the existing
   private file via stdin:

   ```bash
   gh secret set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON \
     --repo sg0zzy/dayrows --env google-play < /secure/path/play-service-account.json
   ```

   Keep this credential outside the repository, logs and chat. The workflow uses
   the SHA-pinned official Google auth action to mint a short-lived token scoped
   to `androidpublisher`; no credential file is created in the workspace.
4. Confirm repository variable **`DAYROWS_UPLOAD_CERT_SHA256`** contains the
   expected AAB upload signer fingerprint. It is mandatory for this destination;
   it is the existing upload certificate, not the separate Play app-signing key.
   Do not replace signing identities or allocate new version codes for this setup.
5. The Play app and its initial listing/setup must already exist. The publisher
   still owns Play's historical version-code inventory, store declarations and
   acceptance checks; this workflow does not establish those facts.

Google's current setup instructions are in [API access setup](https://developers.google.com/android-publisher/getting_started).
Do not grant the billing permissions listed there for unrelated Billing APIs.

## Run

After this workflow is committed to `main`, open **Actions → Upload to Google Play
→ Run workflow**, select branch `main`, enter `v0.1.3` (or another existing stable
release tag) and choose a track. Leave **dry_run** enabled for the first check:

```bash
gh workflow run play-upload.yml --repo sg0zzy/dayrows --ref main \
  -f release_tag=v0.1.3 -f track=internal -f dry_run=true
```

A dry run downloads release files, verifies all four release hashes, checks the
AAB package/version/code/signer and audits the bundle for its embedded mapping and
native symbols. It neither authenticates to Google nor contacts Google Play. The
selected tag must be annotated and have a successful `release.yml` run at its exact
source SHA. Published release assets are preferred. GitHub's read-only workflow
token cannot read draft release assets, so for drafts the uploader downloads only
the named `<tag>-release` artifact from that exact successful Stable Release run.
Those artifacts expire after 14 days; after expiry, publish the GitHub draft to
make its original release assets readable. Both paths verify the same full
checksums and upload signer. The receipt records the selected source and release
run ID. APK/AAB/symbols/mapping are never rebuilt for delivery.

Run again with `dry_run=false` to perform the upload:

```bash
gh workflow run play-upload.yml --repo sg0zzy/dayrows --ref main \
  -f release_tag=v0.1.3 -f track=internal -f dry_run=false
```

## What one delivery does

1. `prepare` — resolve the annotated tag to its source SHA, require the matching
   successful Stable Release run, download the four release assets, verify every
   hash against `SHA256SUMS.txt`, verify package/version/versionCode and the upload
   certificate signature, audit the bundle, and read any prior receipt. No Google
   contact.
2. `POST /edits` — open an edit. An edit is a private draft of the store state:
   uploads inside it are invisible and unpersisted until commit.
3. `GET /edits/{id}/bundles`, `GET /edits/{id}/tracks/{track}` — read committed
   state and decide what is safe.
4. `POST /edits/{id}/bundles` — upload the AAB once; the response versionCode and
   SHA-256 must equal the verified values.
5. `PUT /edits/{id}/tracks/{track}` — append a release with
   `{"versionCodes": ["<code>"], "status": "draft"}` plus a name. No `userFraction`,
   no rollout fields, existing releases preserved verbatim.
6. `POST /edits/{id}:validate` — ask Google to check the edit without applying it.
7. `POST /edits/{id}:commit?changesNotSentForReview=true` — persist. The flag is
   what guarantees the changes are not submitted for review.
8. Open a second edit, read the track back and require a `draft` release naming
   that versionCode, then delete the read-only edit. Only then does the run claim
   success.

Every failure path closes the edit it opened; an abandoned edit discards its
uploads, so a failed run persists nothing.

| Reported state | Meaning |
| --- | --- |
| `prepared` (dry run or upload skipped) | Google Play was not contacted |
| `failed` / `failed_before_persistence` | edit abandoned; nothing persisted |
| `pending_confirmation` / `remote_state_uncertain` | commit outcome unknown; reconcile before retrying |
| `uploaded_draft` / `draft_confirmed` | draft committed and read back; not submitted, not rolled out |
| `already_delivered` / `unchanged_existing` | Play already holds this artifact and release; nothing changed |

Wait for `uploaded_draft` (or `already_delivered`), open the track in Play Console,
inspect the draft, then send it for review or roll it out manually. Upload
completion is not review approval, tester availability or production rollout.

## Integrity, retries and evidence

All Play deliveries for this application share one concurrency queue with no
automatic cancellation. Each run reads existing bundles and the target track before
writing. A matching bundle is reused; different bytes at the same versionCode fail
without overwriting. Another draft is never replaced, a served release is never
downgraded, and a newer track version is never superseded by an older draft.
Production is rejected before any request.

Duplicate versionCode is handled by re-reading state, never by inventing a version:
if Google rejects the upload because the versionCode already exists, the run
discards its edit, opens a fresh one and trusts only committed state. An identical
bundle is then reused for the draft; different bytes stop the run; and a
versionCode that is still absent after the re-read is reported as a failure that
persisted nothing. Google documents no machine-readable duplicate error code, so
the decision comes from re-read state rather than from error strings.

A repeat matching a trusted prior receipt and Google's bundle/track state is a
no-op, including if the publisher has since rolled out the draft. Receipts are
accepted only from this manual workflow on `main`. A version already outside draft
state without matching evidence fails for manual reconciliation rather than
changing a live release. If retrying still requires manual intervention, the run
stops with the reason instead of escalating to a rebuild or a new versionCode.

Before commit, any failure abandons the temporary edit and records `failed`. If the
commit response is lost or its final state cannot be confirmed, record
`pending_confirmation`, retain the edit ID and reconcile on the next manual run —
the next run reads committed state first and uploads nothing that already exists. A
rejected binary/source requires a new release/version, following the existing
policy. Do not upload a rebuilt AAB under an existing version code.

`google-play-<tag>-<track>` workflow artifacts retain `delivery.json` for 90 days.
The receipt includes source SHA, package/versionCode, destination/track, all three
input hashes, signer, edit ID, the bundle audit, phase flags (artifact uploaded,
edit committed, draft confirmed), a bounded sanitized failure reason, run URL and
delivery status. It never includes keys or tokens. Archive receipts externally if
longer retention is needed; expired/deleted artifacts are not a durable historical
ledger.

Errors report the failing operation, HTTP status, Google's error `status`, the
first `reason`, a bounded `message`, the artifact filename, versionCode, track,
edit ID and whether the edit was committed. Google's raw error body is parsed, not
echoed, and the access token is redacted from every message.

## Failure history

- **2026-10-25 — HTTP 400 on `deobfuscationFiles/nativeCode`.** The uploader
  uploaded the bundle correctly, then posted `symbols.zip` as `nativeCode` and
  `mapping.txt` as `proguard` to `apks/6/deobfuscationFiles/…`, an endpoint that
  attaches files to an APK artifact. No APK with versionCode 6 exists, so Google
  rejected the first post; the edit was abandoned, so nothing persisted and the
  draft never appeared in Play Console. The job summary at the time printed the
  stale `prepared` receipt, which is why the failure looked like a success line.
  Fixed by dropping both uploads (the bundle carries the mapping), reporting the
  real state, and adding the bundle audit.
- **2026-10-25 — empty credential.** Two earlier runs failed in
  `Authenticate to Google Play` because `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` resolved
  to nothing for the `google-play` environment; the auth action failed closed, as
  required.

## Validation and limits

Unit tests use a stateful fake of the androidpublisher Edits contract and cover:
successful AAB delivery, embedded-mapping and native-symbol audits, missing symbol
material, HTTP 400/403 diagnostics, failures before commit, successful draft
commit, duplicate versionCode, safe retries, unexpected track states, validation
failure, production refusal and the guarantee that no rollout or review submission
can be expressed. Local `prepare` validation can use the real GitHub release
without Play credentials. Actual Google authentication, permission scope, API
acceptance, review and rollout require the configured service account and a
deliberate manual dispatch; they are not claimed by local tests.

Useful API references: [bundle upload](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload),
[draft track releases](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks),
[edit commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit),
[native symbol handling in app bundles](https://developer.android.com/build/include-native-symbols),
and the [bundle format](https://developer.android.com/guide/app-bundle/app-bundle-format).
