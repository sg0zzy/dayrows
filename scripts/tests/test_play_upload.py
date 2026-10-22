# SPDX-License-Identifier: GPL-3.0-or-later
"""Play destination tests use fake API replies only; no credentials or store writes."""
import copy
import io
import json
import shutil
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import play_upload as play


class FakePlay:
    def __init__(self, record, existing=False, releases=None):
        self.record = record
        self.calls = []
        self.bundles = ([{'versionCode': 4, 'sha256': record['sha256']['aab']}] if existing else [])
        self.track = {'track': 'internal', 'releases': releases or []}
        self.failure = None
        self.commit_failure = None
        self.edits = 0

    def request(self, method, path, body=None, file=None, mime=None):
        self.calls.append((method, path, body, file, mime))
        if self.failure and self.failure in path:
            raise play.UploadError('simulated API rejection')
        if path.endswith('/edits'):
            self.edits += 1
            return {'id': str(self.edits)}
        if method == 'GET' and path.endswith('/bundles'):
            return {'bundles': self.bundles}
        if path.endswith('/tracks/internal'):
            if method == 'PUT':
                self.track = body
            return self.track
        if file and path.endswith('/bundles'):
            bundle = {'versionCode': 4, 'sha256': self.record['sha256']['aab']}
            self.bundles.append(bundle)
            return bundle
        if file and '/deobfuscationFiles/' in path:
            return {'deobfuscationFile': {'symbolType': path.rsplit('/', 1)[1]}}
        if ':commit' in path and self.commit_failure:
            raise self.commit_failure
        return {}


class PlayUploadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.record = dict(destination='google-play', package=play.PACKAGE, tag='v0.1.3',
                           track='internal', version_code=4, source_sha='a' * 40,
                           files=play.filenames('0.1.3'), sha256={}, status='prepared')
        for kind, name in self.record['files'].items():
            path = self.directory / name
            path.write_bytes(kind.encode())
            self.record['sha256'][kind] = play.sha256_file(str(path))
        play.save(self.directory, self.record)

    def state(self):
        return json.loads((self.directory / 'delivery.json').read_text())

    def prior(self, **updates):
        receipt = {**copy.deepcopy(self.record), 'status': 'uploaded_draft', 'edit_id': 'previous', **updates}
        (self.directory / 'previous-delivery.json').write_text(json.dumps(receipt))

    def draft(self):
        return {'name': 'DayRows 0.1.3', 'versionCodes': ['4'], 'status': 'draft'}

    def test_uploads_three_files_commits_draft_and_checks_fresh_remote_state(self):
        live = {'name': 'old', 'versionCodes': ['3'], 'status': 'completed'}
        api = FakePlay(self.record, releases=[live])
        result = play.upload(self.directory, api)
        self.assertEqual('uploaded_draft', result['status'])
        media = [call for call in api.calls if call[3]]
        self.assertEqual(3, len(media))
        self.assertTrue(media[1][1].endswith('/deobfuscationFiles/nativeCode'))
        self.assertTrue(media[2][1].endswith('/deobfuscationFiles/proguard'))
        self.assertEqual([live, self.draft()], api.track['releases'])
        self.assertEqual(2, api.edits)
        self.assertTrue(any(':commit?changesNotSentForReview=true' in call[1] for call in api.calls))
        self.assertNotIn('completed', json.dumps(api.track['releases'][-1]))

    def test_matching_bundle_is_reused_when_adding_draft_and_symbols(self):
        api = FakePlay(self.record, existing=True)
        play.upload(self.directory, api)
        self.assertEqual(2, len([call for call in api.calls if call[3]]))

    def test_existing_manual_draft_can_receive_its_missing_symbols(self):
        api = FakePlay(self.record, existing=True, releases=[self.draft()])
        play.upload(self.directory, api)
        self.assertFalse(any(call[0] == 'PUT' for call in api.calls))
        self.assertEqual(2, len([call for call in api.calls if call[3]]))

    def test_identical_successful_delivery_is_noop_even_after_manual_rollout(self):
        self.prior()
        release = {**self.draft(), 'status': 'completed'}
        api = FakePlay(self.record, existing=True, releases=[release])
        self.assertEqual('already_delivered', play.upload(self.directory, api)['status'])
        self.assertFalse(any(call[3] or call[0] == 'PUT' or ':commit' in call[1] for call in api.calls))

    def test_pending_new_draft_can_be_reconciled_after_lost_commit_response(self):
        self.prior(status='pending_confirmation', draft_preexisted=False)
        api = FakePlay(self.record, existing=True, releases=[self.draft()])
        self.assertEqual('already_delivered', play.upload(self.directory, api)['status'])

    def test_lost_commit_response_for_preexisting_draft_does_not_prove_symbols_saved(self):
        self.prior(status='pending_confirmation', draft_preexisted=True)
        api = FakePlay(self.record, existing=True, releases=[self.draft()])
        self.assertEqual('uploaded_draft', play.upload(self.directory, api)['status'])
        self.assertEqual(2, len([call for call in api.calls if call[3]]))

    def test_changed_receipt_mapping_cannot_skip_upload(self):
        hashes = {**self.record['sha256'], 'mapping': '0' * 64}
        self.prior(sha256=hashes)
        api = FakePlay(self.record, existing=True, releases=[self.draft()])
        self.assertEqual('uploaded_draft', play.upload(self.directory, api)['status'])

    def test_conflicting_remote_bundle_is_never_overwritten(self):
        api = FakePlay(self.record, existing=True)
        api.bundles[0]['sha256'] = '0' * 64
        with self.assertRaisesRegex(play.UploadError, 'different bytes'):
            play.upload(self.directory, api)
        self.assertFalse(any(call[3] for call in api.calls))
        self.assertEqual('failed', self.state()['status'])

    def test_other_draft_is_preserved(self):
        other = {'versionCodes': ['2'], 'status': 'draft'}
        api = FakePlay(self.record, releases=[other])
        with self.assertRaisesRegex(play.UploadError, 'another draft'):
            play.upload(self.directory, api)
        self.assertEqual([other], api.track['releases'])
        self.assertFalse(any(call[3] for call in api.calls))

    def test_older_draft_cannot_replace_a_newer_track_version(self):
        api = FakePlay(self.record, releases=[{'versionCodes': ['5'], 'status': 'completed'}])
        with self.assertRaisesRegex(play.UploadError, 'newer version'):
            play.upload(self.directory, api)
        self.assertFalse(any(call[3] for call in api.calls))

    def test_existing_served_version_without_receipt_is_not_downgraded(self):
        api = FakePlay(self.record, existing=True, releases=[{**self.draft(), 'status': 'completed'}])
        with self.assertRaisesRegex(play.UploadError, 'outside draft'):
            play.upload(self.directory, api)
        self.assertFalse(any(call[3] for call in api.calls))

    def test_local_tampering_stops_before_any_api_call(self):
        (self.directory / self.record['files']['aab']).write_bytes(b'changed')
        api = FakePlay(self.record)
        with self.assertRaisesRegex(play.UploadError, 'changed'):
            play.upload(self.directory, api)
        self.assertFalse(api.calls)

    def test_symbol_failure_never_commits_or_claims_upload_success(self):
        api = FakePlay(self.record)
        api.failure = 'nativeCode'
        with self.assertRaises(play.UploadError):
            play.upload(self.directory, api)
        self.assertFalse(any(':commit' in call[1] for call in api.calls))
        self.assertEqual('failed', self.state()['status'])

    def test_definite_commit_rejection_is_failed_not_uncertain(self):
        api = FakePlay(self.record)
        api.commit_failure = play.UploadError('HTTP 403')
        with self.assertRaises(play.UploadError):
            play.upload(self.directory, api)
        self.assertEqual('failed', self.state()['status'])

    def test_ambiguous_commit_is_retained_for_reconciliation_without_retry(self):
        api = FakePlay(self.record)
        api.commit_failure = play.PlayNetworkError('timeout')
        with self.assertRaises(play.PlayNetworkError):
            play.upload(self.directory, api)
        self.assertEqual('pending_confirmation', self.state()['status'])
        self.assertEqual(1, sum(':commit' in call[1] for call in api.calls))
        self.assertFalse(any(call[0] == 'DELETE' for call in api.calls))

    def test_client_http_error_never_exports_google_error_body_or_token(self):
        error = HTTPError('https://example.test', 403, 'forbidden', {}, io.BytesIO(b'private-error-body'))
        with patch.object(play, 'urlopen', side_effect=error):
            with self.assertRaises(play.UploadError) as caught:
                play.PlayClient('private-access-token').request('GET', '/applications/example')
        self.assertIn('HTTP 403', str(caught.exception))
        self.assertNotIn('private', str(caught.exception))

    def test_network_failure_is_distinguishable_and_not_retried(self):
        with patch.object(play, 'urlopen', side_effect=URLError('private-error')) as request:
            with self.assertRaises(play.PlayNetworkError):
                play.PlayClient('private-access-token').request('POST', '/example')
        self.assertEqual(1, request.call_count)

    def test_invalid_input_and_missing_signer_stop_before_github(self):
        with patch.object(play, 'gh') as github:
            for tag, signer in [('v0.1.3;echo bad', 'abc'), ('v0.1.3', '')]:
                with self.assertRaises(play.UploadError):
                    play.prepare('sg0zzy/dayrows', tag, 'internal', self.directory, signer)
            github.assert_not_called()

    def test_receipts_never_include_access_credentials(self):
        api = FakePlay(self.record)
        with patch.dict('os.environ', {'PLAY_ACCESS_TOKEN': 'private-token',
                                      'GOOGLE_PLAY_SERVICE_ACCOUNT_JSON': 'private-key'}):
            play.upload(self.directory, api)
        self.assertNotIn('private-', (self.directory / 'delivery.json').read_text())

    def test_workflow_is_manual_and_does_not_execute_release_source_with_credentials(self):
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

    def test_receipt_from_another_workflow_cannot_skip_symbol_delivery(self):
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

    def test_missing_symbol_acknowledgement_abandons_edit(self):
        api = FakePlay(self.record)
        original = api.request
        def request(method, path, *args, **kwargs):
            response = original(method, path, *args, **kwargs)
            return {} if path.endswith('nativeCode') else response
        with patch.object(api, 'request', side_effect=request):
            with self.assertRaisesRegex(play.UploadError, 'acknowledge'):
                play.upload(self.directory, api)
        self.assertEqual('failed', self.state()['status'])
        self.assertFalse(any(':commit' in call[1] for call in api.calls))

    def test_unreadable_draft_uses_only_exact_successful_release_run_artifact(self):
        apk = 'DayRows-0.1.3-android-universal-release.apk'
        (self.directory / apk).write_bytes(b'apk')
        hashes = {name: play.sha256_file(str(self.directory / name))
                  for name in [*self.record['files'].values(), apk]}
        (self.directory / 'SHA256SUMS.txt').write_text(''.join(f'{value}  {name}\n' for name, value in hashes.items()))
        destination = self.directory / 'downloaded'
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
                patch.object(play.subprocess, 'run', side_effect=download), \
                patch.object(play, 'aab_identity', return_value={'package': play.PACKAGE, 'version_name': '0.1.3', 'version_code': '4'}), \
                patch.object(play, 'verify_aab_signature', return_value='expected'), \
                patch.object(play, 'check_symbols'), patch.object(play, 'previous_receipt', return_value=None):
            result = play.prepare('sg0zzy/dayrows', 'v0.1.3', 'internal', destination, 'expected')
        self.assertEqual('release-workflow-artifact', result['artifact_source'])
        self.assertEqual(123, result['release_run_id'])
        self.assertEqual(self.record['sha256'], result['sha256'])

    def test_other_tag_build_cannot_supply_the_artifact_fallback(self):
        replies = [{'object': {'type': 'tag'}},
                   {'workflow_runs': [{'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'v0.1.2'}]}]
        with patch.object(play, 'gh', side_effect=replies), \
                patch.object(play, 'remote_tag_sha', return_value='a' * 40), \
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
                patch.object(play.subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(play.UploadError, 'publish the GitHub draft'):
                play.prepare('sg0zzy/dayrows', 'v0.1.3', 'internal', self.directory, 'expected')


if __name__ == '__main__':
    unittest.main()
