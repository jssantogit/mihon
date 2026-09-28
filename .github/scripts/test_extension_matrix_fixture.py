#!/usr/bin/env python3
"""Regression: exact byte checks cannot be bypassed by a valid but different APK."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

FILE = Path(__file__).with_name("verify_extension_matrix_fixture.py")
SPEC = importlib.util.spec_from_file_location("extension_matrix_fixture", FILE)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

class FixtureMatrixTests(unittest.TestCase):
    def test_all_eight_user_apks(self):
        self.assertEqual(len(verifier.verify_all()), 8)

    def test_one_byte_mutation_is_rejected(self):
        name = "manga-ball-1.6.1.apk"
        with tempfile.TemporaryDirectory() as folder:
            changed = bytearray((verifier.DIR / name).read_bytes())
            changed[-1] ^= 1
            file = Path(folder) / name
            file.write_bytes(changed)
            with self.assertRaises(ValueError):
                verifier.verify_one(name, file)

    def test_unpinned_name_is_rejected(self):
        with self.assertRaises(ValueError):
            verifier.verify_one("unknown.apk")

if __name__ == "__main__":
    unittest.main()
