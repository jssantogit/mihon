#!/usr/bin/env python3
"""Fail closed unless the offline migration-33 recovery test spans two app PIDs."""
from __future__ import annotations

import argparse
import re
import sqlite3
import sys
from pathlib import Path


TEST_CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReadingProcessRecoveryInstrumentedTest"
PHASE_METHODS = {
    "PRE_RESTART_READY": "failedAcknowledgementLeavesDurableProjectionForProcessRestart",
    "POST_RESTART": "newProcessStartupReplaysProjectionAndAcknowledgesExactlyOnce",
}
PHASE_FIELDS = {
    "PRE_RESTART_READY": {
        "queue": "PENDING",
        "ackFailure": "INJECTED",
        "retry": "DUE",
        "canonical": "PRESERVED",
        "legacy": "ROLLED_BACK",
    },
    "POST_RESTART": {
        "queue": "ACKNOWLEDGED",
        "replay": "STARTUP",
        "preference": "PRESERVED",
        "progress": "PRESERVED",
        "history": "ONCE",
    },
}
FIXTURE_TITLE_URL = "/cr02/process-recovery"
FIXTURE_CHAPTER_URL = "/cr02/process-recovery/chapter-1"
FIXTURE_CANONICAL_CHAPTER_ID = "cr02-process-recovery-chapter-1"


class RecoveryVerificationError(ValueError):
    """The runner output or process evidence did not prove recovery."""


def _instrumentation_ran(output: str, method: str) -> bool:
    bundles: list[tuple[str, dict[str, str]]] = []
    current: dict[str, str] = {}
    recognized = {"class", "test", "numtests"}
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if key in recognized:
                if not separator or key in current:
                    return False
                current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):].strip()
            bundles.append((code, current))
            current = {}
    if current:
        return False
    events = [(code, fields) for code, fields in bundles if recognized.intersection(fields)]
    if len(events) != 2:
        return False
    (start_code, started), (finish_code, finished) = events
    if start_code != "1" or finish_code != "0":
        return False
    if started != {"class": TEST_CLASS, "test": method, "numtests": "1"}:
        return False
    if finished.get("class") != TEST_CLASS or finished.get("test") != method:
        return False
    if finished.get("numtests", "1") != "1":
        return False
    return (
        re.search(r"(?m)^OK \(1 test\)\s*$", output) is not None
        and re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output) is not None
        and "FAILURES!!!" not in output
    )


def _instrumentation_started_and_blocked(output: str, method: str) -> bool:
    bundles: list[tuple[str, dict[str, str]]] = []
    current: dict[str, str] = {}
    recognized = {"class", "test", "numtests"}
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if key in recognized:
                if not separator or key in current:
                    return False
                current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):].strip()
            bundles.append((code, current))
            current = {}
    if current:
        return False
    events = [(code, fields) for code, fields in bundles if recognized.intersection(fields)]
    if len(events) != 1:
        return False
    code, started = events[0]
    if code != "1" or started != {"class": TEST_CLASS, "test": method, "numtests": "1"}:
        return False
    return not (
        "FAILURES!!!" in output
        or re.search(r"(?m)^OK \(\d+ tests?\)\s*$", output) is not None
        or re.search(r"(?m)^INSTRUMENTATION_CODE:", output) is not None
    )


def _parse_pid(value: str, label: str) -> int:
    if not re.fullmatch(r"[0-9]+", value):
        raise RecoveryVerificationError(f"{label} is not a numeric positive PID")
    pid = int(value)
    if pid <= 0:
        raise RecoveryVerificationError(f"{label} is not a numeric positive PID")
    return pid


def _parse_pid_set(value: str, label: str) -> set[int]:
    tokens = value.split()
    if any(not re.fullmatch(r"[0-9]+", token) or int(token) <= 0 for token in tokens):
        raise RecoveryVerificationError(f"{label} contains invalid PID data")
    if len(tokens) != len(set(tokens)):
        raise RecoveryVerificationError(f"{label} contains duplicate PID data")
    return {int(token) for token in tokens}


def _parse_record(output: str, phase: str) -> tuple[int, dict[str, str]]:
    prefix = "INSTRUMENTATION_STATUS: stream=CR02_PROCESS_RECOVERY|"
    records = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
    if len(records) != 1:
        raise RecoveryVerificationError(f"{phase} must emit exactly one sanitized recovery record")

    fields: dict[str, str] = {}
    for part in records[0].split("|"):
        key, separator, value = part.partition("=")
        if not separator or not key or key in fields:
            raise RecoveryVerificationError(f"{phase} recovery record has malformed or duplicate fields")
        fields[key] = value
    if fields.pop("phase", None) != phase:
        raise RecoveryVerificationError(f"Recovery record does not match the {phase} phase")
    pid = _parse_pid(fields.pop("pid", ""), f"{phase} app PID")
    if fields != PHASE_FIELDS[phase]:
        raise RecoveryVerificationError(f"{phase} recovery facts are incomplete or unexpected")
    return pid, fields


def _read_successful_test(path: Path, phase: str, runner_exit: int) -> tuple[int, dict[str, str]]:
    if runner_exit != 0:
        raise RecoveryVerificationError(f"{phase} AndroidJUnitRunner exited nonzero")
    output = path.read_text(encoding="utf-8", errors="replace")
    method = PHASE_METHODS[phase]
    if not _instrumentation_ran(output, method):
        raise RecoveryVerificationError(f"{phase} named Android test did not execute exactly once successfully")
    return _parse_record(output, phase)


def _read_ready_test(path: Path) -> tuple[int, dict[str, str]]:
    output = path.read_text(encoding="utf-8", errors="replace")
    method = PHASE_METHODS["PRE_RESTART_READY"]
    if not _instrumentation_started_and_blocked(output, method):
        raise RecoveryVerificationError("PRE_RESTART named Android test was not active at its READY marker")
    return _parse_record(output, "PRE_RESTART_READY")


def _verify_stopped_database(database_path: Path) -> None:
    try:
        with sqlite3.connect(database_path, timeout=3.0) as connection:
            check = connection.execute("PRAGMA quick_check").fetchone()
            if check != ("ok",):
                raise RecoveryVerificationError("The stopped app database failed SQLite quick_check")
            chapter = connection.execute(
                """
                SELECT c._id
                FROM chapters c
                JOIN mangas m ON m._id = c.manga_id
                WHERE m.url = ? AND c.url = ?
                LIMIT 1
                """,
                (FIXTURE_TITLE_URL, FIXTURE_CHAPTER_URL),
            ).fetchone()
            if chapter is None:
                raise RecoveryVerificationError("The stopped app database lacks the offline manga fixture")
            row = connection.execute(
                """
                SELECT has_pending_progress, progress_read, progress_page,
                    has_pending_history, pending_duration, attempt_count, next_retry_at
                FROM tsuzuki_mihon_projection_queue
                WHERE canonical_chapter_id = ? AND mihon_chapter_id = ?
                """,
                (FIXTURE_CANONICAL_CHAPTER_ID, chapter[0]),
            ).fetchone()
    except sqlite3.Error as error:
        raise RecoveryVerificationError("The stopped app database could not prove its durable projection queue") from error

    if row is None:
        raise RecoveryVerificationError("The pending projection was missing after force-stop")
    pending_progress, progress_read, progress_page, pending_history, duration, attempts, retry_at = row
    if (pending_progress, progress_read, progress_page) != (1, 1, 6):
        raise RecoveryVerificationError("The stopped database has incomplete durable canonical progress projection")
    if pending_history != 1 or duration != 37_000 or attempts != 1:
        raise RecoveryVerificationError("The stopped database has incomplete durable history or retry state")
    if retry_at <= 0:
        raise RecoveryVerificationError("The stopped database lacks its persisted retry deadline")


def verify(args: argparse.Namespace) -> str:
    phase1_pid, _ = _read_ready_test(args.phase1_output)
    phase1_live = _parse_pid_set(args.phase1_live_pids, "PRE_RESTART live PID evidence")
    if phase1_pid not in phase1_live:
        raise RecoveryVerificationError("The PRE_RESTART app process was not alive at its READY marker")

    if args.phase2_output is None:
        return f"CR02_PROCESS_RECOVERY|phase=PRE_RESTART_READY|pid={phase1_pid}|queue=PENDING|ackFailure=INJECTED|retry=DUE|canonical=PRESERVED|legacy=ROLLED_BACK"

    after_force_stop = _parse_pid_set(args.post_force_stop_pids, "post-force-stop PID evidence")
    if after_force_stop:
        raise RecoveryVerificationError("am force-stop left an app process alive")
    if args.stopped_database is None:
        raise RecoveryVerificationError("The stopped app database was not inspected before startup replay")
    _verify_stopped_database(args.stopped_database)

    phase2_pid, _ = _read_successful_test(args.phase2_output, "POST_RESTART", args.phase2_runner_exit)
    if phase2_pid == phase1_pid:
        raise RecoveryVerificationError("The application did not start with a different process ID")

    return (
        "CR02_PROCESS_RECOVERY|outcome=PASS|restart=PROVEN|"
        f"phase1Pid={phase1_pid}|phase1Queue=PENDING_AFTER_FORCE_STOP|dbQueue=VERIFIED_BEFORE_STARTUP|forceStop=PROVEN|"
        f"phase2Pid={phase2_pid}|phase2Queue=ACKNOWLEDGED|"
        "replay=STARTUP|preference=PRESERVED|progress=PRESERVED|history=ONCE"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase1-output", type=Path, required=True)
    parser.add_argument("--phase1-live-pids", required=True)
    parser.add_argument("--post-force-stop-pids")
    parser.add_argument("--phase2-output", type=Path)
    parser.add_argument("--phase2-runner-exit", type=int)
    parser.add_argument("--stopped-database", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    if args.phase2_output is not None and (
        args.phase2_runner_exit is None
        or args.post_force_stop_pids is None
        or args.stopped_database is None
    ):
        parser.error("phase-two verification requires runner exit, post-force-stop PIDs, and the stopped database")

    try:
        record = verify(args)
    except (OSError, RecoveryVerificationError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    if args.summary is not None:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(record + "\n", encoding="utf-8")
    print(record)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
