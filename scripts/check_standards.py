#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify an independently retrieved policy checkout against the adoption inventory."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'docs/standards-lock.json').read_text())
checkout = Path(sys.argv[1])
head = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
if head != lock['commit']:
    raise SystemExit('Standards checkout HEAD differs from adopted immutable commit')
for name, expected in lock['files'].items():
    original = subprocess.check_output(['git', '-C', str(checkout), 'show', f'{head}:{name}'])
    local = checkout / name
    if local.is_symlink() or hashlib.sha256(original).hexdigest() != expected or local.read_bytes() != original:
        raise SystemExit('Unverified standards document: ' + name)
print(f"Verified {len(lock['files'])} policy/template/example files at {head}")
