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

        script = verifier.ROOT / ".github/scripts/run_android_instrumentation_route.sh"
        workflow = (verifier.ROOT / ".github/workflows/mangafire-real-extension.yml").read_text(encoding="utf-8")
        self.assertIn("script: bash .github/scripts/run_android_instrumentation_route.sh", workflow)
        result = subprocess.run(["bash", "-n", str(script)], check=False, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_compatibility_branch_runs_fixture_and_compile_but_not_emulator_without_marker(self):
        workflow = (verifier.ROOT / ".github/workflows/mangafire-real-extension.yml").read_text(encoding="utf-8")
        push_config = workflow.split("  push:\n", 1)[1].split("  workflow_dispatch:", 1)[0]

        self.assertRegex(push_config, r"(?m)^      - tsuzuki/generic-addon-compatibility$")
        self.assertRegex(push_config, r"(?m)^      - 'app/src/androidTest/\*\*'$")
        self.assertRegex(push_config, r"(?m)^      - '\.github/workflows/mangafire-real-extension\.yml'$")

        fixture_job = workflow.split("  fixture:\n", 1)[1].split("  compile-android-test:", 1)[0]
        compile_job = workflow.split("  compile-android-test:\n", 1)[1].split("  emulator:", 1)[0]
        emulator_job = workflow.split("  emulator:\n", 1)[1]
        self.assertNotRegex(fixture_job, r"(?m)^    if:")
        self.assertNotRegex(compile_job, r"(?m)^    if:")
        expected_emulator_gate = (
            "    if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && "
            "(contains(github.event.head_commit.message, '[android-fixture]') || "
            "contains(github.event.head_commit.message, '[android-live]') || "
            "contains(github.event.head_commit.message, '[android-live-mangaball]')))"
        )
        self.assertIn(expected_emulator_gate, emulator_job)
        self.assertIn("contains(github.event.head_commit.message, '[android-live-mangaball]')", emulator_job)
        self.assertIn("&& 'mangaball' || 'mangafire'", emulator_job)

    def test_android_junit_tests_return_void_on_jvm(self):
        # A Kotlin expression-bodied @Test ending in Log.i() returns Int and JUnit4
        # rejects it as InvalidTestClassError. A block body returns JVM void.
        src = verifier.ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaFireFixtureInstrumentedTest.kt"
        content = src.read_text(encoding='utf-8')
        for method in ("loadsRealExtensionAndRegistersInternalSources", "optionalLiveEnglishSearch"):
            self.assertIn("    @Test\n    fun " + method + "() {\n        runBlocking {", content)
            self.assertNotIn("fun " + method + "() = runBlocking", content)

    def test_apk_is_explicitly_binary_in_git(self):
        attributes = (verifier.ROOT / ".gitattributes").read_text(encoding="utf-8")
        self.assertIn("/test-fixtures/extensions/*.apk binary", attributes)


if __name__ == "__main__":
    unittest.main()
