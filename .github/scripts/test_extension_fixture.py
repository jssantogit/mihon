#!/usr/bin/env python3
"""Fast, network-free integrity regressions for the APK fixture."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).with_name("verify_extension_fixture.py")
SPEC = importlib.util.spec_from_file_location("verify_extension_fixture", MODULE)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class MangaFireFixtureTest(unittest.TestCase):
    def test_repository_fixture_is_byte_exact_and_crc_valid(self):
        self.assertEqual(verifier.verify_fixture(), verifier.EXPECTED_SHA256)

    def test_missing_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(verifier.FixtureVerificationError):
                verifier.verify_fixture(Path(directory) / "missing.apk")

    def test_modified_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            mutated = Path(directory) / "modified.apk"
            content = bytearray(verifier.FIXTURE.read_bytes())
            content[0] ^= 1
            mutated.write_bytes(content)
            with self.assertRaises(verifier.FixtureVerificationError):
                verifier.verify_fixture(mutated)

    def test_emulator_runner_keeps_shell_state_in_one_script(self):
        import subprocess

        script = verifier.ROOT / ".github/scripts/run_mangafire_android.sh"
        workflow = (verifier.ROOT / ".github/workflows/mangafire-real-extension.yml").read_text(encoding="utf-8")
        self.assertIn("script: bash .github/scripts/run_mangafire_android.sh", workflow)
        result = subprocess.run(["bash", "-n", str(script)], check=False, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_apk_is_explicitly_binary_in_git(self):
        attributes = (verifier.ROOT / ".gitattributes").read_text(encoding="utf-8")
        self.assertIn("/test-fixtures/extensions/*.apk binary", attributes)


if __name__ == "__main__":
    unittest.main()
