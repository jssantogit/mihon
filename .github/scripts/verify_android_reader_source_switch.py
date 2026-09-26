#!/usr/bin/env python3
"""Fail closed unless a named Reader source-switch Android test ran and observed pages."""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


TEST_CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest"
METHOD_SCENARIOS = {
    "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition": "SOURCE_A_TO_B",
    "emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession": "EMPTY_OR_FAILING",
    "pageCountDifferenceClampsPositionToValidPage": "PAGE_COUNT_CLAMP",
    "retiredSourceCallbackCannotChangePublishedSession": "RETIRED_CALLBACK",
}


class ReaderSourceSwitchVerificationError(ValueError):
    pass


def _instrumentation_ran(output: str, method: str) -> bool:
    if len(re.findall(r"(?m)^INSTRUMENTATION_STATUS: numtests=1\s*$", output)) != 1:
        return False
    for label, expected in (("class", TEST_CLASS), ("test", method), ("numtests", "1")):
        if not re.search(
            r"(?m)^INSTRUMENTATION_STATUS: " + label + r"=" + re.escape(expected) + r"\s*$",
            output,
        ):
            return False
    return (
        re.search(r"(?m)^INSTRUMENTATION_STATUS_CODE: 0\s*$", output) is not None
        and re.search(r"(?m)^OK \(1 test\)\s*$", output) is not None
        and re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output) is not None
        and "FAILURES!!!" not in output
    )


def _parse_record(line: str) -> dict[str, str] | None:
    prefix = "INSTRUMENTATION_STATUS: stream=ANDROID_SOURCE_SWITCH|"
    if not line.startswith(prefix):
        return None
    fields: dict[str, str] = {}
    for part in line[len(prefix):].split("|"):
        key, separator, value = part.partition("=")
        if not separator or not key or key in fields:
            raise ReaderSourceSwitchVerificationError("Malformed or duplicate source-switch evidence field")
        fields[key] = value
    return fields


def _positive_int(fields: dict[str, str], name: str) -> int:
    value = fields.get(name, "")
    if not re.fullmatch(r"[0-9]+", value):
        raise ReaderSourceSwitchVerificationError("Source-switch evidence lacks numeric " + name)
    number = int(value)
    if number <= 0:
        raise ReaderSourceSwitchVerificationError("Source-switch evidence requires positive " + name)
    return number


def _nonnegative_int(fields: dict[str, str], name: str) -> int:
    value = fields.get(name, "")
    if not re.fullmatch(r"[0-9]+", value):
        raise ReaderSourceSwitchVerificationError("Source-switch evidence lacks numeric " + name)
    return int(value)


def verify(output: str, method: str) -> dict[str, str]:
    if method not in METHOD_SCENARIOS:
        raise ReaderSourceSwitchVerificationError("Unexpected Reader source-switch test method")
    if "POSITION_NOT_OBSERVABLE" in output:
        raise ReaderSourceSwitchVerificationError("Reader page position was not observable")
    if not _instrumentation_ran(output, method):
        raise ReaderSourceSwitchVerificationError("Named Android test was not executed exactly once successfully")

    records: list[dict[str, str]] = []
    for line in output.splitlines():
        record = _parse_record(line)
        if record is not None:
            records.append(record)
    if len(records) != 1:
        raise ReaderSourceSwitchVerificationError("Expected exactly one sanitized source-switch evidence record")

    fields = records[0]
    if fields.get("scenario") != METHOD_SCENARIOS[method] or fields.get("outcome") != "PASS":
        raise ReaderSourceSwitchVerificationError("Source-switch evidence does not match the executed scenario")
    if fields.get("pages") != "LOADED" or fields.get("position") != "OBSERVABLE":
        raise ReaderSourceSwitchVerificationError("Source-switch evidence did not prove loaded pages and position")
    page_count = _positive_int(fields, "pageCount")
    position_index = _nonnegative_int(fields, "positionIndex")
    if position_index >= page_count:
        raise ReaderSourceSwitchVerificationError("Observed Reader position is outside the loaded page list")

    if method == "emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession":
        if fields.get("session") != "PREVIOUS_PRESERVED":
            raise ReaderSourceSwitchVerificationError("Failed replacement did not prove the old Reader session remained active")

    if method == "pageCountDifferenceClampsPositionToValidPage":
        source_a_count = _positive_int(fields, "sourceAPageCount")
        source_b_count = _positive_int(fields, "sourceBPageCount")
        before = _nonnegative_int(fields, "positionBefore")
        after = _nonnegative_int(fields, "positionAfter")
        if source_a_count == source_b_count:
            raise ReaderSourceSwitchVerificationError("Clamp scenario did not use different page counts")
        if before >= source_a_count or after >= source_b_count:
            raise ReaderSourceSwitchVerificationError("Clamp scenario reported an index outside its source page list")
        if after != min(before, source_b_count - 1):
            raise ReaderSourceSwitchVerificationError("Reader page position was not clamped to the available replacement pages")
        if page_count != source_b_count or position_index != after:
            raise ReaderSourceSwitchVerificationError("Observed Reader page count or index disagrees with the clamp result")

    if method == "sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition":
        source_a_count = _positive_int(fields, "sourceAPageCount")
        source_b_count = _positive_int(fields, "sourceBPageCount")
        before = _nonnegative_int(fields, "positionBefore")
        after = _nonnegative_int(fields, "positionAfter")
        if source_a_count <= 1 or source_b_count <= 0:
            raise ReaderSourceSwitchVerificationError("Valid-to-valid switch did not load pages from both sources")
        if before >= source_a_count or after >= source_b_count:
            raise ReaderSourceSwitchVerificationError("Valid-to-valid switch reported an out-of-range position")
        if after != min(before, source_b_count - 1):
            raise ReaderSourceSwitchVerificationError("Valid-to-valid switch did not apply the bounded replacement position policy")
        if page_count != source_b_count or position_index != after:
            raise ReaderSourceSwitchVerificationError("Valid-to-valid switch reported an index outside the loaded replacement pages")

    return fields


def sanitized_summary(method: str, fields: dict[str, str], passed: bool) -> str:
    result = "PASS" if passed else "FAIL"
    lines = [
        "ANDROID_SOURCE_SWITCH_RESULT|method=" + method + "|scenario=" + METHOD_SCENARIOS[method] + "|outcome=" + result,
    ]
    if passed:
        # Whitelist only numeric fixture observations and fixed state tokens.
        page_count = fields.get("pageCount", "UNKNOWN")
        position = fields.get("positionIndex", "UNKNOWN")
        lines.append("ANDROID_SOURCE_SWITCH_RESULT|pages=LOADED|pageCount=" + page_count + "|position=OBSERVABLE|positionIndex=" + position)
        if fields.get("session") == "PREVIOUS_PRESERVED":
            lines.append("ANDROID_SOURCE_SWITCH_RESULT|session=PREVIOUS_PRESERVED")
        if "sourceAPageCount" in fields and "sourceBPageCount" in fields:
            lines.append(
                "ANDROID_SOURCE_SWITCH_RESULT|sourceAPageCount=" + fields["sourceAPageCount"]
                + "|sourceBPageCount=" + fields["sourceBPageCount"]
                + "|positionBefore=" + fields.get("positionBefore", "UNKNOWN")
                + "|positionAfter=" + fields.get("positionAfter", "UNKNOWN")
            )
    else:
        lines.append("ANDROID_SOURCE_SWITCH_RESULT|evidence=NOT_PROVEN")
    return "\n".join(lines) + "\n"


def write_junit(path: Path, method: str, passed: bool) -> None:
    suite = ET.Element(
        "testsuite",
        attrib={
            "name": TEST_CLASS,
            "tests": "1",
            "failures": "0" if passed else "0",
            "errors": "0" if passed else "1",
            "skipped": "0",
        },
    )
    case = ET.SubElement(suite, "testcase", attrib={"classname": TEST_CLASS, "name": method})
    if not passed:
        error = ET.SubElement(case, "error", attrib={"message": "Reader source-switch evidence not proven"})
        error.text = "Raw runner diagnostics intentionally omitted."
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("runner_output", type=Path)
    parser.add_argument("method", choices=METHOD_SCENARIOS)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--junit", type=Path, required=True)
    args = parser.parse_args(argv)

    output = args.runner_output.read_text(encoding="utf-8", errors="replace")
    fields: dict[str, str] = {}
    error = None
    try:
        fields = verify(output, args.method)
    except ReaderSourceSwitchVerificationError as exception:
        error = exception
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(sanitized_summary(args.method, fields, error is None), encoding="utf-8")
    write_junit(args.junit, args.method, error is None)
    if error is not None:
        print("::error::" + str(error), file=sys.stderr)
        return 1
    print(args.summary.read_text(encoding="utf-8"), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
