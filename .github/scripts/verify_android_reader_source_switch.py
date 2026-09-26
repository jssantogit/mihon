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
    "activityRecreationRestoresObservedCanonicalPosition": "ACTIVITY_RECREATE",
    "repeatedSourceSwitchKeepsPreferenceAndSingleHistoryEntry": "HISTORY_IDEMPOTENCE",
    "slowSourceDoesNotBlockHealthySourceOption": "SLOW_TO_HEALTHY",
    "cancelledDiscoveryCannotMutateActiveReaderSession": "DISCOVERY_CANCEL",
}
SAFE_TEST_CLASS_PREFIX = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation."
SAFE_TEST_SOURCE = "CanonicalReaderSourceSwitchInstrumentedTest.kt"
SAFE_FRAME_PREFIXES = (
    SAFE_TEST_CLASS_PREFIX,
    "eu.kanade.tachiyomi.ui.reader.",
    "eu.kanade.tachiyomi.data.tsuzuki.",
    "eu.kanade.tachiyomi.ui.tsuzuki.",
    "tachiyomi.data.",
)
FIXTURE_DIAGNOSTIC_PREFIXES = (
    "INSTRUMENTATION_STATUS: stream=READER_FIXTURE_DIAGNOSTIC|",
    "INSTRUMENTATION_STATUS: stream=ANDROID_SOURCE_SWITCH_DIAGNOSTIC|",
)
FIXTURE_DIAGNOSTIC_KEYS = (
    "scenario", "selector", "aSearch", "aInventory", "aPages",
    "bSearch", "bInventory", "bPages", "bHeld",
)
FIXTURE_DIAGNOSTIC_SELECTORS = {
    "READY_WITH_A", "DISCOVERING", "OPEN_WITHOUT_A", "CLOSED_OR_OTHER",
}
READER_VIEW_DIAGNOSTIC_PREFIX = "INSTRUMENTATION_STATUS: stream=READER_VIEW_DIAGNOSTIC|"
READER_VIEW_DIAGNOSTIC_KEYS = (
    "scenario", "phase", "viewer", "focused", "stream", "pagesLoaded",
    "pageCount", "pageState", "position", "pagerVisible", "pagerCount",
    "pagerCurrentItem", "pagerIdle", "interactionInjected", "interactionTarget",
    "matchingSamples", "matchingRows",
)
READER_VIEW_OPTIONAL_KEYS = ("expectedColor",)
READER_VIEWERS = {
    "NONE", "L2RPagerViewer", "R2LPagerViewer", "VerticalPagerViewer",
    "WebtoonViewer", "WebGpuViewer", "WebGpuViewerContinuous",
}
READER_PAGE_STATES = {"QUEUE", "LOAD_PAGE", "DOWNLOAD_IMAGE", "READY", "ERROR", "NONE"}
READER_FOCUS_STATES = {"FOCUSED", "NO_FOCUS", "UNKNOWN"}
READER_INTERACTION_TARGETS = {"READER_PAGER", "NONE"}
READER_EXPECTED_COLORS = {"RED", "BLUE", "NONE"}
READER_DIAGNOSTIC_SCENARIOS = {*METHOD_SCENARIOS.values(), "PAGER_READINESS"}
READER_BOOL_FIELDS = {"stream", "pagesLoaded", "pagerVisible", "interactionInjected", "pagerIdle"}
READER_SELECTOR_DIAGNOSTIC_PREFIX = "INSTRUMENTATION_STATUS: stream=READER_SELECTOR_DIAGNOSTIC|"
READER_SELECTOR_DIAGNOSTIC_KEYS = (
    "scenario", "state", "options", "aOption", "bOption", "chapterMatch",
    "failedProviders", "aSearchDelta", "aInventoryDelta", "aPagesDelta",
    "bSearchDelta", "bInventoryDelta", "bPagesDelta", "bHeld",
)
# UNKNOWN is retained as an explicitly inconclusive diagnostic state. It does
# not satisfy or bypass the instrumented test's functional assertions.
READER_SELECTOR_STATES = {"LOADING", "DISCOVERING", "READY", "EMPTY", "ERROR", "UNKNOWN"}
SAFE_DIAGNOSTIC_CATEGORIES = {
    "ASSERTION_FAILURE",
    "EVIDENCE_NOT_EMITTED",
    "INSTRUMENTATION_INCOMPLETE",
    "POSITION_NOT_OBSERVABLE",
    "RUNNER_ERROR",
    "TEST_EXCEPTION",
    "TEST_NOT_OBSERVED",
    "TIMEOUT",
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
        if source_a_count != source_b_count or after != before:
            raise ReaderSourceSwitchVerificationError("Equal-length valid source switch did not preserve the observed page position")
        if page_count != source_b_count or position_index != after:
            raise ReaderSourceSwitchVerificationError("Valid-to-valid switch reported an index outside the loaded replacement pages")

    if method == "activityRecreationRestoresObservedCanonicalPosition":
        source_a_count = _positive_int(fields, "sourceAPageCount")
        source_b_count = _positive_int(fields, "sourceBPageCount")
        before = _nonnegative_int(fields, "positionBefore")
        after = _nonnegative_int(fields, "positionAfter")
        if before >= source_a_count or after >= source_b_count:
            raise ReaderSourceSwitchVerificationError("Activity recreation reported an index outside its loaded page list")
        if after != before or page_count != source_b_count or position_index != after:
            raise ReaderSourceSwitchVerificationError("Activity recreation did not preserve the observed page position")

    if method == "repeatedSourceSwitchKeepsPreferenceAndSingleHistoryEntry":
        source_a_count = _positive_int(fields, "sourceAPageCount")
        source_b_count = _positive_int(fields, "sourceBPageCount")
        before = _nonnegative_int(fields, "positionBefore")
        after = _nonnegative_int(fields, "positionAfter")
        history_rows = _positive_int(fields, "historyRows")
        if before >= source_b_count or after >= source_a_count:
            raise ReaderSourceSwitchVerificationError("Repeated source switch reported an index outside its loaded page list")
        if before != after or page_count != source_a_count or position_index != after or history_rows != 1:
            raise ReaderSourceSwitchVerificationError("Repeated source switch did not prove one canonical history row and valid page evidence")

    if method == "slowSourceDoesNotBlockHealthySourceOption":
        if fields.get("sourceAHealthy") != "true" or fields.get("sourceBPending") != "true":
            raise ReaderSourceSwitchVerificationError("Healthy source was not proven available while the other source remained pending")
        if fields.get("session") != "PREVIOUS_PRESERVED":
            raise ReaderSourceSwitchVerificationError("Slow-source scenario did not prove the active Reader session remained intact")

    if method == "cancelledDiscoveryCannotMutateActiveReaderSession":
        if fields.get("cancelled") != "true" or fields.get("lateResponsesReleased") != "true":
            raise ReaderSourceSwitchVerificationError("Cancelled discovery did not prove cancellation and delivery of late responses")
        if fields.get("session") != "PREVIOUS_PRESERVED":
            raise ReaderSourceSwitchVerificationError("Cancelled discovery did not prove the active Reader session remained intact")

    return fields


def _fixture_diagnostic(output: str, method: str) -> dict[str, str] | None:
    if method != "slowSourceDoesNotBlockHealthySourceOption":
        return None
    records: list[str] = []
    for line in output.splitlines():
        for prefix in FIXTURE_DIAGNOSTIC_PREFIXES:
            if line.startswith(prefix):
                records.append(line[len(prefix):])
                break
    if not records:
        return {"scenario": "SLOW_TO_HEALTHY", "evidence": "NOT_EMITTED"}
    if len(records) != 1:
        return {"scenario": "SLOW_TO_HEALTHY", "evidence": "DUPLICATE"}

    fields: dict[str, str] = {}
    for part in records[0].split("|"):
        key, separator, value = part.partition("=")
        if not separator or key not in FIXTURE_DIAGNOSTIC_KEYS or key in fields:
            return {"scenario": "SLOW_TO_HEALTHY", "evidence": "MALFORMED"}
        fields[key] = value
    if set(fields) != set(FIXTURE_DIAGNOSTIC_KEYS):
        return {"scenario": "SLOW_TO_HEALTHY", "evidence": "MALFORMED"}
    if fields["scenario"] != "SLOW_TO_HEALTHY" or fields["selector"] not in FIXTURE_DIAGNOSTIC_SELECTORS:
        return {"scenario": "SLOW_TO_HEALTHY", "evidence": "MALFORMED"}
    for key in FIXTURE_DIAGNOSTIC_KEYS[2:]:
        if not re.fullmatch(r"[0-9]{1,6}", fields[key]):
            return {"scenario": "SLOW_TO_HEALTHY", "evidence": "MALFORMED"}
    return {key: fields[key] for key in FIXTURE_DIAGNOSTIC_KEYS}


def _split_diagnostic(
    line: str,
    prefix: str,
    expected_keys: tuple[str, ...],
    optional_keys: tuple[str, ...] = (),
) -> dict[str, str] | None:
    if not line.startswith(prefix):
        return None
    fields: dict[str, str] = {}
    for part in line[len(prefix):].split("|"):
        key, separator, value = part.partition("=")
        if not separator or key not in (*expected_keys, *optional_keys) or key in fields:
            return {}
        fields[key] = value
    if not set(expected_keys).issubset(fields):
        return {}
    return fields


def _reader_view_diagnostics(output: str) -> list[dict[str, str]]:
    """Retain only known Reader enums and numeric facts from test-side probes."""
    records: list[dict[str, str]] = []
    for line in output.splitlines():
        fields = _split_diagnostic(
            line,
            READER_VIEW_DIAGNOSTIC_PREFIX,
            READER_VIEW_DIAGNOSTIC_KEYS,
            READER_VIEW_OPTIONAL_KEYS,
        )
        if fields is None:
            continue
        if not fields:
            records.append({"evidence": "MALFORMED"})
            continue
        if fields["scenario"] not in READER_DIAGNOSTIC_SCENARIOS:
            records.append({"evidence": "MALFORMED"})
            continue
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,39}", fields["phase"]):
            records.append({"evidence": "MALFORMED"})
            continue
        if fields["viewer"] not in READER_VIEWERS:
            records.append({"evidence": "MALFORMED"})
            continue
        if fields["focused"] not in READER_FOCUS_STATES or fields["pageState"] not in READER_PAGE_STATES:
            records.append({"evidence": "MALFORMED"})
            continue
        if fields["interactionTarget"] not in READER_INTERACTION_TARGETS:
            records.append({"evidence": "MALFORMED"})
            continue
        if "expectedColor" in fields and fields["expectedColor"] not in READER_EXPECTED_COLORS:
            records.append({"evidence": "MALFORMED"})
            continue
        if any(fields[key] not in {"TRUE", "FALSE"} for key in READER_BOOL_FIELDS):
            records.append({"evidence": "MALFORMED"})
            continue
        numeric_keys = ("pageCount", "pagerCount", "matchingSamples", "matchingRows")
        if any(not re.fullmatch(r"[0-9]{1,7}", fields[key]) for key in numeric_keys):
            records.append({"evidence": "MALFORMED"})
            continue
        if not re.fullmatch(r"-1|[0-9]{1,7}", fields["position"]) or not re.fullmatch(
            r"-1|[0-9]{1,7}", fields["pagerCurrentItem"],
        ):
            records.append({"evidence": "MALFORMED"})
            continue
        records.append(fields)
    return records[:16]


def _reader_selector_diagnostics(output: str) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    binary_keys = {"aOption", "bOption", "chapterMatch"}
    for line in output.splitlines():
        fields = _split_diagnostic(line, READER_SELECTOR_DIAGNOSTIC_PREFIX, READER_SELECTOR_DIAGNOSTIC_KEYS)
        if fields is None:
            continue
        if not fields or fields["scenario"] != "SLOW_TO_HEALTHY" or fields["state"] not in READER_SELECTOR_STATES:
            records.append({"evidence": "MALFORMED"})
            continue
        valid = True
        for key in READER_SELECTOR_DIAGNOSTIC_KEYS[2:]:
            if key in binary_keys:
                valid = valid and fields[key] in {"0", "1"}
            else:
                valid = valid and re.fullmatch(r"[0-9]{1,7}", fields[key]) is not None
        records.append(fields if valid else {"evidence": "MALFORMED"})
    return records[:16]


def _failure_diagnosis(output: str, method: str, runner_exit: int) -> dict[str, str]:
    """Extract fixed categories, numeric facts, an exception class, and safe stack frames."""
    class_seen = re.search(r"(?m)^INSTRUMENTATION_STATUS: class=" + re.escape(TEST_CLASS) + r"\s*$", output) is not None
    test_seen = re.search(r"(?m)^INSTRUMENTATION_STATUS: test=" + re.escape(method) + r"\s*$", output) is not None
    numtests = re.findall(r"(?m)^INSTRUMENTATION_STATUS: numtests=([0-9]{1,6})\s*$", output)
    status_codes = re.findall(r"(?m)^INSTRUMENTATION_STATUS_CODE: (-?[0-9]{1,4})\s*$", output)
    code = status_codes[-1] if status_codes else "UNKNOWN"
    method_seen = class_seen and test_seen

    exception_types = re.findall(
        r"(?m)^INSTRUMENTATION_STATUS: stack=([A-Za-z_$][A-Za-z0-9_.$]*)(?=[:\s]|$)",
        output,
    )
    exception_type = ""
    if exception_types:
        candidate = exception_types[-1].rsplit(".", 1)[-1]
        if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]{0,79}", candidate):
            exception_type = candidate

    frames: list[str] = []
    for match in re.finditer(
        r"(?m)^[ \t]*at ([A-Za-z_$][A-Za-z0-9_.$]*)\.([A-Za-z_$][A-Za-z0-9_$<>]*)"
        r"\(([^()/\\\s:]+):([0-9]{1,7})\)[ \t]*$",
        output,
    ):
        class_name, method_name, filename, line = match.groups()
        if any(class_name.startswith(prefix) for prefix in SAFE_FRAME_PREFIXES) and filename.endswith((".kt", ".java")):
            safe_frame = class_name + "." + method_name + "(" + filename + ":" + line + ")"
            if safe_frame not in frames:
                frames.append(safe_frame)
        if len(frames) == 6:
            break

    if runner_exit in (124, 137, 143):
        category = "TIMEOUT"
    elif "POSITION_NOT_OBSERVABLE" in output:
        category = "POSITION_NOT_OBSERVABLE"
    elif "-2" in status_codes:
        if exception_type in {"AssertionError", "ComparisonFailure", "AssertionFailedError"}:
            category = "ASSERTION_FAILURE"
        else:
            category = "TEST_EXCEPTION"
    elif runner_exit != 0:
        category = "RUNNER_ERROR"
    elif not method_seen or (numtests and numtests[-1] == "0"):
        category = "TEST_NOT_OBSERVED"
    elif "OK (1 test)" in output and "ANDROID_SOURCE_SWITCH|" not in output:
        category = "EVIDENCE_NOT_EMITTED"
    else:
        category = "INSTRUMENTATION_INCOMPLETE"

    if category not in SAFE_DIAGNOSTIC_CATEGORIES:
        category = "INSTRUMENTATION_INCOMPLETE"
    return {
        "category": category,
        "methodStatus": "SEEN" if method_seen else "NOT_SEEN",
        "junitTests": numtests[-1] if numtests else "UNKNOWN",
        "statusCode": code,
        "frames": ",".join(frames),
        "exceptionType": exception_type,
    }


def sanitized_summary(
    method: str,
    fields: dict[str, str],
    passed: bool,
    diagnosis: dict[str, str] | None = None,
    fixture_diagnostic: dict[str, str] | None = None,
    reader_view_diagnostics: list[dict[str, str]] | None = None,
    reader_selector_diagnostics: list[dict[str, str]] | None = None,
) -> str:
    result = "PASS" if passed else "FAIL"
    lines = [
        "ANDROID_SOURCE_SWITCH_RESULT|method=" + method + "|scenario=" + METHOD_SCENARIOS[method] + "|outcome=" + result,
    ]
    if passed:
        # Whitelist only numeric fixture observations and fixed state tokens.
        page_count = fields.get("pageCount", "UNKNOWN")
        position = fields.get("positionIndex", "UNKNOWN")
        lines.append("ANDROID_SOURCE_SWITCH_RESULT|pages=LOADED|pageCount=" + page_count + "|position=OBSERVABLE|positionIndex=" + position)
        safe_keys = (
            "sourceAPageCount", "sourceBPageCount", "positionBefore", "positionAfter", "historyRows",
            "session", "sourceAHealthy", "sourceBPending", "cancelled", "lateResponsesReleased",
        )
        safe_fields = [
            key + "=" + fields[key]
            for key in safe_keys
            if key in fields and re.fullmatch(r"[A-Za-z0-9_-]{1,40}", fields[key])
        ]
        if safe_fields:
            lines.append("ANDROID_SOURCE_SWITCH_RESULT|" + "|".join(safe_fields))
    else:
        lines.append("ANDROID_SOURCE_SWITCH_RESULT|evidence=NOT_PROVEN")
        if diagnosis is not None:
            lines.append(
                "ANDROID_SOURCE_SWITCH_DIAGNOSTIC|category=" + diagnosis["category"]
                + "|methodStatus=" + diagnosis["methodStatus"]
                + "|junitTests=" + diagnosis["junitTests"]
                + "|statusCode=" + diagnosis["statusCode"]
            )
            if diagnosis["frames"]:
                lines.append("ANDROID_SOURCE_SWITCH_DIAGNOSTIC|frames=" + diagnosis["frames"])
            if diagnosis.get("exceptionType"):
                lines.append("ANDROID_SOURCE_SWITCH_DIAGNOSTIC|exceptionType=" + diagnosis["exceptionType"])
    if fixture_diagnostic is not None:
        if "evidence" in fixture_diagnostic:
            lines.append(
                "ANDROID_SOURCE_SWITCH_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY|evidence="
                + fixture_diagnostic["evidence"]
            )
        else:
            lines.append(
                "ANDROID_SOURCE_SWITCH_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY|selector="
                + fixture_diagnostic["selector"]
                + "|aSearch=" + fixture_diagnostic["aSearch"]
                + "|aInventory=" + fixture_diagnostic["aInventory"]
                + "|aPages=" + fixture_diagnostic["aPages"]
                + "|bSearch=" + fixture_diagnostic["bSearch"]
                + "|bInventory=" + fixture_diagnostic["bInventory"]
                + "|bPages=" + fixture_diagnostic["bPages"]
                + "|bHeld=" + fixture_diagnostic["bHeld"]
            )
    for record in reader_view_diagnostics or []:
        if "evidence" in record:
            lines.append("ANDROID_SOURCE_SWITCH_READER_VIEW|evidence=" + record["evidence"])
        else:
            lines.append(
                "ANDROID_SOURCE_SWITCH_READER_VIEW|"
                + "|".join(
                    key + "=" + record[key]
                    for key in (*READER_VIEW_DIAGNOSTIC_KEYS, *READER_VIEW_OPTIONAL_KEYS)
                    if key in record
                )
            )
    for record in reader_selector_diagnostics or []:
        if "evidence" in record:
            lines.append("ANDROID_SOURCE_SWITCH_READER_SELECTOR|evidence=" + record["evidence"])
        else:
            interpretation = "|interpretation=INCONCLUSIVE" if record["state"] == "UNKNOWN" else ""
            lines.append(
                "ANDROID_SOURCE_SWITCH_READER_SELECTOR|"
                + "|".join(key + "=" + record[key] for key in READER_SELECTOR_DIAGNOSTIC_KEYS)
                + interpretation
            )
    return "\n".join(lines) + "\n"


def write_junit(path: Path, method: str, passed: bool, diagnosis: dict[str, str] | None = None) -> None:
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
        category = diagnosis["category"] if diagnosis else "INSTRUMENTATION_INCOMPLETE"
        error = ET.SubElement(case, "error", attrib={"message": category})
        frames = diagnosis["frames"] if diagnosis else ""
        error.text = "Sanitized category=" + category + ("; frames=" + frames if frames else "; source frame unavailable")
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("runner_output", type=Path)
    parser.add_argument("method", choices=METHOD_SCENARIOS)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--runner-exit", type=int, default=0)
    args = parser.parse_args(argv)

    output = args.runner_output.read_text(encoding="utf-8", errors="replace")
    fields: dict[str, str] = {}
    error = None
    try:
        fields = verify(output, args.method)
    except ReaderSourceSwitchVerificationError:
        error = ReaderSourceSwitchVerificationError("Instrumented test or evidence failed validation")
    if error is None and args.runner_exit != 0:
        error = ReaderSourceSwitchVerificationError("Instrumentation runner exited unsuccessfully")
    diagnosis = None if error is None else _failure_diagnosis(output, args.method, args.runner_exit)
    fixture_diagnostic = _fixture_diagnostic(output, args.method)
    reader_view_diagnostics = _reader_view_diagnostics(output)
    reader_selector_diagnostics = _reader_selector_diagnostics(output)
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        sanitized_summary(
            args.method,
            fields,
            error is None,
            diagnosis,
            fixture_diagnostic,
            reader_view_diagnostics,
            reader_selector_diagnostics,
        ),
        encoding="utf-8",
    )
    write_junit(args.junit, args.method, error is None, diagnosis)
    if error is not None:
        print("::error::Reader source-switch test evidence was not accepted", file=sys.stderr)
        print(args.summary.read_text(encoding="utf-8"), end="")
        return 1
    print(args.summary.read_text(encoding="utf-8"), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
