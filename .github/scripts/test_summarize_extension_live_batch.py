#!/usr/bin/env python3
"""Failure summaries retain safe probe facts without retaining raw runner output."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import unittest

MODULE = Path(__file__).with_name("summarize_extension_live_batch.py")
SPEC = importlib.util.spec_from_file_location("summarize_extension_live_batch", MODULE)
assert SPEC and SPEC.loader
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)

BASE = (
    "INSTRUMENTATION_STATUS: numtests=1\n"
    "INSTRUMENTATION_STATUS: class=" + summary.TEST_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + summary.TEST_METHOD + "\n"
)


class ExtensionLiveBatchSummaryTest(unittest.TestCase):
    def test_retains_valid_per_source_observation_when_junit_did_not_pass(self):
        raw = BASE + (
            "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE|sourceId=123|lang=en"
            "|outcome=TIMEOUT|httpStatus=none|resultCount=0|elapsedMs=18000\n"
            "INSTRUMENTATION_RESULT: shortMsg=private test detail token=SECRET\n"
            "INSTRUMENTATION_CODE: 0\n"
        )

        report = "\n".join(summary.summarize(raw, process_exit=0))

        self.assertIn("runnerExit=0", report)
        self.assertIn("junitPass=false", report)
        self.assertIn(
            "SOURCE_PROBE|sourceId=123|lang=en|outcome=TIMEOUT|httpStatus=none|resultCount=0|elapsedMs=18000",
            report,
        )
        self.assertNotIn("SECRET", report)
        self.assertNotIn("private test detail", report)

    def test_manual_probe_selector_accepts_only_allowlisted_target(self):
        runner = Path(__file__).with_name("run_extension_live_android.sh")

        invalid_probe = subprocess.run(
            ["bash", str(runner), "0", "arbitrary"],
            capture_output=True,
            text=True,
            check=False,
        )
        invalid_shard = subprocess.run(
            ["bash", str(runner), "1", "animexnovel"],
            capture_output=True,
            text=True,
            check=False,
        )
        wrong_mangafire_shard = subprocess.run(
            ["bash", str(runner), "0", "mangafire"],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(invalid_probe.returncode, 2)
        self.assertIn("Probe must be all, animexnovel, mangalivreto or mangafire", invalid_probe.stdout)
        self.assertEqual(invalid_shard.returncode, 2)
        self.assertIn("Targeted source probe is in shard 0", invalid_shard.stdout)
        self.assertEqual(wrong_mangafire_shard.returncode, 2)
        self.assertIn("Targeted source probe is in shard 3", wrong_mangafire_shard.stdout)

    def test_nonzero_runner_without_events_is_unknown_not_empty(self):
        report = "\n".join(summary.summarize("", process_exit=1))

        self.assertIn("runnerExit=1", report)
        self.assertIn("junitPass=false", report)
        self.assertIn("probeEvents=0", report)
        self.assertNotIn("outcome=EMPTY", report)

    def test_rejected_oversize_runner_input_does_not_emit_observations(self):
        report = "\n".join(summary.summarize(
            "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE|sourceId=123|lang=en"
            "|outcome=RESULTS|httpStatus=none|resultCount=1|elapsedMs=5",
            process_exit=0,
            input_rejected=True,
        ))

        self.assertIn("inputRejected=true", report)
        self.assertIn("probeEvents=0", report)
        self.assertNotIn("SOURCE_PROBE|", report)

    def test_strips_unknown_outcomes_and_untrusted_text(self):
        raw = BASE + (
            "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE|sourceId=123|lang=en"
            "|outcome=SECRET_OUTCOME|httpStatus=none|resultCount=0|elapsedMs=1\n"
            "INSTRUMENTATION_RESULT: stream=https://private.example/secret?token=SECRET\n"
        )

        report = "\n".join(summary.summarize(raw, process_exit=0))

        self.assertIn("probeEvents=0", report)
        self.assertNotIn("SECRET", report)
        self.assertNotIn("https://", report)

    def test_retains_only_allowlisted_progress_stages_for_incomplete_run(self):
        raw = BASE + (
            "INSTRUMENTATION_STATUS: stream=LIVE_STAGE|stage=EXTENSION_READY|sourceCount=7\n"
            "INSTRUMENTATION_STATUS: stream=SOURCE_ATTEMPT|ordinal=1|sourceId=456|lang=pt-BR\n"
            "INSTRUMENTATION_STATUS: stream=LIVE_STAGE|stage=SECRET_STAGE|value=SECRET\n"
        )

        report = "\n".join(summary.summarize(raw, process_exit=0))

        self.assertIn("LIVE_STAGE|stage=EXTENSION_READY|sourceCount=7", report)
        self.assertIn("SOURCE_ATTEMPT|ordinal=1|sourceId=456|lang=pt-BR", report)
        self.assertNotIn("SECRET", report)

    def test_detail_output_is_bounded_for_repeated_or_malformed_events(self):
        valid = (
            "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE|sourceId=123|lang=en"
            "|outcome=RESULTS|httpStatus=none|resultCount=1|elapsedMs=5\n"
        )
        report = summary.summarize(BASE + valid * 200, process_exit=0)

        self.assertLessEqual(len(report), 128)
        self.assertIn("detailsTruncated=true", "\n".join(report))
        self.assertIn("probeEvents=56", "\n".join(report))


if __name__ == "__main__":
    unittest.main()
