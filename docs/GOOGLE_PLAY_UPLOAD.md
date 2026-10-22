# Manual Google Play upload

**Upload to Google Play** (`play-upload.yml`) is a separate, manually dispatched
workflow. It consumes an existing successful Stable Release build, including a
GitHub draft release, without rebuilding or changing the app version. Dispatch
authorizes upload of that tag's verified files to the chosen Play track. It never
runs on push, tags or release publication, and never starts a rollout.

The workflow uploads the signed AAB, the native `symbols.zip` as `nativeCode`, and
the separate R8 `mapping.txt` as `proguard`. It creates a **draft** on the selected
track (default `internal`), with changes not sent for review. Review and rollout
remain manual in Play Console. GitHub release publication is independent.

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
AAB package/version/code/signer and verifies native symbols against the bundle.
It neither authenticates to Google nor contacts Google Play. The selected tag must
be annotated and have a successful `release.yml` run at its exact source SHA.
Files are read from GitHub release assets; temporary Actions build artifacts are
not required and APK/AAB/symbols/mapping are never rebuilt for delivery.

Run again with `dry_run=false` to perform the upload:

```bash
gh workflow run play-upload.yml --repo sg0zzy/dayrows --ref main \
  -f release_tag=v0.1.3 -f track=internal -f dry_run=false
```

Wait for the workflow summary's `uploaded_draft` (or `already_delivered`). Open
the selected track in Play Console, inspect the draft/version and both symbol
attachments, then manually send for review or release. Upload completion is not
review approval, tester availability or production rollout.

## Integrity, retries and evidence

All Play deliveries for this application share one concurrency queue with no
automatic cancellation. Each run queries existing bundles and the target track
before writing. A matching bundle is reused; another payload at the same
versionCode fails. Another draft is never replaced, and existing active releases
are preserved. Concurrent edits made outside this workflow can invalidate the
Google edit; the run fails rather than blindly retrying.

`google-play-<tag>-<track>` workflow artifacts retain `delivery.json` for 90 days.
The receipt includes source SHA, package/versionCode, destination/track, all three
input hashes, signer, edit ID, run URL and separate delivery status. It never
includes keys or tokens. Archive receipts externally if longer retention is
needed; expired/deleted artifacts are not a durable historical ledger.

A repeat matching a trusted prior receipt and Google's bundle/track state is a
no-op, including if the publisher has since rolled out the draft. Receipts are
accepted only from this manual workflow on `main`. Google does not expose symbol
hashes through this API; without a receipt, an existing matching **draft** can have
its verified symbol attachments uploaded again. A version already outside draft
state without matching evidence fails for manual reconciliation rather than
changing a live release.

Before commit, any failure abandons the temporary edit and records `failed`.
If the commit response is lost or its final state cannot be confirmed, record
`pending_confirmation`, retain the edit ID and reconcile against Play on the next
manual run. Do not upload a rebuilt AAB under an existing version code. A rejected
binary/source requires a new release/version, following the existing policy.

Useful API references: [bundle hashes](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles),
[draft track releases](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks),
[native/proguard attachments](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.deobfuscationfiles),
and [edit commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit).

## Validation and limits

Unit tests use fake Play replies and cover hash conflicts, active/draft track
preservation, successful retries, ambiguous commit responses, symbols/mapping
errors and credential-safe errors. Local `prepare` validation can use the real
GitHub release without Play credentials. Actual Google authentication, permission
scope, API acceptance, review and rollout require the configured service account
and a deliberate manual dispatch; they are not claimed by local tests.
