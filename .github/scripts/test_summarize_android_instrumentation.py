#!/usr/bin/env python3
"""Regression: instrumentation diagnostic must not echo exception messages or URLs."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).with_name("summarize_android_instrumentation.py")
SPEC = importlib.util.spec_from_file_location("summarize_android_instrumentation", MODULE)
assert SPEC is not None and SPEC.loader is not None
diagnostic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostic)

class SummaryTest(unittest.TestCase):
    def test_startup_crash_is_classified_without_message_leaks(self):
        runner = ("INSTRUMENTATION_RESULT: shortMsg=Process crashed token=SECRET\n"
                  "INSTRUMENTATION_CODE: 0\n"
                  "Caused by: java.lang.IllegalStateException: https://private.example/secret\n")
        crash = "FATAL EXCEPTION: main\njava.lang.NoClassDefFoundError: secret password\n"
        result = '\n'.join(diagnostic.summarize(runner, crash))
        self.assertIn("androidRuntimeCrash=true", result)
        self.assertIn("exceptionType=java.lang.IllegalStateException", result)
        self.assertNotIn("SECRET", result)
        self.assertNotIn("https://", result)
        self.assertNotIn("password", result)

    def test_absent_class_and_zero_test_are_observable(self):
        result = "\n".join(diagnostic.summarize("INSTRUMENTATION_STATUS: numtests=0\n", ""))
        self.assertIn("numtests=0", result)
        self.assertNotIn("classMatchesFixture=true", result)

if __name__ == "__main__":
    unittest.main()
