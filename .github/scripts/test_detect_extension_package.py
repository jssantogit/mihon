#!/usr/bin/env python3
"""Regression for APK package identification independent of build-tools aapt."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

FILE = Path(__file__).with_name("detect_extension_package.py")
SPEC = importlib.util.spec_from_file_location("detect_extension_package", FILE)
assert SPEC and SPEC.loader
detector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(detector)


class PackageMetadataTest(unittest.TestCase):
    def test_exactly_one_new_mangaball_package(self):
        before = "package:app.mihon.dev\npackage:app.mihon.dev.test\n"
        after = before + "package:eu.kanade.tachiyomi.extension.pt.mangaball\n"
        self.assertEqual(
            detector.package_from_install(before, after),
            "eu.kanade.tachiyomi.extension.pt.mangaball",
        )

    def test_missing_or_multiple_installs_cannot_pass(self):
        before = "package:app.mihon.dev\n"
        with self.assertRaises(detector.PackageMetadataError):
            detector.package_from_install(before, before)
        with self.assertRaises(detector.PackageMetadataError):
            detector.package_from_install(
                before,
                before + "package:eu.kanade.tachiyomi.extension.pt.a\n"
                "package:eu.kanade.tachiyomi.extension.pt.b\n",
            )

    def test_arbitrary_app_is_rejected(self):
        with self.assertRaises(detector.PackageMetadataError):
            detector.package_from_install("", "package:com.example.untrusted\n")

    def test_version_from_android_package_manager(self):
        self.assertEqual(
            detector.version_from_dumpsys(
                "Package [eu.kanade.tachiyomi.extension.pt.mangaball]\n"
                "  versionCode=106001 minSdk=26 targetSdk=35\n"
                "  versionName=1.6.1\n",
            ),
            "1.6.1",
        )

    def test_no_version_is_not_silently_assumed(self):
        with self.assertRaises(detector.PackageMetadataError):
            detector.version_from_dumpsys("Package [test]\nversionCode=1\n")


if __name__ == "__main__":
    unittest.main()
