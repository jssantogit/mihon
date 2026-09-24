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
LIVE_METHOD = "optionalRealEnglishReadingJourney"
CONTEXT_DB_METHOD = "instrumentationContextDatabasePersistsCanonicalTitle"
DB_IDENTITY_METHOD = "instrumentationDatabaseIdentityProbe"
E2E_CLASS = checker.E2E_CLASS
E2E_STAGES = (
    "EXTENSION_INSTALL",
    "SOURCE_REGISTRATION",
    "LIVE_SEARCH",
    "CANDIDATE_IDENTIFICATION",
    "MATCH_DECISION",
    "BINDING_MATERIALIZATION",
    "BINDING_CREATE",
    "BINDING_PERSISTENCE",
    "INVENTORY",
    "CHAPTER_PROBE",
    "RECONCILIATION",
    "CONTENT_RESOLUTION",
    "READER_PREPARATION",
    "GET_PAGE_LIST",
)
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
LIVE_GOOD = (
    "INSTRUMENTATION_STATUS: numtests=1\n"
    "INSTRUMENTATION_STATUS: class=" + E2E_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + LIVE_METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 1\n"
    "INSTRUMENTATION_STATUS: class=" + E2E_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + LIVE_METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 0\n"
    + "\n".join(
        "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage + "|outcome=PASS"
        for stage in E2E_STAGES
    ) + "\n"
    "INSTRUMENTATION_RESULT: stream=\nTime: 2.0\n\nOK (1 test)\n"
    "INSTRUMENTATION_CODE: -1\n"
)
CONTEXT_DB_GOOD = (
    "INSTRUMENTATION_STATUS: numtests=1\n"
    "INSTRUMENTATION_STATUS: class=" + E2E_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + CONTEXT_DB_METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 1\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT|applicationContext=null"
    "|targetIsolation=isolated|databaseContext=wrapped\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CREATE|outcome=PASS\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DATABASE_CREATE|outcome=PASS\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA|outcome=PASS|titles=present"
    "|outbox=present|dirtyInsertTrigger=present\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_READ|outcome=PASS\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CLOSE|outcome=PASS\n"
    "INSTRUMENTATION_STATUS: class=" + E2E_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + CONTEXT_DB_METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 0\n"
    "INSTRUMENTATION_RESULT: stream=\nTime: 2.0\n\nOK (1 test)\n"
    "INSTRUMENTATION_CODE: -1\n"
)
DB_IDENTITY_GOOD = (
    "INSTRUMENTATION_STATUS: numtests=1\n"
    "INSTRUMENTATION_STATUS: class=" + E2E_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + DB_IDENTITY_METHOD + "\n"
    "INSTRUMENTATION_STATUS_CODE: 1\n"
    "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
    "|processIsTestUid=true|processIsTargetUid=false"
    "|testDbParentWritable=true|testDbParentState=EXISTS"
    "|targetDbParentWritable=false|targetDbParentState=EXISTS"
    "|testDataDirWritable=true|testDataDirExecutable=true|testDataDirState=EXISTS"
    "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS\n"
    "INSTRUMENTATION_STATUS: class=" + E2E_CLASS + "\n"
    "INSTRUMENTATION_STATUS: test=" + DB_IDENTITY_METHOD + "\n"
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

    def test_live_journey_requires_one_pass_event_for_every_stage(self):
        output = "\n".join(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage + "|outcome=PASS"
            for stage in E2E_STAGES
        )
        checker.verify_e2e_stages(output)

    def test_live_journey_requires_real_class_method_and_all_pass_stages(self):
        checker.verify(LIVE_GOOD, LIVE_METHOD)

    def test_isolated_context_database_diagnostic_requires_safe_context_and_persist_events(self):
        checker.verify(CONTEXT_DB_GOOD, CONTEXT_DB_METHOD)

    def test_database_identity_probe_requires_one_sanitized_observation(self):
        checker.verify(DB_IDENTITY_GOOD, DB_IDENTITY_METHOD)

    def test_database_identity_probe_rejects_missing_observation(self):
        output = DB_IDENTITY_GOOD.replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
            "|processIsTestUid=true|processIsTargetUid=false"
            "|testDbParentWritable=true|testDbParentState=EXISTS"
            "|targetDbParentWritable=false|targetDbParentState=EXISTS"
            "|testDataDirWritable=true|testDataDirExecutable=true|testDataDirState=EXISTS"
            "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS\n",
            "",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(output, DB_IDENTITY_METHOD)

    def test_database_identity_probe_rejects_paths_and_uid_values(self):
        unsafe = DB_IDENTITY_GOOD.replace(
            "|targetDbParentState=EXISTS",
            "|targetDbParentState=EXISTS|uid=1234|path=/data/user/0/private",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(unsafe, DB_IDENTITY_METHOD)

    def test_database_identity_probe_rejects_duplicate_observations(self):
        duplicate = DB_IDENTITY_GOOD.replace(
            "INSTRUMENTATION_STATUS: class=" + E2E_CLASS,
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
            "|processIsTestUid=true|processIsTargetUid=false"
            "|testDbParentWritable=true|testDbParentState=EXISTS"
            "|targetDbParentWritable=false|targetDbParentState=EXISTS"
            "|testDataDirWritable=true|testDataDirExecutable=true|testDataDirState=EXISTS"
            "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS\n"
            "INSTRUMENTATION_STATUS: class=" + E2E_CLASS,
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(duplicate, DB_IDENTITY_METHOD)

    def test_isolated_context_database_diagnostic_rejects_missing_phase(self):
        output = CONTEXT_DB_GOOD.replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n",
            "",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(output, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_rejects_duplicate_phase(self):
        output = CONTEXT_DB_GOOD.replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n",
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(output, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_rejects_unsafe_context_event(self):
        output = CONTEXT_DB_GOOD.replace(
            "|targetIsolation=isolated|databaseContext=wrapped",
            "|targetIsolation=isolated|databaseContext=wrapped|path=/private/user/db",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(output, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_rejects_unknown_database_context(self):
        wrong_context = CONTEXT_DB_GOOD.replace("databaseContext=wrapped", "databaseContext=other")
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(wrong_context, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_rejects_duplicate_context_events(self):
        duplicate_context = CONTEXT_DB_GOOD.replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CREATE|",
            "INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT|applicationContext=null"
            "|targetIsolation=isolated|databaseContext=wrapped\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CREATE|",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(duplicate_context, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_requires_one_schema_observation(self):
        no_schema = CONTEXT_DB_GOOD.replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA|outcome=PASS|titles=present"
            "|outbox=present|dirtyInsertTrigger=present\n",
            "",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(no_schema, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_rejects_unsafe_schema_fields(self):
        unsafe = CONTEXT_DB_GOOD.replace(
            "|dirtyInsertTrigger=present",
            "|dirtyInsertTrigger=present|sql=PRIVATE",
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(unsafe, CONTEXT_DB_METHOD)

    def test_isolated_context_database_diagnostic_rejects_schema_after_insert(self):
        schema = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA|outcome=PASS|titles=present"
            "|outbox=present|dirtyInsertTrigger=present\n"
        )
        moved = CONTEXT_DB_GOOD.replace(schema, "").replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n",
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=PASS\n" + schema,
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(moved, CONTEXT_DB_METHOD)

    def test_live_journey_accepts_the_real_long_source_id_on_registration(self):
        registration = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=SOURCE_REGISTRATION|outcome=PASS"
            "|sourceId=6084907896154116083|language=en"
        )
        output = LIVE_GOOD.replace(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=SOURCE_REGISTRATION|outcome=PASS",
            registration,
        )
        checker.verify(output, LIVE_METHOD)

    def test_live_journey_wrong_class_is_rejected(self):
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify(LIVE_GOOD.replace(E2E_CLASS, checker.CLASS), LIVE_METHOD)

    def test_missing_stage_is_not_a_green_live_journey(self):
        output = "\n".join(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage + "|outcome=PASS"
            for stage in E2E_STAGES[:-1]
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify_e2e_stages(output)

    def test_inconclusive_stage_is_not_a_green_live_journey(self):
        output = "\n".join(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage + "|outcome=" +
            ("INCONCLUSIVE" if stage == "CANDIDATE_IDENTIFICATION" else "PASS")
            for stage in E2E_STAGES
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify_e2e_stages(output)

    def test_source_registration_timeout_is_recognized_but_still_fails_closed(self):
        output = "\n".join(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage +
            ("|outcome=INCONCLUSIVE|category=SOURCE_REGISTRATION_TIMEOUT|sourceId=6084907896154116083"
             "|language=en|elapsedMs=30000" if stage == "SOURCE_REGISTRATION" else "|outcome=PASS")
            for stage in E2E_STAGES
        )
        with self.assertRaisesRegex(
            checker.AndroidTestVerificationError,
            "Runtime E2E includes a non-pass required stage",
        ):
            checker.verify_e2e_stages(output)

    def test_passing_stage_cannot_carry_http_failure_category(self):
        output = "\n".join(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage + "|outcome=PASS" +
            ("|category=HTTP_403" if stage == "LIVE_SEARCH" else "")
            for stage in E2E_STAGES
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify_e2e_stages(output)

    def test_duplicate_and_unrecognized_stage_events_are_rejected(self):
        output = "\n".join(
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=" + stage + "|outcome=PASS"
            for stage in (*E2E_STAGES, E2E_STAGES[0], "PROVIDER_RAW_RESPONSE")
        )
        with self.assertRaises(checker.AndroidTestVerificationError):
            checker.verify_e2e_stages(output)

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
