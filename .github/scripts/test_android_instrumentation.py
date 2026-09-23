#!/usr/bin/env python3
"""Regression: a Gradle exit code zero with zero Android tests is not green."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
import unittest

FILE = Path(__file__).with_name("verify_android_instrumentation.py")
SPEC = importlib.util.spec_from_file_location("verify_android_instrumentation", FILE)
assert SPEC is not None and SPEC.loader is not None
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)
METHOD = "loadsRealExtensionAndRegistersInternalSources"
GOOD = (
    "INSTRUMENTATION_STATUS: numtests=1\n"
    "INSTRUMENTATION_STATUS: class=" + checker.CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 1\n"
    "INSTRUMENTATION_STATUS: class=" + checker.CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 0\n"
    "INSTRUMENTATION_RESULT: stream=\nTime: 2.0\n\nOK (1 test)\n"
    "INSTRUMENTATION_CODE: -1\n"
)

class VerifyAndroidInstrumentationTest(unittest.TestCase):
    def test_proven_single_test_is_accepted(self):
        checker.verify(GOOD, METHOD)

    def test_zero_tests_is_rejected(self):
        bad = "INSTRUMENTATION_RESULT: stream=\nTime: 0.0\n\nOK (0 tests)\nINSTRUMENTATION_CODE: -1\n"
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(bad, METHOD)

    def test_wrong_method_is_rejected(self):
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(GOOD, "optionalLiveEnglishSearch")

    def test_failed_test_is_rejected(self):
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(GOOD.replace("OK (1 test)", "FAILURES!!!"), METHOD)

    def test_report_contains_one_real_test_only_after_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            previous = checker.REPORTS
            try:
                checker.REPORTS = Path(temp)
                checker.verify(GOOD, METHOD)
                path = checker.write_verified_report(METHOD)
                self.assertIn('tests="1"', path.read_text(encoding="utf-8"))
            finally:
                checker.REPORTS = previous

if __name__ == "__main__":
    unittest.main()
