#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Official release gates: tag validation, high-water reservation and notes.

Used by the stable release workflow before/while publishing. Exit status 0
means the requested gate passed; any other status blocks the release. All
semantics follow VERSIONING.md and RELEASE.md at the pinned standards commit:
canonical X.Y.Z tags, prerelease forms, SemVer precedence, one official stream
across stable and beta, and notes since the previous published stable.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

STABLE = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
PRERELEASE = re.compile(
    r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)\Z")
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
IDENTIFIER = re.compile(r"^(?:0|[1-9][0-9]*|[0-9A-Za-z-]+)\Z")


class GateError(Exception):
    """A release gate failed."""


# --------------------------------------------------------------------------
# Versions and precedence.
# --------------------------------------------------------------------------

def parse_version(value: str) -> dict:
    match = VERSION.fullmatch(value)
    if not match:
        raise GateError(f"not a canonical X.Y.Z version: {value!r}")
    return dict(major=int(match.group(1)), minor=int(match.group(2)),
                patch=int(match.group(3)))


def parse_tag(name: str) -> dict | None:
    """Parse an official v-tag; None for nonofficial/unparseable tag names."""
    stable = STABLE.fullmatch(name)
    if stable:
        return dict(major=int(stable.group(1)), minor=int(stable.group(2)),
                    patch=int(stable.group(3)), prerelease=None)
    prerelease = PRERELEASE.fullmatch(name)
    if prerelease:
        identifiers = prerelease.group(4)[1:].split(".")
        numeric = [bool(IDENTIFIER.fullmatch(item) and item.isdigit()) for item in identifiers]
        if any(count.isdigit() and len(count) > 1 and count.startswith("0") for count in identifiers):
            return None
        core = numeric[:1]
        # SemVer precedence: numeric identifiers numerically, numeric before nonnumeric,
        # then ASCII order; nonnumeric identifiers compare lexically.
        if len(numeric) == 1 and core[0]:
            return None
        return dict(major=int(prerelease.group(1)), minor=int(prerelease.group(2)),
                    patch=int(prerelease.group(3)), prerelease=prerelease.group(4)[1:])
    return None


def precedence_key(version: dict) -> tuple:
    # dev < local < stable at the same core: numeric prerelease identifiers use
    # the dev/local name (never a bare integer compare against the next label).
    prerelease = version.get("prerelease")
    if prerelease is None:
        return (version["major"], version["minor"], version["patch"], 1, 0, 0, ())
    labels = prerelease.split(".")
    name, number = (labels[0], int(labels[1]) if len(labels) > 1 and labels[1].isdigit() else 0)
    ranks = {"dev": 0, "local": 1}
    if name not in ranks:
        raise GateError(f"unsupported prerelease label in official version: {name}")
    return (version["major"], version["minor"], version["patch"], 0, ranks[name], number, ())


# --------------------------------------------------------------------------
# Remote official state under Git / GitHub.
# --------------------------------------------------------------------------

def remote_inventory() -> dict:
    repository = repository_from_git()
    refs = github_api(f"repos/{repository}/git/matching-refs/tags")
    releases = github_api(f"repos/{repository}/releases?per_page=100")
    return {
        "tags": [ref["ref"].removeprefix("refs/tags/") for ref in refs],
        "released": [release["tag_name"] for release in releases if release.get("published_at")],
    }


def slug_from_env() -> str | None:
    """GitHub slug from Actions/gh environment, if present."""
    candidates = (os.environ.get("GITHUB_REPOSITORY"),
                  os.environ.get("GH_REPO"),
                  os.environ.get("DAYROWS_GITHUB_REPO"))
    for candidate in candidates:
        if candidate and re.fullmatch(r"[\w.-]+/[\w.-]+", candidate.strip()):
            return candidate.strip()
    return None


def repository_from_git() -> str:
    slug = slug_from_env()
    if slug:
        return slug
    try:
        slug = subprocess.run(["git", "config", "--get", "remote.origin.url"], check=True,
                              capture_output=True, text=True).stdout.strip()
        slug = re.sub(r"^git@github\.com:", "", slug)
        slug = re.sub(r"^https://[^/]*github\.com/", "", slug)
        slug = slug.removesuffix(".git")
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", slug):
            raise GateError(f"GitHub origin is not an owner/repo URL: {slug!r}")
        return slug
    except FileNotFoundError:
        raise GateError("git is required for release verification") from None
    except subprocess.CalledProcessError as error:
        raise GateError(f"git remote query failed ({error.returncode})") from None


def github_api(path: str) -> list | dict:
    url = f"https://api.github.com/{path}"
    result = subprocess.run(["gh", "api", "--paginate", url], capture_output=True, text=True,
                            check=False, env={**os.environ, "GH_TOKEN": os.environ.get("GH_TOKEN", "")})
    if result.returncode != 0:
        raise GateError(f"GitHub API query failed for {path}: {result.stderr.strip()}")
    merged: list = []
    for document in result.stdout.splitlines():
        if not document.strip():
            continue
        try:
            parsed = json.loads(document)
        except json.JSONDecodeError as error:
            raise GateError(f"GitHub API returned non-JSON output for {path}: {error}") from None
        if isinstance(parsed, list):
            merged.extend(parsed)
        else:
            return parsed
    return merged


def official_versions(inventory: dict, exclude: str) -> tuple[dict[str, dict], list[str]]:
    reserved: dict[str, dict] = {}
    ignored: set[str] = set()
    for name in inventory["tags"]:
        if name.endswith("^{}"):
            peeled = name[:-3]
            if peeled in reserved or peeled == exclude:
                continue
            name = peeled
        if name == exclude:
            continue
        parsed = parse_tag(name)
        if parsed is None:
            ignored.add(name)
            continue
        reserved[name] = parsed
    for name in inventory["released"]:
        if name == exclude or name in reserved:
            continue
        parsed = parse_tag(name)
        if parsed is None:
            ignored.add(name)
            continue
        reserved.setdefault(name, parsed)
    return reserved, sorted(ignored)


# --------------------------------------------------------------------------
# Subcommands: tag, notes.
# --------------------------------------------------------------------------

def command_tag(arguments: argparse.Namespace) -> int:
    version = parse_version(arguments.version)
    tag_version = parse_tag(arguments.tag)
    if tag_version is None:
        raise GateError("tag does not use a canonical vMAJOR.MINOR.PATCH form; "
                        "no official tag may be created from this name")
    if tag_version["prerelease"] is not None and not arguments.allow_prerelease:
        raise GateError("stable release pipeline requires a stable tag without a prerelease suffix")
    if (tag_version["major"], tag_version["minor"], tag_version["patch"]) != (
            version["major"], version["minor"], version["patch"]):
        raise GateError(f"tag {arguments.tag} does not match VERSION {arguments.version}")
    if arguments.expect_sha:
        peeled = subprocess.run(["git", "rev-parse", f"{arguments.tag}^{{commit}}"],
                                capture_output=True, text=True, check=False)
        if peeled.returncode != 0:
            raise GateError(f"tag object {arguments.tag} is missing from the checkout")
        if peeled.stdout.strip() != arguments.expect_sha:
            raise GateError(f"tag {arguments.tag} identifies commit {peeled.stdout.strip()}, "
                            f"not the workflow revision {arguments.expect_sha}")
    if arguments.require_annotation:
        ref = subprocess.run(
            ["git", "for-each-ref", f"refs/tags/{arguments.tag}",
             "--format=%(objecttype)%00%(contents:subject)"],
            capture_output=True, text=True, check=False)
        if ref.returncode != 0:
            raise GateError(f"git could not inspect tag {arguments.tag}")
        objecttype, _, subject = ref.stdout.partition("\0")
        if objecttype == "commit":
            raise GateError(f"tag {arguments.tag} is lightweight; stable/beta official tags "
                            "must be annotated")
        if objecttype != "tag":
            raise GateError(f"tag {arguments.tag} is an unexpected object type {objecttype!r}")
        expected = f"DayRows {arguments.version}"
        if subject.strip() != expected:
            raise GateError(f"annotation of {arguments.tag} is {subject.strip()!r}; "
                            f"expected {expected!r}")
    if arguments.branch_tip:
        tip = subprocess.run(["git", "ls-remote", "origin", f"refs/heads/{arguments.branch_tip}"],
                             capture_output=True, text=True, check=False)
        if tip.returncode != 0 or not tip.stdout.strip():
            raise GateError(f"could not read the remote tip of {arguments.branch_tip}")
        if tip.stdout.split()[0] != arguments.expect_sha:
            raise GateError(f"tag commit {arguments.expect_sha} is not the tip of "
                            f"{arguments.branch_tip}; release tags must be cut from the "
                            "reviewed branch head")
    if arguments.remote_high_water:
        inventory = remote_inventory()
        reserved, ignored = official_versions(inventory, exclude=arguments.tag)
        if ignored:
            raise GateError("unparseable official records need reconciliation before reservation: "
                            + ", ".join(ignored))
        ours = precedence_key(tag_version)
        worse = {name: tag_version for name, parsed in reserved.items()
                 if precedence_key(parsed) >= ours}
        if worse:
            raise GateError("reservation ordering violated; an official version already "
                            f"at or above {arguments.tag} exists: {', '.join(sorted(worse))}")
        print(f"high-water check passed over {len(reserved)} official remote records")
    print(f"tag gate passed: {arguments.tag} -> {arguments.version}")
    return 0


def command_baseline(arguments: argparse.Namespace) -> int:
    """Print the previous successfully published stable tag, or the fallback."""
    head = parse_tag(arguments.head)
    if head is None or head["prerelease"] is not None:
        raise GateError(f"head {arguments.head!r} is not an official stable tag")
    inventory = remote_inventory()
    reserved, ignored = official_versions(inventory, exclude=arguments.head)
    released_stables = [name for name, parsed in reserved.items() if parsed["prerelease"] is None]
    if not released_stables:
        print(arguments.fallback)
        return 0
    newest = max(released_stables, key=lambda name: precedence_key(reserved[name]))
    if precedence_key(reserved[newest]) >= precedence_key(head):
        raise GateError(f"published stable {newest} is not below {arguments.head}; "
                        "the notes baseline would be inverted")
    print(newest)
    return 0


def command_notes(arguments: argparse.Namespace) -> int:
    if arguments.head_ref is None:
        raise GateError("--head-ref is required with --base-ref")
    for ref in (arguments.base_ref, arguments.head_ref):
        verified = subprocess.run(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"],
                                  capture_output=True, text=True, check=False)
        if verified.returncode != 0:
            raise GateError(f"notes baseline reference is not a commit: {ref}")
    slug = repository_from_git()
    comparison = (f"https://github.com/{slug}/compare/"
                  f"{arguments.base_ref.removeprefix('v')}...{arguments.head_ref.removeprefix('v')}")
    body = subprocess.run(["git", "log", "--no-merges", "--format=* %s (%h)",
                           f"{arguments.base_ref}..{arguments.head_ref}"],
                          capture_output=True, text=True, check=True).stdout
    with open(arguments.output, "w", encoding="utf-8") as stream:
        if arguments.version is not None:
            stream.write(f"DayRows {arguments.version}\n\n")
        if arguments.previous_stable is not None:
            stream.write(f"Previous stable release: {arguments.previous_stable}\n\n")
        stream.write(f"Compare: {comparison}\n\n")
        stream.write(body if body.strip() else "No additional commits in this range.\n")
    print(f"notes written to {arguments.output} from {arguments.base_ref}..{arguments.head_ref}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    tag = commands.add_parser("tag", help="validate a stable or beta tag reservation")
    tag.add_argument("--tag", required=True)
    tag.add_argument("--version", required=True, help="authoritative VERSION file content")
    tag.add_argument("--expect-sha", default=None)
    tag.add_argument("--allow-prerelease", action="store_true")
    tag.add_argument("--require-annotation", action="store_true", default=True)
    tag.add_argument("--no-require-annotation", dest="require_annotation", action="store_false")
    tag.add_argument("--annotate", dest="require_annotation", action="store_true",
                     help=argparse.SUPPRESS)
    tag.add_argument("--branch-tip", default=None,
                     help="require the tagged commit to be the remote tip of this branch")
    tag.add_argument("--remote-high-water", action="store_true")

    notes = commands.add_parser("notes", help="generate notes since the previous stable")
    notes.add_argument("--base-ref", required=True, help="previous stable tag or fallback SHA")
    notes.add_argument("--head-ref", required=True, help="current release tag")
    notes.add_argument("--version", default=None)
    notes.add_argument("--previous-stable", default=None)
    notes.add_argument("--output", required=True)

    baseline = commands.add_parser(
        "notes-baseline", help="resolve the previous successfully published stable baseline")
    baseline.add_argument("--head", required=True, help="current release tag")
    baseline.add_argument("--fallback", required=True,
                          help="root commit SHA when no published stable exists")

    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "tag":
            return command_tag(arguments)
        if arguments.command == "notes":
            return command_notes(arguments)
        return command_baseline(arguments)
    except GateError as error:
        print(f"release gate failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())