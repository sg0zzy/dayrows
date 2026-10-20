# SPDX-License-Identifier: GPL-3.0-or-later
"""Isolated tests for the official release gates: no real API calls, no repo mutation."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_publish import PublishError, asset_digests, release_body
from release_verify import (GateError, command_baseline, command_tag, official_versions,
                            parse_tag, parse_version, precedence_key)
import release_publish
import release_verify


class VersionClassificationTest(unittest.TestCase):
    def test_canonical_examples_from_versioning_md(self):
        keys = {
            "0.9.4": precedence_key(parse_version("0.9.4")),
            "0.9.5-dev.2": precedence_key(parse_tag("v0.9.5-dev.2")),
            "0.9.5-dev.10": precedence_key(parse_tag("v0.9.5-dev.10")),
            "0.9.5": precedence_key(parse_version("0.9.5")),
            "0.9.5-dev.99": precedence_key(parse_tag("v0.9.5-dev.99")),
            "0.9.6-dev.1": precedence_key(parse_tag("v0.9.6-dev.1")),
            "0.9.5-local.1": precedence_key(parse_tag("v0.9.5-local.1")),
            "0.9.6": precedence_key(parse_version("0.9.6")),
            "0.9.7": precedence_key(parse_version("0.9.7")),
        }
        self.assertLess(keys["0.9.4"], keys["0.9.5-dev.2"])
        self.assertLess(keys["0.9.5-dev.2"], keys["0.9.5-dev.10"])
        self.assertLess(keys["0.9.5-dev.10"], keys["0.9.5"])
        self.assertLess(keys["0.9.5-dev.99"], keys["0.9.6-dev.1"])
        self.assertLess(keys["0.9.5-local.1"], keys["0.9.5"])
        self.assertGreater(keys["0.9.5-local.1"], keys["0.9.5-dev.2"])
        self.assertLess(keys["0.9.5"], keys["0.9.6-dev.1"])

    def test_numeric_fields_compare_numerically(self):
        self.assertGreater(parse_tag("v0.1.10")["patch"], parse_tag("v0.1.9")["patch"])
        self.assertGreater(precedence_key(parse_tag("v0.2.0")),
                           precedence_key(parse_tag("v0.1.9")))

    def test_malformed_and_nonofficial_names_unparse(self):
        for name in ("v0.1.05", "v0.01.1", "v01.1.0", "v0.1.0+meta", "v0.1", "release-1",
                     "v0.1.0-01", "v0.1.0-dev.03", "V0.1.0", "v0.1.0-"):
            self.assertIsNone(parse_tag(name), name)

    def test_supported_and_unsupported_prerelease_labels(self):
        self.assertEqual(parse_tag("v0.1.0-dev.1")["prerelease"], "dev.1")
        self.assertEqual(parse_tag("v0.1.0-local.4")["prerelease"], "local.4")
        parsed = parse_tag("v0.1.0-rc.1")
        self.assertEqual(parsed["prerelease"], "rc.1")
        with self.assertRaises(GateError):
            precedence_key(parsed)  # single-stream policy: only dev/local rank


class OfficialRecordsTest(unittest.TestCase):
    def test_peeled_tags_and_releases_join(self):
        inventory = {"tags": ["v0.9.4", "v0.9.4^{}", "v0.9.6-dev.1", "v0.9.6-dev.1^{}",
                              "chore-tag"],
                     "released": ["v0.9.6-dev.1"]}
        reserved, ignored = official_versions(inventory, exclude="v0.0.9")
        self.assertIn("v0.9.4", reserved)
        self.assertIn("v0.9.6-dev.1", reserved)
        self.assertNotIn("v0.9.4^{}", reserved)
        self.assertEqual(ignored, ["chore-tag"])

    def test_exclude_keeps_the_current_reservation_out(self):
        inventory = {"tags": ["v1.2.3", "v1.2.3^{}"], "released": ["v1.2.3"]}
        reserved, ignored = official_versions(inventory, exclude="v1.2.3")
        self.assertEqual(reserved, {})

    def test_missing_annotation_fails(self):
        inventory = {"tags": ["v0.9.4"], "released": []}
        reserved, ignored = official_versions(inventory, exclude="v9.9.9")
        self.assertIn("v0.9.4", reserved)


class BaselineResolutionTest(unittest.TestCase):
    def inventory(self, tags, released):
        return lambda: {"tags": tags, "released": released}

    def run_baseline(self, head, tags, released, fallback="ROOT"):
        with patch.object(release_verify, "remote_inventory", self.inventory(tags, released)):
            arguments = type("Args", (), {"head": head, "fallback": fallback})()
            return command_baseline(arguments)

    def test_no_published_stable_returns_fallback(self):
        observed = self.run_baseline("v0.9.7", ["v0.9.6-dev.1", "v0.9.6-dev.1^{}"],
                                     ["v0.9.6-dev.1"])
        self.assertEqual(observed, 0)
        with patch("builtins.print") as printed:
            observed = self.run_baseline("v0.9.7", [], [])
            printed_output = printed.call_args[0][0]
        self.assertEqual(printed_output, "ROOT")

    def test_newest_published_stable_is_baseline(self):
        with patch("builtins.print") as printed:
            self.run_baseline("v0.9.7", ["v0.9.4", "v0.9.4^{}", "weird"], ["v0.9.4"])
        self.assertEqual(printed.call_args[0][0], "v0.9.4")

    def test_beta_publication_is_not_a_stable_baseline(self):
        with patch("builtins.print") as printed:
            self.run_baseline("v0.9.7", ["v0.9.6-dev.1", "v0.9.6-dev.1^{}"],
                              ["v0.9.6-dev.1"])
        self.assertEqual(printed.call_args[0][0], "ROOT")

    def test_inverted_baseline_is_rejected(self):
        with patch("builtins.print"), self.assertRaises(GateError):
            self.run_baseline("v0.9.3", ["v0.9.4", "v0.9.4^{}"], ["v0.9.4"])

    def test_beta_head_is_rejected(self):
        with patch("builtins.print"), self.assertRaises(GateError):
            self.run_baseline("v0.9.6-dev.2", ["v0.9.6-dev.1", "v0.9.6-dev.1^{}"],
                              ["v0.9.6-dev.1"])


class StageAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dist = Path(self.temp.name) / "dist"
        self.dist.mkdir()
        (self.dist / "a.bin").write_bytes(b"alpha")
        (self.dist / "b.bin").write_bytes(b"beta")

    def write_checksums(self):
        import hashlib
        lines = []
        for name in ("a.bin", "b.bin"):
            lines.append(hashlib.sha256((self.dist / name).read_bytes()).hexdigest() + f"  {name}")
        (self.dist / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n")
        return self.dist / "SHA256SUMS.txt"

    def test_digests_match_clean_assets(self):
        digests = asset_digests(str(self.dist), str(self.write_checksums()))
        self.assertEqual(set(digests), {"a.bin", "b.bin"})

    def test_tampered_asset_fails(self):
        checksums = self.write_checksums()
        (self.dist / "a.bin").write_bytes(b"tampered")
        with self.assertRaises(PublishError):
            asset_digests(str(self.dist), str(checksums))

    def test_missing_asset_fails(self):
        checksums = self.write_checksums()
        (self.dist / "b.bin").unlink()
        with self.assertRaises(PublishError):
            asset_digests(str(self.dist), str(checksums))

    def test_malformed_record_fails(self):
        checksums = self.dist / "SHA256SUMS.txt"
        checksums.write_text("nothex  a.bin\n")
        with self.assertRaises(PublishError):
            asset_digests(str(self.dist), str(checksums))


class RemoteTagTest(unittest.TestCase):
    """remote_tag_sha: existing annotated/lightweight tag, missing tag, malformed ref."""

    def command_target(self):
        try:
            return release_publish.remote_tag_sha("sg0zzy/dayrows", "v0.1.1")
        except release_publish.PublishError as error:
            return error

    def test_missing_tag_fails_with_push_hint(self):
        with patch.object(release_publish.subprocess, "run",
                          lambda args, **k: subprocess.CompletedProcess(args, 1, "",
                                                                        "HTTP 404: Not Found")):
            error = self.command_target()
        self.assertIn("does not exist", str(error))

    def test_annotated_tag_peels_to_commit(self):
        ref = json.dumps({"object": {"sha": "TAGSHA", "type": "tag"}})
        peeled = json.dumps({"object": {"sha": "COMMITSHA", "type": "commit"}})

        def fake_run(args, **kwargs):
            if "git/ref/tags/" in args[2]:
                return subprocess.CompletedProcess(args, 0, ref, "")
            return subprocess.CompletedProcess(args, 0, peeled, "")
        with patch.object(release_publish.subprocess, "run", fake_run):
            self.assertEqual(release_publish.remote_tag_sha("sg0zzy/dayrows", "v0.1.1"),
                             "COMMITSHA")

    def test_lightweight_tag_returns_commit(self):
        ref = json.dumps({"object": {"sha": "COMMITSHA", "type": "commit"}})
        with patch.object(release_publish.subprocess, "run",
                          lambda args, **k: subprocess.CompletedProcess(args, 0, ref, "")):
            self.assertEqual(release_publish.remote_tag_sha("sg0zzy/dayrows", "v0.1.1"),
                             "COMMITSHA")

    def test_malformed_ref_fails(self):
        with patch.object(release_publish.subprocess, "run",
                          lambda args, **k: subprocess.CompletedProcess(
                              args, 0, json.dumps({"object": {}}), "")):
            error = self.command_target()
        self.assertIn("unexpected remote ref payload", str(error))


class ReleaseBodyTest(unittest.TestCase):
    def test_notes_and_checksums_rendered(self):
        notes = Path(tempfile.mkdtemp()) / "n.md"
        notes.write_text("Changes.\n")
        body = release_body("sg0zzy/dayrows", "v0.1.1", "0.1.1", str(notes),
                            {"a.bin": "ab" * 32})
        self.assertIn("# DayRows 0.1.1", body)
        self.assertIn("Changes.", body)
        self.assertIn("[`a.bin`](../../releases/download/v0.1.1/a.bin)", body)
        self.assertIn("[`SHA256SUMS.txt`](../../releases/download/v0.1.1/SHA256SUMS.txt)", body)
        self.assertIn("Google Play", body)


class TagGateFixtureTest(unittest.TestCase):
    """Full command_tag run on a throwaway Git repo with annotated tags."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git(*["init", "-q", "-b", "main"])
        self.git(*["config", "user.email", "t@e.c"])
        self.git(*["config", "user.name", "t"])
        (self.root / "VERSION").write_text("0.1.1\n")
        self.git(*["add", "-A"])
        self.git(*["commit", "-qm", "initial"])
        self.commit = self.git(*["rev-parse", "HEAD"])

    def run_in_fixture(self, function, arguments):
        previous = Path.cwd()
        import os
        os.chdir(self.root)
        try:
            return function(arguments)
        finally:
            os.chdir(previous)

    def git(self, *args):
        result = subprocess.run(["git", "--git-dir", str(self.root / ".git"),
                                 "--work-tree", str(self.root), *args],
                                capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def annotate(self, name):
        self.git(*["tag", "-a", name, "-m", f"DayRows {name[1:]}"])

    def arguments(self, **overrides):
        base = dict(tag="v0.1.1", version="0.1.1", allow_prerelease=False,
                    require_annotation=True, expect_sha=None, branch_tip=None,
                    remote_high_water=False)
        base.update(overrides)
        return type("Args", (), base)()

    def test_valid_annotated_tag_passes(self):
        self.annotate("v0.1.1")
        with patch("builtins.print"):
            result = self.run_in_fixture(command_tag, self.arguments(expect_sha=self.commit))
        self.assertEqual(result, 0)

    def test_lightweight_tag_is_rejected(self):
        self.git("tag", "v0.1.1")
        with self.assertRaises(GateError):
            self.run_in_fixture(command_tag, self.arguments(require_annotation=True))

    def test_wrong_annotation_subject_is_rejected(self):
        self.git(*["tag", "-a", "v0.1.1", "-m", "wrong subject"])
        with self.assertRaises(GateError):
            self.run_in_fixture(command_tag, self.arguments())

    def test_expect_sha_mismatch_is_rejected(self):
        self.annotate("v0.1.1")
        with self.assertRaises(GateError):
            self.run_in_fixture(command_tag, self.arguments(expect_sha="0" * 40))

    def test_version_mismatch_is_rejected(self):
        self.annotate("v0.1.1")
        with self.assertRaises(GateError):
            self.run_in_fixture(command_tag, self.arguments(version="0.1.2"))

    def test_prerelease_tag_is_rejected_for_stable_job(self):
        self.git(*["tag", "-a", "v0.1.1-dev.1", "-m", "DayRows 0.1.1-dev.1"])
        with self.assertRaises(GateError):
            self.run_in_fixture(command_tag, self.arguments(tag="v0.1.1-dev.1",
                                                            version="0.1.1"))

    def test_prerelease_tag_passes_when_allowed(self):
        self.git(*["tag", "-a", "v0.1.1-dev.1", "-m", "DayRows 0.1.1-dev.1"])
        with patch("builtins.print"):
            result = self.run_in_fixture(command_tag, self.arguments(
                tag="v0.1.1-dev.1", version="0.1.1", allow_prerelease=True,
                require_annotation=False))
        self.assertEqual(result, 0)

    def test_high_water_rejects_existing_greater_reservation(self):
        self.annotate("v0.2.0")
        inventory = lambda: {"tags": ["v0.2.0", "v0.2.0^{}"], "released": []}
        with patch.object(release_verify, "remote_inventory", inventory), \
             self.assertRaises(GateError):
            self.run_in_fixture(command_tag, self.arguments(remote_high_water=True))

    def test_high_water_accepts_greater_candidate(self):
        self.annotate("v0.1.1")
        inventory = lambda: {"tags": ["v0.1.0", "v0.1.0^{}"], "released": ["v0.1.0"]}
        with patch.object(release_verify, "remote_inventory", inventory):
            with patch("builtins.print"):
                result = self.run_in_fixture(command_tag, self.arguments(
                    remote_high_water=True, expect_sha=self.commit))
        self.assertEqual(result, 0)


if __name__ == "__main__":
    unittest.main()