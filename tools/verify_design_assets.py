#!/usr/bin/env python3
"""Verify immutable Nasedka original UI references against the approved SHA-256 index.

Usage: python3 tools/verify_design_assets.py
Exit code 0 means all 43 expected originals are present, unchanged and no
unexpected DES-*.png files exist in docs/design/assets.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "docs/design/ASSET_CHECKSUMS.md"
ASSETS = ROOT / "docs/design/assets"
PATTERN = re.compile(r"^(DES-\d{3}) ([0-9a-f]{64})$", re.MULTILINE)


def main() -> int:
    if not INDEX.is_file():
        print(f"ERROR: missing checksum index: {INDEX}", file=sys.stderr)
        return 2
    entries = dict(PATTERN.findall(INDEX.read_text(encoding="utf-8")))
    if len(entries) != 43:
        print(f"ERROR: expected 43 unique image hashes, found {len(entries)}", file=sys.stderr)
        return 2
    errors = []
    for identifier, expected in entries.items():
        source = ASSETS / f"{identifier}.png"
        if not source.is_file():
            errors.append(f"MISSING {source.relative_to(ROOT)}")
            continue
        if source.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
            errors.append(f"INVALID_PNG_HEADER {identifier}")
            continue
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if digest != expected:
            errors.append(f"SHA256_MISMATCH {identifier}: {digest} != {expected}")
    if ASSETS.exists():
        extras = {p.stem for p in ASSETS.glob("DES-*.png")} - entries.keys()
        errors.extend(f"UNKNOWN_IMAGE {identifier}" for identifier in sorted(extras))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        print(f"FAIL: {len(errors)} problem(s), cannot claim design assets are complete", file=sys.stderr)
        return 1
    print(f"PASS: all {len(entries)} approved-or-archived original PNG files present and byte-identical")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
