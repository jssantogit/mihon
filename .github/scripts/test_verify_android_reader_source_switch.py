#!/usr/bin/env python3
"""Regression tests for the offline Reader source-switch CI evidence gate."""
from __future__ import annotations

import importlib.util
from contextlib import redirect_stderr, redirect_stdout
import io
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
                "pageCount": "10",
                "positionIndex": "4",
                "sourceBPageCount": "10",
                "positionBefore": "4",
                "positionAfter": "4",
            },
        )
    elif method == "activityRecreationRestoresObservedCanonicalPosition":
        fields.update(
            {
                "pageCount": "10",
                "positionIndex": "6",
                "sourceAPageCount": "10",
                "sourceBPageCount": "10",
                "positionBefore": "6",
                "positionAfter": "6",
            },
        )
    elif method == "repeatedSourceSwitchKeepsPreferenceAndSingleHistoryEntry":
        fields.update(
            {
                "pageCount": "10",
                "positionIndex": "2",
                "sourceAPageCount": "10",
                "sourceBPageCount": "10",
                "positionBefore": "2",
                "positionAfter": "2",
                "historyRows": "1",
            },
        )
    elif method == "slowSourceDoesNotBlockHealthySourceOption":
        fields.update(
            {
                "sourceAHealthy": "true",
                "sourceBPending": "true",
                "session": "PREVIOUS_PRESERVED",
            },
        )
    elif method == "cancelledDiscoveryCannotMutateActiveReaderSession":
        fields.update(
            {
                "cancelled": "true",
                "lateResponsesReleased": "true",
                "session": "PREVIOUS_PRESERVED",
            },
        )

    event = "ANDROID_SOURCE_SWITCH|" + "|".join(key + "=" + value for key, value in fields.items())
    diagnostic = ""
    if method == "slowSourceDoesNotBlockHealthySourceOption":
        diagnostic = (
            "INSTRUMENTATION_STATUS: stream=READER_FIXTURE_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY"
            "|selector=DISCOVERING|aSearch=1|aInventory=2|aPages=3|bSearch=4|bInventory=5|bPages=6|bHeld=1\n"
        )
    status = (
        "INSTRUMENTATION_STATUS: numtests=1\n"
        "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
        "INSTRUMENTATION_STATUS: test=" + method + "\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
    )
    status += diagnostic
    return status + (
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
            valid.replace("positionAfter=4", "positionAfter=1"),
            valid.replace("sourceBPageCount=10", "sourceBPageCount=0"),
        ):
            with self.subTest(invalid=invalid.split("ANDROID_SOURCE_SWITCH|")[-1]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_recreation_requires_restored_page_and_matching_observed_index(self):
        method = "activityRecreationRestoresObservedCanonicalPosition"
        valid = output_for(method)
        verifier.verify(valid, method)
        for invalid in (
            valid.replace("positionAfter=6", "positionAfter=5"),
            valid.replace("sourceBPageCount=10", "sourceBPageCount=0"),
        ):
            with self.subTest(invalid=invalid.split("ANDROID_SOURCE_SWITCH|")[-1]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_history_scenario_requires_exactly_one_row_and_observable_position(self):
        method = "repeatedSourceSwitchKeepsPreferenceAndSingleHistoryEntry"
        valid = output_for(method)
        verifier.verify(valid, method)
        for invalid in (
            valid.replace("historyRows=1", "historyRows=2"),
            valid.replace("positionIndex=2", "positionIndex=10"),
        ):
            with self.subTest(invalid=invalid.split("ANDROID_SOURCE_SWITCH|")[-1]):
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(invalid, method)

    def test_slow_and_cancelled_discovery_require_the_expected_state_evidence(self):
        cases = (
            (
                "slowSourceDoesNotBlockHealthySourceOption",
                "sourceBPending=true",
                "sourceBPending=false",
            ),
            (
                "cancelledDiscoveryCannotMutateActiveReaderSession",
                "lateResponsesReleased=true",
                "lateResponsesReleased=false",
            ),
        )
        for method, expected, altered in cases:
            with self.subTest(method=method):
                valid = output_for(method)
                verifier.verify(valid, method)
                with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
                    verifier.verify(valid.replace(expected, altered), method)

    def test_duplicate_evidence_is_not_green(self):
        method = "retiredSourceCallbackCannotChangePublishedSession"
        line = next(line for line in output_for(method).splitlines() if "ANDROID_SOURCE_SWITCH|" in line)
        with self.assertRaisesRegex(verifier.ReaderSourceSwitchVerificationError, "exactly one"):
            verifier.verify(output_for(method) + line + "\n", method)

    def test_failure_summary_does_not_copy_raw_runner_output(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        secret = "private-provider-title-token https://provider.invalid/read?token=do-not-leak"
        raw = (
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
            "INSTRUMENTATION_STATUS: test=" + method + "\n"
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: " + secret + "\n"
            "\tat " + verifier.TEST_CLASS + ".sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition(" + verifier.SAFE_TEST_SOURCE + ":117)\n"
            "\tat org.junit.Assert.fail(Assert.java:88)\n"
            "INSTRUMENTATION_RESULT: shortMsg=" + secret + "\n"
            "INSTRUMENTATION_STATUS_CODE: -2\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            input_path = directory / "runner.txt"
            summary = directory / "summary.txt"
            junit = directory / "TEST-switch.xml"
            input_path.write_text(raw, encoding="utf-8")
            captured = io.StringIO()
            with redirect_stdout(captured), redirect_stderr(captured):
                result = verifier.main(
                    [str(input_path), method, "--summary", str(summary), "--junit", str(junit)],
                )
            self.assertEqual(1, result)
            self.assertNotIn(secret, summary.read_text(encoding="utf-8"))
            self.assertNotIn(secret, junit.read_text(encoding="utf-8"))
            self.assertNotIn(secret, captured.getvalue())
            self.assertIn("category=ASSERTION_FAILURE", summary.read_text(encoding="utf-8"))
            self.assertIn("exceptionType=AssertionError", summary.read_text(encoding="utf-8"))
            self.assertIn(
                "ANDROID_SOURCE_SWITCH_TERMINATION|method=EXPECTED|class=EXPECTED|numtests=1"
                "|lastStatusCode=-2|instrumentationCode=RESULT_OK|summary=NONE|shortMsg=OTHER",
                summary.read_text(encoding="utf-8"),
            )
            self.assertIn(
                verifier.TEST_CLASS + ".sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition(" + verifier.SAFE_TEST_SOURCE + ":117)",
                summary.read_text(encoding="utf-8"),
            )
            self.assertNotIn("Assert.java", summary.read_text(encoding="utf-8"))

    def test_failure_diagnosis_keeps_safe_app_frame_without_test_frame(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        secret = "private title https://provider.invalid/chapter?token=do-not-leak"
        raw = (
            "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
            "INSTRUMENTATION_STATUS: test=" + method + "\n"
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS: stack=java.sql.SQLException: " + secret + "\n"
            "\tat eu.kanade.tachiyomi.ui.reader.ReaderActivity.dispatchKeyEvent(ReaderActivity.kt:579)\n"
            "\tat tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl.upsertProgressInternal(CanonicalReadingRepositoryImpl.kt:81)\n"
            "INSTRUMENTATION_STATUS_CODE: -2\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        diagnosis = verifier._failure_diagnosis(raw, method, 0)
        self.assertEqual("TEST_EXCEPTION", diagnosis["category"])
        self.assertEqual("SQLException", diagnosis["exceptionType"])
        self.assertIn("ReaderActivity.dispatchKeyEvent(ReaderActivity.kt:579)", diagnosis["frames"])
        self.assertIn(
            "CanonicalReadingRepositoryImpl.upsertProgressInternal(CanonicalReadingRepositoryImpl.kt:81)",
            diagnosis["frames"],
        )
        self.assertNotIn(secret, str(diagnosis))

    def test_failure_diagnosis_distinguishes_unobserved_test_and_timeout(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        unobserved = verifier._failure_diagnosis("INSTRUMENTATION_STATUS: numtests=0\n", method, 0)
        timeout = verifier._failure_diagnosis("", method, 124)
        self.assertEqual("TEST_NOT_OBSERVED", unobserved["category"])
        self.assertEqual("TIMEOUT", timeout["category"])

    def test_terminal_evidence_is_allowlisted_and_distinguishes_test_finish_from_runner_completion(self):
        method = "emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession"
        incomplete = (
            "INSTRUMENTATION_STATUS: class=" + verifier.TEST_CLASS + "\n"
            "INSTRUMENTATION_STATUS: test=" + method + "\n"
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
        )
        evidence = verifier._terminal_evidence(incomplete, method)
        self.assertEqual(
            {
                "method": "EXPECTED",
                "class": "EXPECTED",
                "numtests": "1",
                "lastStatusCode": "0",
                "instrumentationCode": "MISSING",
                "summary": "NONE",
                "shortMsg": "NONE",
            },
            evidence,
        )
        summary = verifier.sanitized_summary(method, {}, False, terminal_evidence=evidence)
        self.assertIn("lastStatusCode=0|instrumentationCode=MISSING|summary=NONE|shortMsg=NONE", summary)

        complete = verifier._terminal_evidence(output_for(method), method)
        self.assertEqual("OK_1_TEST", complete["summary"])
        self.assertEqual("RESULT_OK", complete["instrumentationCode"])
        self.assertEqual(
            "FAILURES",
            verifier._terminal_evidence(output_for(method).replace("OK (1 test)", "FAILURES!!!"), method)["summary"],
        )
        self.assertEqual(
            "OK_OTHER_COUNT",
            verifier._terminal_evidence(output_for(method).replace("OK (1 test)", "OK (2 tests)"), method)["summary"],
        )

        private_value = "https://provider.invalid/read?token=private-title"
        method = "slowSourceDoesNotBlockHealthySourceOption"
        raw = (
            "INSTRUMENTATION_STATUS: class=" + private_value + "\n"
            "INSTRUMENTATION_STATUS: test=" + private_value + "\n"
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed. " + private_value + "\n"
            "INSTRUMENTATION_CODE: 0\n"
            "INSTRUMENTATION_RESULT: stream=" + private_value + "\n"
        )
        evidence = verifier._terminal_evidence(raw, method)
        self.assertEqual("MISMATCH", evidence["method"])
        self.assertEqual("MISMATCH", evidence["class"])
        self.assertEqual("PROCESS_CRASHED", evidence["shortMsg"])
        self.assertEqual("RESULT_CANCELED", evidence["instrumentationCode"])
        self.assertNotIn(private_value, str(evidence))
        rendered = verifier.sanitized_summary(method, {}, False, terminal_evidence=evidence)
        self.assertNotIn(private_value, rendered)

    def test_nonzero_runner_exit_cannot_be_accepted_even_with_pass_marker(self):
        method = "retiredSourceCallbackCannotChangePublishedSession"
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            input_path = directory / "runner.txt"
            summary = directory / "summary.txt"
            junit = directory / "TEST-switch.xml"
            input_path.write_text(output_for(method), encoding="utf-8")
            captured = io.StringIO()
            with redirect_stdout(captured), redirect_stderr(captured):
                result = verifier.main(
                    [
                        str(input_path), method, "--summary", str(summary), "--junit", str(junit),
                        "--runner-exit", "1",
                    ],
                )
            self.assertEqual(1, result)
            self.assertIn("category=RUNNER_ERROR", summary.read_text(encoding="utf-8"))

    def test_slow_source_diagnostic_is_sanitized_and_retained_when_test_fails(self):
        method = "slowSourceDoesNotBlockHealthySourceOption"
        raw = output_for(method).replace("|outcome=PASS", "|outcome=FAIL", 1)
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            input_path = directory / "runner.txt"
            summary = directory / "summary.txt"
            junit = directory / "TEST-switch.xml"
            input_path.write_text(raw, encoding="utf-8")
            captured = io.StringIO()
            with redirect_stdout(captured), redirect_stderr(captured):
                result = verifier.main(
                    [str(input_path), method, "--summary", str(summary), "--junit", str(junit)],
                )
            summary_text = summary.read_text(encoding="utf-8")
            self.assertEqual(1, result)
            self.assertIn(
                "ANDROID_SOURCE_SWITCH_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY|selector=DISCOVERING"
                "|aSearch=1|aInventory=2|aPages=3|bSearch=4|bInventory=5|bPages=6|bHeld=1",
                summary_text,
            )
            self.assertNotIn("READER_FIXTURE_DIAGNOSTIC", summary_text)
            self.assertNotIn("ANDROID_SOURCE_SWITCH_DIAGNOSTIC", junit.read_text(encoding="utf-8"))
            self.assertNotIn("provider.invalid", captured.getvalue())

    def test_slow_source_diagnostic_accepts_renamed_prefix_but_rejects_free_text(self):
        method = "slowSourceDoesNotBlockHealthySourceOption"
        output = output_for(method)
        diagnostic_line = next(line for line in output.splitlines() if "READER_FIXTURE_DIAGNOSTIC|" in line)
        renamed = output.replace("READER_FIXTURE_DIAGNOSTIC|", "ANDROID_SOURCE_SWITCH_DIAGNOSTIC|")
        self.assertEqual(
            verifier._fixture_diagnostic(output, method),
            verifier._fixture_diagnostic(renamed, method),
        )

        private_value = "https://provider.invalid/title?token=private"
        malformed = output.replace("selector=DISCOVERING", "selector=" + private_value)
        diagnosis = verifier._fixture_diagnostic(malformed, method)
        self.assertEqual("MALFORMED", diagnosis["evidence"])
        sanitized = verifier.sanitized_summary(method, {}, False, fixture_diagnostic=diagnosis)
        self.assertNotIn(private_value, sanitized)
        self.assertNotIn(diagnostic_line.split("|selector=")[1].split("|")[0], sanitized)

    def test_reader_view_diagnostic_exposes_only_allowlisted_state_and_counts(self):
        method = "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition"
        private_value = "https://provider.invalid/private-title?token=private"
        line = (
            "INSTRUMENTATION_STATUS: stream=READER_VIEW_DIAGNOSTIC|scenario=PAGER_READINESS"
            "|phase=PAGE_SWIPE_TIMEOUT|viewer=WebtoonViewer|focused=FOCUSED|stream=FALSE"
            "|pagesLoaded=TRUE|pageCount=10|pageState=READY|position=2"
            "|pagerVisible=TRUE|pagerCount=12|pagerCurrentItem=3|pagerIdle=TRUE"
            "|interactionInjected=TRUE|interactionTarget=READER_PAGER|matchingSamples=40|matchingRows=10"
            "|expectedColor=RED|imageRequestsA=1|imageRequestsB=1"
            "|holderPresent=TRUE|holderAttached=TRUE|holderVisible=TRUE"
            "|imageViewPresent=TRUE|imageViewVisible=TRUE|imageViewReady=FALSE|errorVisible=FALSE"\n            "|windowSecure=FALSE|windowHasFocus=TRUE\n"
        )
        parsed = verifier._reader_view_diagnostics(line)
        self.assertEqual(1, len(parsed))
        summary = verifier.sanitized_summary(method, {}, False, reader_view_diagnostics=parsed)
        self.assertIn("viewer=WebtoonViewer|focused=FOCUSED|stream=FALSE", summary)
        self.assertIn("pagerVisible=TRUE|pagerCount=12|pagerCurrentItem=3|pagerIdle=TRUE", summary)
        self.assertIn("interactionInjected=TRUE|interactionTarget=READER_PAGER", summary)
        self.assertIn("expectedColor=RED", summary)
        self.assertIn("imageRequestsA=1|imageRequestsB=1", summary)
        self.assertIn("holderPresent=TRUE|holderAttached=TRUE|holderVisible=TRUE", summary)
        self.assertIn("imageViewPresent=TRUE|imageViewVisible=TRUE|imageViewReady=FALSE|errorVisible=FALSE", summary)
        self.assertIn("windowSecure=FALSE|windowHasFocus=TRUE", summary)

        for malformed in (
            line.replace("viewer=WebtoonViewer", "viewer=" + private_value),
            line.replace("imageRequestsA=1", "imageRequestsA=" + private_value),
            line.replace("holderPresent=TRUE", "holderPresent=" + private_value),
            line.replace("imageViewReady=FALSE", "imageViewReady=1"),
            line.replace("windowSecure=FALSE", "windowSecure=1"),
        ):
            sanitized = verifier.sanitized_summary(
                method, {}, False, reader_view_diagnostics=verifier._reader_view_diagnostics(malformed),
            )
            self.assertIn("ANDROID_SOURCE_SWITCH_READER_VIEW|evidence=MALFORMED", sanitized)
            self.assertNotIn(private_value, sanitized)

    def test_reader_selector_diagnostic_uses_per_scenario_deltas(self):
        line = (
            "INSTRUMENTATION_STATUS: stream=READER_SELECTOR_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY"
            "|state=DISCOVERING|options=1|aOption=1|bOption=0|chapterMatch=1|failedProviders=0"
            "|aSearchDelta=0|aInventoryDelta=0|aPagesDelta=0|bSearchDelta=1"
            "|bInventoryDelta=1|bPagesDelta=0|bHeld=1\n"
        )
        parsed = verifier._reader_selector_diagnostics(line)
        self.assertEqual(1, len(parsed))
        summary = verifier.sanitized_summary(
            "slowSourceDoesNotBlockHealthySourceOption", {}, False,
            reader_selector_diagnostics=parsed,
        )
        self.assertIn("state=DISCOVERING|options=1|aOption=1|bOption=0", summary)
        self.assertIn("aSearchDelta=0|aInventoryDelta=0|aPagesDelta=0", summary)
        self.assertNotIn("INSTRUMENTATION_STATUS: stream=", summary)

    def test_unknown_selector_state_is_reported_as_inconclusive_only(self):
        line = (
            "INSTRUMENTATION_STATUS: stream=READER_SELECTOR_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY"
            "|state=UNKNOWN|options=0|aOption=0|bOption=0|chapterMatch=0|failedProviders=0"
            "|aSearchDelta=0|aInventoryDelta=0|aPagesDelta=0|bSearchDelta=0"
            "|bInventoryDelta=0|bPagesDelta=0|bHeld=1\n"
        )
        parsed = verifier._reader_selector_diagnostics(line)
        self.assertEqual("UNKNOWN", parsed[0]["state"])
        summary = verifier.sanitized_summary(
            "slowSourceDoesNotBlockHealthySourceOption", {}, False,
            reader_selector_diagnostics=parsed,
        )
        self.assertIn("state=UNKNOWN", summary)
        self.assertIn("interpretation=INCONCLUSIVE", summary)
        self.assertNotIn("ANDROID_SOURCE_SWITCH_READER_SELECTOR|outcome=PASS", summary)

        with self.assertRaises(verifier.ReaderSourceSwitchVerificationError):
            verifier.verify(line, "slowSourceDoesNotBlockHealthySourceOption")

    def test_workflow_has_manual_offline_suite_opt_in_and_preserves_navigation_default(self):
        workflow = (ROOT / ".github/workflows/mangafire-real-extension.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("live_probe:", workflow)
        self.assertIn("instrumentation_suite:", workflow)
        self.assertIn("default: navigation", workflow)
        self.assertIn("- reader-source-switch", workflow)
        self.assertIn("run_android_instrumentation_route.sh", workflow)
        self.assertIn("inputs.instrumentation_suite || 'navigation'", workflow)
        self.assertIn("test_summarize_reader_screenshot.py", workflow)
        runner = (ROOT / ".github/scripts/run_android_reader_source_switch.sh").read_text(encoding="utf-8")
        self.assertIn("--runner-exit", runner)
        self.assertIn("overall_status", runner)
        self.assertIn("settings get global airplane_mode_on", runner)
        self.assertIn("adb exec-out screencap -p", runner)
        self.assertIn("summarize_reader_screenshot.py", runner)
        self.assertIn("record_failure_screen_histogram", runner)
        self.assertNotIn("syntheticMihonSourceLoadsObservablePagesInReaderActivity", workflow)
        for method in verifier.METHOD_SCENARIOS:
            with self.subTest(method=method):
                self.assertIn("public final void " + method + "();", workflow)


if __name__ == "__main__":
    unittest.main()
