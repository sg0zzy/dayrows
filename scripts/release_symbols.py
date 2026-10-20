#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package Play debug symbols (native .so set) for the current release bundle.

Google Play Console accepts a zipped set of native libraries under
[App bundle explorer] -> [Downloads] -> [Debug symbols] (or per-release upload):
Play matches native crash/ANR frames against the packaged .so files and reports
them deobfuscated in Android Vitals. For a Kotlin/Compose app without an NDK
build the only symbol material is the merged native set that ships inside the
bundle; this tool packages exactly that set plus a manifest so the store upload
is checkable.

Usage:
  python3 scripts/release_symbols.py --aab <app-release.aab> --output <zip>

The zip layout follows Play's expectations:
  symbols/
    lib/<abi>/<lib>.so                      # verbatim bytes from the AAB
    MANIFEST.txt                            # version/ABI/SHA-256 inventory

Exit status 0 means the zip was written and every packaged file re-hashes to
its manifest record. Any error prints a clear reason and exits 1.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

MANIFEST_HEADER = """DayRows debug symbols
Generated: {generated}
Application archive: {arch}
Archive SHA-256: {arch_sha256}
R8/Proguard mapping.txt: {mapping_status}
Libraries: {count} ({abis})
The .so bytes are copied verbatim from the app bundle. Play matches native
crash/ANR frames against these files in [Debug symbols]; with a Proguard/R8
mapping.txt present, Java/Kotlin stack traces retrace via the same zip.
"""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def symbol_entries(aab: zipfile.ZipFile) -> list[tuple[str, bytes]]:
    """All native libraries, ordered by ABI then library name."""
    entries = [(name, aab.read(name)) for name in aab.namelist()
               if re.fullmatch(r"base/lib/[^/]+/[^/]+\.so", name)]
    entries.sort(key=lambda entry: entry[0])
    return entries


def package(aab_path: str, output: str, generated: str, mapping: str | None = None) -> None:
    source = Path(aab_path)
    if not source.is_file():
        raise SystemExit(f"app bundle not found: {source}")
    mapping_path = Path(mapping) if mapping else None
    if mapping_path is not None and not mapping_path.is_file():
        raise SystemExit(f"mapping file not found: {mapping_path}")

    with zipfile.ZipFile(source) as bundle:
        entries = symbol_entries(bundle)
        if not entries:
            raise SystemExit(f"no native libraries under base/lib/ in {source}")
        source_sha256 = sha256_bytes(source.read_bytes())

    mapping_status = "present" if mapping_path is not None else "not available (minify disabled)"
    manifest_lines = [MANIFEST_HEADER.format(
        generated=generated, arch=source, arch_sha256=source_sha256,
        count=len(entries), abis=sorted({name.split("/")[2] for name, _ in entries}),
        mapping_status=mapping_status)]
    for name, payload in entries:
        abi, library = name.split("/")[2], name.split("/")[3]
        manifest_lines.append(
            f"  lib/{abi}/{library}  {len(payload)} bytes  sha256={sha256_bytes(payload)}")

    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as package_file:
        for name, payload in entries:
            _, _, abi, library = name.split("/", 3)
            package_file.writestr(f"symbols/lib/{abi}/{library}", payload)
        if mapping_path is not None:
            mapping_bytes = mapping_path.read_bytes()
            package_file.writestr("symbols/mapping.txt", mapping_bytes)
            manifest_lines.insert(
                3, f"  mapping.txt  {len(mapping_bytes)} bytes  sha256={sha256_bytes(mapping_bytes)}")
        package_file.writestr("symbols/MANIFEST.txt", "\n".join(manifest_lines) + "\n")

    # Re-open and verify every packaged file against the recorded digest.
    with zipfile.ZipFile(output) as result:
        for name in result.namelist():
            if name.endswith("MANIFEST.txt"):
                continue
            raw = result.read(name)
            if not raw:
                raise SystemExit(f"packaged empty payload: {name}")
            if name == "symbols/mapping.txt":
                reference = next(line for line in manifest_lines if line.startswith("  mapping.txt  "))
            else:
                reference = next(line for line in manifest_lines
                                 if f"lib/{name.removeprefix('symbols/lib/')}" in line)
            if f"sha256={sha256_bytes(raw)}" not in reference:
                raise SystemExit(f"unrecorded payload in zip: {name}")
    print(f"wrote {output} with {len(entries)} libraries "
          f"({', '.join(sorted({name.split('/')[2] for name, _ in entries}))})")


def check(symbols_zip: str, aab_source: str) -> None:
    """Verify a symbols zip against its manifest and the source bundle."""
    bundle = Path(aab_source)
    symbols = Path(symbols_zip)
    if not symbols.is_file():
        raise SystemExit(f"symbols zip not found: {symbols}")
    if not bundle.is_file():
        raise SystemExit(f"app bundle not found: {bundle}")

    with zipfile.ZipFile(symbols) as zipped:
        manifest = zipped.read("symbols/MANIFEST.txt").decode("utf-8").splitlines()
        listed = [name for name in zipped.namelist() if re.fullmatch(r"symbols/lib/[^/]+/[^/]+\.so", name)]
        recorded: dict[str, str] = {}
        for line in manifest:
            match = re.fullmatch(r"  (lib/[^/]+/[^/]+\.so)\s+\d+ bytes\s+sha256=([0-9a-f]{64})", line)
            if match:
                recorded[match.group(1)] = match.group(2)
        if not recorded:
            raise SystemExit("manifest carries no sha256 records")
        for relative, digest in recorded.items():
            path = f"symbols/{relative}"
            if path not in zipped.namelist():
                raise SystemExit(f"manifest lists a missing payload: {path}")
            observed = sha256_bytes(zipped.read(path))
            if observed != digest:
                raise SystemExit(f"payload hash mismatch for {relative}: "
                                 f"manifest {digest}, file {observed}")
        unexpected = [name for name in listed if name.removeprefix("symbols/") not in recorded]
        if unexpected:
            raise SystemExit(f"zip contains unrecorded payloads: {unexpected}")
        mapping_line = next((line for line in manifest
                             if line.startswith("  mapping.txt  ")), None)
        if "symbols/mapping.txt" in zipped.namelist():
            if mapping_line is None:
                raise SystemExit("symbols zip carries mapping.txt but the manifest omits it")
            recorded_digest = re.search(r"sha256=([0-9a-f]{64})$", mapping_line)
            if recorded_digest is None:
                raise SystemExit("manifest mapping.txt record has no digest")
            observed = sha256_bytes(zipped.read("symbols/mapping.txt"))
            if observed != recorded_digest.group(1):
                raise SystemExit(f"mapping.txt hash mismatch: manifest {recorded_digest.group(1)}, "
                                 f"file {observed}")
            if "R8" not in zipped.read("symbols/mapping.txt").decode("utf-8", "replace")[:200]:
                raise SystemExit("mapping.txt does not look like an R8/Proguard mapping")

    with zipfile.ZipFile(bundle) as source:
        origin = {name: sha256_bytes(source.read(name))
                  for name in source.namelist()
                  if re.fullmatch(r"base/lib/[^/]+/[^/]+\.so", name)}
    for relative, digest in recorded.items():
        expected = next((sha for name, sha in origin.items()
                         if name.endswith(relative)), None)
        if expected is None:
            raise SystemExit(f"symbol {relative} does not exist in the source bundle")
        if expected != digest:
            raise SystemExit(f"symbol {relative} differs from the source bundle bytes")
    print(f"symbols verified: {len(recorded)} libraries match their manifest and the bundle")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)

    build = sub.add_parser("build", help="generate a symbols zip from the release .aab")
    build.add_argument("--aab", required=True)
    build.add_argument("--output", required=True)
    build.add_argument("--generated", default="", help="UTC timestamp recorded in the manifest")
    build.add_argument("--mapping", default=None,
                       help="R8/Proguard mapping.txt to include when minification is enabled")

    inspect = sub.add_parser("check", help="verify a symbols zip against the release .aab")
    inspect.add_argument("--zip", required=True)
    inspect.add_argument("--aab", required=True)

    arguments = parser.parse_args(argv)
    if arguments.mode == "build":
        package(arguments.aab, arguments.output, arguments.generated, arguments.mapping)
    else:
        check(arguments.zip, arguments.aab)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())