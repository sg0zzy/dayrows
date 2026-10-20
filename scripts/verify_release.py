#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify official release package bytes before publication or delivery.

Exit status 0 means every requested check passed; any other status is a failed
gate. Nothing here falls back to a weaker check: a missing tool, absent
signature, unexpected identity or transport-hash mismatch is always a failure.

The AAB manifest is protocol-buffer encoded inside the bundle, so identity is
read with a small stdlib decoder. The APK manifest is binary AXML and is read
through the SDK's aapt instead. apksigner cannot process AAB containers (they
have no root AndroidManifest.xml), so AAB JAR signatures are verified with
jarsigner and the signer certificate with keytool.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile

ANDROID_MANIFEST_AAB = "base/manifest/AndroidManifest.xml"


class VerificationError(Exception):
    """A release verification gate failed."""


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def run(tool: str, args: list[str]) -> subprocess.CompletedProcess[str]:
    resolved = shutil.which(tool) or tool
    if not os.path.exists(resolved):
        raise VerificationError(f"required verification tool is unavailable: {tool}")
    return subprocess.run([resolved, *args], capture_output=True, text=True, check=False)


# --------------------------------------------------------------------------
# Protocol buffer reading for the AAB AndroidManifest.xml resource format.
# --------------------------------------------------------------------------

def protobuf_fields(data: bytes) -> list[tuple[int, int, object]]:
    position, fields = 0, []
    while position < len(data):
        key, position = _read_varint(data, position)
        number, wire = key >> 3, key & 0b111
        if wire == 0:
            value, position = _read_varint(data, position)
        elif wire == 1:
            value, position = data[position : position + 8], position + 8
        elif wire == 2:
            length, position = _read_varint(data, position)
            value, position = data[position : position + length], position + length
        elif wire == 5:
            value, position = data[position : position + 4], position + 4
        else:
            raise VerificationError(f"unsupported protobuf wire type {wire} in field {number}")
        fields.append((number, wire, value))
    return fields


def _read_varint(data: bytes, position: int) -> tuple[int, int]:
    result, shift = 0, 0
    while True:
        if position >= len(data):
            raise VerificationError("truncated protobuf varint")
        byte = data[position]
        position += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, position
        shift += 7


def aab_manifest_identity(path: str) -> dict[str, str]:
    """Return the root manifest attributes of an AAB (package and versions)."""
    if not zipfile.is_zipfile(path):
        raise VerificationError(f"not a zip container: {path}")
    with zipfile.ZipFile(path) as bundle:
        try:
            payload = bundle.read(ANDROID_MANIFEST_AAB)
        except KeyError:
            raise VerificationError(f"{path} lacks {ANDROID_MANIFEST_AAB}") from None
    root = [value for number, _, value in protobuf_fields(payload) if number == 1 and isinstance(value, bytes)]
    if len(root) != 1:
        raise VerificationError(f"{path}: expected exactly one manifest root node, found {len(root)}")
    identity: dict[str, str] = {}
    for number, _, value in protobuf_fields(root[0]):
        if number != 4 or not isinstance(value, bytes):
            continue
        name, text = "", ""
        for inner, _, inner_value in protobuf_fields(value):
            if inner == 2 and isinstance(inner_value, bytes):
                name = inner_value.decode("utf-8", "replace")
            elif inner == 3 and isinstance(inner_value, bytes):
                text = inner_value.decode("utf-8", "replace")
        if name:
            identity[name] = text
    return identity


# --------------------------------------------------------------------------
# Signature verification.
# --------------------------------------------------------------------------

def parse_apksigner_fingerprint(output: str) -> str:
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output)
    if not matches:
        raise VerificationError("apksigner reported no signer certificate SHA-256 digest")
    if len(set(matches)) != 1:
        raise VerificationError(f"apksigner reported multiple signer certificates: {sorted(set(matches))}")
    return matches[0].lower()


def parse_keytool_fingerprint(output: str) -> str:
    if "Not a signed jar file" in output:
        raise VerificationError("AAB is not signed (keytool: not a signed jar file)")
    matches = re.findall(r"^\s*SHA256:\s*([0-9A-Fa-f:]{64,95})\s*$", output, re.MULTILINE)
    if not matches:
        raise VerificationError("keytool reported no signer certificate SHA256 fingerprint")
    fingerprints = {value.replace(":", "").lower() for value in matches}
    if len(fingerprints) != 1:
        raise VerificationError(f"keytool reported multiple signer certificates: {sorted(fingerprints)}")
    return fingerprints.pop()


def normalize_fingerprint(value: str) -> str:
    """Accept colon- or space-separated display formats as well as raw hex."""
    return re.sub(r"[^0-9a-fA-F]", "", value.strip()).lower()


def require_expected_signer(path: str, fingerprint: str, expected_signer_sha256: str | None) -> None:
    if expected_signer_sha256 and fingerprint.lower() != normalize_fingerprint(expected_signer_sha256):
        raise VerificationError(
            f"{os.path.basename(path)} signer fingerprint {fingerprint} does not match "
            f"the expected official release certificate"
        )


def verify_apk_signature(apksigner: str, path: str, expected_signer_sha256: str | None) -> str:
    result = run(apksigner, ["verify", "--print-certs", path])
    if result.returncode != 0:
        raise VerificationError(
            f"apksigner rejected {os.path.basename(path)} (exit {result.returncode}):\n"
            f"{(result.stdout + result.stderr).strip()}"
        )
    fingerprint = parse_apksigner_fingerprint(result.stdout)
    require_expected_signer(path, fingerprint, expected_signer_sha256)
    return fingerprint


def verify_apk_alignment(zipalign: str, path: str) -> None:
    result = run(zipalign, ["-c", "-p", "4", path])
    if result.returncode != 0:
        raise VerificationError(
            f"{os.path.basename(path)} is not zipaligned: {(result.stdout + result.stderr).strip()}"
        )


def verify_aab_signature(path: str, expected_signer_sha256: str | None,
                         jarsigner: str = "jarsigner", keytool: str = "keytool") -> str:
    """Verify the AAB JAR signature and return its signer fingerprint.

    jarsigner exits 0 for unsigned input (it prints a state line instead of
    failing), so the reported state is mandatory evidence.
    """
    with zipfile.ZipFile(path) as bundle:
        signed_entries = [name for name in bundle.namelist()
                          if name.startswith("META-INF/") and (name.endswith(".SF") or name.endswith(".RSA"))]
    if not signed_entries:
        raise VerificationError(f"{os.path.basename(path)} carries no JAR signature block")
    result = run(jarsigner, ["-verify", path])
    output = result.stdout + result.stderr
    if result.returncode != 0:
        raise VerificationError(
            f"jarsigner rejected {os.path.basename(path)} (exit {result.returncode}):\n{output.strip()}"
        )
    lowered = output.lower()
    if "jar verified." not in lowered:
        state = "unsigned" if ("jar is unsigned" in lowered or "not signed" in lowered) else "unverified"
        raise VerificationError(f"{os.path.basename(path)} is {state}; jarsigner reported:\n{output.strip()}")
    keytool_result = run(keytool, ["-printcert", "-jarfile", path])
    if keytool_result.returncode != 0:
        raise VerificationError(
            f"keytool could not read the signer of {os.path.basename(path)}:\n"
            f"{(keytool_result.stdout + keytool_result.stderr).strip()}"
        )
    fingerprint = parse_keytool_fingerprint(keytool_result.stdout)
    require_expected_signer(path, fingerprint, expected_signer_sha256)
    return fingerprint


# --------------------------------------------------------------------------
# Declared identity verification.
# --------------------------------------------------------------------------

def require_identity(path: str, identity: dict[str, str], package: str, version: str, code: int) -> None:
    observed = {
        "package": identity.get("package"),
        "versionName": identity.get("version_name"),
        "versionCode": identity.get("version_code"),
    }
    expected = {"package": package, "versionName": version, "versionCode": str(code)}
    if any(observed[key] != value for key, value in expected.items()):
        raise VerificationError(
            f"{os.path.basename(path)} identity mismatch: expected {expected}, observed "
            f"package={observed['package']!r} versionName={observed['versionName']!r} "
            f"versionCode={observed['versionCode']!r}"
        )


def aab_identity(path: str) -> dict[str, str]:
    attributes = aab_manifest_identity(path)
    return {
        "package": attributes.get("package"),
        "version_name": attributes.get("versionName"),
        "version_code": attributes.get("versionCode"),
    }


def apk_identity(aapt: str, path: str) -> dict[str, str]:
    result = run(aapt, ["dump", "badging", path])
    if result.returncode != 0:
        raise VerificationError(
            f"aapt could not read {os.path.basename(path)}:\n{(result.stdout + result.stderr).strip()}"
        )
    package = re.search(
        r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", result.stdout, re.MULTILINE)
    if not package:
        raise VerificationError(f"aapt reported no package identity for {path}")
    identity = {
        "package": package.group(1),
        "version_code": package.group(2),
        "version_name": package.group(3),
    }
    for key, pattern in (("sdk_version", r"^sdkVersion:'([^']*)'"),
                         ("target_sdk_version", r"^targetSdkVersion:'([^']*)'")):
        match = re.search(pattern, result.stdout, re.MULTILINE)
        if not match:
            raise VerificationError(f"aapt reported no {key} for {path}")
        identity[key] = match.group(1)
    return identity


# --------------------------------------------------------------------------
# Transport checksums.
# --------------------------------------------------------------------------

def write_checksums(paths: list[str], output: str) -> int:
    lines = []
    for path in sorted(paths, key=os.path.basename):
        if not os.path.isfile(path):
            raise VerificationError(f"cannot checksum missing file: {path}")
        lines.append(f"{sha256_file(path)}  {os.path.basename(path)}")
    with open(output, "wb") as stream:
        stream.write(("\n".join(lines) + "\n").encode())
    return len(lines)


def verify_checksums(directory: str, checksums: str) -> int:
    verified = 0
    with open(checksums, encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            line = line.strip()
            if not line:
                continue
            digest, separator, name = line.partition("  ")
            if not separator or not name or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
                raise VerificationError(f"{checksums}:{number}: malformed checksum record")
            target = os.path.join(directory, os.path.basename(name))
            if not os.path.isfile(target):
                raise VerificationError(f"checksummed asset is missing: {target}")
            observed = sha256_file(target)
            if observed != digest.lower():
                raise VerificationError(
                    f"transport hash mismatch for {name}: recorded {digest}, observed {observed}")
            verified += 1
    if verified == 0:
        raise VerificationError(f"{checksums} lists no assets")
    return verified


# --------------------------------------------------------------------------
# Commands.
# --------------------------------------------------------------------------

def command_apk(arguments: argparse.Namespace) -> int:
    identity = apk_identity(arguments.aapt, arguments.input)
    require_identity(arguments.input, identity, arguments.package, arguments.version, arguments.code)
    if arguments.target_sdk and identity["target_sdk_version"] != arguments.target_sdk:
        raise VerificationError(
            f"{os.path.basename(arguments.input)} targetSdkVersion {identity['target_sdk_version']} "
            f"does not match the required {arguments.target_sdk}"
        )
    if arguments.min_sdk and identity["sdk_version"] != arguments.min_sdk:
        raise VerificationError(
            f"{os.path.basename(arguments.input)} sdkVersion {identity['sdk_version']} "
            f"does not match the required {arguments.min_sdk}"
        )
    fingerprint = None
    if not arguments.identity_only:
        fingerprint = verify_apk_signature(arguments.apksigner, arguments.input, arguments.expected_signer_sha256)
        verify_apk_alignment(arguments.zipalign, arguments.input)
    report(arguments, identity, fingerprint)
    return 0


def command_aab(arguments: argparse.Namespace) -> int:
    identity = aab_identity(arguments.input)
    require_identity(arguments.input, identity, arguments.package, arguments.version, arguments.code)
    fingerprint = None
    if not arguments.identity_only:
        fingerprint = verify_aab_signature(arguments.input, arguments.expected_signer_sha256)
    report(arguments, identity, fingerprint)
    return 0


def report(arguments: argparse.Namespace, identity: dict[str, str], fingerprint: str | None) -> None:
    sha256 = sha256_file(arguments.input)
    print(f"verified {os.path.basename(arguments.input)}")
    print(f"  sha256: {sha256}")
    print(f"  package: {identity['package']}")
    print(f"  versionName: {identity['version_name']}")
    print(f"  versionCode: {identity['version_code']}")
    for key in ("sdk_version", "target_sdk_version"):
        if identity.get(key):
            print(f"  {key}: {identity[key]}")
    if fingerprint:
        print(f"  signer_sha256: {fingerprint}")


def add_identity(sub: argparse.ArgumentParser) -> None:
    sub.add_argument("--input", required=True)
    sub.add_argument("--package", required=True)
    sub.add_argument("--version", required=True)
    sub.add_argument("--code", required=True, type=int)
    sub.add_argument("--expected-signer-sha256",
                     help="required SHA-256 hex of the signer certificate")
    sub.add_argument("--identity-only", action="store_true",
                     help="check declared identity without signature tools")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    apk = commands.add_parser("apk", help="verify a release APK signature, alignment and identity")
    add_identity(apk)
    apk.add_argument("--aapt", default="aapt")
    apk.add_argument("--apksigner", default="apksigner")
    apk.add_argument("--zipalign", default="zipalign")
    apk.add_argument("--min-sdk")
    apk.add_argument("--target-sdk")

    aab = commands.add_parser("aab", help="verify a release AAB JAR signature and identity")
    add_identity(aab)

    checksums = commands.add_parser("checksums", help="write SHA256SUMS.txt for final assets")
    checksums.add_argument("--output", required=True)
    checksums.add_argument("files", nargs="+")

    transport = commands.add_parser("verify-checksums", help="re-hash assets against SHA256SUMS.txt")
    transport.add_argument("--directory", required=True)
    transport.add_argument("--checksums", required=True)

    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "apk":
            return command_apk(arguments)
        if arguments.command == "aab":
            return command_aab(arguments)
        if arguments.command == "checksums":
            print(f"wrote {arguments.output} for {write_checksums(arguments.files, arguments.output)} assets")
            return 0
        print(f"verified {verify_checksums(arguments.directory, arguments.checksums)} assets against "
              f"{arguments.checksums}")
        return 0
    except VerificationError as error:
        print(f"release verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
