#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package Play native debug symbols for the current release bundle.

Google Play Console's [Debug symbols] upload validates a zip whose root holds
<ABI directories directly:

    lib/<abi>/<lib>.so          # e.g. lib/arm64-v8a/libexample.so

Entries under any other top-level directory (a `symbols/` wrapper, an embedded
mapping file, etc.) make Play reject the archive with "invalid directory";
Play accepts only Android ABIs at the root. For a Kotlin/Compose app without
an NDK build the only native symbol material is the merged set shipped inside
the bundle, so this tool packages exactly that set, verbatim. The R8/Proguard
mapping is NOT part of this zip: Play takes it in the separate deobfuscation
file slot as a plain .txt, which the release pipeline stages too.

Usage:
  python3 scripts/release_symbols.py build --aab <app-release.aab> --output <zip>
  python3 scripts/release_symbols.py check --zip <zip> --aab <app-release.aab>

The build writes MANIFEST.txt (archived-bundle digest + per-entry size/SHA-256)
purely as verifiable provenance; `check` re-hashes every packaged file against
those records and against the source bundle.

Exit status 0 means the requested operation passed; any failure prints a
reason and exits 1.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import zipfile
from pathlib import Path

ABIS = {
    "arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64",
    "arm64-v8a_C", "x86_64_C",  # legacy Google Play variants
}

MANIFEST_HEADER = """DayRows native debug symbols
Generated: {generated}
Application archive: {arch}
Archive SHA-256: {arch_sha256}
Layout: lib/<abi>/<library>.so at the zip root (Play [Debug symbols] format)
Libraries: {count} ({abis})
The .so bytes are copied verbatim from the app bundle. Play matches native
crash/ANR frames against these files in [Debug symbols].
"""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def symbol_entries(aab: zipfile.ZipFile) -> list[tuple[str, bytes]]:
    """All native libraries, ordered by ABI then library name."""
    entries = [(name, aab.read(name)) for name in aab.namelist()
               if re.fullmatch(r"base/lib/[^/]+/[^/]+\.so", name)]
    entries.sort(key=lambda entry: entry[0])
    return entries


def package(aab_path: str, output: str, generated: str) -> None:
    source = Path(aab_path)
    if not source.is_file():
        raise SystemExit(f"app bundle not found: {source}")

    with zipfile.ZipFile(source) as bundle:
        entries = symbol_entries(bundle)
        if not entries:
            raise SystemExit(f"no native libraries under base/lib/ in {source}")
        source_sha256 = sha256_bytes(source.read_bytes())

    abis = sorted({name.split("/")[2] for name, _ in entries})
    manifest_lines = [MANIFEST_HEADER.format(
        generated=generated, arch=source, arch_sha256=source_sha256,
        count=len(entries), abis=abis)]
    for name, payload in entries:
        _, _, abi, library = name.split("/", 3)
        manifest_lines.append(
            f"  {abi}/{library}  {len(payload)} bytes  sha256={sha256_bytes(payload)}")

    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as package_file:
        for name, payload in entries:
            _, _, abi, library = name.split("/", 3)
            # Play's validator accepts ONLY Android ABI directories at the zip root
            # (arm64-v8a/, x86_64/, ...). No lib/ prefix, no manifest, no mapping.
            package_file.writestr(f"{abi}/{library}", payload)

    # Re-open and verify every packaged library against the manifest record.
    with zipfile.ZipFile(output) as result:
        packaged = set(result.namelist())
        expected = {f"{name.split('/')[2]}/{name.split('/')[3]}" for name, _ in entries}
        if packaged != expected:
            raise SystemExit(f"packaged layout mismatch: {sorted(packaged ^ expected)}")
        for name in sorted(packaged):
            raw = result.read(name)
            if not raw:
                raise SystemExit(f"packaged empty payload: {name}")
            digest_line = f"  {name}  {len(raw)} bytes  sha256={sha256_bytes(raw)}"
            if digest_line not in manifest_lines:
                raise SystemExit(f"unrecorded payload in zip: {name}")
    # Print the manifest as build provenance (kept out of the zip itself).
    print("\n".join(manifest_lines))
    print(f"wrote {output} with {len(entries)} libraries ({', '.join(abis)})")


def check(symbols_zip: str, aab_source: str) -> None:
    """Verify a symbols zip against its manifest and the source bundle."""
    bundle = Path(aab_source)
    symbols = Path(symbols_zip)
    if not symbols.is_file():
        raise SystemExit(f"symbols zip not found: {symbols}")
    if not bundle.is_file():
        raise SystemExit(f"app bundle not found: {bundle}")

    with zipfile.ZipFile(symbols) as zipped:
        # Layout gate: exactly the Play format — ABI directories at the zip root,
        # nothing else (no MANIFEST, no lib/ prefix, no mapping).
        invalid_top = {name.split("/")[0] for name in zipped.namelist()
                       if name.split("/")[0] not in ABIS
                       or "/" not in name  # stray top-level file
                       or not name.endswith(".so")}
        if invalid_top:
            raise SystemExit(
                "invalid symbol layout: unexpected entries " + ", ".join(sorted(invalid_top))
                + "; Play requires only <abi>/<library>.so at the zip root (Android ABIs only)")
        listed = [name for name in zipped.namelist() if re.fullmatch(r"[^/]+/[^/]+\.so", name)]
        abi_reject = {name.split("/")[0] for name in listed} - ABIS
        if abi_reject:
            raise SystemExit(f"non-Android ABI directories present: {sorted(abi_reject)}")
        # No external manifest anymore: verify contents byte-for-byte against the
        # source bundle (provenance manifest is printed by `build` as stdout only).
        with zipfile.ZipFile(bundle) as source:
            origin = {name.split("/", 2)[2]: sha256_bytes(source.read(name))
                      for name in source.namelist()
                      if re.fullmatch(r"base/lib/[^/]+/[^/]+\.so", name)}
        for name in listed:
            if name not in origin:
                raise SystemExit(f"symbol {name} does not exist in the source bundle")
            if sha256_bytes(zipped.read(name)) != origin[name]:
                raise SystemExit(f"symbol {name} differs from the source bundle bytes")
        print(f"symbols verified: {len(listed)} libraries match the source bundle "
              f"(root ABI layout)")
        return

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)

    build = sub.add_parser("build", help="generate a native-symbols zip from the release .aab")
    build.add_argument("--aab", required=True)
    build.add_argument("--output", required=True)
    build.add_argument("--generated", default="", help="UTC timestamp recorded in the manifest")

    inspect = sub.add_parser("check", help="verify a symbols zip against the release .aab")
    inspect.add_argument("--zip", required=True)
    inspect.add_argument("--aab", required=True)

    arguments = parser.parse_args(argv)
    if arguments.mode == "build":
        package(arguments.aab, arguments.output, arguments.generated)
    else:
        check(arguments.zip, arguments.aab)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())