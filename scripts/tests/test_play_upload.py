# SPDX-License-Identifier: GPL-3.0-or-later
"""Play delivery tests use a stateful androidpublisher fake: no credentials, no store writes.

The fake models the parts of the Edits contract the delivery depends on: an edit
inherits committed state, uploads stay inside the edit until commit, commit publishes
the edit's snapshot and delete discards it. It raises if any code touches an APK-only
endpoint, so a bundle release can never regress into per-APK symbol attachment.
"""
import copy
import io
import json
import shutil
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile
from collections import namedtuple
from types import SimpleNamespace
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import play_upload as play

Call = namedtuple('Call', 'method path body file mime operation context')
MAP_BYTES = b'# compiler: R8\na -> a:\n'
APK_ONLY = ('deobfuscationFile', 'binarySymbol', '/apks/')

GOOGLE_REJECTION = {'error': {'code': 400, 'status': 'INVALID_ARGUMENT',
                              'message': 'Apk for versionCode 4 does not exist in this edit.',
                              'details': [{'@type': 'type.googleapis.com/google.rpc.ErrorInfo',
                                           'reason': 'APK_NOT_FOUND',
                                           'domain': 'androidpublisher.googleapis.com'}]}}


def http_error(code, payload=None, padding=b''):
    body = (json.dumps(payload).encode() if payload is not None else padding) or b''
    return HTTPError('https://androidpublisher.googleapis.com/androidpublisher/v3', code,
                     'rejected', {}, io.BytesIO(body))


def fake_elf(bits=64, symbol_types=(0,)):
    """Minimal but valid ELF header plus section headers for the symbol probe."""
    entry = 64 if bits == 64 else 40
    start = 0x40
    table = bytearray(entry * len(symbol_types))
    for index, kind in enumerate(symbol_types):
        struct.pack_into('<I', table, index * entry + 4, kind)
    data = bytearray(start) + table
    if bits == 64:
        struct.pack_into('<Q', data, 0x28, start)
        struct.pack_into('<HH', data, 0x3a, entry, len(symbol_types))
    else:
        struct.pack_into('<I', data, 0x20, start)
        struct.pack_into('<HH', data, 0x2e, entry, len(symbol_types))
    data[0:4] = b'\x7fELF'
    data[4] = 1 if bits == 32 else 2
    data[5] = 1
    return bytes(data)


class FakePlay:
    def __init__(self, record, releases=None, bundles=None):
        self.record = record
        self.track = record['track']
        self.edits = {}
        self.calls = []
        self.rejections = []
        self.fail_validate = None
        self.fail_commit = None
        self.fail_delete = False
        self.forget_draft_on_commit = False
        self.committed = {'bundles': copy.deepcopy(bundles or []),
                          'tracks': {self.track: {'track': self.track,
                                                  'releases': copy.deepcopy(releases or [])}}}
        self.commits = 0
        self.publish_on_upload = None

    def reject_next(self, fragment, error, times=1, method='POST'):
        self.rejections.append([method, fragment, error, times])

    # --- helpers --------------------------------------------------------------
    def media(self):
        return [call for call in self.calls if call.file]

    def open_edit_ids(self):
        return set(self.edits)

    def body(self, fragment, method=None):
        return [call for call in self.calls if fragment in call.path
                and (method is None or call.method == method)]

    def request(self, method, path, body=None, file=None, mime=None, operation='', context=''):
        self.calls.append(Call(method, path, body, file, mime, operation, context))
        for fragment in APK_ONLY:
            if fragment in path:
                raise AssertionError(f'bundle delivery must never call the APK endpoint {path}')
        for entry in self.rejections:
            if entry[3] > 0 and entry[0] == method and entry[1] in path:
                entry[3] -= 1
                raise entry[2]
        if method == 'POST' and path.endswith('/edits'):
            identifier = str(len(self.edits) + self.commits + 1)
            self.edits[identifier] = copy.deepcopy(self.committed)
            self.edits[identifier]['closed'] = False
            return {'id': identifier}
        remainder = path.split('/edits/', 1)[1]
        identifier, _, suffix = remainder.partition('/')
        if ':' in identifier:
            identifier, suffix = identifier.split(':', 1)
            suffix = ':' + suffix
        state = self.edits.get(identifier)
        if state is None or state.get('closed'):
            raise play.PlayApiError(f'edit {identifier} is gone', method=method, path=path, status=404)
        if suffix == ':commit' or suffix.startswith(':commit'):
            if self.fail_commit is not None:
                error = self.fail_commit
                self.fail_commit = None
                raise error
            self.commits += 1
            snapshot = {key: copy.deepcopy(value) for key, value in state.items() if key != 'closed'}
            if self.forget_draft_on_commit:
                snapshot['tracks'] = {key: {'track': key, 'releases': []} for key in snapshot['tracks']}
            self.committed = snapshot
            state['closed'] = True
            del self.edits[identifier]
            return {}
        if suffix == ':validate':
            if self.fail_validate is not None:
                raise self.fail_validate
            return {}
        if method == 'DELETE':
            if self.fail_delete:
                raise play.PlayApiError('edit cleanup rejected', method=method, path=path, status=500)
            del self.edits[identifier]
            return {}
        if suffix == 'bundles' and method == 'GET':
            return {'bundles': state['bundles']}
        if suffix == 'bundles' and method == 'POST':
            bundle = {'versionCode': self.record['version_code'], 'sha256': self.record['sha256']['aab']}
            if self.publish_on_upload is not None:
                # A race: the versionCode became committed between our read and our upload.
                self.committed['bundles'].append(copy.deepcopy(bundle))
                error, self.publish_on_upload = self.publish_on_upload, None
                raise error
            state['bundles'].append(bundle)
            return bundle
        if suffix.startswith('tracks/'):
            name = suffix.split('/', 1)[1]
            if method == 'PUT':
                state['tracks'][name] = body
            return state['tracks'].get(name, {'track': name, 'releases': []})
        raise AssertionError(f'unexpected request {method} {path}')


class PlayUploadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.record = dict(destination='google-play', package=play.PACKAGE, tag='v0.1.3',
                           track='internal', version_code=4, source_sha='a' * 40,
                           files=play.filenames('0.1.3'), sha256={}, status='prepared',
                           phase='prepared_locally', artifact_uploaded=False, edit_committed=False,
                           draft_confirmed=False, remote_state='none',
                           embedded={'mapping_matches_bundle': True, 'native_debug_symbols': 'absent'})
        for kind, name in self.record['files'].items():
            path = self.directory / name
            path.write_bytes(kind.encode())
            self.record['sha256'][kind] = play.sha256_file(str(path))
        play.save(self.directory, self.record)

    # --- helpers --------------------------------------------------------------
    def state(self):
        return json.loads((self.directory / 'delivery.json').read_text())

    def prior(self, **updates):
        receipt = {**copy.deepcopy(self.record), 'status': 'uploaded_draft', 'edit_id': 'previous', **updates}
        (self.directory / 'previous-delivery.json').write_text(json.dumps(receipt))

    def draft(self, status='draft'):
        return {'name': 'DayRows 0.1.3', 'versionCodes': ['4'], 'status': status}

    def bundle(self, sha256=None):
        return {'versionCode': 4, 'sha256': sha256 or self.record['sha256']['aab']}

    def bundle_bytes(self, text=b'zip', mapping=MAP_BYTES, symbols=False):
        path = self.directory / 'bundle.aab'
        with zipfile.ZipFile(path, 'w') as archive:
            if mapping is not None:
                archive.writestr(play.BUNDLE_MAPPING, mapping)
            archive.writestr(text.decode() if isinstance(text, str) else 'base/dex/classes.dex', b'dex')
            if symbols:
                archive.writestr(play.BUNDLE_SYMBOLS + 'arm64-v8a/lib.so', b'sym')
            archive.writestr('base/lib/arm64-v8a/lib.so', fake_elf(64, (0, 1, 3)))
        return path

    def mapping_file(self, content=MAP_BYTES):
        path = self.directory / 'mapping.txt'
        path.write_bytes(content)
        return path

    # --- case 1/2/7/12: successful draft delivery -----------------------------
    def test_uploads_only_the_signed_aab_and_confirms_the_draft(self):
        live = {'name': 'old', 'versionCodes': ['3'], 'status': 'completed'}
        api = FakePlay(self.record, releases=[live])
        result = play.upload(self.directory, api)
        self.assertEqual('uploaded_draft', result['status'])
        self.assertEqual('draft_confirmed', result['phase'])
        self.assertTrue(result['artifact_uploaded'] and result['edit_committed'] and result['draft_confirmed'])
        self.assertEqual(1, len(api.media()))
        self.assertTrue(api.media()[0].path.endswith('/bundles'))
        self.assertEqual([live, self.draft()], api.committed['tracks']['internal']['releases'])
        self.assertEqual({'name': 'DayRows 0.1.3', 'versionCodes': ['4'], 'status': 'draft'},
                         api.committed['tracks']['internal']['releases'][1])
        self.assertEqual(1, api.commits)        # exactly one commit for a fresh delivery
        self.assertFalse(api.open_edit_ids())     # delivery and verification edits both closed

    def test_bundle_delivery_never_touches_apk_only_endpoints(self):
        # Regression for the HTTP 400: a bundle release has no APK to attach files to.
        api = FakePlay(self.record)
        play.upload(self.directory, api)
        self.assertFalse([call for call in api.calls if any(f in call.path for f in APK_ONLY)])
        self.assertEqual(1, len(api.media()))

    def test_commit_never_rolls_out_and_pins_the_in_review_behaviour(self):
        api = FakePlay(self.record)
        result = play.upload(self.directory, api)
        commit = api.body(':commit')[0]
        # The API default CANCEL_IN_REVIEW_AND_SUBMIT would cancel a review the
        # publisher already has in flight for other changes.
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', commit.path)
        # This app sends changes for review automatically, and Google rejects the
        # suppression flag in that state (live HTTP 400 INVALID_ARGUMENT, 2026-10-26).
        self.assertNotIn('changesNotSentForReview', commit.path)
        self.assertEqual('automatic', result['review_submission'])
        self.assertEqual('automatic', self.state()['review_submission'])
        positions = [index for index, call in enumerate(api.calls)
                     if ':validate' in call.path or ':commit' in call.path]
        self.assertEqual(2, len(positions))
        self.assertLess(positions[0], positions[1])   # validate is the dry run before commit
        payload = json.dumps(api.body('/tracks/', 'PUT')[0].body)
        for forbidden in ('userFraction', 'rolloutPercentage', 'inProgress', 'halted', 'completed',
                          'countryTargeting'):
            self.assertNotIn(forbidden, payload)
        self.assertEqual('draft', api.committed['tracks']['internal']['releases'][0]['status'])
        self.assertEqual('draft', self.state()['remote_release_status'])

    def test_commit_adopts_the_suppression_flag_only_when_google_asks(self):
        api = FakePlay(self.record)
        api.fail_commit = play.PlayApiError(
            'Google Play commit edit: HTTP 400 FAILED_PRECONDITION; Google said: Changes cannot be sent '
            'for review automatically. Please set the query parameter changesNotSentForReview to true.',
            method='POST', path='/edits/1:commit', status=400)
        result = play.upload(self.directory, api)
        commits = api.body(':commit')
        self.assertEqual(2, len(commits))
        self.assertNotIn('changesNotSentForReview', commits[0].path)
        self.assertIn('changesNotSentForReview=true', commits[1].path)
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', commits[1].path)
        self.assertEqual('suppressed', result['review_submission'])
        self.assertEqual('uploaded_draft', result['status'])
        self.assertEqual(1, api.commits)             # the rejected attempt persisted nothing
        self.assertFalse(api.open_edit_ids())

    def test_suppression_refusal_names_the_recovery_instead_of_looping(self):
        api = FakePlay(self.record)
        api.fail_commit = play.PlayApiError(
            'Google Play commit edit: HTTP 400 INVALID_ARGUMENT; Google said: Changes are sent for review '
            'automatically. The query parameter changesNotSentForReview must not be set.',
            method='POST', path='/edits/1:commit', status=400)
        with self.assertRaises(play.UploadError) as caught:
            play.upload(self.directory, api, 'suppress')
        self.assertIn('review_submission=auto', str(caught.exception))
        self.assertEqual(1, len(api.body(':commit')))   # never retried with a flipped flag
        self.assertEqual(0, api.commits)
        self.assertEqual('failed', self.state()['status'])
        self.assertFalse(api.open_edit_ids())           # the rejected edit was discarded

    def test_unrelated_commit_rejection_never_becomes_a_retry(self):
        api = FakePlay(self.record)
        api.fail_commit = play.PlayApiError(
            'Google Play commit edit: HTTP 400 FAILED_PRECONDITION; Google said: You already have changes '
            'in review. Cancel this review or wait for it to complete before you try again.',
            method='POST', path='/edits/1:commit', status=400, reason='CHANGES_ALREADY_IN_REVIEW')
        with self.assertRaises(play.UploadError):
            play.upload(self.directory, api)
        self.assertEqual(1, len(api.body(':commit')))
        self.assertEqual(0, api.commits)
        self.assertEqual('failed', self.state()['status'])

    def test_committed_draft_is_verified_in_a_separate_read_only_edit(self):
        api = FakePlay(self.record)
        play.upload(self.directory, api)
        commit_index = next(index for index, call in enumerate(api.calls) if ':commit' in call.path)
        verification = [index for index, call in enumerate(api.calls)
                        if call.method == 'POST' and call.path.endswith('/edits')]
        self.assertGreater(min(verification[1:]), commit_index)
        self.assertEqual(0, len(api.open_edit_ids()))
        self.assertEqual(1, len(api.body(':commit')))

    def test_existing_bundle_is_reused_when_attaching_the_draft(self):
        api = FakePlay(self.record, bundles=[self.bundle()])
        play.upload(self.directory, api)
        self.assertEqual(0, len(api.media()))
        self.assertEqual(1, len(api.body('/tracks/', 'PUT')))
        self.assertEqual('uploaded_draft', self.state()['status'])

    def test_preexisting_draft_is_confirmed_without_replacing_it(self):
        api = FakePlay(self.record, bundles=[self.bundle()], releases=[self.draft()])
        play.upload(self.directory, api)
        self.assertEqual(0, len(api.body('/tracks/', 'PUT')))
        self.assertEqual('uploaded_draft', self.state()['status'])
        self.assertTrue(self.state()['draft_preexisted'])

    # --- case 8: duplicate versionCode ---------------------------------------
    def test_duplicate_version_code_reconciles_without_reupload(self):
        api = FakePlay(self.record)
        # A concurrent commit published versionCode 4 between our read and our upload.
        api.publish_on_upload = play.PlayApiError(
            'Google Play upload signed AAB: HTTP 409 ALREADY_EXISTS: Version code 4 has already been used',
            method='POST', path='/bundles', status=409, api_status='ALREADY_EXISTS')
        result = play.upload(self.directory, api)
        self.assertEqual('uploaded_draft', result['status'])
        self.assertEqual(1, len(api.media()))     # only the rejected attempt touched the endpoint
        self.assertTrue(result['reused_committed_bundle'])
        self.assertIn('already been used', result['bundle_upload_rejection'])
        self.assertEqual([self.draft()], api.committed['tracks']['internal']['releases'])
        self.assertEqual(1, api.commits)
        self.assertFalse(api.open_edit_ids())

    def test_duplicate_version_code_with_different_bytes_is_refused(self):
        api = FakePlay(self.record, bundles=[self.bundle(sha256='0' * 64)])
        api.reject_next('/bundles', play.PlayApiError('HTTP 409 already used', method='POST',
                                                     path='/bundles', status=409))
        with self.assertRaisesRegex(play.UploadError, 'different bytes'):
            play.upload(self.directory, api)
        self.assertEqual(0, len(api.media()))
        self.assertEqual(0, api.commits)
        self.assertEqual('failed', self.state()['status'])

    def test_rejected_upload_absent_from_committed_state_stops_cleanly(self):
        api = FakePlay(self.record)
        api.reject_next('/bundles', play.PlayApiError(
            'Google Play upload signed AAB: HTTP 400 INVALID_ARGUMENT: rejected',
            method='POST', path='/bundles', status=400, api_status='INVALID_ARGUMENT'))
        with self.assertRaisesRegex(play.UploadError, 'nothing persisted'):
            play.upload(self.directory, api)
        self.assertEqual(0, api.commits)
        self.assertFalse(api.open_edit_ids())
        state = self.state()
        self.assertEqual('failed', state['status'])
        self.assertEqual('failed_before_persistence', state['phase'])
        self.assertEqual('none', state['remote_state'])

    # --- cases 4/5: diagnostics ----------------------------------------------
    def test_http_400_reports_google_reason_and_context_without_credentials(self):
        error = http_error(400, GOOGLE_REJECTION, padding=b'A' * 5000)
        with patch.object(play, 'urlopen', side_effect=error):
            with self.assertRaises(play.PlayApiError) as caught:
                play.PlayClient('private-access-token').request(
                    'POST', '/applications/example/edits/1/bundles', file=None, operation='upload signed AAB',
                    context='artifact=DayRows-0.1.5-android-universal-play.aab versionCode=6 track=internal')
        message = str(caught.exception)
        self.assertEqual(400, caught.exception.status)
        self.assertEqual('INVALID_ARGUMENT', caught.exception.api_status)
        self.assertEqual('APK_NOT_FOUND', caught.exception.reason)
        for expected in ('HTTP 400', 'INVALID_ARGUMENT', 'APK_NOT_FOUND', 'Google said',
                         'upload signed AAB', 'versionCode=6', 'track=internal'):
            self.assertIn(expected, message)
        self.assertNotIn('private-access-token', message)
        self.assertNotIn('AAAA', message)
        self.assertNotIn('type.googleapis.com', message)

    def test_permission_error_explains_the_service_account_requirement(self):
        payload = {'error': {'code': 403, 'status': 'PERMISSION_DENIED',
                             'message': 'The user has not sufficient permission for app it.stefanogozzi.dayrows.',
                             'details': [{'reason': 'theUserHasNotSufficientPermission',
                                          'domain': 'androidpublisher.googleapis.com'}]}}
        with patch.object(play, 'urlopen', side_effect=http_error(403, payload)):
            with self.assertRaises(play.PlayApiError) as caught:
                play.PlayClient('token').request('POST', '/applications/example/edits', {},
                                                 operation='create edit')
        self.assertIn('service account lacks Android Publisher access', str(caught.exception))
        self.assertIn('PERMISSION_DENIED', str(caught.exception))

    def test_unparsable_error_body_is_bounded_and_never_echoed(self):
        with patch.object(play, 'urlopen', side_effect=http_error(502, padding=b'B' * 4000)):
            with self.assertRaises(play.PlayApiError) as caught:
                play.PlayClient('token').request('GET', '/applications/example/edits')
        message = str(caught.exception)
        self.assertIn('HTTP 502', message)
        self.assertLess(len(message), 400)
        self.assertNotIn('BBBB', message)

    def test_network_failure_is_distinguishable_and_not_retried(self):
        with patch.object(play, 'urlopen', side_effect=URLError('private-reason')) as request:
            with self.assertRaises(play.PlayNetworkError) as caught:
                play.PlayClient('private-token').request('POST', '/example', operation='commit edit')
        self.assertEqual(1, request.call_count)
        self.assertIn('reconcile before retrying', str(caught.exception))
        self.assertNotIn('private-token', str(caught.exception))

    # --- cases 6/11: failure before commit ------------------------------------
    def test_track_rejection_stops_before_commit_and_discards_the_edit(self):
        api = FakePlay(self.record)
        api.reject_next('/tracks/internal', play.PlayApiError(
            'Google Play attach draft release: HTTP 400 INVALID_ARGUMENT: bad release', method='PUT',
            path='/tracks', status=400, api_status='INVALID_ARGUMENT'), method='PUT')
        with self.assertRaisesRegex(play.UploadError, 'INVALID_ARGUMENT'):
            play.upload(self.directory, api)
        self.assertEqual(0, api.commits)
        self.assertFalse(api.open_edit_ids())
        state = self.state()
        self.assertEqual('failed', state['status'])
        self.assertEqual('failed_before_persistence', state['phase'])
        self.assertIn('INVALID_ARGUMENT', state['last_error'])

    def test_validate_failure_stops_before_commit(self):
        api = FakePlay(self.record)
        api.fail_validate = play.PlayApiError(
            'Google Play validate edit: HTTP 400 FAILED_PRECONDITION: release incomplete', method='POST',
            path=':validate', status=400, api_status='FAILED_PRECONDITION', reason='releaseIncomplete')
        with self.assertRaisesRegex(play.UploadError, 'FAILED_PRECONDITION'):
            play.upload(self.directory, api)
        self.assertEqual(0, api.commits)
        self.assertFalse(api.open_edit_ids())
        self.assertEqual('none', self.state()['remote_state'])

    def test_definite_commit_rejection_is_failed_not_uncertain(self):
        api = FakePlay(self.record)
        api.fail_commit = play.PlayApiError(
            'Google Play commit edit: HTTP 400 FAILED_PRECONDITION: CHANGES_ALREADY_IN_REVIEW',
            method='POST', path=':commit', status=400, api_status='FAILED_PRECONDITION')
        with self.assertRaises(play.PlayApiError):
            play.upload(self.directory, api)
        self.assertEqual('failed', self.state()['status'])
        self.assertEqual('failed_before_persistence', self.state()['phase'])

    def test_ambiguous_commit_is_retained_for_reconciliation_without_retry(self):
        api = FakePlay(self.record)
        api.fail_commit = play.PlayNetworkError('timeout')
        with self.assertRaises(play.PlayNetworkError):
            play.upload(self.directory, api)
        state = self.state()
        self.assertEqual('pending_confirmation', state['status'])
        self.assertEqual('remote_state_uncertain', state['phase'])
        self.assertEqual('uncertain', state['remote_state'])
        self.assertEqual(1, len(api.body(':commit')))
        self.assertEqual(0, len([call for call in api.calls if call.method == 'DELETE']))

    def test_commit_without_readable_draft_never_claims_success(self):
        api = FakePlay(self.record)
        api.forget_draft_on_commit = True
        with self.assertRaisesRegex(play.UploadError, 'could not be read back'):
            play.upload(self.directory, api)
        state = self.state()
        self.assertEqual('pending_confirmation', state['status'])
        self.assertEqual('remote_state_uncertain', state['phase'])
        self.assertFalse(state['draft_confirmed'])

    def test_edit_cleanup_failure_reports_uncertain_remote_state(self):
        api = FakePlay(self.record)
        api.fail_delete = True
        api.reject_next('/tracks/internal', play.PlayApiError('HTTP 400 nope', method='PUT',
                                                             path='/tracks', status=400), method='PUT')
        with self.assertRaises(play.PlayApiError):
            play.upload(self.directory, api)
        state = self.state()
        self.assertEqual('failed', state['status'])
        self.assertEqual('edit-discard-unconfirmed', state['remote_state'])
        self.assertEqual('remote_state_uncertain', state['phase'])

    # --- case 9: safe retries -------------------------------------------------
    def test_confirmed_delivery_retries_as_noop(self):
        self.prior()
        api = FakePlay(self.record, bundles=[self.bundle()], releases=[self.draft(status='completed')])
        self.assertEqual('already_delivered', play.upload(self.directory, api)['status'])
        self.assertEqual(0, len(api.media()))
        self.assertEqual(0, api.commits)
        self.assertEqual(0, len(api.body('/tracks/', 'PUT')))

    def test_lost_commit_response_is_reconciled_on_retry(self):
        self.prior(status='pending_confirmation', draft_preexisted=False)
        api = FakePlay(self.record, bundles=[self.bundle()], releases=[self.draft()])
        self.assertEqual('already_delivered', play.upload(self.directory, api)['status'])
        self.assertEqual(0, len(api.media()))
        self.assertEqual(0, api.commits)

    def test_lost_commit_for_preexisting_draft_revalidates_without_reupload(self):
        self.prior(status='pending_confirmation', draft_preexisted=True)
        api = FakePlay(self.record, bundles=[self.bundle()], releases=[self.draft()])
        self.assertEqual('uploaded_draft', play.upload(self.directory, api)['status'])
        self.assertEqual(0, len(api.media()))
        self.assertEqual(1, api.commits)

    def test_changed_receipt_identity_cannot_skip_the_upload(self):
        self.prior(sha256={**self.record['sha256'], 'aab': '0' * 64})
        api = FakePlay(self.record, bundles=[self.bundle()], releases=[self.draft()])
        self.assertEqual('uploaded_draft', play.upload(self.directory, api)['status'])
        self.assertEqual(1, api.commits)

    # --- case 10: unexpected track state -------------------------------------
    def test_other_draft_is_preserved(self):
        other = {'versionCodes': ['2'], 'status': 'draft'}
        api = FakePlay(self.record, releases=[other])
        with self.assertRaisesRegex(play.UploadError, 'another draft'):
            play.upload(self.directory, api)
        self.assertEqual([other], api.committed['tracks']['internal']['releases'])
        self.assertEqual(0, len(api.media()))
        self.assertEqual(0, len(api.body('/tracks/', 'PUT')))

    def test_older_draft_cannot_replace_a_newer_track_version(self):
        api = FakePlay(self.record, releases=[{'versionCodes': ['5'], 'status': 'completed'}])
        with self.assertRaisesRegex(play.UploadError, 'newer version'):
            play.upload(self.directory, api)
        self.assertEqual(0, len(api.media()))

    def test_served_version_without_receipt_is_not_downgraded(self):
        api = FakePlay(self.record, bundles=[self.bundle()],
                       releases=[self.draft(status='inProgress')])
        with self.assertRaisesRegex(play.UploadError, 'not a draft'):
            play.upload(self.directory, api)
        self.assertEqual(0, api.commits)
        self.assertEqual(0, len(api.media()))

    def test_production_is_never_a_delivery_destination(self):
        with patch.object(play, 'gh') as github:
            with self.assertRaisesRegex(play.UploadError, 'supported Play track'):
                play.prepare('sg0zzy/dayrows', 'v0.1.3', 'production', self.directory, 'expected')
            github.assert_not_called()
        record = {**self.record, 'track': 'production'}
        play.save(self.directory, record)
        api = FakePlay(record)
        with self.assertRaisesRegex(play.UploadError, 'Invalid delivery plan'):
            play.upload(self.directory, api)
        self.assertEqual(0, len(api.calls))

    def test_local_tampering_stops_before_any_api_call(self):
        (self.directory / self.record['files']['aab']).write_bytes(b'changed')
        api = FakePlay(self.record)
        with self.assertRaisesRegex(play.UploadError, 'changed'):
            play.upload(self.directory, api)
        self.assertEqual(0, len(api.calls))

    def test_receipts_never_include_access_credentials(self):
        api = FakePlay(self.record)
        with patch.dict('os.environ', {'PLAY_ACCESS_TOKEN': 'private-token',
                                       'GOOGLE_PLAY_SERVICE_ACCOUNT_JSON': 'private-key'}):
            play.upload(self.directory, api)
        self.assertNotIn('private-', (self.directory / 'delivery.json').read_text())

    # --- case 2/3: the bundle is self-describing ------------------------------
    def test_audit_accepts_bundle_with_embedded_mapping_and_no_native_symbols(self):
        bundle = self.bundle_bytes()
        audit = play.audit_bundle(str(bundle), str(self.mapping_file()))
        self.assertTrue(audit['mapping_matches_bundle'])
        self.assertEqual('absent', audit['native_debug_symbols'])
        self.assertEqual({'stripped': 1}, audit['native_libraries'])
        self.assertEqual('none-required', audit['native_symbols_action'])

    def test_audit_reports_symbols_carried_inside_the_bundle(self):
        bundle = self.bundle_bytes(symbols=True)
        audit = play.audit_bundle(str(bundle), str(self.mapping_file()))
        self.assertEqual('embedded', audit['native_debug_symbols'])
        self.assertEqual('delivered-inside-bundle', audit['native_symbols_action'])

    def test_audit_rejects_bundle_without_the_shipped_mapping(self):
        bundle = self.bundle_bytes(mapping=b'# different build\n')
        with self.assertRaisesRegex(play.UploadError, 'does not embed the R8 mapping'):
            play.audit_bundle(str(bundle), str(self.mapping_file()))

    def test_audit_rejects_unstripped_libraries_without_embedded_symbols(self):
        path = self.directory / 'ndk.aab'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr(play.BUNDLE_MAPPING, MAP_BYTES)
            archive.writestr('base/lib/arm64-v8a/libgame.so', fake_elf(64, (0, 2)))
        with self.assertRaisesRegex(play.UploadError, 'debugSymbolLevel'):
            play.audit_bundle(str(path), str(self.mapping_file()))

    def test_elf_probe_reads_32_and_64_bit_section_headers(self):
        self.assertTrue(play.elf_has_symbol_table(fake_elf(64, (0, 1, 2))))
        self.assertFalse(play.elf_has_symbol_table(fake_elf(64, (0, 1, 3))))
        self.assertTrue(play.elf_has_symbol_table(fake_elf(32, (0, 2))))
        self.assertFalse(play.elf_has_symbol_table(fake_elf(32, (0,))))
        self.assertIsNone(play.elf_has_symbol_table(b'not an elf file at all'))
        self.assertIsNone(play.elf_has_symbol_table(b'\x7fELF' + b'\x00' * 10))

    def test_prepare_records_the_bundle_audit(self):
        audit = {'proguard_map': 'a' * 64, 'mapping_matches_bundle': True,
                 'native_debug_symbols': 'absent', 'native_libraries': {'stripped': 2},
                 'native_symbols_action': 'none-required'}
        apk = 'DayRows-0.1.3-android-universal-release.apk'
        (self.directory / apk).write_bytes(b'apk')
        all_names = [*self.record['files'].values(), apk]
        hashes = {name: play.sha256_file(str(self.directory / name)) for name in all_names}
        (self.directory / 'SHA256SUMS.txt').write_text(
            ''.join(f'{value}  {name}\n' for name, value in hashes.items()))
        assets = [*all_names, 'SHA256SUMS.txt']
        replies = [{'object': {'type': 'tag'}}, {'workflow_runs': [{'id': 123, 'head_sha': 'a' * 40,
                                                                   'head_branch': 'v0.1.3'}]},
                   {'tagName': 'v0.1.3', 'assets': [{'name': name} for name in assets]}]

        def download(command, **kwargs):
            return SimpleNamespace(returncode=0)

        with patch.object(play, 'gh', side_effect=replies), \
                patch.object(play, 'remote_tag_sha', return_value='a' * 40), \
                patch.object(play, 'version_code', return_value=4), \
                patch.object(play, 'asset_digests', return_value=hashes), \
                patch.object(play, 'require_identity'), \
                patch.object(play, 'aab_identity', return_value={'package': play.PACKAGE,
                                                                'version_name': '0.1.3', 'version_code': '4'}), \
                patch.object(play, 'verify_aab_signature', return_value='expected'), \
                patch.object(play, 'check_symbols'), patch.object(play, 'audit_bundle', return_value=audit), \
                patch.object(play, 'previous_receipt', return_value=None), \
                patch.object(play.subprocess, 'run', side_effect=download):
            result = play.prepare('sg0zzy/dayrows', 'v0.1.3', 'internal', self.directory, 'expected')
        self.assertEqual(audit, result['embedded'])
        self.assertEqual('prepared_locally', result['phase'])
        self.assertFalse(result['artifact_uploaded'])
        self.assertEqual('github-release-assets', result['artifact_source'])

    # --- case 6 plus workflow reporting --------------------------------------
    def test_summary_states_play_was_not_contacted_when_upload_skipped(self):
        text, confirmed = play.summary_text(self.record, '', dry_run=True)
        self.assertFalse(confirmed)
        self.assertIn('Prepared locally', text)
        self.assertIn('Google Play was not contacted', text)
        self.assertIn('Dry run', text)

    def test_summary_does_not_report_prepared_after_a_failed_upload_step(self):
        text, confirmed = play.summary_text(self.record, 'failure')
        self.assertFalse(confirmed)
        self.assertIn('Failed before Google Play persistence', text)
        self.assertNotIn('Google Play **prepared**', text)

    def test_summary_reports_uncertain_state_without_claiming_success(self):
        record = {**self.record, 'status': 'pending_confirmation', 'phase': 'remote_state_uncertain',
                  'edit_id': '7', 'edit_committed': False}
        text, confirmed = play.summary_text(record, 'failure')
        self.assertFalse(confirmed)
        self.assertIn('Remote state uncertain', text)
        self.assertIn('reconcile before retrying', text.lower())

    def test_summary_reports_confirmed_draft_with_artifact_identity(self):
        record = {**self.record, 'status': 'uploaded_draft', 'phase': 'draft_confirmed', 'edit_id': '9',
                  'artifact_uploaded': True, 'edit_committed': True, 'draft_confirmed': True,
                  'review_submission': 'automatic', 'remote_release_status': 'draft',
                  'artifact_source': 'github-release-assets', 'release_run_id': 456,
                  'signer_sha256': 'd' * 64}
        text, confirmed = play.summary_text(record, 'success')
        self.assertTrue(confirmed)
        self.assertIn('Draft confirmed in Google Play', text)
        self.assertIn('DayRows-0.1.3-android-universal-play.aab', text)
        self.assertIn(self.record['sha256']['aab'], text)
        self.assertIn('Google Play sends changes for review automatically', text)
        self.assertIn('nothing is rolled out', text)
        self.assertIn('ERROR_IF_IN_REVIEW', text)
        self.assertNotIn('changesNotSentForReview=true', text)

    def test_summary_distinguishes_a_held_from_an_automatically_reviewed_commit(self):
        base = {**self.record, 'status': 'uploaded_draft', 'phase': 'draft_confirmed',
                'artifact_uploaded': True, 'edit_committed': True, 'draft_confirmed': True,
                'remote_release_status': 'draft'}
        held, _ = play.summary_text({**base, 'review_submission': 'suppressed'}, 'success')
        self.assertIn('changesNotSentForReview=true', held)
        self.assertIn('manual review submission', held)
        unstated, _ = play.summary_text(base, 'success')
        self.assertIn('unknown', unstated)
        self.assertIn('committed', unstated)

    def test_summary_shows_the_failure_reason_for_a_failed_delivery(self):
        record = {**self.record, 'status': 'failed', 'phase': 'failed_before_persistence',
                  'edit_id': '3', 'last_error': 'Google Play commit edit: HTTP 400 FAILED_PRECONDITION'}
        text, confirmed = play.summary_text(record, 'failure')
        self.assertFalse(confirmed)
        self.assertIn('Failed before persistence', text)
        self.assertIn('FAILED_PRECONDITION', text)

    # --- unchanged guarantees -------------------------------------------------
    def test_invalid_input_and_missing_signer_stop_before_github(self):
        with patch.object(play, 'gh') as github:
            for tag, signer in [('v0.1.3;echo bad', 'abc'), ('v0.1.3', '')]:
                with self.assertRaises(play.UploadError):
                    play.prepare('sg0zzy/dayrows', tag, 'internal', self.directory, signer)
            github.assert_not_called()

    def test_receipt_from_another_workflow_cannot_skip_delivery(self):
        process = SimpleNamespace(returncode=0, stdout='17\t27\n')
        with patch.object(play.subprocess, 'run', return_value=process) as query, \
                patch.object(play, 'gh', return_value={'path': '.github/workflows/other.yml',
                                                      'event': 'workflow_dispatch'}) as github:
            self.assertIsNone(play.previous_receipt('sg0zzy/dayrows', 'v0.1.3', 'internal'))
            self.assertEqual(1, github.call_count)
            self.assertIn('.workflow_run.head_branch == "main"', query.call_args.args[0][-1])

    def test_only_named_receipt_is_read_without_extracting_archive_files(self):
        receipt = {**self.record, 'status': 'uploaded_draft'}
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('delivery.json', json.dumps(receipt))
            archive.writestr('../../untrusted-file', 'never extracted')
        process = SimpleNamespace(returncode=0, stdout='17\t27\n')
        with patch.object(play.subprocess, 'run', return_value=process), \
                patch.object(play, 'gh', side_effect=[{'path': '.github/workflows/play-upload.yml',
                                                      'event': 'workflow_dispatch'}, data.getvalue()]):
            self.assertEqual(receipt, play.previous_receipt('sg0zzy/dayrows', 'v0.1.3', 'internal'))

    def test_unreadable_draft_uses_only_exact_successful_release_run_artifact(self):
        apk = 'DayRows-0.1.3-android-universal-release.apk'
        (self.directory / apk).write_bytes(b'apk')
        hashes = {name: play.sha256_file(str(self.directory / name))
                  for name in [*self.record['files'].values(), apk]}
        (self.directory / 'SHA256SUMS.txt').write_text(''.join(f'{value}  {name}\n'
                                                               for name, value in hashes.items()))
        destination = self.directory / 'downloaded'
        destination.mkdir()
        replies = [{'object': {'type': 'tag'}},
                   {'workflow_runs': [{'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'v0.1.3'}]},
                   play.UploadError('draft hidden')]

        def download(command, **kwargs):
            self.assertEqual(['gh', 'run', 'download', '123'], command[:4])
            self.assertIn('v0.1.3-release', command)
            for name in [*hashes, 'SHA256SUMS.txt']:
                shutil.copy(self.directory / name, destination / name)
            return SimpleNamespace(returncode=0)

        with patch.object(play, 'gh', side_effect=replies), \
                patch.object(play, 'remote_tag_sha', return_value='a' * 40), \
                patch.object(play, 'version_code', return_value=4), \
                patch.object(play.subprocess, 'run', side_effect=download), \
                patch.object(play, 'aab_identity', return_value={'package': play.PACKAGE,
                                                                'version_name': '0.1.3', 'version_code': '4'}), \
                patch.object(play, 'verify_aab_signature', return_value='expected'), \
                patch.object(play, 'check_symbols'), patch.object(play, 'audit_bundle', return_value={}), \
                patch.object(play, 'previous_receipt', return_value=None):
            result = play.prepare('sg0zzy/dayrows', 'v0.1.3', 'internal', destination, 'expected')
        self.assertEqual('release-workflow-artifact', result['artifact_source'])
        self.assertEqual(123, result['release_run_id'])
        self.assertEqual(self.record['sha256'], result['sha256'])

    def test_other_tag_build_cannot_supply_the_artifact_fallback(self):
        replies = [{'object': {'type': 'tag'}},
                   {'workflow_runs': [{'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'v0.1.2'}]}]
        with patch.object(play, 'gh', side_effect=replies), \
                patch.object(play, 'remote_tag_sha', return_value='a' * 40), \
                patch.object(play, 'version_code', return_value=4), \
                patch.object(play.subprocess, 'run') as download:
            with self.assertRaisesRegex(play.UploadError, 'No successful'):
                play.prepare('sg0zzy/dayrows', 'v0.1.3', 'internal', self.directory, 'expected')
            download.assert_not_called()

    def test_expired_draft_artifact_fails_without_rebuilding_or_broadening_token(self):
        replies = [{'object': {'type': 'tag'}},
                   {'workflow_runs': [{'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'v0.1.3'}]},
                   play.UploadError('draft hidden')]
        with patch.object(play, 'gh', side_effect=replies), \
                patch.object(play, 'remote_tag_sha', return_value='a' * 40), \
                patch.object(play, 'version_code', return_value=4), \
                patch.object(play.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(play.UploadError, 'publish the GitHub draft'):
                play.prepare('sg0zzy/dayrows', 'v0.1.3', 'internal', self.directory, 'expected')

    def test_workflow_is_manual_and_reports_the_actual_step_outcome(self):
        workflow = Path(__file__).resolve().parents[2] / '.github/workflows/play-upload.yml'
        text = workflow.read_text()
        self.assertIn('on:\n  workflow_dispatch:', text)
        self.assertNotIn('pull_request', text)
        self.assertNotIn('\n  push:', text)
        self.assertIn("if: github.ref == 'refs/heads/main'", text)
        self.assertIn('ref: ${{ github.sha }}', text)
        self.assertIn('create_credentials_file: false', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('environment: google-play', text)
        self.assertLess(text.index('scripts/play_upload.py prepare'), text.index('id: auth'))
        # production is not offered, and the summary knows whether upload ran
        self.assertIn('options: [internal, alpha, beta]', text)
        self.assertNotIn('production]', text)
        self.assertIn('id: upload', text)
        self.assertIn('UPLOAD_OUTCOME: ${{ steps.upload.outcome }}', text)
        self.assertIn('--upload-outcome', text)
        self.assertIn('if: ${{ !inputs.dry_run }}', text)


if __name__ == '__main__':
    unittest.main()
