#!/usr/bin/env python3
"""Fail-closed tests for the opt-in Android navigation result verifier."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


MODULE = Path(__file__).with_name("verify_android_navigation_instrumentation.py")
SPEC = importlib.util.spec_from_file_location("verify_android_navigation_instrumentation", MODULE)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def output_for(method: str, position: str = "POSITION_NOT_OBSERVABLE") -> str:
    scenario, reader_activity = verifier.METHOD_SCENARIOS[method]
    if scenario == "INVALID_INTENT":
        event = (
            "ANDROID_NAVIGATION|scenario=INVALID_INTENT|outcome=PASS"
            "|route=REJECTED|sheetCount=0|progress=UNCHANGED|preferences=UNCHANGED"
        )
    else:
        event = (
            "ANDROID_NAVIGATION|scenario=" + scenario + "|outcome=PASS"
            + "|identity=CANONICAL"
            + "|sheetCount=1|recreation=PASS|closed=PASS"
            + "|return=READER|readerActivity=" + reader_activity
            + "|readerChapter=CANONICAL|task=UNCHANGED|position=" + position
            + "|progress=UNCHANGED|preferences=UNCHANGED"
        )
    return (
        "INSTRUMENTATION_STATUS: numtests=1\n"
        "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
        "INSTRUMENTATION_STATUS: test=" + method + "\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        "INSTRUMENTATION_STATUS: stream=" + event + "\n"
        "INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_CLEANUP|fixtureRows=DELETED\n"
        "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
        "INSTRUMENTATION_STATUS: test=" + method + "\n"
        "INSTRUMENTATION_STATUS_CODE: 0\n"
        "INSTRUMENTATION_RESULT: stream=\nTime: 3.0\n\nOK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


class VerifyAndroidNavigationTest(unittest.TestCase):
    def test_all_allowlisted_methods_require_one_exact_pass_event(self):
        for method in verifier.METHOD_SCENARIOS:
            with self.subTest(method=method):
                verifier.verify(output_for(method), method)
                if method != "invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences":
                    verifier.verify(output_for(method, "PRESERVED"), method)
                    summary = verifier.sanitized_summary(output_for(method), method, passed=True)
                    self.assertIn("readerContinuity=PASS", summary)
                    self.assertNotIn("activityTransition=PASS", summary)
                    self.assertIn("position=POSITION_NOT_OBSERVABLE", summary)

    def test_zero_or_skipped_junit_is_not_green(self):
        output = output_for("coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce")
        with self.assertRaises(verifier.AndroidNavigationVerificationError):
            verifier.verify(
                output.replace("OK (1 test)", "OK (0 tests)"),
                "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce",
            )

    def test_missing_disposable_fixture_cleanup_is_not_green(self):
        method = "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce"
        output = output_for(method).replace(
            "INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_CLEANUP|fixtureRows=DELETED\n",
            "",
        )
        with self.assertRaises(verifier.AndroidNavigationVerificationError):
            verifier.verify(output, method)

    def test_wrong_test_count_is_not_green(self):
        output = output_for("invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences")
        with self.assertRaises(verifier.AndroidNavigationVerificationError):
            verifier.verify(
                output.replace("numtests=1", "numtests=2"),
                "invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences",
            )

    def test_duplicate_or_incomplete_ui_evidence_is_not_green(self):
        method = "warmReaderDiscoveryReusesMainActivityAndOpensCanonicalTitleBindingSheetOnce"
        output = output_for(method)
        with self.assertRaises(verifier.AndroidNavigationVerificationError):
            duplicate_event = next(line for line in output.splitlines() if "ANDROID_NAVIGATION|" in line)
            verifier.verify(output + duplicate_event + "\n", method)
        with self.assertRaises(verifier.AndroidNavigationVerificationError):
            verifier.verify(output.replace("sheetCount=1", "sheetCount=2"), method)

    def test_activity_task_and_same_reader_return_are_required(self):
        method = "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce"
        output = output_for(method)
        for invalid in (
            output.replace("readerActivity=SAME_INSTANCE", "readerActivity=REPLACED"),
            output.replace("task=UNCHANGED", "task=CHANGED"),
            output.replace("position=POSITION_NOT_OBSERVABLE", "position=CHANGED"),
            output.replace("readerChapter=CANONICAL", "readerChapter=UNKNOWN"),
        ):
            with self.subTest(invalid=invalid[-180:]):
                with self.assertRaises(verifier.AndroidNavigationVerificationError):
                    verifier.verify(invalid, method)

    def test_old_activity_transition_contract_is_not_accepted(self):
        method = "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce"
        output = output_for(method).replace(
            "identity=CANONICAL",
            "mainActivity=CREATED|identity=CANONICAL",
        )
        with self.assertRaises(verifier.AndroidNavigationVerificationError):
            verifier.verify(output, method)

    def test_reader_position_change_or_missing_position_is_not_green(self):
        method = "warmReaderDiscoveryReusesMainActivityAndOpensCanonicalTitleBindingSheetOnce"
        output = output_for(method, "PRESERVED")
        for invalid in (
            output.replace("position=PRESERVED", "position=CHANGED"),
            output.replace("|position=PRESERVED", ""),
        ):
            with self.subTest(invalid=invalid[-180:]):
                with self.assertRaises(verifier.AndroidNavigationVerificationError):
                    verifier.verify(invalid, method)

    def test_failure_report_never_copies_raw_runner_diagnostics(self):
        method = "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce"
        output = (
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
            "INSTRUMENTATION_STATUS: test=" + method + "\n"
            "INSTRUMENTATION_RESULT: failureMessage=secret-title-token\n"
            "FAILURES!!!\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            summary = Path(temp) / "summary.txt"
            junit = Path(temp) / "TEST-navigation.xml"
            result = verifier.main(
                [str(self._write_input(temp, output)), method, "--summary", str(summary), "--junit", str(junit)],
            )
            self.assertEqual(1, result)
            self.assertNotIn("secret-title-token", summary.read_text(encoding="utf-8"))
            self.assertNotIn("secret-title-token", junit.read_text(encoding="utf-8"))

    def test_failure_summary_includes_only_sanitized_onboarding_precondition(self):
        method = "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce"
        for result in ("COMPLETED", "INCOMPLETE"):
            with self.subTest(result=result):
                output = output_for(method) + (
                    "INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_OBSERVATION"
                    "|scenario=FIXTURE|checkpoint=ONBOARDING_PRECONDITION|result=" + result + "\n"
                    "INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_OBSERVATION"
                    "|scenario=FIXTURE|checkpoint=ONBOARDING_TEXT|result=PRIVATE_TITLE\n"
                    "INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_OBSERVATION"
                    "|scenario=FIXTURE|checkpoint=ONBOARDING_PRECONDITION"
                    "|result=COMPLETED|message=secret-token\n"
                )

                summary = verifier.sanitized_summary(output, method, passed=False)

                self.assertIn(
                    "ANDROID_NAVIGATION_RESULT|observation=FIXTURE"
                    "|checkpoint=ONBOARDING_PRECONDITION|result=" + result,
                    summary,
                )
                self.assertNotIn("ONBOARDING_TEXT", summary)
                self.assertNotIn("PRIVATE_TITLE", summary)
                self.assertNotIn("secret-token", summary)

    def test_missing_junit_execution_does_not_emit_a_fake_zero_or_one_test_report(self):
        method = "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce"
        with tempfile.TemporaryDirectory() as temp:
            summary = Path(temp) / "summary.txt"
            junit = Path(temp) / "TEST-navigation.xml"
            result = verifier.main(
                [str(self._write_input(temp, "")), method, "--summary", str(summary), "--junit", str(junit)],
            )
            self.assertEqual(1, result)
            self.assertFalse(junit.exists())
            self.assertIn("junitTests=0|junitStatus=FAIL_OR_UNPROVEN", summary.read_text(encoding="utf-8"))

    @staticmethod
    def _write_input(directory: str, output: str) -> Path:
        path = Path(directory) / "runner.txt"
        path.write_text(output, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
