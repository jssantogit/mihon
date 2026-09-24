#!/usr/bin/env python3
"""Accept only complete, non-sensitive observations from each real-source shard.

A site HTTP error is an observational outcome, not proof of a Tsuzuki defect.
An incomplete AndroidJUnitRunner run or missing source observation is RED.
"""
from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

TEST_CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.InstalledExtensionFixtureInstrumentedTest"
TEST_METHOD = "optionalLiveSearchSourceBatch"
ALLOWED = frozenset((
    "RESULTS", "EMPTY", "SOURCE_DISABLED", "UNSUPPORTED", "PAUSED_BACKOFF",
    "SOURCE_UNAVAILABLE", "HTTP_RESPONSE", "NETWORK_FAILURE", "TIMEOUT",
    "CAPTCHA_REQUIRED", "MALFORMED_RESPONSE", "EXTENSION_FAILURE", "INDETERMINATE",
))
STATUS = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=SOURCE_PROBE"
    r"\|sourceId=(-?\d{1,19})\|lang=([A-Za-z0-9-]{1,15})"
    r"\|outcome=([A-Z_]+)\|httpStatus=(none|[1-5]\d\d)"
    r"\|resultCount=(\d{1,4})\|elapsedMs=(\d{1,8})$"
)
REPORTS = Path(".github/results/extension-live")


class EvidenceError(ValueError):
    pass


def inspect(raw: str, count: int) -> list[tuple[str, ...]]:
    if count not in range(1, 57):
        raise EvidenceError("Live batch count is out of bounds")
    for field, wanted in (("class", TEST_CLASS), ("test", TEST_METHOD), ("numtests", "1")):
        target = "INSTRUMENTATION_STATUS: " + field + "=" + wanted
        if target not in raw.splitlines():
            raise EvidenceError("AndroidJUnitRunner did not execute the expected test")
    if "INSTRUMENTATION_CODE: -1" not in raw.splitlines() or "OK (1 test)" not in raw.splitlines():
        raise EvidenceError("Instrumented live batch did not finish with one passing method")
    rows = []
    for line in raw.splitlines():
        match = STATUS.fullmatch(line.strip())
        if match:
            rows.append(match.groups())
    if len(rows) != count:
        raise EvidenceError("One sanitized observation is required for every requested source")
    if len({row[0] for row in rows}) != count:
        raise EvidenceError("Duplicate source ID in one live batch")
    for source, lang, kind, code, results, ms in rows:
        if kind not in ALLOWED or (kind == "HTTP_RESPONSE") != (code != "none"):
            raise EvidenceError("Unsupported or contradictory outcome/HTTP metadata")
        if (kind == "RESULTS") != (int(results) > 0):
            raise EvidenceError("Contradictory result count")
        if int(ms) > 90_000:
            raise EvidenceError("Implausible duration")
    return rows


def save(filename: str, shard: int, observations: list[tuple[str, ...]]) -> Path:
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]+\.apk", filename) or shard not in range(4):
        raise EvidenceError("Unrecognized fixture or shard")
    folder = REPORTS / ("shard-" + str(shard))
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / (filename + ".csv")
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(("fixture", "source_id", "language", "outcome", "http", "results", "elapsed_ms"))
        for source_id, language, outcome, http, results, ms in observations:
            writer.writerow((filename, source_id, language, outcome, http, results, ms))
    return path


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: verify_extension_live_report.py RUNNER_LOG FILE.APK SHARD EXPECTED_COUNT")
    try:
        raw = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
        filename, shard, expected = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
        records = inspect(raw, expected)
        artifact = save(filename, shard, records)
    except (OSError, ValueError, EvidenceError) as exc:
        print("::error::Incomplete or invalid source observations: " + str(exc), file=sys.stderr)
        raise SystemExit(1) from None
    attempted = sum(row[2] not in ("SOURCE_DISABLED", "UNSUPPORTED", "PAUSED_BACKOFF") for row in records)
    found = sum(row[2] == "RESULTS" for row in records)
    throttled = sum(row[2] == "PAUSED_BACKOFF" for row in records)
    print("LIVE_SOURCE_BATCH|fixture=" + filename + "|observed=" + str(len(records)) +
          "|attempted=" + str(attempted) + "|results=" + str(found) +
          "|backoff=" + str(throttled))
    print("Safe CSV: " + str(artifact))
