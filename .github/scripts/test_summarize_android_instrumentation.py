#!/usr/bin/env python3
"""Regression: instrumentation diagnostic must not echo exception messages or URLs."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

MODULE = Path(__file__).with_name("summarize_android_instrumentation.py")
SPEC = importlib.util.spec_from_file_location("summarize_android_instrumentation", MODULE)
assert SPEC is not None and SPEC.loader is not None
diagnostic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostic)

class SummaryTest(unittest.TestCase):
    def test_database_identity_probe_is_summarized_without_sensitive_values(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
            "|processIsTestUid=true|processIsTargetUid=false"
            "|testDbParentWritable=unknown|testDbParentState=MISSING"
            "|targetDbParentWritable=true|targetDbParentState=EXISTS"
            "|testDataDirWritable=true|testDataDirExecutable=true|testDataDirState=EXISTS"
            "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS"
            "\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
            "|processIsTestUid=true|processIsTargetUid=false"
            "|testDbParentWritable=unknown|testDbParentState=MISSING"
            "|targetDbParentWritable=true|targetDbParentState=EXISTS"
            "|testDataDirWritable=true|testDataDirExecutable=true|testDataDirState=EXISTS"
            "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS"
            "|uid=1234|path=/data/user/0/private|token=SECRET\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "DIAGNOSTIC|dbIdentity|processIsTestUid=true|processIsTargetUid=false"
            "|testDbParentWritable=unknown|testDbParentState=MISSING"
            "|targetDbParentWritable=true|targetDbParentState=EXISTS"
            "|testDataDirWritable=true|testDataDirExecutable=true|testDataDirState=EXISTS"
            "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS",
            result,
        )
        self.assertNotIn("1234", result)
        self.assertNotIn("/data/user", result)
        self.assertNotIn("SECRET", result)

    def test_database_directory_probe_drops_unrecognized_states_and_values(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
            "|processIsTestUid=false|processIsTargetUid=true"
            "|testDbParentWritable=unknown|testDbParentState=MISSING"
            "|targetDbParentWritable=true|targetDbParentState=EXISTS"
            "|testDataDirWritable=false|testDataDirExecutable=false|testDataDirState=EXISTS"
            "|targetDataDirWritable=true|targetDataDirExecutable=true|targetDataDirState=EXISTS\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn("testDataDirWritable=false|testDataDirExecutable=false", result)

    def test_database_path_creation_result_is_summarized_without_paths(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_PATH_PROBE"
            "|outcome=PARENT_MISSING|parentState=MISSING|parentWritable=unknown"
            "|cleanup=NOT_NEEDED\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_DB_PATH_PROBE"
            "|outcome=CREATED|parentState=EXISTS|parentWritable=true"
            "|cleanup=REMOVED|path=/private|name=secret.db\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "DIAGNOSTIC|dbPathProbe|outcome=PARENT_MISSING|parentState=MISSING"
            "|parentWritable=unknown|cleanup=NOT_NEEDED",
            result,
        )
        self.assertNotIn("/private", result)
        self.assertNotIn("secret.db", result)

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

    def test_missing_instrumentation_runner_reports_only_safe_class(self):
        crash = ('FATAL EXCEPTION: main\n'
                 'java.lang.ClassNotFoundException: Didn\'t find class "androidx.test.runner.AndroidJUnitRunner"\n')
        result = "\n".join(diagnostic.summarize("INSTRUMENTATION_CODE: 0\n", crash))
        self.assertIn("missingRuntimeClass=androidx.test.runner.AndroidJUnitRunner", result)
        self.assertNotIn("Didn\'t find class", result)

    def test_direct_class_not_found_is_observable(self):
        crash = "java.lang.ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner\n"
        result = "\n".join(diagnostic.summarize("", crash))
        self.assertIn("missingRuntimeClass=androidx.test.runner.AndroidJUnitRunner", result)

    def test_missing_private_name_is_redacted(self):
        crash = 'java.lang.ClassNotFoundException: Didn\'t find class "com.example.secret.Private"\n'
        result = "\n".join(diagnostic.summarize("", crash))
        self.assertIn("missingRuntimeClass=OTHER", result)
        self.assertNotIn("com.example.secret", result)

    def test_live_probe_reports_only_allowlisted_failure(self):
        output = (
            "MANGAFIRE_LIVE|outcome=HTTP_RESPONSE|httpStatus=403|elapsedMs=19897 "
            "https://example.test/secret cookie=HIDDEN\n"
        )
        result = "\n".join(diagnostic.summarize(output, ""))
        self.assertIn("liveOutcome=HTTP_RESPONSE|httpStatus=403|elapsedMs=19897", result)
        self.assertNotIn("HIDDEN", result)
        self.assertNotIn("https://", result)

    def test_runtime_e2e_stages_are_sanitized_and_retained(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=LIVE_SEARCH|outcome=INCONCLUSIVE"
            "|category=HTTP_403|sourceId=6084907896154116083|language=en|elapsedMs=19897\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=LIVE_SEARCH|outcome=PASS"
            "|title=PRIVATE|url=https://private.example/path|cookie=SECRET\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "e2eStage=LIVE_SEARCH|outcome=INCONCLUSIVE|category=HTTP_403"
            "|sourceId=6084907896154116083|language=en|elapsedMs=19897",
            result,
        )
        self.assertNotIn("PRIVATE", result)
        self.assertNotIn("private.example", result)
        self.assertNotIn("SECRET", result)

    def test_probe_stage_and_closed_categories_are_retained(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=CHAPTER_PROBE|outcome=INCONCLUSIVE"
            "|category=SOURCE_DISABLED|sourceId=6084907896154116083|language=en\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn("e2eStage=CHAPTER_PROBE|outcome=INCONCLUSIVE|category=SOURCE_DISABLED", result)

    def test_source_registration_timeout_is_preserved_as_closed_diagnostic(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=SOURCE_REGISTRATION"
            "|outcome=INCONCLUSIVE|category=SOURCE_REGISTRATION_TIMEOUT"
            "|sourceId=6084907896154116083|language=en|elapsedMs=30000\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "e2eStage=SOURCE_REGISTRATION|outcome=INCONCLUSIVE"
            "|category=SOURCE_REGISTRATION_TIMEOUT|sourceId=6084907896154116083"
            "|language=en|elapsedMs=30000",
            result,
        )

    def test_binding_materialization_stage_is_retained(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|stage=BINDING_MATERIALIZATION|outcome=FAIL"
            "|category=MATERIALIZATION|elapsedMs=12\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "e2eStage=BINDING_MATERIALIZATION|outcome=FAIL|category=MATERIALIZATION|elapsedMs=12",
            result,
        )

    def test_real_journey_class_is_reported_as_expected_without_name_leak(self):
        runner = (
            "INSTRUMENTATION_STATUS: class="
            "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireRealReadingJourneyInstrumentedTest\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn("classMatchesFixture=True", result)
        self.assertNotIn("MangaFireRealReadingJourneyInstrumentedTest", result)

    def test_absent_class_and_zero_test_are_observable(self):
        result = "\n".join(diagnostic.summarize("INSTRUMENTATION_STATUS: numtests=0\n", ""))
        self.assertIn("numtests=0", result)
        self.assertNotIn("classMatchesFixture=true", result)

    def test_cli_accepts_empty_crash_path(self):
        # Reproduce run_mangafire_android.sh's successful-instrumentation call.
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "runner.txt"
            output.write_text("INSTRUMENTATION_STATUS: numtests=1\n", encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(MODULE), str(output), ""],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("DIAGNOSTIC|numtests=1", completed.stdout)

    def test_cli_accepts_omitted_crash_argument(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "runner.txt"
            output.write_text("INSTRUMENTATION_STATUS: numtests=1\n", encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(MODULE), str(output)],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("DIAGNOSTIC|numtests=1", completed.stdout)

    def test_setup_diagnostics_are_sanitized_and_retained(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CONTEXT_ISOLATION"
            "|outcome=FAIL|exception=NullPointerException\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=SQL_DRIVER|outcome=PASS\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST"
            "|outcome=FAIL|exception=SQLiteException\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DB_RESET"
            "|outcome=FAIL|exception=PRIVATE|token=SECRET\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "setupPhase=CONTEXT_ISOLATION|outcome=FAIL|exception=NullPointerException",
            result,
        )
        self.assertIn("setupPhase=SQL_DRIVER|outcome=PASS", result)
        self.assertIn(
            "setupPhase=CANONICAL_TITLE_PERSIST|outcome=FAIL|exception=SQLiteException",
            result,
        )
        self.assertNotIn("PRIVATE", result)
        self.assertNotIn("SECRET", result)

    def test_canonical_title_construction_and_persistence_are_distinct_phases(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_CREATE|outcome=PASS\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn("setupPhase=CANONICAL_TITLE_CREATE|outcome=PASS", result)
        self.assertIn(
            "setupPhase=CANONICAL_TITLE_PERSIST|outcome=FAIL|exception=NullPointerException",
            result,
        )
        self.assertNotIn("One-Punch Man", result)

    def test_focused_database_phases_are_independently_summarized_and_sanitized(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CREATE|outcome=PASS\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DATABASE_CREATE|outcome=PASS\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|"
            "outcome=FAIL|exception=SQLException|frame=ANDROIDX_BUNDLED_DRIVER\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=PRIVATE|outcome=FAIL|sql=SECRET\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=DRIVER_CLOSE|outcome=PASS\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn("setupPhase=DRIVER_CREATE|outcome=PASS", result)
        self.assertIn("setupPhase=DATABASE_CREATE|outcome=PASS", result)
        self.assertIn(
            "setupPhase=TITLE_INSERT|outcome=FAIL|exception=SQLException|frame=ANDROIDX_BUNDLED_DRIVER",
            result,
        )
        self.assertIn("setupPhase=DRIVER_CLOSE|outcome=PASS", result)
        self.assertNotIn("SECRET", result)
        self.assertNotIn("sql=", result)

    def test_schema_probe_and_sql_error_categories_are_allowlisted(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA|outcome=PASS|titles=missing"
            "|outbox=present|dirtyInsertTrigger=missing\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TITLE_INSERT|outcome=FAIL"
            "|exception=SQLException|frame=ANDROIDX_BUNDLED_DRIVER|sqlCategory=SQLITE_OTHER\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA|outcome=FAIL|titles=unknown"
            "|outbox=unknown|dirtyInsertTrigger=unknown|sqlCategory=NOT_SQLITE\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA|outcome=FAIL|titles=unknown"
            "|outbox=unknown|dirtyInsertTrigger=unknown|sqlCategory=PRIVATE\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "DIAGNOSTIC|schemaOutcome=PASS|titles=missing|outbox=present|dirtyInsertTrigger=missing",
            result,
        )
        self.assertIn("sqlCategory=SQLITE_OTHER", result)
        self.assertIn(
            "DIAGNOSTIC|schemaOutcome=FAIL|titles=unknown|outbox=unknown|dirtyInsertTrigger=unknown",
            result,
        )
        self.assertNotIn("PRIVATE", result)
        self.assertNotIn("message=", result)

    def test_isolated_context_database_events_keep_only_closed_context_and_persist_fields(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT|applicationContext=null"
            "|targetIsolation=isolated|databaseContext=wrapped\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT|applicationContext=present"
            "|targetIsolation=isolated|databaseContext=raw|path=/private/user/db\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=TEST_CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException|frame=EYGRABER_ANDROIDX_DRIVER\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "contextApplicationContext=null|targetIsolation=isolated|databaseContext=wrapped",
            result,
        )
        self.assertIn(
            "setupPhase=TEST_CANONICAL_TITLE_PERSIST|outcome=FAIL|"
            "exception=NullPointerException|frame=EYGRABER_ANDROIDX_DRIVER",
            result,
        )
        self.assertNotIn("private/user", result)
        self.assertNotIn("path=", result)

    def test_persistence_failure_frame_is_closed_and_sanitized(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException|frame=SQLDELIGHT_DRIVER_AWAIT\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException|frame=ANDROIDX_BUNDLED_DRIVER\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException|frame=EYGRABER_SCHEMA_DRIVER\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException|frame=EYGRABER_CONFIGURABLE_DRIVER\n"
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=CANONICAL_TITLE_PERSIST|"
            "outcome=FAIL|exception=NullPointerException|frame=private.Method|token=SECRET\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertIn(
            "setupPhase=CANONICAL_TITLE_PERSIST|outcome=FAIL|"
            "exception=NullPointerException|frame=SQLDELIGHT_DRIVER_AWAIT",
            result,
        )
        self.assertNotIn("SECRET", result)
        self.assertNotIn("private.Method", result)
        self.assertIn("frame=ANDROIDX_BUNDLED_DRIVER", result)
        self.assertIn("frame=EYGRABER_SCHEMA_DRIVER", result)
        self.assertIn("frame=EYGRABER_CONFIGURABLE_DRIVER", result)

    def test_unknown_setup_diagnostics_are_dropped(self):
        runner = (
            "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=PRIVATE_PHASE"
            "|outcome=PASS|secret=HIDDEN\n"
        )
        result = "\n".join(diagnostic.summarize(runner, ""))
        self.assertNotIn("setupPhase=", result)
        self.assertNotIn("HIDDEN", result)

if __name__ == "__main__":
    unittest.main()
