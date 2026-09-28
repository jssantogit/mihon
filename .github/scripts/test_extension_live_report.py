#!/usr/bin/env python3
"""Reject false-greens, missing source coverage and accidental raw data in reports."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).with_name("verify_extension_live_report.py")
SPEC = importlib.util.spec_from_file_location("verify_extension_live_report", MODULE)
assert SPEC and SPEC.loader
v = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v)
GOOD = (
    "INSTRUMENTATION_STATUS: numtests=1\n"
    "INSTRUMENTATION_STATUS: class=" + v.TEST_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + v.TEST_METHOD + "\n"
    "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE|sourceId=123|lang=pt-BR"
    "|outcome=RESULTS|httpStatus=none|resultCount=2|elapsedMs=350\n"
    "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE|sourceId=456|lang=en"
    "|outcome=HTTP_RESPONSE|httpStatus=503|resultCount=0|elapsedMs=1720\n"
    "INSTRUMENTATION_RESULT: stream=\nTime: 5.0\n\nOK (1 test)\n"
    "INSTRUMENTATION_CODE: -1\n"
)


class LiveSourceReportTest(unittest.TestCase):
    def test_exact_two_source_observations(self):
        self.assertEqual(len(v.inspect(GOOD, 2)), 2)

    def test_zero_tests_is_not_green(self):
        with self.assertRaises(v.EvidenceError):
            v.inspect("INSTRUMENTATION_RESULT: stream=OK (0 tests)\nINSTRUMENTATION_CODE: -1", 1)

    def test_one_missing_source_is_not_green(self):
        with self.assertRaises(v.EvidenceError):
            v.inspect(GOOD, 3)

    def test_duplicate_source_is_not_green(self):
        with self.assertRaises(v.EvidenceError):
            v.inspect(GOOD.replace("sourceId=456", "sourceId=123"), 2)

    def test_inconsistent_http_status_is_rejected(self):
        with self.assertRaises(v.EvidenceError):
            v.inspect(GOOD.replace("outcome=HTTP_RESPONSE", "outcome=EMPTY"), 2)

    def test_observational_http_503_is_valid_data(self):
        self.assertEqual(v.inspect(GOOD, 2)[1][2:4], ("HTTP_RESPONSE", "503"))

    def test_only_allowlisted_fields_reach_csv(self):
        with tempfile.TemporaryDirectory() as temp:
            old = v.REPORTS
            try:
                v.REPORTS = Path(temp)
                rows = v.inspect(GOOD + "https://example.invalid/?token=secret\n", 2)
                file = v.save("manga-ball-1.6.1.apk", 0, rows)
                csv = file.read_text(encoding="utf-8")
                self.assertEqual(csv.count("\n"), 3)
                self.assertNotIn("secret", csv)
                self.assertNotIn("http://", csv)
                self.assertNotIn("https://", csv)
            finally:
                v.REPORTS = old


if __name__ == "__main__":
    unittest.main()
