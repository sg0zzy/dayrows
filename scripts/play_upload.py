#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Manually deliver verified GitHub release bytes to a Google Play draft. No builds.

An Android App Bundle is self-describing: AGP embeds the R8 mapping (and, when
ndk.debugSymbolLevel is set, the native debug symbols) inside it, so the bundle
upload is the only artifact Play accepts for a bundle release. prepare() verifies
that fact from the bytes instead of attaching files to an APK-only endpoint.

Delivery commits a draft with changesInReviewBehavior=ERROR_IF_IN_REVIEW, because the
API default would cancel a review already in flight for the publisher's other changes.
Whether that commit also sends the change to Google for review is Google's decision for
the app: changesNotSentForReview is rejected when changes go to review automatically and
demanded when they cannot, so auto mode follows Google's own rejection text instead of
assuming either state.

Authentication is supplied as a short-lived environment token by the official
Google auth action. No account keys/tokens enter plans, receipts or error logs.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import struct
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
import zipfile

from build_debug import version_code
from release_publish import asset_digests, remote_tag_sha
from release_symbols import check as check_symbols
from release_verify import STABLE
from verify_release import aab_identity, require_identity, sha256_file, verify_aab_signature

PACKAGE = 'it.stefanogozzi.dayrows'
# Delivery only creates or preserves a draft. production is excluded: a production
# draft sits one console click from rollout, and the adopted distribution plan keeps
# every production action separately authorised.
TRACKS = ('internal', 'alpha', 'beta')
API = 'https://androidpublisher.googleapis.com/androidpublisher/v3'
MEDIA = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3'
IDENTITY = ('destination', 'package', 'tag', 'track', 'version_code', 'sha256', 'source_sha')
# Google error bodies are untrusted text: bound every extracted field.
MAX_ERROR_BYTES = 65_536
MAX_FIELD = 300
# An Android App Bundle carries its own deobfuscation data (AGP embeds the R8 mapping
# under this BUNDLE-METADATA entry) and, when ndk.debugSymbolLevel is set, its native
# debug symbols. Play therefore needs no separate symbol/mapping upload; the legacy
# `edits.deobfuscationfiles.upload` endpoint attaches to an *APK* artifact and rejects a
# bundle versionCode with HTTP 400.
BUNDLE_MAPPING = 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'
BUNDLE_SYMBOLS = 'base/native_debug_symbols/'
# edits.commit needs both query parameters stated explicitly. changesInReviewBehavior
# defaults to CANCEL_IN_REVIEW_AND_SUBMIT, which cancels an in-flight review of the
# publisher's other changes before publishing everything, so delivery pins
# ERROR_IF_IN_REVIEW and fails instead. changesNotSentForReview is only valid in one of
# the two app states: Google answers "Changes are sent for review automatically. The
# query parameter changesNotSentForReview must not be set." when the app's changes do go
# to review automatically, and demands the flag when they cannot.
IN_REVIEW_BEHAVIOR = 'ERROR_IF_IN_REVIEW'
REVIEW_MODES = ('auto', 'suppress')
HINTS = {400: 'Google rejected the request; correct what the reason names instead of retrying',
         401: 'the access token is missing or expired; rerun after authentication succeeds',
         403: 'the service account lacks Android Publisher access to this app in Play Console',
         404: 'the app, edit or track does not exist; an uncommitted edit expires on its own',
         409: 'another edit or conflicting change is in progress; retry later with the same artifact',
         429: 'Google rate-limited the request; retry later without changing the artifact'}


class UploadError(Exception):
    pass


class PlayNetworkError(UploadError):
    """A write may have reached Google even if its response was lost."""


class PlayApiError(UploadError):
    """A request Google rejected, carrying the documented error fields."""

    def __init__(self, message, *, method, path, status=None, api_status=None, reason=None, details=()):
        super().__init__(message)
        self.method = method
        self.path = path
        self.status = status
        self.api_status = api_status
        self.reason = reason
        self.details = tuple(details)

    def with_persistence(self, statement):
        """Say what Google may still be holding; never claim more than is known."""
        return UploadError(f'{self}; {statement}')


def clean(value, limit=MAX_FIELD):
    """Flatten and bound untrusted API text so a body can never become a log payload."""
    return re.sub(r'\s+', ' ', str(value)).strip()[:limit]


def google_error_fields(error):
    """Pull only documented error fields from a Google error body.

    The body is untrusted API text: never echo it raw, and never include request
    headers, the access token or artifact bytes.
    """
    try:
        payload = json.loads(error.read()[:MAX_ERROR_BYTES].decode('utf-8', 'replace'))
    except (ValueError, OSError, AttributeError):
        return {}
    body = payload.get('error') if isinstance(payload, dict) else None
    if not isinstance(body, dict):
        return {}
    details = []
    for entry in body.get('details') or ():
        if not isinstance(entry, dict):
            continue
        reason = entry.get('reason') or (entry.get('metadata') or {}).get('reason')
        item = {'domain': clean(entry.get('domain'), 60), 'reason': clean(reason, 120),
                'location': clean(entry.get('location') or entry.get('resource'), 100)}
        details.append({key: value for key, value in item.items() if value})
    first = next((entry['reason'] for entry in details if entry.get('reason')), '')
    fields = {'api_status': clean(body.get('status'), 60), 'reason': first,
              'message': clean(body.get('message'), MAX_FIELD * 2), 'details': tuple(details)}
    return {key: value for key, value in fields.items() if value}


def gh(*arguments, binary=False):
    result = subprocess.run(['gh', *arguments], capture_output=True, text=not binary)
    if result.returncode:
        # gh errors can contain remote payloads. Report the operation, never credentials.
        raise UploadError(f'GitHub {arguments[0]} failed (exit {result.returncode})')
    return result.stdout if binary else json.loads(result.stdout)


def filenames(version):
    stem = f'DayRows-{version}-android-universal-'
    return {'aab': stem + 'play.aab', 'symbols': stem + 'symbols.zip', 'mapping': stem + 'mapping.txt'}


def elf_has_symbol_table(data):
    """True/false from ELF section headers; None when the layout cannot be judged."""
    if len(data) < 64 or not data.startswith(b'\x7fELF') or data[5] not in (1, 2):
        return None
    endian = '<' if data[5] == 1 else '>'
    if data[4] == 2:
        offset = struct.unpack_from(endian + 'Q', data, 0x28)[0]
        entry_size, count = struct.unpack_from(endian + 'HH', data, 0x3a)
    elif data[4] == 1:
        offset = struct.unpack_from(endian + 'I', data, 0x20)[0]
        entry_size, count = struct.unpack_from(endian + 'HH', data, 0x2e)
    else:
        return None
    if not offset or not entry_size or not count or offset + entry_size * count > len(data):
        return None
    for index in range(count):
        # SHT_SYMTAB is 2, at sh_type in every ELF section header.
        if struct.unpack_from(endian + 'I', data, offset + index * entry_size + 4)[0] == 2:
            return True
    return False


def audit_bundle(aab_path, mapping_path):
    """Prove Google Play can deobfuscate and symbolicate from the bundle alone.

    An AAB is the whole delivery: Play reads the R8 mapping embedded by AGP and the
    native debug symbols AGP embeds when ndk.debugSymbolLevel is set. Nothing else can
    be attached to a bundle artifact, so verify the bundle is self-describing here
    instead of uploading bytes Play will reject.
    """
    audit = {'proguard_map': None, 'mapping_matches_bundle': False,
             'native_debug_symbols': 'absent', 'native_libraries': {},
             'native_symbols_action': 'none-required'}
    with zipfile.ZipFile(aab_path) as archive:
        names = archive.namelist()
        if BUNDLE_MAPPING in names:
            audit['proguard_map'] = hashlib.sha256(archive.read(BUNDLE_MAPPING)).hexdigest()
            audit['mapping_matches_bundle'] = audit['proguard_map'] == sha256_file(mapping_path)
        if any(name.startswith(BUNDLE_SYMBOLS) for name in names):
            audit['native_debug_symbols'] = 'embedded'
        for name in (entry for entry in names if entry.startswith('base/lib/') and entry.endswith('.so')):
            verdict = elf_has_symbol_table(archive.read(name))
            label = {True: 'unstripped', False: 'stripped'}.get(verdict, 'unknown')
            audit['native_libraries'][label] = audit['native_libraries'].get(label, 0) + 1
    if not audit['mapping_matches_bundle']:
        raise UploadError('The AAB does not embed the R8 mapping shipped as the release mapping asset; '
                          'Google Play could not deobfuscate this artifact, so delivery stopped before any upload')
    if audit['native_debug_symbols'] == 'absent' and audit['native_libraries'].get('unstripped'):
        raise UploadError('Native libraries keep symbol tables but the AAB embeds no native debug symbols; '
                          'set android.buildTypes.release.ndk.debugSymbolLevel (AGP >= 4.1) and rebuild so '
                          'Google Play receives them inside the bundle')
    if audit['native_debug_symbols'] == 'embedded':
        audit['native_symbols_action'] = 'delivered-inside-bundle'
    return audit


def save(directory, record):
    record['updated_at'] = datetime.now(timezone.utc).isoformat()
    path = directory / 'delivery.json'
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(record, indent=2) + '\n')
    temporary.replace(path)


def previous_receipt(repo, tag, track):
    """Read only the named JSON entry: never extract or execute a remote archive."""
    name = f'google-play-{tag}-{track}'
    result = subprocess.run(['gh', 'api', '--paginate', f'repos/{repo}/actions/artifacts?per_page=100',
                             '--jq', f'.artifacts[] | select(.name == "{name}" and .expired == false and .workflow_run.head_branch == "main") | [.id, .workflow_run.id] | @tsv'],
                            capture_output=True, text=True)
    if result.returncode:
        raise UploadError('Cannot query prior Google Play delivery evidence')
    for line in result.stdout.splitlines():
        identifier, run_id = line.split('\t')
        if not identifier.isdigit() or not run_id.isdigit():
            raise UploadError('Invalid delivery artifact identifier')
        run = gh('api', f'repos/{repo}/actions/runs/{run_id}')
        if run.get('path') != '.github/workflows/play-upload.yml' or run.get('event') != 'workflow_dispatch':
            continue
        raw = gh('api', f'repos/{repo}/actions/artifacts/{identifier}/zip', binary=True)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            if archive.getinfo('delivery.json').file_size > 64_000:
                raise UploadError('Delivery receipt is unexpectedly large')
            receipt = json.loads(archive.read('delivery.json'))
        if receipt.get('status') in ('uploaded_draft', 'already_delivered', 'pending_confirmation'):
            return receipt
    return None


def prepare(repo, tag, track, directory, expected_signer):
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', repo) or not STABLE.fullmatch(tag) or track not in TRACKS:
        raise UploadError('Expected an owner/repo, canonical stable tag and supported Play track')
    if not expected_signer:
        raise UploadError('DAYROWS_UPLOAD_CERT_SHA256 is required for Google Play delivery')
    version = tag[1:]
    ref = gh('api', f'repos/{repo}/git/ref/tags/{tag}')
    if ref.get('object', {}).get('type') != 'tag':
        raise UploadError('Google Play delivery requires an annotated release tag')
    source = remote_tag_sha(repo, tag)
    runs = gh('api', f'repos/{repo}/actions/workflows/release.yml/runs?head_sha={source}&event=push&status=success&per_page=100')
    completed = next((run for run in runs.get('workflow_runs', [])
                      if run['head_sha'] == source and run['head_branch'] == tag), None)
    if not completed:
        raise UploadError('No successful Stable Release workflow exists for this exact tag/commit')
    names = filenames(version)
    required = {*names.values(), 'SHA256SUMS.txt'}
    directory.mkdir(parents=True, exist_ok=True)
    try:
        release = gh('release', 'view', tag, '--repo', repo, '--json', 'tagName,assets')
    except UploadError:
        # GitHub hides draft releases from the read-only workflow token. Consume
        # only the already verified artifact from this exact successful tagged run.
        # Never broaden the token or rebuild delivery bytes to work around this.
        artifact_source = 'release-workflow-artifact'
        result = subprocess.run(['gh', 'run', 'download', str(completed['id']), '--repo', repo,
                                 '--name', tag + '-release', '--dir', str(directory)], capture_output=True)
        if result.returncode:
            raise UploadError('Release assets are not readable and its verified workflow artifact is unavailable; '
                              'publish the GitHub draft or restore the original verified artifacts')
    else:
        artifact_source = 'github-release-assets'
        if release['tagName'] != tag or not required <= {asset['name'] for asset in release['assets']}:
            raise UploadError('Release is missing its AAB, symbols, mapping or checksums')
        for name in sorted(required | {f'DayRows-{version}-android-universal-release.apk'}):
            result = subprocess.run(['gh', 'release', 'download', tag, '--repo', repo,
                                     '--pattern', name, '--dir', str(directory)], capture_output=True)
            if result.returncode:
                raise UploadError(f'Cannot download release asset {name}')
    # The checksum file also names the APK, which is not delivered to Google Play.
    # Verify all declared bytes with the existing strict gate, then retain only the delivery inputs.
    apk = f'DayRows-{version}-android-universal-release.apk'
    checksum_names = [line.partition('  ')[2] for line in
                      (directory / 'SHA256SUMS.txt').read_text().splitlines() if line.strip()]
    if set(checksum_names) != {*names.values(), apk} or len(checksum_names) != 4:
        raise UploadError('Unexpected or duplicate filename in release checksum manifest')
    digests = asset_digests(str(directory), str(directory / 'SHA256SUMS.txt'))
    aab = directory / names['aab']
    code = version_code(version)
    require_identity(str(aab), aab_identity(str(aab)), PACKAGE, version, code)
    signer = verify_aab_signature(str(aab), expected_signer)
    check_symbols(str(directory / names['symbols']), str(aab))
    embedded = audit_bundle(str(aab), str(directory / names['mapping']))
    hashes = {kind: sha256_file(str(directory / name)) for kind, name in names.items()}
    if any(digests.get(name) != hashes[kind] for kind, name in names.items()):
        raise UploadError('Delivery inputs differ from the release checksum manifest')
    record = dict(destination='google-play', package=PACKAGE, tag=tag, track=track,
                  version_code=code, source_sha=source, sha256=hashes, files=names,
                  signer_sha256=signer, status='prepared', phase='prepared_locally', edit_id=None,
                  artifact_uploaded=False, edit_committed=False, draft_confirmed=False,
                  remote_state='none', embedded=embedded,
                  artifact_source=artifact_source, release_run_id=completed['id'],
                  run_url=os.environ.get('GITHUB_SERVER_URL', 'https://github.com') + '/' + repo +
                  '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', 'local-verification'))
    prior = previous_receipt(repo, tag, track)
    if prior:
        (directory / 'previous-delivery.json').write_text(json.dumps(prior))
    save(directory, record)
    print(f'Verified {tag}, versionCode {code}, track {track}; Google Play has not been contacted')
    return record


class PlayClient:
    def __init__(self, token):
        if not token:
            raise UploadError('Google Play access token is missing')
        self.token = token

    def request(self, method, path, body=None, file=None, mime=None, operation='', context=''):
        data = Path(file).read_bytes() if file else (json.dumps(body).encode() if body is not None else None)
        url = (MEDIA if file else API) + path + ('?uploadType=media' if file else '')
        request = Request(url, data=data, method=method, headers={
            'Authorization': 'Bearer ' + self.token,
            'Content-Type': mime or 'application/json'})
        label = clean(operation or method + ' ' + path, 120)
        try:
            with urlopen(request, timeout=300) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as error:
            fields = google_error_fields(error)
            parts = [f'Google Play {label}: HTTP {error.code}', fields.get('api_status', ''),
                     fields.get('reason', '')]
            message = ' '.join(part for part in parts if part)
            if fields.get('message'):
                message += f'; Google said: {fields["message"]}'
            if fields.get('details'):
                message += '; details: ' + json.dumps(fields['details'], separators=(',', ':'))[:MAX_FIELD]
            if context:
                message += f'; {context}'
            if error.code in HINTS:
                message += f'; {HINTS[error.code]}'
            message = message.replace(self.token, '[redacted]') if self.token else message
            raise PlayApiError(message, method=method, path=path, status=error.code,
                               api_status=fields.get('api_status'), reason=fields.get('reason'),
                               details=fields.get('details', ())) from None
        except (URLError, TimeoutError, OSError) as error:
            raise PlayNetworkError(f'Google Play {label}: network failure '
                                   f'({clean(getattr(error, "reason", None) or error)}); the request may have '
                                   f'reached Google, so reconcile before retrying'
                                   + (f'; {context}' if context else '')) from None


def same_delivery(first, second):
    return all(first.get(key) == second.get(key) for key in IDENTITY)


def confirmed_receipt(receipt):
    return receipt.get('status') in ('uploaded_draft', 'already_delivered') or (
        receipt.get('status') == 'pending_confirmation' and (
            receipt.get('commit_acknowledged') is True or receipt.get('draft_preexisted') is False))


def delivery_context(record, edit_id=None, committed=False):
    """Non-secret identity of what is in flight, repeated in every failure message."""
    parts = [f'artifact={record["files"]["aab"]}', f'versionCode={record["version_code"]}',
             f'track={record["track"]}']
    if edit_id:
        parts.append(f'edit={edit_id}')
    return ' '.join(parts) + ' editCommitted=' + ('yes' if committed else 'no')


def reconcile(api, base, record, operation='read committed state'):
    bundles = api.request('GET', base + '/bundles', operation=operation,
                          context=delivery_context(record, record.get('edit_id'))).get('bundles', [])
    existing = next((b for b in bundles if b['versionCode'] == record['version_code']), None)
    if existing and existing.get('sha256', '').lower() != record['sha256']['aab']:
        raise UploadError(f'Google Play holds different bytes for versionCode '
                          f'{record["version_code"]}; refusing to overwrite them. Nothing was committed.')
    track = api.request('GET', base + '/tracks/' + record['track'], operation='read track',
                        context=delivery_context(record, record.get('edit_id')))
    release = next((r for r in track.get('releases', [])
                    if str(record['version_code']) in r.get('versionCodes', [])), None)
    return existing, track, release


def inspect_edit(api, directory, root, record, prior):
    """Open an edit and decide what may safely happen to it.

    Returns (base, existing, track, release, decision) with decision one of
    'already-delivered', 'attach-draft' or 'confirm-existing-draft'. A failed read
    closes its own edit, so a rejected request never leaves one open behind it.
    """
    edit_id = str(api.request('POST', root, {}, operation='create edit')['id'])
    base = root + '/' + quote(edit_id, safe='')
    record.update(edit_id=edit_id, status='reconciling', phase='reconciling')
    save(directory, record)
    try:
        existing, current, release = reconcile(api, base, record)
        record['draft_preexisted'] = release is not None
        if existing and release and same_delivery(prior, record) and confirmed_receipt(prior):
            decision = 'already-delivered'
        elif release and release.get('status') != 'draft':
            raise UploadError(f'versionCode {record["version_code"]} is already on track '
                              f'{record["track"]} as {release.get("status")}, not a draft; this run has '
                              'no verified receipt for it, so it will not change a served release. '
                              'Reconcile in Play Console manually.')
        elif not release and any(int(code) > record['version_code']
                                 for entry in current.get('releases', [])
                                 for code in entry.get('versionCodes', [])):
            raise UploadError('Track already contains a newer version; refusing an older draft')
        elif not release and any(r.get('status') == 'draft' for r in current.get('releases', [])):
            raise UploadError('Track already has another draft; refusing to replace it')
        else:
            decision = 'attach-draft' if not release else 'confirm-existing-draft'
    except UploadError:
        try:
            api.request('DELETE', base, operation='discard edit', context=delivery_context(record, edit_id))
        except UploadError:
            print('Google Play reconciliation edit could not be closed; nothing was committed, so it '
                  'expires on its own.', file=sys.stderr)
        raise
    return base, existing, current, release, decision


def mark_delivered(directory, record, prior, release):
    """Record that Google Play already holds this artifact and release."""
    record.update(status='already_delivered', phase='unchanged_existing',
                  prior_edit_id=prior.get('edit_id'), remote_release_status=release['status'],
                  artifact_uploaded=True, draft_confirmed=True, edit_committed=False,
                  remote_state=release['status'], last_error=None)
    save(directory, record)
    return record

def commit_query(suppress_review):
    """Build the edits.commit query with both review parameters stated explicitly."""
    query = 'changesInReviewBehavior=' + IN_REVIEW_BEHAVIOR
    return (query + '&changesNotSentForReview=true') if suppress_review else query


def google_demands_suppression(error):
    """True only when Google explicitly orders changesNotSentForReview on."""
    text = str(error).lower()
    return ('changesnotsentforreview' in text and ('cannot be sent for review automatically' in text
                                                   or 'set the query parameter' in text))


def google_refuses_suppression(error):
    """True when Google says this app sends changes for review automatically."""
    text = str(error).lower()
    return 'changesnotsentforreview' in text and 'must not be set' in text


def commit_edit(api, base, record, edit_id, mode):
    """Commit the edit using the review-submission form Google Play accepts.

    `auto` commits without changesNotSentForReview and adopts the flag only when
    Google's own rejection asks for it, because the accepted form depends on app state
    this tool cannot read. `suppress` insists on the flag and explains how to recover
    when Google refuses it. At most two commit requests are ever sent: a rejected
    commit persists nothing, so the same still-open edit is retried as-is, never a
    rebuilt or re-uploaded artifact. Both requests pin changesInReviewBehavior so an
    unrelated change already in review is never cancelled by this delivery.
    """
    suppress = mode == 'suppress'
    for attempt in (0, 1):
        try:
            api.request('POST', base + ':commit?' + commit_query(suppress),
                        operation='commit edit', context=delivery_context(record, edit_id))
            return 'suppressed' if suppress else 'automatic'
        except PlayApiError as error:
            if attempt == 0 and mode == 'auto' and google_demands_suppression(error):
                print('Google Play: this app cannot send changes for review automatically; retrying '
                      'the same open edit with changesNotSentForReview=true.', file=sys.stderr)
                suppress = True
                continue
            if suppress and google_refuses_suppression(error):
                raise UploadError(clean(f'{error}; the flag was requested explicitly by this dispatch; '
                                        're-dispatch with review_submission=auto so Google Play can pick '
                                        'the accepted form', 1200)) from None
            raise



def upload(directory, api, review_submission='auto'):
    if review_submission not in REVIEW_MODES:
        raise UploadError('review_submission must be one of ' + ', '.join(REVIEW_MODES))
    record = json.loads((directory / 'delivery.json').read_text())
    tag, track = record['tag'], record['track']
    if not STABLE.fullmatch(tag) or track not in TRACKS or record['package'] != PACKAGE:
        raise UploadError('Invalid delivery plan; production is never a delivery destination')
    if record['files'] != filenames(tag[1:]) or record['version_code'] != version_code(tag[1:]):
        raise UploadError('Unexpected delivery filenames or versionCode')
    for kind, name in record['files'].items():
        if sha256_file(str(directory / name)) != record['sha256'][kind]:
            raise UploadError('Verified input changed before Google Play upload')
    prior_path = directory / 'previous-delivery.json'
    prior = json.loads(prior_path.read_text()) if prior_path.exists() else dict(record)
    root = '/applications/' + PACKAGE + '/edits'
    edit_id = None
    commit_sent = False
    commit_acknowledged = False
    try:
        base, existing, current, release, decision = inspect_edit(api, directory, root, record, prior)
        edit_id = record['edit_id']
        if decision == 'already-delivered':
            return mark_delivered(directory, record, prior, release)
        if not existing:
            try:
                bundle = api.request('POST', base + '/bundles', file=directory / record['files']['aab'],
                                     mime='application/octet-stream', operation='upload signed AAB',
                                     context=delivery_context(record, edit_id))
                if (bundle.get('versionCode') != record['version_code']
                        or bundle.get('sha256', '').lower() != record['sha256']['aab']):
                    raise UploadError('Google Play returned versionCode '
                                      f'{bundle.get("versionCode")} and sha256 '
                                      f'{clean(bundle.get("sha256"), 64) or "absent"} instead of the '
                                      'verified artifact; nothing was committed')
            except PlayApiError as error:
                # Google may already serve this versionCode from an earlier committed edit or a manual
                # Play Console upload. Discard this edit and trust only freshly read committed state:
                # never increment versionCode, rebuild, resign or overwrite different bytes.
                api.request('DELETE', root + '/' + quote(edit_id, safe=''), operation='discard edit',
                            context=delivery_context(record, edit_id))
                edit_id = None
                base, existing, current, release, decision = inspect_edit(api, directory, root, record, prior)
                edit_id = record['edit_id']
                if decision == 'already-delivered':
                    return mark_delivered(directory, record, prior, release)
                if not existing:
                    raise error.with_persistence('nothing persisted: the versionCode is absent from freshly '
                                                 'read committed state and this edit was discarded') from None
                record['reused_committed_bundle'] = True
                record['bundle_upload_rejection'] = clean(error, 900)
            record['artifact_uploaded'] = True
            save(directory, record)
        if decision == 'attach-draft':
            desired = {'name': f'DayRows {tag[1:]}', 'versionCodes': [str(record['version_code'])],
                       'status': 'draft'}
            api.request('PUT', base + '/tracks/' + track,
                        {'track': track, 'releases': [*current.get('releases', []), desired]},
                        operation='attach draft release', context=delivery_context(record, edit_id))
        api.request('POST', base + ':validate', operation='validate edit',
                    context=delivery_context(record, edit_id, committed=False))
        # Persist before sending: a lost commit response must never cause a blind upload retry.
        record.update(status='pending_confirmation', phase='edit_ready', artifact_uploaded=True,
                      draft_requested=decision == 'attach-draft', review_submission=review_submission)
        save(directory, record)
        commit_sent = True
        review = commit_edit(api, base, record, edit_id, review_submission)
        commit_acknowledged = True
        record.update(commit_acknowledged=True, edit_committed=True, phase='edit_committed',
                      review_submission=review)
        save(directory, record)
        # Confirm in a fresh edit rather than trusting submission alone.
        check_id = str(api.request('POST', root, {}, operation='open read-only edit')['id'])
        check_base = root + '/' + quote(check_id, safe='')
        try:
            verified, _, observed = reconcile(api, check_base, record, operation='confirm committed draft')
            if not verified or not observed or observed.get('status') != 'draft':
                raise UploadError('Commit was accepted but the draft could not be read back; Google Play '
                                  'state is unconfirmed, so this run does not claim success')
        finally:
            api.request('DELETE', check_base, operation='close read-only edit')
        record.update(status='uploaded_draft', phase='draft_confirmed', remote_release_status='draft',
                      artifact_uploaded=True, draft_confirmed=True, remote_state='draft', last_error=None)
        save(directory, record)
        return record
    except Exception as error:
        uncertain = commit_acknowledged or (commit_sent and isinstance(error, PlayNetworkError))
        record['status'] = 'pending_confirmation' if uncertain else 'failed'
        record['phase'] = 'remote_state_uncertain' if uncertain else 'failed_before_persistence'
        record['remote_state'] = 'uncertain' if uncertain else 'none'
        record['last_error'] = clean(error, 900)
        save(directory, record)
        raise
    finally:
        if edit_id and (not commit_sent or record['status'] == 'failed'):
            try:
                api.request('DELETE', root + '/' + quote(edit_id, safe=''), operation='discard edit',
                            context=delivery_context(record, edit_id))
            except UploadError:
                record['remote_state'] = 'edit-discard-unconfirmed'
                record['phase'] = 'remote_state_uncertain'
                save(directory, record)
                print('Google Play temporary edit cleanup failed; no commit was submitted, so nothing can '
                      'be served. The edit expires on its own.', file=sys.stderr)


def state_view(record, outcome='', dry_run=False):
    """Describe the state Google Play is actually in, never the requested operation."""
    status = record.get('status')
    attempted = outcome in ('success', 'failure', 'cancelled')
    if status == 'prepared' and not attempted:
        headline = 'Prepared locally — Google Play was not contacted'
        detail = ('Dry run: only release verification ran, as requested.' if dry_run else
                  'The upload step did not run, so no edit was opened and nothing was uploaded.')
        return headline, detail, False
    if status == 'prepared':
        return ('Failed before Google Play persistence',
                f'The upload step ended as “{outcome}” before any artifact was uploaded, so the receipt still '
                'records local preparation only. Nothing persisted and no edit was committed.', False)
    if status == 'uploaded_draft':
        review = {'automatic': 'committed while Google Play sends this app’s changes for review '
                               'automatically',
                  'suppressed': 'committed with `changesNotSentForReview=true`, so the changes wait for '
                                'manual review submission'}.get(record.get('review_submission'), 'committed')
        return ('Draft confirmed in Google Play',
                f'The edit was {review}, and the draft was read back from a second, read-only edit. The '
                'release stays `draft`: nothing is rolled out and no track is promoted.', True)
    if status == 'already_delivered':
        return ('Already present in Google Play — nothing changed',
                f'versionCode {record.get("version_code")} is on track {record.get("track")} as '
                f'{record.get("remote_release_status")}, matching this verified artifact, so this run '
                'uploaded and committed nothing.', True)
    if status == 'pending_confirmation':
        return ('Remote state uncertain — reconcile before retrying',
                'A commit was sent and its outcome is unknown, so the draft may or may not exist. The next '
                'run reconciles against committed state first; it never increments versionCode, rebuilds or '
                'overwrites different bytes.', False)
    return ('Failed before persistence',
            'No edit was committed, so nothing can be served to testers. The failing operation, Google’s own '
            'reason and the artifact context are recorded below.', False)


def review_submission_view(record):
    """Say what the committed edit actually did about review submission."""
    if not record.get('edit_committed'):
        return 'not sent (no commit)'
    return {'automatic': 'Google Play sends changes for review automatically',
            'suppressed': 'held with `changesNotSentForReview=true`'}.get(record.get('review_submission'),
                                                                          'unknown')


def summary_text(record, outcome='', dry_run=False):
    """Job summary: exact state, artifact identity and the non-secret verification trail."""
    headline, detail, confirmed = state_view(record, outcome, dry_run)
    embedded = record.get('embedded') or {}
    files = record.get('files') or {}
    mapping = 'inside the AAB, verified against mapping.txt' if embedded.get('mapping_matches_bundle') else \
        'not verified'
    rows = [('State', f'`{record.get("phase", record.get("status"))}` (status `{record.get("status")}`)'),
            ('Artifact', f'`{files.get("aab", "unknown")}`'),
            ('AAB SHA-256', record.get('sha256', {}).get('aab', 'unknown')),
            ('Upload signer', record.get('signer_sha256', 'unknown')),
            ('Google Play edit', record.get('edit_id') or 'not opened'),
            ('Artifact uploaded', 'yes' if record.get('artifact_uploaded') else 'no'),
            ('Edit committed', 'yes' if record.get('edit_committed') else 'no'),
            ('Review submission', review_submission_view(record)),
            ('Remote release', record.get('remote_release_status') or record.get('remote_state') or 'none'),
            ('R8 mapping', mapping),
            ('Native debug symbols', embedded.get('native_debug_symbols', 'not verified')),
            ('Verified inputs', f'{record.get("artifact_source", "unknown")} · run '
                               f'{record.get("release_run_id", "unknown")}')]
    lines = [f'## Google Play delivery · {record.get("tag")} · versionCode '
             f'{record.get("version_code")} · track {record.get("track")}', '', f'**{headline}**', '', detail,
             '', '| Field | Value |', '| --- | --- |']
    lines += [f'| {key} | {value} |' for key, value in rows]
    if record.get('last_error'):
        lines += ['', '```text', f'Failure: {record["last_error"]}', '```']
    lines += ['', 'Rollout stays manual: this workflow never rolls out, never promotes a release between '
              'tracks and never delivers to production. Whether committed changes go to Google for review is '
              f'decided by Google Play for the app; the commit pins `changesInReviewBehavior=' +
              IN_REVIEW_BEHAVIOR + '`, so a change already in review is reported, never cancelled.']
    return '\n'.join(lines), confirmed


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    prepare_parser = commands.add_parser('prepare', help='download and verify, without Google credentials or API calls')
    for flag in ('repo', 'tag', 'track', 'expected-signer'):
        prepare_parser.add_argument('--' + flag, required=True)
    upload_parser = commands.add_parser('upload', help='deliver the verified bytes and commit a draft')
    upload_parser.add_argument('--review-submission', choices=REVIEW_MODES, default='auto',
                               help='auto lets Google Play choose whether committed changes go to review, '
                                    'following its own rejection text; suppress insists on '
                                    'changesNotSentForReview=true')
    summary_parser = commands.add_parser('summary', help='render the state Google Play is actually in')
    summary_parser.add_argument('--upload-outcome', default='',
                                help='GitHub outcome of the upload step, when that step ran')
    summary_parser.add_argument('--dry-run', action='store_true',
                                help='the workflow ran without contacting Google Play')
    for command in (prepare_parser, upload_parser, summary_parser):
        command.add_argument('--directory', type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == 'prepare':
            prepare(arguments.repo, arguments.tag, arguments.track, arguments.directory, arguments.expected_signer)
        elif arguments.command == 'upload':
            record = upload(arguments.directory, PlayClient(os.environ.get('PLAY_ACCESS_TOKEN')),
                            arguments.review_submission)
            print(f'Google Play: {record["phase"]} (status {record["status"]}, review submission '
                  f'{record.get("review_submission", "not sent")}); acceptance and rollout remain manual')
        else:
            record = json.loads((arguments.directory / 'delivery.json').read_text())
            text, confirmed = summary_text(record, arguments.upload_outcome, arguments.dry_run)
            print(text)
            if not confirmed and os.environ.get('GITHUB_ACTIONS') and (
                    arguments.upload_outcome or record.get('status') != 'prepared'):
                print(f'::error::Google Play delivery reached {record.get("status")} '
                      f'({record.get("phase")}); see the job summary')
        return 0
    except (UploadError, ValueError, OSError, RuntimeError) as error:
        print(f'Google Play delivery failed: {error}', file=sys.stderr)
        return 1
    except Exception as error:
        # Unexpected HTTP/client/verification payloads must never dump credentials.
        print(f'Google Play delivery failed: {type(error).__name__}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
