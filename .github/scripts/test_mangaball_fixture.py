#!/usr/bin/env python3
"""Network-free regressions for the pinned MangaBall Android fixture."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).with_name("verify_mangaball_fixture.py")
SPEC = importlib.util.spec_from_file_location("verify_mangaball_fixture", MODULE)
assert SPEC is not None and SPEC.loader is not None
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class MangaBallFixtureTests(unittest.TestCase):
    def test_repository_fixture_is_pinned_and_crc_valid(self):
        self.assertEqual(fixture.verify_fixture(), fixture.EXPECTED_SHA256)

    def test_missing_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(fixture.MangaBallFixtureError):
                fixture.verify_fixture(Path(directory) / "missing.apk")

    def test_mutated_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "mutated.apk"
            data = bytearray(fixture.FIXTURE.read_bytes())
            data[-1] ^= 1
            target.write_bytes(data)
            with self.assertRaises(fixture.MangaBallFixtureError):
                fixture.verify_fixture(target)

    def test_android_runner_is_explicit_opt_in_and_live_probe_is_bounded(self):
        workflow_path = fixture.ROOT / ".github/workflows/mangafire-real-extension.yml"
        runner_path = fixture.ROOT / ".github/scripts/run_mangaball_android.sh"
        router_path = fixture.ROOT / ".github/scripts/run_android_instrumentation_route.sh"
        workflow = workflow_path.read_text(encoding="utf-8")
        runner = runner_path.read_text(encoding="utf-8")
        router = router_path.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("extension_profile:", workflow)
        self.assertIn("default: mangafire", workflow)
        self.assertIn("- mangaball", workflow)
        self.assertIn("live_probe:", workflow)
        self.assertIn("default: false", workflow)
        self.assertIn("run_android_instrumentation_route.sh", workflow)
        self.assertIn('if [[ "$live_probe" == "true" ]]', runner)
        self.assertIn("optionalLivePtBrReadingJourney", runner)
        self.assertIn("journey=INCONCLUSIVE", runner)
        self.assertIn("is not a passing E2E result", runner)
        self.assertNotIn("run_extension_matrix_android.sh", runner)
        self.assertNotIn("run_extension_live_android.sh", runner)
        self.assertIn("MANGABALL_FIXTURE_ONLY", router)
        self.assertIn("MANGABALL_LIVE", router)
        self.assertFalse((fixture.ROOT / ".github/workflows/mangaball-real-extension.yml").exists())

    def test_workflow_and_script_validate_integrity_and_signer(self):
        workflow = (fixture.ROOT / ".github/workflows/mangafire-real-extension.yml").read_text(encoding="utf-8")
        runner = (fixture.ROOT / ".github/scripts/run_mangaball_android.sh").read_text(encoding="utf-8")
        self.assertIn("verify_mangaball_fixture.py", workflow)
        self.assertIn("verify_mangaball_fixture.py", runner)
        self.assertIn("apksigner", runner)
        self.assertIn("verify --verbose", runner)


if __name__ == "__main__":
    unittest.main()
