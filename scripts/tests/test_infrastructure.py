# SPDX-License-Identifier: GPL-3.0-or-later
"""Isolated failure/retention fixtures: never delete or change real archives/refs."""
from concurrent.futures import ProcessPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from artifacts import Storage, atomic_json
from build_debug import source_digest, version_code


def complete_one(root):
    storage = Storage(root)
    directory, record = storage.reserve({})
    storage.finish(directory, record, True)
    return directory.name


class ArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'local_artifacts'
        self.storage = Storage(self.root)

    def make(self, success=True):
        directory, record = self.storage.reserve({})
        (directory / 'payload').write_bytes(b'preserve me')
        if success is not None:
            self.storage.finish(directory, record, success)
        return directory

    def test_unique_builds_and_completion_order(self):
        first = self.make(None)
        second = self.make()
        record = json.loads((first / 'build-info.json').read_text())
        self.storage.finish(first, record, True)
        os.utime(first, (1, 1))
        self.storage.clean(1, True)
        self.assertTrue(first.exists())
        self.assertFalse(second.exists())

    def test_default_ten_and_keep_not_counted(self):
        protected = self.make()
        # A dangling .keep entry also protects a build.
        (protected / '.keep').symlink_to('missing')
        dirs = [self.make() for _ in range(13)]
        self.storage.clean(apply=True)
        self.assertTrue(protected.exists())
        self.assertEqual(sum(path.exists() for path in dirs), 10)
        self.assertTrue(all(path.exists() for path in dirs[-10:]))

    def test_failure_in_progress_and_unknown_never_collected(self):
        failed, active = self.make(False), self.make(None)
        unknown = self.root / 'legacy'
        unknown.mkdir()
        (unknown / 'valuable').write_text('keep')
        self.make()
        rows = self.storage.clean(0, True)
        self.assertTrue(all(path.exists() for path in (failed, active, unknown)))
        self.assertTrue(any('unsafe/unknown' in row['reason'] for row in rows))

    def test_zero_and_override_and_negative(self):
        dirs = [self.make() for _ in range(4)]
        self.storage.clean(2, True)
        self.assertEqual(sum(path.exists() for path in dirs), 2)
        with self.assertRaises(ValueError):
            self.storage.clean(-1, True)
        self.storage.clean(0, True)
        self.assertFalse(any(path.exists() for path in dirs))

    def test_symlinked_root_child_and_manifest(self):
        original = self.make()
        external = Path(self.temp.name) / 'outside'
        external.mkdir()
        (external / 'valuable').write_text('keep')
        (self.root / 'escape').symlink_to(external, target_is_directory=True)
        (original / 'build-info.json').unlink()
        (original / 'build-info.json').symlink_to(external / 'valuable')
        self.storage.clean(0, True)
        self.assertEqual((external / 'valuable').read_text(), 'keep')
        self.assertTrue(original.exists())
        alias = Path(self.temp.name) / 'alias'
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError):
            Storage(alias).clean(0, True)
        for invalid in ('../outside', '/tmp', 'a/b', '..'):
            with self.storage.locked(), self.assertRaises(ValueError):
                self.storage.read(invalid)

    def test_internal_symlink_is_unlinked_not_followed(self):
        original = self.make()
        external = Path(self.temp.name) / 'outside'
        external.mkdir()
        (external / 'valuable').write_text('keep')
        (original / 'link').symlink_to(external, target_is_directory=True)
        self.storage.clean(0, True)
        self.assertEqual((external / 'valuable').read_text(), 'keep')

    def test_dry_run_and_protection_revalidation(self):
        original = self.make()
        def snapshot():
            return {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in self.root.rglob('*') if p.is_file()}
        before = snapshot()
        rows = self.storage.clean(0)
        self.assertEqual(rows[0]['reason'], 'delete')
        self.assertEqual(before, snapshot())
        self.storage.protect(original.name)
        self.storage.clean(0, True)
        self.assertTrue(original.exists())

    def test_explicit_remove_requires_confirmation(self):
        original = self.make(False)
        self.storage.protect(original.name)
        self.storage.remove(original.name)
        self.assertTrue(original.exists())
        self.storage.remove(original.name, confirmed=True)
        self.assertFalse(original.exists())

    def test_duplicate_or_invalid_completion_skipped(self):
        first, second = self.make(), self.make()
        record = json.loads((second / 'build-info.json').read_text())
        record['completion_sequence'] = 1
        atomic_json(second, 'build-info.json', record)
        self.storage.clean(0, True)
        self.assertTrue(first.exists() and second.exists())
        record['completion_sequence'] = 'yesterday'
        atomic_json(second, 'build-info.json', record)
        self.storage.clean(0, True)
        self.assertTrue(second.exists())

    def test_concurrent_process_completion_and_cleanup(self):
        active = self.make(None)
        with ProcessPoolExecutor(max_workers=4) as pool:
            ids = list(pool.map(complete_one, [self.root] * 20))
            list(pool.map(clean_one, [self.root] * 4))
        self.assertEqual(len(set(ids)), 20)
        self.assertTrue(active.exists())
        sequences = [json.loads((p / 'build-info.json').read_text())['completion_sequence']
                     for p in self.root.iterdir() if p.is_dir() and p != active]
        self.assertEqual(sorted(sequences), list(range(11, 21)))

    def test_substitution_and_protection_checked_at_delete(self):
        original = self.make()
        with self.storage.locked():
            record, _, inode = self.storage.read(original.name)
            (original / '.keep').touch()
            with self.assertRaises(ValueError):
                self.storage.delete(original.name, inode, {'succeeded'}, False)
        self.assertTrue(original.exists())

    def test_corrupt_sequence_does_not_mark_success(self):
        original = self.make(None)
        atomic_json(self.root, '.sequence.json', -1)
        record = json.loads((original / 'build-info.json').read_text())
        with self.assertRaises(ValueError):
            self.storage.finish(original, record, True)
        self.assertEqual(json.loads((original / 'build-info.json').read_text())['state'], 'in_progress')

    def test_regressed_sequence_blocks_new_success(self):
        self.make()
        original = self.make(None)
        atomic_json(self.root, '.sequence.json', 0)
        record = json.loads((original / 'build-info.json').read_text())
        with self.assertRaises(ValueError):
            self.storage.finish(original, record, True)

    def test_nonexistent_dry_run_does_not_create_storage(self):
        self.assertEqual(self.storage.clean(), [])
        self.assertFalse(self.root.exists())

    def test_nonregular_state_files_are_rejected_without_blocking(self):
        original = self.make(None)
        record = json.loads((original / 'build-info.json').read_text())
        (original / 'build-info.json').unlink()
        os.mkfifo(original / 'build-info.json')
        self.assertIn('unsafe/unknown', self.storage.clean(0, True)[0]['reason'])
        self.assertTrue(original.exists())
        (original / 'build-info.json').unlink()
        atomic_json(original, 'build-info.json', record)
        os.mkfifo(self.root / '.sequence.json')
        with self.assertRaises(ValueError):
            self.storage.finish(original, record, True)


class SourceVersionTest(unittest.TestCase):
    def test_existing_android_mapping_and_bounds(self):
        self.assertEqual(version_code('0.1.0'), 1)
        self.assertEqual(version_code('0.1.1'), 2)
        self.assertEqual(version_code('0.2.0'), 1001)
        self.assertEqual(version_code('1.0.0'), 999001)
        self.assertEqual(version_code('2100.0.999'), 2_100_000_000)
        for value in ('01.1.0', '0.01.0', '0.1.00', '0.0.0', '2101.0.0',
                      '0.1000.0', '0.1.1000', '0.1.0-dev.1', '-1.0.0', '0.1.0+meta'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                version_code(value)

    def test_dirty_untracked_ignored_and_deleted_input_digests(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', temp], check=True)
            (root / '.gitignore').write_text('*.kt\nlocal_artifacts/\n')
            (root / 'app/src').mkdir(parents=True)
            source = root / 'app/src/ignored.kt'
            before = source_digest(root)
            source.write_text('first')
            first = source_digest(root)
            source.write_text('second')
            second = source_digest(root)
            source.unlink()
            self.assertEqual(source_digest(root), before)
            self.assertEqual(len({before, first, second}), 3)
            (root / 'untracked').write_text('a')
            self.assertNotEqual(source_digest(root), before)
            (root / 'local_artifacts').mkdir()
            digest = source_digest(root)
            (root / 'local_artifacts/output').write_text('not source')
            self.assertEqual(source_digest(root), digest)

    def test_invalid_arguments_fail_before_build_or_artifacts(self):
        from build_debug import build
        with patch('build_debug.ROOT', Path(tempfile.gettempdir())), self.assertRaises(ValueError):
            build(['-Ppassword=not-a-real-secret'])

    def test_build_failure_and_source_change_never_collect(self):
        # Mock only the compiler/SDK boundary; exercise real state transitions,
        # failure propagation and storage. No actual compilation or remote calls.
        from build_debug import build
        for fail_compilation in (False, True):
            with self.subTest(fail_compilation=fail_compilation), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                original_cwd = Path.cwd()
                paths = ['VERSION', 'DEVELOPMENT_STANDARDS.md', 'scripts/build_debug.py',
                         'scripts/artifacts.py', 'app/build.gradle.kts', 'build.gradle.kts',
                         'settings.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties',
                         'docs/DEVELOPMENT.md', '.github/workflows/build-apk.yml',
                         'docs/standards-lock.json', 'sdk/build-tools/36.0.0/aapt',
                         'sdk/build-tools/36.0.0/apksigner', 'sdk/build-tools/36.0.0/source.properties',
                         'sdk/platforms/android-36/android.jar', 'sdk/platforms/android-36/source.properties']
                for name in paths:
                    target = root / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text('fixture')
                (root / 'VERSION').write_text('0.1.0')
                (root / 'docs/standards-lock.json').write_text(json.dumps({'commit': 'b' * 40}))
                storage = Storage(root / 'local_artifacts')
                previous, record = storage.reserve({})
                storage.finish(previous, record, True)
                def fake_run(*args, **kwargs):
                    if args[0][:2] == ['git', 'rev-parse']:
                        return 'a' * 40
                    return ''
                def fake_subprocess(args, **kwargs):
                    if args[0] == './gradlew' and fail_compilation:
                        raise subprocess.CalledProcessError(1, args)
                    return SimpleNamespace(stderr='fixture Java', stdout='', returncode=0)
                try:
                    with patch('build_debug.ROOT', root), \
                         patch.dict(os.environ, {'ANDROID_HOME': str(root / 'sdk'),
                                                 'DAYROWS_ARTIFACT_LIMIT': '0'}, clear=True), \
                         patch('build_debug.shutil.which', return_value='/fixture/tool'), \
                         patch('build_debug.run', side_effect=fake_run), \
                         patch('build_debug.source_digest', side_effect=['a' * 64, 'b' * 64]), \
                         patch('build_debug.subprocess.run', side_effect=fake_subprocess), \
                         self.assertRaises((ValueError, subprocess.CalledProcessError)):
                        build([])
                    self.assertTrue(previous.exists(), 'Failed build must not prune old successes')
                    failed = [json.loads(p.read_text()) for p in storage.root.glob('*/build-info.json')
                              if p.parent != previous]
                    self.assertEqual(len(failed), 1)
                    self.assertEqual(failed[0]['state'], 'failed')
                    expected_stage = 'compile-and-checks' if fail_compilation else 'source-coherence'
                    self.assertEqual(failed[0]['targets'][0]['stage'], expected_stage)
                finally:
                    os.chdir(original_cwd)


def clean_one(root):
    Storage(root).clean(10, True)


if __name__ == '__main__':
    unittest.main()
