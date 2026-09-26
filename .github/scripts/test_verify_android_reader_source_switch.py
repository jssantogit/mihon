#!/usr/bin/env python3
"""Regression tests for the offline Reader source-switch CI evidence gate."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE = Path(__file__).with_name("verify_android_reader_source_switch.py")
SPEC = importlib.util.spec_from_file_location("verify_android_reader_source_switch", MODULE)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def output_for(method: str) -> str:
    scenario = verifier.METHOD_SCENARIOS[method]
    fields = {
        "scenario": scenario,
        "pages": "LOADED",
        "position": "OBSERVABLE",
        "outcome": "PASS",
        "pageCount": "10",
        "positionIndex": "4",
    }
    if method == "emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession":
        fields["session"] = "PREVIOUS_PRESERVED"
    elif method == "pageCountDifferenceClampsPositionToValidPage":
        fields.update(
            {
                "pageCount": "3",
                "positionIndex": "2",
                "sourceAPageCount": "10",
                "sourceBPageCount": "3",
                "positionBefore": "8",
                "positionAfter": "2",
            },
        )
    elif method == "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition":
        fields.update(
            {
                "sourceAPageCount": "10",
                "pageCount": "3",
                "positionIndex": "2",
                "sourceBPageCount": "3",
                "positionBefore": "4",
                "positionAfter": "2",
            },
        )

    event = "ANDROID_SOURCE_SWITCH|" + "|".join(key + "=" + value for key, value in fields.items())
    return (
        "INSTRUMENTATION_STATUS: numtests=1\n"
        "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
        "INSTRUMENTATION_STATUS: test=" + method + "\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        "INSTRUMENTATION_STATUS: stream=" + event + "\n"
        "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
        "INSTRUMENTATION_STATUS: test=" + method + "\n"
        "INSTRUMENTATION_STATUS_CODE: 0\n"
        "INSTRUMENTATION_RESULT: stream=\nTime: 3.0\n\nOK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


class VerifyAndroidReaderSourceSwitchTest(unittest.TestCase):
    def test_each_named_method_requires_one_executed_junit_test_and_observed_pages(self):
        for method in verifier.METHOD_SCENARIOS:
            with self.subTest(method=method):
                fields = verifier.verify(output_for(method), method)
                self.assertEqual("OBSERVABLE", fields["position"])
                self.assertGreater(int(fields["pageCount"]), 0)

    def test_zero_skipped_or_unexecuted_method_is_not_green(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        valid = output_for(method)
        for invalid in (
            valid.replace("OK (1 test)", "OK (0 tests)"),
            valid.replace("numtests=1", "numtests=0"),
            valid.replace(
                "INSTRUMENTATION_STATUS: test=" + method,
                "INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test=" + method,
            ),
            valid.replace("test=" + method, "test=anotherTest"),
            valid.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
        ):
            with self.subTest(invalid=invalid[-120:]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_position_not_observable_always_fails(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        with self.assertRaisesRegex(verifier.ReaderSourceSwitchVerificationError, "not observable"):
            verifier.verify(output_for(method) + "POSITION_NOT_OBSERVABLE\n", method)

    def test_missing_numeric_count_or_index_fails(self):
        method = "retiredSourceCallbackCannotChangePublishedSession"
        valid = output_for(method)
        for invalid in (
            valid.replace("|pageCount=10", ""),
            valid.replace("|positionIndex=4", ""),
            valid.replace("|pageCount=10", "|pageCount=0"),
            valid.replace("|positionIndex=4", "|positionIndex=10"),
        ):
            with self.subTest(invalid=invalid.split("ANDROID_SOURCE_SWITCH|")[-1]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_empty_or_failing_source_must_prove_old_session_is_intact(self):
        method = "emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession"
        with self.assertRaisesRegex(verifier.ReaderSourceSwitchVerificationError, "old Reader session"):
            verifier.verify(output_for(method).replace("|session=PREVIOUS_PRESERVED", ""), method)

    def test_clamp_requires_distinct_counts_and_in_range_clamped_position(self):
        method = "pageCountDifferenceClampsPositionToValidPage"
        valid = output_for(method)
        verifier.verify(valid, method)
        for invalid in (
            valid.replace("positionAfter=2", "positionAfter=8"),
            valid.replace("sourceBPageCount=3", "sourceBPageCount=10"),
            valid.replace("pageCount=3", "pageCount=2"),
        ):
            with self.subTest(invalid=invalid.split("ANDROID_SOURCE_SWITCH|")[-1]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_valid_source_switch_requires_two_loaded_sources_and_preserved_index(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        valid = output_for(method)
        for invalid in (
            valid.replace("sourceAPageCount=10", "sourceAPageCount=1"),
            valid.replace("positionAfter=2", "positionAfter=1"),
            valid.replace("sourceBPageCount=3", "sourceBPageCount=0"),
        ):
            with self.subTest(invalid=invalid.split("ANDROID_SOURCE_SWITCH|")[-1]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_duplicate_evidence_is_not_green(self):
        method = "retiredSourceCallbackCannotChangePublishedSession"
        line = next(line for line in output_for(method).splitlines() if "ANDROID_SOURCE_SWITCH|" in line)
        with self.assertRaisesRegex(verifier.ReaderSourceSwitchVerificationError, "exactly one"):
            verifier.verify(output_for(method) + line + "\n", method)

    def test_failure_summary_does_not_copy_raw_runner_output(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        secret = "private-provider-title-token"
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            input_path = directory / "runner.txt"
            summary = directory / "summary.txt"
            junit = directory / "TEST-switch.xml"
            input_path.write_text(secret, encoding="utf-8")
            result = verifier.main(
                [str(input_path), method, "--summary", str(summary), "--junit", str(junit)],
            )
            self.assertEqual(1, result)
            self.assertNotIn(secret, summary.read_text(encoding="utf-8"))
            self.assertNotIn(secret, junit.read_text(encoding="utf-8"))

    def test_workflow_has_manual_offline_suite_opt_in_and_preserves_navigation_default(self):
        workflow = (ROOT / ".github/workflows/mangafire-real-extension.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("live_probe:", workflow)
        self.assertIn("instrumentation_suite:", workflow)
        self.assertIn("default: navigation", workflow)
        self.assertIn("- reader-source-switch", workflow)
        self.assertIn("run_android_instrumentation_route.sh", workflow)
        self.assertIn("inputs.instrumentation_suite || 'navigation'", workflow)


if __name__ == "__main__":
    unittest.main()
