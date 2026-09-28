#!/usr/bin/env python3
"""Verify the immutable MangaBall 1.6.1 APK bytes before emulator installation."""
from __future__ import annotations

import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "test-fixtures/extensions/manga-ball-1.6.1.apk"
EXPECTED_SHA256 = "c2212a46d201034c15717873183b1b1261a00fbc5db52ab5eae6976f386d6739"
EXPECTED_SIZE = 81635
REQUIRED_FILES = frozenset(("AndroidManifest.xml", "classes.dex", "resources.arsc"))


class MangaBallFixtureError(ValueError):
    pass


def verify_fixture(path: Path = FIXTURE) -> str:
    if not path.is_file():
        raise MangaBallFixtureError("MangaBall fixture is missing")
    content = path.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    if len(content) != EXPECTED_SIZE or digest != EXPECTED_SHA256:
        raise MangaBallFixtureError("MangaBall fixture differs from its immutable SHA-256 pin")
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.namelist()
            if len(members) != len(set(members)):
                raise MangaBallFixtureError("MangaBall APK contains duplicate archive entries")
            if not REQUIRED_FILES <= set(members):
                raise MangaBallFixtureError("MangaBall APK lacks required Android archive entries")
            if archive.testzip() is not None:
                raise MangaBallFixtureError("MangaBall APK failed ZIP CRC validation")
    except (OSError, zipfile.BadZipFile) as error:
        raise MangaBallFixtureError("MangaBall fixture is not a valid APK archive") from error
    return digest


if __name__ == "__main__":
    print("MangaBall 1.6.1 fixture OK: sha256=" + verify_fixture())
