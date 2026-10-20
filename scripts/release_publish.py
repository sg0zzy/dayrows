#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Create the draft release, attach verified assets, and confirm publication.

Idempotent per RELEASE.md: an existing draft for the tag is staged further, an
existing published release is reconciled against the manifest instead of being
silently replaced. Nothing here builds packages; inputs are the workflow's
verified dist/ directory and the GitHub API via the gh CLI.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

STABLE = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")


class PublishError(Exception):
    """A release staging/publication gate failed."""


def run_gh(args: list[str]) -> subprocess.CompletedProcess:
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise PublishError(f"{' '.join(args[:3])} failed: {(result.stderr or result.stdout).strip()}")
    return result


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def asset_digests(directory: str, checksums: str) -> dict[str, str]:
    expected: dict[str, str] = {}
    with open(checksums, encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            line = line.strip()
            if not line:
                continue
            digest, separator, name = line.partition("  ")
            if not separator or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
                raise PublishError(f"{checksums}:{number}: malformed checksum record")
            expected[name] = digest.lower()
    for name, digest in expected.items():
        path = os.path.join(directory, name)
        if not os.path.isfile(path):
            raise PublishError(f"staged asset is missing: {path}")
        observed = sha256_file(path)
        if observed != digest:
            raise PublishError(f"staged asset {name} hash mismatch: "
                               f"checksums say {digest}, file is {observed}")
    if not expected:
        raise PublishError(f"{checksums} lists no assets")
    return expected


def current_release(repository: str, tag: str) -> dict | None:
    result = subprocess.run(["gh", "release", "view", tag, "--repo", repository, "--json",
                             "isDraft,publishedAt,assets,url"],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        if re.search(r"release not found|not found", result.stderr, re.IGNORECASE):
            return None
        raise PublishError(f"gh release view failed: {result.stderr.strip()}")
    return json.loads(result.stdout)


def release_body(repository: str, tag: str, version: str, notes_file: str,
                 checksums: dict[str, str]) -> str:
    with open(notes_file, encoding="utf-8") as stream:
        notes = stream.read().rstrip()
    lines = [f"# DayRows {version}", "", notes, "", "## Downloads", ""]
    for name in sorted(checksums, key=str.lower):
        base = os.path.basename(name)
        lines.append(
            f"- [`{base}`](../../releases/download/{tag}/{base}) "
            f"(SHA-256 `{checksums[name]}`)")
    lines.append("- [`SHA256SUMS.txt`](../../releases/download/"
                 f"{tag}/SHA256SUMS.txt) (checksums for all release assets)")
    lines += ["", "## Google Play", "",
              "The `.aab` above is the Play upload package: download it and upload it",
              "to the internal testing track in Play Console (manual step; see",
              "docs/AUDIT_REPORT.md §F). Play acceptance is a separate destination state",
              "and is not implied by this GitHub release."]
    return "\n".join(lines) + "\n"


def ensure_draft(repository: str, tag: str, version: str, notes_file: str,
                 checksums: dict[str, str]) -> dict:
    existing = current_release(repository, tag)
    if existing is None:
        run_gh(["gh", "release", "create", tag, "--repo", repository, "--draft",
                "--title", f"DayRows {version}", "--notes-file", notes_file])
        existing = current_release(repository, tag)
        if existing is None:
            raise PublishError("create reported success but the release is not visible")
    body_file = notes_file + ".body.md"
    with open(body_file, "w", encoding="utf-8") as stream:
        stream.write(release_body(repository, tag, version, notes_file, checksums))
    run_gh(["gh", "release", "edit", tag, "--repo", repository,
            "--title", f"DayRows {version}", "--notes-file", body_file])
    refreshed = current_release(repository, tag)
    if refreshed is None:
        raise PublishError("release stopped being visible after editing")
    return refreshed


def publish_release(repository: str, tag: str) -> dict:
    existing = current_release(repository, tag)
    if existing is None:
        raise PublishError("nothing staged to publish; run the staging command first")
    if not existing.get("isDraft", True):
        print(f"already published: {existing.get('url')}")
        return existing
    result = subprocess.run(["gh", "release", "edit", tag, "--repo", repository,
                             "--draft=false"],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise PublishError(f"publishing failed: {(result.stderr or '').strip()}")
    published = current_release(repository, tag)
    if published is None or published.get("isDraft", True):
        raise PublishError("publish command succeeded but the release is still a draft")
    return published


def remote_tag_sha(repository: str, tag: str) -> str:
    result = subprocess.run(
        ["gh", "api", f"repos/{repository}/git/ref/tags/{tag}"],
        capture_output=True, text=True, check=False)
    if result.returncode != 0:
        if re.search(r"Not Found|no ref|404", result.stderr, re.IGNORECASE):
            raise PublishError(
                f"remote tag {tag} does not exist in {repository}; push the annotated "
                "tag before staging a release for it")
        raise PublishError(f"gh api tag lookup failed: {result.stderr.strip()}")
    ref = json.loads(result.stdout)
    object_type = ref.get("object", {}).get("type")
    sha = ref.get("object", {}).get("sha")
    if object_type not in ("tag", "commit") or not sha:
        raise PublishError(f"unexpected remote ref payload for {tag}: {object_type!r}")
    if object_type == "tag":
        resolved = subprocess.run(
            ["gh", "api", f"repos/{repository}/git/tags/{sha}"],
            capture_output=True, text=True, check=False)
        if resolved.returncode != 0:
            raise PublishError(f"gh api tag-object lookup failed: {resolved.stderr.strip()}")
        commit_sha = json.loads(resolved.stdout).get("object", {}).get("sha")
        if not commit_sha:
            raise PublishError(f"remote annotated tag {tag} has no peeled commit")
        return commit_sha
    return sha


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    stage = commands.add_parser("stage", help="create/update the draft release with assets")
    stage.add_argument("--repo", required=True, help="owner/repo")
    stage.add_argument("--tag", required=True)
    stage.add_argument("--version", required=True)
    stage.add_argument("--directory", required=True, help="dist directory with verified assets")
    stage.add_argument("--checksums", required=True)
    stage.add_argument("--notes", required=True)
    stage.add_argument("--attach-checksums", action="store_true",
                       help="attach the checksums file itself as a release asset")

    release = commands.add_parser("publish", help="flip the fully staged draft to public")
    release.add_argument("--repo", required=True, help="owner/repo")
    release.add_argument("--tag", required=True)

    arguments = parser.parse_args(argv)
    try:
        if not STABLE.fullmatch(arguments.tag):
            raise PublishError(f"refusing non-stable tag: {arguments.tag}")
        if arguments.command == "stage":
            remote_tag_sha(arguments.repo, arguments.tag)
            checksums = asset_digests(arguments.directory, arguments.checksums)
            names = sorted(os.path.basename(name) for name in checksums)
            body_file = arguments.notes + ".body.md"
            with open(body_file, "w", encoding="utf-8") as stream:
                stream.write(release_body(arguments.repo, arguments.tag, arguments.version,
                                          arguments.notes, checksums))
            existing = current_release(arguments.repo, arguments.tag)
            if existing is not None and existing.get("isDraft", True) is False:
                # S09: a completed release is never silently replaced; differing bytes
                # must go through a new version or the incident procedure.
                for name in names:
                    observed_size = next((asset.get("size") for asset in
                                          existing.get("assets", []) if asset["name"] == name), None)
                    local_size = os.path.getsize(os.path.join(arguments.directory, name))
                    if observed_size is not None and observed_size != local_size:
                        raise PublishError(
                            f"published asset {name} differs in size from the staged bytes; "
                            "published releases are immutable, use a new version or an "
                            "incident record")
            ensure_draft(arguments.repo, arguments.tag, arguments.version,
                         arguments.notes, checksums)
            present = {asset["name"]: asset for asset
                       in (current_release(arguments.repo, arguments.tag) or {}).get("assets", [])}
            for name in names:
                path = os.path.join(arguments.directory, name)
                if name in present:
                    state = present[name].get("state", "uploaded")
                    if state != "uploaded":
                        raise PublishError(f"asset {name} exists in state "
                                           f"{present[name].get('state')!r}; reconcile manually")
                run_gh(["gh", "release", "upload", arguments.tag,
                        "--repo", arguments.repo, "--clobber", path])
            extra_assets = names
            if arguments.attach_checksums:
                sums_name = os.path.basename(arguments.checksums)
                if sums_name not in checksums:  # never sums itself; record absence is expected
                    extra_assets = [*names, sums_name]
                    sums_path = os.path.abspath(arguments.checksums)
                    if sums_name not in present:
                        run_gh(["gh", "release", "upload", arguments.tag,
                                "--repo", arguments.repo, "--clobber", sums_path])
            reconcile(arguments.repo, arguments.tag, extra_assets,
                      existing.get("isDraft", True) if existing else True)
            print(f"release staged: {len(names)} verified assets attached to the draft")
            return 0
        published = publish_release(arguments.repo, arguments.tag)
        print(f"published: {published.get('url')}")
        return 0
    except PublishError as error:
        print(f"release publication failed: {error}", file=sys.stderr)
        return 1


def reconcile(repository: str, tag: str, names: list[str], is_draft: bool | None = None) -> None:
    release = current_release(repository, tag)
    if release is None:
        raise PublishError("release stopped being visible during staging")
    observed = {asset["name"]: asset.get("size") for asset in release.get("assets", [])}
    missing = [name for name in names if name not in observed]
    if missing:
        raise PublishError(f"GitHub release is missing expected assets: {missing}")
    empty = [name for name, size in observed.items() if size == 0]
    if empty:
        raise PublishError(f"GitHub release has zero-byte assets: {empty}")
    if is_draft is False and release.get("isDraft", False):
        raise PublishError("release went backwards from published to draft; investigate manually")
    print(f"reconciled {len(observed)} assets; draft={release.get('isDraft')}")


if __name__ == "__main__":
    raise SystemExit(main())