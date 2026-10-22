#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Manually deliver verified GitHub release bytes to a Google Play draft. No builds.

Authentication is supplied as a short-lived environment token by the official
Google auth action. No account keys/tokens enter plans, receipts or error logs.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
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
TRACKS = ('internal', 'alpha', 'beta', 'production')
API = 'https://androidpublisher.googleapis.com/androidpublisher/v3'
MEDIA = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3'
IDENTITY = ('destination', 'package', 'tag', 'track', 'version_code', 'sha256', 'source_sha')


class UploadError(Exception):
    pass


class PlayNetworkError(UploadError):
    """A write may have reached Google even if its response was lost."""


def gh(*arguments, binary=False):
    result = subprocess.run(['gh', *arguments], capture_output=True, text=not binary)
    if result.returncode:
        # gh errors can contain remote payloads. Report the operation, never credentials.
        raise UploadError(f'GitHub {arguments[0]} failed (exit {result.returncode})')
    return result.stdout if binary else json.loads(result.stdout)


def filenames(version):
    stem = f'DayRows-{version}-android-universal-'
    return {'aab': stem + 'play.aab', 'symbols': stem + 'symbols.zip', 'mapping': stem + 'mapping.txt'}


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
    if not any(run['head_sha'] == source and run['head_branch'] == tag
               for run in runs.get('workflow_runs', [])):
        raise UploadError('No successful Stable Release workflow exists for this exact tag/commit')
    release = gh('release', 'view', tag, '--repo', repo, '--json', 'tagName,assets')
    names = filenames(version)
    required = {*names.values(), 'SHA256SUMS.txt'}
    if release['tagName'] != tag or not required <= {asset['name'] for asset in release['assets']}:
        raise UploadError('Release is missing its AAB, symbols, mapping or checksums')
    directory.mkdir(parents=True, exist_ok=True)
    for name in sorted(required):
        result = subprocess.run(['gh', 'release', 'download', tag, '--repo', repo,
                                 '--pattern', name, '--dir', str(directory)], capture_output=True)
        if result.returncode:
            raise UploadError(f'Cannot download release asset {name}')
    # The checksum file also names the APK, which is not delivered to Google Play.
    # Verify all declared bytes with the existing strict gate, then retain only the delivery inputs.
    apk = f'DayRows-{version}-android-universal-release.apk'
    result = subprocess.run(['gh', 'release', 'download', tag, '--repo', repo,
                             '--pattern', apk, '--dir', str(directory)], capture_output=True)
    if result.returncode:
        raise UploadError('Cannot download APK for complete release checksum verification')
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
    hashes = {kind: sha256_file(str(directory / name)) for kind, name in names.items()}
    if any(digests.get(name) != hashes[kind] for kind, name in names.items()):
        raise UploadError('Delivery inputs differ from the release checksum manifest')
    record = dict(destination='google-play', package=PACKAGE, tag=tag, track=track,
                  version_code=code, source_sha=source, sha256=hashes, files=names,
                  signer_sha256=signer, status='prepared', edit_id=None,
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

    def request(self, method, path, body=None, file=None, mime=None):
        data = Path(file).read_bytes() if file else (json.dumps(body).encode() if body is not None else None)
        url = (MEDIA if file else API) + path + ('?uploadType=media' if file else '')
        request = Request(url, data=data, method=method, headers={
            'Authorization': 'Bearer ' + self.token,
            'Content-Type': mime or 'application/json'})
        try:
            with urlopen(request, timeout=300) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as error:
            raise UploadError(f'Google Play {method} {path}: HTTP {error.code}; check API access, track and app setup') from None
        except (URLError, TimeoutError, OSError):
            raise PlayNetworkError(f'Google Play {method} {path}: network failure; reconcile before retrying') from None


def same_delivery(first, second):
    return all(first.get(key) == second.get(key) for key in IDENTITY)


def confirmed_receipt(receipt):
    return receipt.get('status') in ('uploaded_draft', 'already_delivered') or (
        receipt.get('status') == 'pending_confirmation' and (
            receipt.get('commit_acknowledged') is True or receipt.get('draft_preexisted') is False))


def reconcile(api, base, record):
    bundles = api.request('GET', base + '/bundles').get('bundles', [])
    existing = next((b for b in bundles if b['versionCode'] == record['version_code']), None)
    if existing and existing.get('sha256', '').lower() != record['sha256']['aab']:
        raise UploadError('Google Play already has different bytes for this versionCode; nothing will be overwritten')
    track = api.request('GET', base + '/tracks/' + record['track'])
    release = next((r for r in track.get('releases', [])
                    if str(record['version_code']) in r.get('versionCodes', [])), None)
    return existing, track, release


def upload(directory, api):
    record = json.loads((directory / 'delivery.json').read_text())
    tag, track = record['tag'], record['track']
    if not STABLE.fullmatch(tag) or track not in TRACKS or record['package'] != PACKAGE:
        raise UploadError('Invalid delivery plan')
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
        edit_id = str(api.request('POST', root, {})['id'])
        base = root + '/' + quote(edit_id, safe='')
        record.update(edit_id=edit_id, status='reconciling')
        save(directory, record)
        existing, current, release = reconcile(api, base, record)
        record['draft_preexisted'] = release is not None
        if existing and release and same_delivery(prior, record) and confirmed_receipt(prior):
            record.update(status='already_delivered', prior_edit_id=prior.get('edit_id'),
                          remote_release_status=release['status'])
            save(directory, record)
            return record
        if release and release.get('status') != 'draft':
            raise UploadError('Version is already on this track outside draft state; no verified receipt, reconcile manually')
        if not release and any(int(code) > record['version_code']
                               for entry in current.get('releases', []) for code in entry.get('versionCodes', [])):
            raise UploadError('Track already contains a newer version; refusing an older draft')
        if not release and any(r.get('status') == 'draft' for r in current.get('releases', [])):
            raise UploadError('Track already has another draft; refusing to replace it')
        if not existing:
            bundle = api.request('POST', base + '/bundles', file=directory / record['files']['aab'],
                                 mime='application/octet-stream')
            if bundle.get('versionCode') != record['version_code'] or bundle.get('sha256', '').lower() != record['sha256']['aab']:
                raise UploadError('Google Play bundle response does not match the verified version/hash')
        for kind, symbol_type, mime in [('symbols', 'nativeCode', 'application/zip'),
                                        ('mapping', 'proguard', 'application/octet-stream')]:
            response = api.request('POST', base + f'/apks/{record["version_code"]}/deobfuscationFiles/{symbol_type}',
                                   file=directory / record['files'][kind], mime=mime)
            if response.get('deobfuscationFile', {}).get('symbolType') != symbol_type:
                raise UploadError(f'Google Play did not acknowledge the {symbol_type} attachment')
        if not release:
            desired = {'name': f'DayRows {tag[1:]}', 'versionCodes': [str(record['version_code'])], 'status': 'draft'}
            api.request('PUT', base + '/tracks/' + track,
                        {'track': track, 'releases': [*current.get('releases', []), desired]})
        api.request('POST', base + ':validate')
        # Persist before sending: a lost commit response must never cause a blind upload retry.
        record['status'] = 'pending_confirmation'
        save(directory, record)
        commit_sent = True
        api.request('POST', base + ':commit?changesNotSentForReview=true')
        commit_acknowledged = True
        record['commit_acknowledged'] = True
        save(directory, record)
        # Confirm in a fresh edit rather than trusting submission alone.
        check_id = str(api.request('POST', root, {})['id'])
        check_base = root + '/' + quote(check_id, safe='')
        try:
            verified, _, observed = reconcile(api, check_base, record)
            if not verified or not observed or observed.get('status') != 'draft':
                raise UploadError('Commit returned but the requested draft is not confirmed')
        finally:
            api.request('DELETE', check_base)
        record.update(status='uploaded_draft', remote_release_status='draft')
        save(directory, record)
        return record
    except Exception as error:
        uncertain = commit_acknowledged or (commit_sent and isinstance(error, PlayNetworkError))
        record['status'] = 'pending_confirmation' if uncertain else 'failed'
        save(directory, record)
        raise
    finally:
        if edit_id and (not commit_sent or record['status'] == 'failed'):
            try:
                api.request('DELETE', root + '/' + quote(edit_id, safe=''))
            except UploadError:
                print('Google Play temporary edit cleanup failed; no commit was submitted', file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    prepare_parser = commands.add_parser('prepare', help='download and verify, without Google credentials or API calls')
    for flag in ('repo', 'tag', 'track', 'expected-signer'):
        prepare_parser.add_argument('--' + flag, required=True)
    for command in (prepare_parser, commands.add_parser('upload'), commands.add_parser('summary')):
        command.add_argument('--directory', type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == 'prepare':
            prepare(arguments.repo, arguments.tag, arguments.track, arguments.directory, arguments.expected_signer)
        elif arguments.command == 'upload':
            record = upload(arguments.directory, PlayClient(os.environ.get('PLAY_ACCESS_TOKEN')))
            print(f'Google Play: {record["status"]}; rollout/review remain manual')
        else:
            record = json.loads((arguments.directory / 'delivery.json').read_text())
            print(f'Google Play **{record["status"]}** · {record["tag"]} · track {record["track"]} · versionCode {record["version_code"]}')
            print('\nDraft upload is separate from Play review, acceptance and rollout.')
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
