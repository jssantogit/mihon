#!/usr/bin/env python3
"""Verify the exact user-provided MangaFire APK before using it as a test fixture.

This does not certify upstream origin or live provider availability.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "test-fixtures/extensions/mangafire-v1.6.34.apk"
EXPECTED_SHA256 = "f5a2bedca694bcf1ef7b133c82d0c0d99a8e1c59e9237b0d93f9153d9c3c7378"
EXPECTED_SIZE = 83403
REQUIRED_FILES = frozenset(("AndroidManifest.xml", "classes.dex", "resources.arsc"))


class FixtureVerificationError(ValueError):
    pass


def verify_fixture(path: Path = FIXTURE) -> str:
    if not path.is_file():
        raise FixtureVerificationError("MangaFire fixture is missing")
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != EXPECTED_SIZE or digest != EXPECTED_SHA256:
        raise FixtureVerificationError(
            "MangaFire fixture does not match the immutable user-provided APK"
        )
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise FixtureVerificationError("MangaFire APK has duplicate ZIP members")
            missing = REQUIRED_FILES.difference(names)
            if missing:
                raise FixtureVerificationError(
                    "MangaFire APK lacks required Android archive members"
                )
            if archive.testzip() is not None:
                raise FixtureVerificationError("MangaFire APK failed ZIP CRC verification")
    except (OSError, zipfile.BadZipFile) as error:
        raise FixtureVerificationError("MangaFire APK is not a valid ZIP archive") from error
    return digest


if __name__ == "__main__":
    print("MangaFire 1.6.34 fixture OK: sha256=" + verify_fixture())
