#!/usr/bin/env python3
"""Emit a bounded, allowlisted summary when an Android live batch is incomplete."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import sys

MAX_RUNNER_OUTPUT_BYTES = 1_048_576
VERIFY_MODULE = Path(__file__).with_name("verify_extension_live_report.py")
SPEC = importlib.util.spec_from_file_location("verify_extension_live_report", VERIFY_MODULE)
assert SPEC and SPEC.loader
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)
TEST_CLASS = verify.TEST_CLASS
TEST_METHOD = verify.TEST_METHOD

TEST_KEYS = {
    "class": verify.TEST_CLASS,
    "test": verify.TEST_METHOD,
    "numtests": "1",
}
MAX_BATCH_SOURCES = 56
MAX_DETAIL_EVENTS = 2 * MAX_BATCH_SOURCES + 6
SAFE_STATUS = re.compile(r"^INSTRUMENTATION_STATUS: (class|test|numtests)=([^\s]+)$")
LIVE_STAGE = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=LIVE_STAGE\|stage="
    r"(EXTENSION_LOOKUP_START|EXTENSION_LOOKUP_TIMEOUT|EXTENSION_LOOKUP_CANCELLED|"
    r"EXTENSION_LOOKUP_FAILURE|EXTENSION_READY|SOURCE_LOOP_START|BATCH_COMPLETE)"
    r"(?:\|sourceCount=(\d{1,3})|\|elapsedMs=(\d{1,6}))?$"
)
SOURCE_ATTEMPT = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=SOURCE_ATTEMPT\|ordinal=(\d{1,3})"
    r"\|sourceId=(-?\d{1,19})\|lang=([A-Za-z0-9-]{1,15})$"
)


def summarize(raw: str, process_exit: int, input_rejected: bool = False) -> list[str]:
    """Summarize only known JUnit markers and validated SOURCE_PROBE events."""
    lines = [] if input_rejected else raw.splitlines()
    statuses = {key: set() for key in TEST_KEYS}
    for line in lines:
        match = SAFE_STATUS.fullmatch(line.strip())
        if match:
            key, value = match.groups()
            statuses[key].add(value)

    all_rows = [] if input_rejected else verify.safe_observations(raw)
    rows = all_rows[:MAX_BATCH_SOURCES]
    source_event_lines = min(
        MAX_BATCH_SOURCES,
        sum("INSTRUMENTATION_STATUS: stream=SOURCE_PROBE" in line for line in lines),
    )
    stage_event_count = sum(LIVE_STAGE.fullmatch(line.strip()) is not None for line in lines)
    attempt_event_count = sum(SOURCE_ATTEMPT.fullmatch(line.strip()) is not None for line in lines)
    report = [
        "ANDROID_LIVE_DIAGNOSTIC|runnerExit=" + str(process_exit),
        "ANDROID_LIVE_DIAGNOSTIC|inputRejected=" + str(input_rejected).lower(),
        "ANDROID_LIVE_DIAGNOSTIC|expectedClass=" + str(TEST_KEYS["class"] in statuses["class"]).lower(),
        "ANDROID_LIVE_DIAGNOSTIC|expectedMethod=" + str(TEST_KEYS["test"] in statuses["test"]).lower(),
        "ANDROID_LIVE_DIAGNOSTIC|singleTestDeclared=" + str(TEST_KEYS["numtests"] in statuses["numtests"]).lower(),
        "ANDROID_LIVE_DIAGNOSTIC|junitPass=" + str(
            "OK (1 test)" in lines and "INSTRUMENTATION_CODE: -1" in lines
        ).lower(),
        "ANDROID_LIVE_DIAGNOSTIC|probeEvents=" + str(len(rows)),
        "ANDROID_LIVE_DIAGNOSTIC|invalidProbeEvents=" + str(max(0, source_event_lines - len(rows))),
        "ANDROID_LIVE_DIAGNOSTIC|detailsTruncated=" + str(
            len(all_rows) > MAX_BATCH_SOURCES or
                stage_event_count > 6 or
                attempt_event_count > MAX_BATCH_SOURCES
        ).lower(),
    ]
    detail_count = 0
    for line in lines:
        if detail_count >= MAX_DETAIL_EVENTS:
            break
        stage_match = LIVE_STAGE.fullmatch(line.strip())
        if stage_match:
            stage, source_count, elapsed_ms = stage_match.groups()
            event = "LIVE_STAGE|stage=" + stage
            if source_count is not None:
                event += "|sourceCount=" + source_count
            if elapsed_ms is not None:
                event += "|elapsedMs=" + elapsed_ms
            report.append(event)
            detail_count += 1
            continue
        attempt_match = SOURCE_ATTEMPT.fullmatch(line.strip())
        if attempt_match:
            ordinal, source_id, lang = attempt_match.groups()
            report.append(
                "SOURCE_ATTEMPT|ordinal=" + ordinal + "|sourceId=" + source_id + "|lang=" + lang
            )
            detail_count += 1
            continue
        if "INSTRUMENTATION_STATUS: stream=SOURCE_PROBE" in line:
            event = verify.safe_observations(line)
            if event:
                source, lang, outcome, http_status, result_count, elapsed_ms = event[0]
                report.append(
                    "SOURCE_PROBE|sourceId=" + source + "|lang=" + lang + "|outcome=" + outcome +
                    "|httpStatus=" + http_status + "|resultCount=" + result_count + "|elapsedMs=" + elapsed_ms
                )
                detail_count += 1
    return report


if __name__ == "__main__":
    if len(sys.argv) != 3 or not re.fullmatch(r"-?\d{1,3}", sys.argv[2]):
        raise SystemExit("usage: summarize_extension_live_batch.py RUNNER_LOG PROCESS_EXIT")
    path = Path(sys.argv[1])
    input_rejected = path.stat().st_size > MAX_RUNNER_OUTPUT_BYTES
    runner_log = "" if input_rejected else path.read_text(encoding="utf-8", errors="replace")
    for line in summarize(runner_log, int(sys.argv[2]), input_rejected=input_rejected):
        print(line)
