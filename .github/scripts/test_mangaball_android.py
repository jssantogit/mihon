#!/usr/bin/env python3
"""Fail-closed tests for the bounded MangaBall Android report."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).with_name("mangaball_android_report.py")
SPEC = importlib.util.spec_from_file_location("mangaball_android_report", MODULE)
assert SPEC is not None and SPEC.loader is not None
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def junit(method: str, body: str, *, test_count: int = 1, junit_result: str = "OK (1 test)") -> str:
    return (
        "INSTRUMENTATION_STATUS: class=" + report.JOURNEY_CLASS + "\n"
        + "INSTRUMENTATION_STATUS: test=" + method + "\n"
        + "INSTRUMENTATION_STATUS: numtests=" + str(test_count) + "\n"
        + body
        + "INSTRUMENTATION_CODE: -1\n"
        + junit_result + "\n"
    )


def journey(*, blocker: tuple[str, str] | None = None, category: str = "NO_RESULTS") -> str:
    events: list[str] = []
    for stage in report.JOURNEY_STAGES:
        if blocker and stage == blocker[0]:
            events.append(report.event(stage, blocker[1], category=category))
            stopped = True
        elif blocker and "stopped" in locals() and stopped:
            events.append(report.event(stage, "NOT_RUN", category="NO_RESULTS"))
        else:
            events.append(report.event(stage, "PASS"))
    return "\n".join(
        [
            "INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT|applicationContext=present|storage=TARGET_DISPOSABLE|databaseContext=wrapped",
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CLOSE|outcome=PASS",
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DATABASE_CLEANUP|outcome=PASS",
            *events,
        ],
    ) + "\n"


class MangaBallReportTests(unittest.TestCase):
    def test_fixture_requires_grouping_count_and_disabled_peer_absence(self):
        output = junit(
            report.FIXTURE_METHOD,
            "INSTRUMENTATION_STATUS: stream=MANGABALL_FIXTURE|outcome=PASS|addonGroups=1"
            "|internalSources=42|eligibleBefore=42|eligibleAfter=41|ptBrSourceId="
            + str(report.SOURCE_ID)
            + "|disabledPeerExcluded=true\n",
        )
        summary = report.verify_and_summarize(output, report.FIXTURE_METHOD)
        self.assertIn("DIAGNOSTIC|providerCalls=0", summary)

        incomplete = output.replace("|eligibleAfter=41", "|eligibleAfter=42")
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(incomplete, report.FIXTURE_METHOD)

    def test_complete_real_journey_requires_one_junit_method_and_exact_stage_order(self):
        output = junit(report.LIVE_METHOD, journey())
        summary = report.verify_and_summarize(output, report.LIVE_METHOD)
        self.assertTrue(any("journey=PASS" in line for line in summary))

    def test_external_no_results_is_inconclusive_not_a_false_pass(self):
        output = junit(report.LIVE_METHOD, journey(blocker=("LIVE_SEARCH", "INCONCLUSIVE")))
        summary = report.verify_and_summarize(output, report.LIVE_METHOD)
        self.assertTrue(any("journey=INCONCLUSIVE" in line for line in summary))
        self.assertFalse(any("journey=PASS" in line for line in summary))

    def test_closed_external_failure_categories_remain_distinguishable(self):
        for category in ("HTTP_403", "HTTP_429", "HTTP_5XX", "NETWORK", "TIMEOUT", "CAPTCHA"):
            with self.subTest(category=category):
                output = junit(
                    report.LIVE_METHOD,
                    journey(blocker=("LIVE_SEARCH", "INCONCLUSIVE"), category=category),
                )
                summary = report.verify_and_summarize(output, report.LIVE_METHOD)
                self.assertIn("category=" + category, summary[3])

    def test_pass_after_external_blocker_is_rejected(self):
        output = junit(report.LIVE_METHOD, journey(blocker=("LIVE_SEARCH", "INCONCLUSIVE")))
        output = output.replace(
            report.event("CANDIDATE_IDENTIFICATION", "NOT_RUN", category="NO_RESULTS"),
            report.event("CANDIDATE_IDENTIFICATION", "PASS"),
        )
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(output, report.LIVE_METHOD)

    def test_zero_junit_tests_are_rejected(self):
        output = junit(report.LIVE_METHOD, journey(), test_count=0, junit_result="OK (0 tests)")
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(output, report.LIVE_METHOD)

    def test_missing_duplicate_and_out_of_order_stage_are_rejected(self):
        complete = junit(report.LIVE_METHOD, journey())
        missing = complete.replace(report.event("RECONCILIATION", "PASS") + "\n", "")
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(missing, report.LIVE_METHOD)

        duplicate = complete.replace(
            report.event("RECONCILIATION", "PASS") + "\n",
            report.event("RECONCILIATION", "PASS") + "\n" + report.event("RECONCILIATION", "PASS") + "\n",
        )
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(duplicate, report.LIVE_METHOD)

        reversed_events = complete.replace(
            report.event("INVENTORY", "PASS") + "\n" + report.event("CHAPTER_PROBE", "PASS") + "\n",
            report.event("CHAPTER_PROBE", "PASS") + "\n" + report.event("INVENTORY", "PASS") + "\n",
        )
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(reversed_events, report.LIVE_METHOD)

    def test_sensitive_candidate_data_is_never_summarized(self):
        unsafe = journey().replace(
            report.event("LIVE_SEARCH", "PASS"),
            report.event("LIVE_SEARCH", "PASS") + "|url=https://secret.example/path?token=secret",
        )
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(junit(report.LIVE_METHOD, unsafe), report.LIVE_METHOD)

    def test_confirmation_requires_identity_verified_candidate_before_pass(self):
        self.assertIn("MANGABALL_REFERENCE_PATH", report.__dict__)
        self.assertIn("SOURCE_ID", report.__dict__)

    def test_live_journey_requires_real_disposable_database_cleanup(self):
        output = junit(report.LIVE_METHOD, journey()).replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DATABASE_CLEANUP|outcome=PASS\n",
            "",
        )
        with self.assertRaises(report.MangaBallReportError):
            report.verify_and_summarize(output, report.LIVE_METHOD)


if __name__ == "__main__":
    unittest.main()
