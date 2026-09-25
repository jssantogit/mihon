#!/usr/bin/env python3
"""Pin workflow dispatch modes without starting an emulator or provider request."""
from __future__ import annotations

from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
ROUTER = ROOT / ".github/scripts/run_android_instrumentation_route.sh"


class AndroidInstrumentationRouteTest(unittest.TestCase):
    def route(
        self,
        event: str,
        live: str,
        fixture_marker: str = "false",
        profile: str = "mangafire",
        mangaball_marker: str = "false",
    ) -> str:
        result = subprocess.run(
            ["bash", str(ROUTER), event, live, fixture_marker, "true", profile, mangaball_marker],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_dispatch_false_runs_offline_navigation_without_provider_calls(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=NAVIGATION_ONLY|liveProbe=false|providerCalls=0",
            self.route("workflow_dispatch", "false"),
        )

    def test_dispatch_true_preserves_explicit_mangafire_live_route(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=MANGAFIRE_LIVE|liveProbe=true|providerCalls=1",
            self.route("workflow_dispatch", "true"),
        )

    def test_mangaball_dispatch_false_runs_fixture_only_without_provider_calls(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=MANGABALL_FIXTURE_ONLY|liveProbe=false|providerCalls=0|extensionProfile=mangaball",
            self.route("workflow_dispatch", "false", profile="mangaball"),
        )

    def test_mangaball_dispatch_true_is_explicitly_live(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=MANGABALL_LIVE|liveProbe=true|providerCalls=1|extensionProfile=mangaball",
            self.route("workflow_dispatch", "true", profile="mangaball"),
        )

    def test_fixture_push_marker_keeps_the_non_live_mangafire_fixture_route(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=MANGAFIRE_FIXTURE|liveProbe=false|providerCalls=0",
            self.route("push", "false", "true"),
        )

    def test_live_push_marker_keeps_the_mangafire_live_route(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=MANGAFIRE_LIVE|liveProbe=true|providerCalls=1",
            self.route("push", "true", "true"),
        )

    def test_unmarked_push_is_rejected(self):
        result = subprocess.run(
            ["bash", str(ROUTER), "push", "false", "false", "true"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("reason=PUSH_MARKER_REQUIRED", result.stderr)
        self.assertNotIn("providerCalls=1", result.stdout)

    def test_explicit_mangaball_push_marker_runs_only_bounded_live_profile(self):
        self.assertEqual(
            "ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=MANGABALL_LIVE"
            "|liveProbe=true|providerCalls=1|extensionProfile=mangaball",
            self.route("push", "true", "true", profile="mangaball", mangaball_marker="true"),
        )

    def test_mangaball_push_marker_refuses_non_live_probe(self):
        result = subprocess.run(
            ["bash", str(ROUTER), "push", "false", "true", "true", "mangaball", "true"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("reason=MANGABALL_LIVE_MARKER_REQUIRED", result.stderr)
        self.assertNotIn("providerCalls=1", result.stdout)

    def test_mangaball_push_marker_refuses_mismatched_profile(self):
        result = subprocess.run(
            ["bash", str(ROUTER), "push", "true", "true", "true", "mangafire", "true"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("reason=MANGABALL_LIVE_MARKER_REQUIRED", result.stderr)
        self.assertNotIn("providerCalls=1", result.stdout)

    def test_mangaball_profile_is_rejected_for_push(self):
        result = subprocess.run(
            ["bash", str(ROUTER), "push", "false", "true", "true", "mangaball"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("reason=PROFILE_UNSUPPORTED_FOR_PUSH", result.stderr)
        self.assertNotIn("providerCalls=1", result.stdout)


if __name__ == "__main__":
    unittest.main()
