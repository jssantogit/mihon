#!/usr/bin/env python3
"""Regression tests for the fail-closed Android process-recovery evidence gate."""
from __future__ import annotations

import importlib.util
from argparse import Namespace
from pathlib import Path
import sqlite3
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("verify_android_process_recovery.py")
SPEC = importlib.util.spec_from_file_location("verify_android_process_recovery", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def output_for(phase: str, pid: int) -> str:
    facts = verifier.PHASE_FIELDS[phase]
    marker = "CR02_PROCESS_RECOVERY|phase=" + phase + f"|pid={pid}|"
    marker += "|".join(f"{key}={value}" for key, value in facts.items())
    method = verifier.PHASE_METHODS[phase]
    return (
        "INSTRUMENTATION_STATUS: numtests=1\n"
        f"INSTRUMENTATION_STATUS: class={verifier.TEST_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        f"INSTRUMENTATION_STATUS: stream={marker}\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        f"INSTRUMENTATION_STATUS: class={verifier.TEST_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        "INSTRUMENTATION_STATUS: numtests=1\n"
        "INSTRUMENTATION_STATUS_CODE: 0\n"
        "INSTRUMENTATION_RESULT: stream=\n"
        "Time: 1.0\n\n"
        "OK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


def ready_output_for(phase: str, pid: int) -> str:
    facts = verifier.PHASE_FIELDS[phase]
    marker = "CR02_PROCESS_RECOVERY|phase=" + phase + f"|pid={pid}|"
    marker += "|".join(f"{key}={value}" for key, value in facts.items())
    method = verifier.PHASE_METHODS[phase]
    return (
        "INSTRUMENTATION_STATUS: numtests=1\n"
        f"INSTRUMENTATION_STATUS: class={verifier.TEST_CLASS}\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        f"INSTRUMENTATION_STATUS: stream={marker}\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
    )


class VerifyAndroidProcessRecoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.phase1 = root / "phase1.txt"
        self.phase2 = root / "phase2.txt"
        self.database = root / "tachiyomi.db"
        self.phase1.write_text(ready_output_for("PRE_RESTART_READY", 1001), encoding="utf-8")
        self.phase2.write_text(output_for("POST_RESTART", 2002), encoding="utf-8")
        with sqlite3.connect(self.database) as connection:
            connection.executescript(
                """
                CREATE TABLE mangas(_id INTEGER PRIMARY KEY, url TEXT NOT NULL);
                CREATE TABLE chapters(_id INTEGER PRIMARY KEY, manga_id INTEGER NOT NULL, url TEXT NOT NULL);
                CREATE TABLE tsuzuki_mihon_projection_queue(
                    canonical_chapter_id TEXT NOT NULL,
                    mihon_chapter_id INTEGER NOT NULL,
                    has_pending_progress INTEGER NOT NULL,
                    progress_read INTEGER,
                    progress_page INTEGER,
                    has_pending_history INTEGER NOT NULL,
                    pending_duration INTEGER NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    next_retry_at INTEGER NOT NULL
                );
                INSERT INTO mangas VALUES(7, '/cr02/process-recovery');
                INSERT INTO chapters VALUES(42, 7, '/cr02/process-recovery/chapter-1');
                INSERT INTO tsuzuki_mihon_projection_queue VALUES(
                    'cr02-process-recovery-chapter-1', 42, 1, 1, 6, 1, 37000, 1, 2000
                );
                """,
            )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def args(self, **overrides: object) -> Namespace:
        args = {
            "phase1_output": self.phase1,
            "phase1_live_pids": "1001",
            "post_force_stop_pids": "",
            "phase2_output": self.phase2,
            "phase2_runner_exit": 0,
            "stopped_database": self.database,
            "summary": None,
        }
        args.update(overrides)
        return Namespace(**args)

    def test_requires_two_successful_named_android_tests_and_proves_process_change(self) -> None:
        record = verifier.verify(self.args())
        self.assertIn("restart=PROVEN", record)
        self.assertIn(
            "phase1Pid=1001|phase1Queue=PENDING_AFTER_FORCE_STOP|dbQueue=VERIFIED_BEFORE_STARTUP|forceStop=PROVEN|phase2Pid=2002",
            record,
        )
        self.assertIn("phase2Queue=ACKNOWLEDGED|replay=STARTUP|preference=PRESERVED|progress=PRESERVED|history=ONCE", record)

    def test_phase_one_must_still_be_alive_after_ready_marker(self) -> None:
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "not alive at its READY marker"):
            verifier.verify(self.args(phase1_live_pids=""))

    def test_force_stop_must_remove_every_app_pid(self) -> None:
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "force-stop left an app process"):
            verifier.verify(self.args(post_force_stop_pids="1001"))

    def test_pending_queue_must_be_read_from_database_before_phase_two(self) -> None:
        with sqlite3.connect(self.database) as connection:
            connection.execute("DELETE FROM tsuzuki_mihon_projection_queue")
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "missing after force-stop"):
            verifier.verify(self.args())

    def test_stopped_queue_must_keep_the_failed_acknowledgement_retry_state(self) -> None:
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE tsuzuki_mihon_projection_queue SET pending_duration = 0")
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "incomplete durable history"):
            verifier.verify(self.args())

    def test_second_phase_must_report_a_different_process_id(self) -> None:
        self.phase2.write_text(output_for("POST_RESTART", 1001), encoding="utf-8")
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "different process ID"):
            verifier.verify(self.args())

    def test_each_phase_requires_one_executed_successful_test_event(self) -> None:
        self.phase2.write_text(output_for("POST_RESTART", 2002).replace("OK (1 test)", "OK (0 tests)"), encoding="utf-8")
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "did not execute exactly once"):
            verifier.verify(self.args())

    def test_failure_before_ack_must_be_proven_with_persisted_retry_facts(self) -> None:
        self.phase1.write_text(
            ready_output_for("PRE_RESTART_READY", 1001).replace("retry=DUE", "retry=MISSING"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "incomplete or unexpected"):
            verifier.verify(self.args())

    def test_duplicate_or_extra_fixture_records_are_rejected(self) -> None:
        self.phase1.write_text(
            ready_output_for("PRE_RESTART_READY", 1001)
            + "INSTRUMENTATION_STATUS: stream=CR02_PROCESS_RECOVERY|phase=PRE_RESTART_READY|pid=1001\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "exactly one sanitized"):
            verifier.verify(self.args())

    def test_phase_one_only_evidence_is_not_misreported_as_a_restart(self) -> None:
        record = verifier.verify(self.args(phase2_output=None))
        self.assertIn("phase=PRE_RESTART_READY", record)
        self.assertNotIn("restart=PROVEN", record)

    def test_phase_one_must_be_blocked_and_active_when_host_observes_ready(self) -> None:
        self.phase1.write_text(
            ready_output_for("PRE_RESTART_READY", 1001) + "INSTRUMENTATION_CODE: -1\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(verifier.RecoveryVerificationError, "not active at its READY marker"):
            verifier.verify(self.args())

    def test_runner_requires_live_pid_check_before_force_stop(self) -> None:
        runner = (SCRIPT.parents[1] / "scripts/run_android_process_recovery.sh").read_text(encoding="utf-8")
        force_stop = runner.index("run_adb_stage 'FORCE_STOP_PHASE1_PROCESS'")
        self.assertLess(runner.index("phase1_live_pids=\"$(pid_list)\""), force_stop)
        self.assertLess(runner.index("VERIFY_INSTRUMENTATION_ACTIVE_AT_FORCE_STOP"), force_stop)
        self.assertIn("host_instrumentation_command_is_active", runner)
        self.assertLess(runner.index("WAIT_FOR_PRE_RESTART_READY"), force_stop)
        self.assertIn("PRE_RESTART_READY", runner)
        self.assertIn("PHASE1_APP_PROCESS_NOT_LIVE_AT_READY", runner)
        self.assertLess(runner.index("copy_stopped_database \"$stopped_db_dir\""), runner.index("newProcessStartupReplaysProjectionAndAcknowledgesExactlyOnce"))


if __name__ == "__main__":
    unittest.main()
