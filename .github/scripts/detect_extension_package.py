#!/usr/bin/env python3
"""Derive one *installed* extension package and version from Android PM snapshots.

Avoid aapt badging: the eight real APKs all failed the old aapt metadata path.
Only sanitized package names/version strings are emitted; no raw manifest or
untrusted dumpsys text reaches the CI output.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

PACKAGE = re.compile(r"package:(eu\.kanade\.tachiyomi\.extension\.[a-z0-9._]+)")
VERSION = re.compile(r"(?m)^\s*versionName=([A-Za-z0-9][A-Za-z0-9._+-]*)\s*$")


class PackageMetadataError(ValueError):
    pass


def package_from_install(before: str, after: str) -> str:
    previous = {line.strip() for line in before.splitlines() if line.strip()}
    current = {line.strip() for line in after.splitlines() if line.strip()}
    new_packages = current - previous
    # Require exactly one newly installed app, with the Mihon extension prefix.
    if len(new_packages) != 1:
        raise PackageMetadataError(
            "Expected exactly one new installed package; observed " + str(len(new_packages))
        )
    package = next(iter(new_packages))
    matched = PACKAGE.fullmatch(package)
    if not matched:
        raise PackageMetadataError("Newly installed package is not a Mihon extension")
    return matched.group(1)


def version_from_dumpsys(dumpsys: str) -> str:
    versions = VERSION.findall(dumpsys)
    if not versions:
        raise PackageMetadataError("Android PackageManager returned no versionName")
    return versions[0]


if __name__ == "__main__":
    try:
        if len(sys.argv) == 4 and sys.argv[1] == "package":
            print(package_from_install(
                Path(sys.argv[2]).read_text(encoding="utf-8"),
                Path(sys.argv[3]).read_text(encoding="utf-8"),
            ))
        elif len(sys.argv) == 3 and sys.argv[1] == "version":
            print(version_from_dumpsys(Path(sys.argv[2]).read_text(encoding="utf-8")))
        else:
            raise PackageMetadataError(
                "usage: detect_extension_package.py package BEFORE AFTER | version DUMPSYS"
            )
    except (PackageMetadataError, OSError) as error:
        print("::error::APK PackageManager metadata check failed: " + str(error), file=sys.stderr)
        raise SystemExit(1) from None
