#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Repository-owned managed debug build. Extra arguments are passed to Gradle.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
exec python3 scripts/build_debug.py "$@"
