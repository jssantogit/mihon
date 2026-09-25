#!/usr/bin/env python3
"""Accept only an exact, passing Android navigation JUnit method and sanitized evidence."""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


TEST_CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalTitleSourceNavigationInstrumentedTest"
METHOD_SCENARIOS = {
    "coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce": (
        "COLD_READER",
        "SAME_INSTANCE",
    ),
    "warmReaderDiscoveryReusesMainActivityAndOpensCanonicalTitleBindingSheetOnce": (
        "WARM_READER",
        "SAME_INSTANCE",
    ),
    "invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences": (
        "INVALID_INTENT",
        "REJECTED",
    ),
}
OBSERVATION_FIELDS = {
    "COLD_READER": {
        "BINDING_SHEET": {"ONE", "MISSING", "DUPLICATE"},
        "CONTENT_SELECTOR": {"REVEALED", "MISSING"},
        "READER_ACTIVITY": {"SAME_INSTANCE", "REPLACED", "MISSING"},
        "READER_CHAPTER": {"MATCH", "MISMATCH"},
        "READER_CHAPTER_ACTIVE": {"MATCH", "MISMATCH"},
        "READER_CHAPTER_RESTORED": {"MATCH", "MISMATCH"},
        "READER_CHAPTER_RETURNED": {"MATCH", "MISMATCH"},
        "READER_INSTANCE": {"PRESERVED", "REPLACED"},
        "READER_LIFECYCLE": {"CREATED", "DESTROYED", "INITIALIZED", "RESUMED", "STARTED"},
        "READER_TASK_ACTIVE": {"SAME", "DIFFERENT"},
        "READER_TASK_RETURNED": {"SAME", "DIFFERENT"},
        "RECREATE_RESULT": {"PRESERVED", "REPLACED"},
        "READER_POSITION_INITIAL": {"OBSERVABLE", "POSITION_NOT_OBSERVABLE"},
        "READER_POSITION_ACTIVE": {"PRESERVED", "CHANGED", "POSITION_NOT_OBSERVABLE"},
        "READER_POSITION_RESTORED": {"PRESERVED", "CHANGED", "POSITION_NOT_OBSERVABLE"},
        "READER_POSITION_RETURNED": {"PRESERVED", "CHANGED", "POSITION_NOT_OBSERVABLE"},
    },
    "WARM_READER": {
        "BINDING_SHEET": {"ONE", "MISSING", "DUPLICATE"},
        "CONTENT_SELECTOR": {"REVEALED", "MISSING"},
        "READER_ACTIVITY": {"SAME_INSTANCE", "REPLACED", "MISSING"},
        "READER_CHAPTER": {"MATCH", "MISMATCH"},
        "READER_CHAPTER_ACTIVE": {"MATCH", "MISMATCH"},
        "READER_CHAPTER_RESTORED": {"MATCH", "MISMATCH"},
        "READER_CHAPTER_RETURNED": {"MATCH", "MISMATCH"},
        "READER_INSTANCE": {"PRESERVED", "REPLACED"},
        "READER_LIFECYCLE": {"CREATED", "DESTROYED", "INITIALIZED", "RESUMED", "STARTED"},
        "READER_TASK_ACTIVE": {"SAME", "DIFFERENT"},
        "READER_TASK_RETURNED": {"SAME", "DIFFERENT"},
        "RECREATE_RESULT": {"PRESERVED", "REPLACED"},
        "READER_POSITION_INITIAL": {"OBSERVABLE", "POSITION_NOT_OBSERVABLE"},
        "READER_POSITION_ACTIVE": {"PRESERVED", "CHANGED", "POSITION_NOT_OBSERVABLE"},
        "READER_POSITION_RESTORED": {"PRESERVED", "CHANGED", "POSITION_NOT_OBSERVABLE"},
        "READER_POSITION_RETURNED": {"PRESERVED", "CHANGED", "POSITION_NOT_OBSERVABLE"},
    },
    "INVALID_INTENT": {
        "CANONICAL_TITLE": {"HIDDEN", "VISIBLE"},
        "BINDING_SHEET": {"CLOSED", "OPEN"},
    },
    "FIXTURE": {
        "ONBOARDING_PRECONDITION": {"COMPLETED", "INCOMPLETE"},
    },
}


class AndroidNavigationVerificationError(ValueError):
    pass


def verify(output: str, method: str) -> None:
    if method not in METHOD_SCENARIOS:
        raise AndroidNavigationVerificationError("Unexpected Android navigation test method")
    for label, wanted in (
        ("class", TEST_CLASS),
        ("test", method),
        ("numtests", "1"),
    ):
        if not re.search(
            r"(?m)^INSTRUMENTATION_STATUS: " + label + "=" + re.escape(wanted) + r"\s*$",
            output,
        ):
            raise AndroidNavigationVerificationError("Expected one named Android navigation test was not observed")
    if not re.search(r"(?m)^INSTRUMENTATION_STATUS_CODE: 0\s*$", output):
        raise AndroidNavigationVerificationError("Android navigation test did not report JUnit success")
    if not re.search(r"(?m)^OK \(1 test\)\s*$", output):
        raise AndroidNavigationVerificationError("Zero-test, skipped, or failed Android navigation is not green")
    if not re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output):
        raise AndroidNavigationVerificationError("AndroidJUnitRunner did not complete successfully")
    if "FAILURES!!!" in output:
        raise AndroidNavigationVerificationError("Android navigation JUnit reported a failure")
    cleanup_events = [
        line for line in output.splitlines()
        if line == "INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_CLEANUP|fixtureRows=DELETED"
    ]
    if len(cleanup_events) != 1:
        raise AndroidNavigationVerificationError("Disposable database fixture cleanup was not proven exactly once")

    scenario, reader_activity = METHOD_SCENARIOS[method]
    event = (
        r"^INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION\|scenario="
        + re.escape(scenario)
        + r"\|outcome=PASS\|"
        + (
            r"identity=CANONICAL\|sheetCount=1\|recreation=PASS\|closed=PASS"
            + r"\|return=READER\|readerActivity=" + re.escape(reader_activity)
            + r"\|readerChapter=CANONICAL\|task=UNCHANGED"
            + r"\|position=(?:PRESERVED|POSITION_NOT_OBSERVABLE)"
            + r"\|progress=UNCHANGED\|preferences=UNCHANGED"
            if scenario != "INVALID_INTENT"
            else r"route=REJECTED\|sheetCount=0\|progress=UNCHANGED\|preferences=UNCHANGED"
        )
        + r"$"
    )
    events = [line for line in output.splitlines() if "ANDROID_NAVIGATION|" in line]
    if len(events) != 1 or re.fullmatch(event, events[0]) is None:
        raise AndroidNavigationVerificationError("Required sanitized Android navigation evidence is missing or invalid")


def sanitized_summary(output: str, method: str, passed: bool) -> str:
    result = "PASS" if passed else "FAIL"
    lines = ["ANDROID_NAVIGATION_RESULT|method=" + method + "|outcome=" + result]
    if not passed:
        lines.append("ANDROID_NAVIGATION_RESULT|evidence=NOT_PROVEN")
        observed_one = (
            len(re.findall(r"(?m)^INSTRUMENTATION_STATUS: numtests=1\s*$", output)) == 1
            and re.search(r"(?m)^INSTRUMENTATION_STATUS: class=" + re.escape(TEST_CLASS) + r"\s*$", output) is not None
            and re.search(r"(?m)^INSTRUMENTATION_STATUS: test=" + re.escape(method) + r"\s*$", output) is not None
        )
        lines.append(
            "ANDROID_NAVIGATION_RESULT|junitTests=" + ("1" if observed_one else "0")
            + "|junitStatus=FAIL_OR_UNPROVEN"
        )
        scenario, _ = METHOD_SCENARIOS[method]
        observations = []
        pattern = re.compile(
            r"^INSTRUMENTATION_STATUS: stream=ANDROID_NAVIGATION_OBSERVATION"
            r"\|scenario=([A-Z_]+)\|checkpoint=([A-Z_]+)\|result=([A-Z_]+)$"
        )
        seen: set[str] = set()
        for raw_line in output.splitlines():
            match = pattern.fullmatch(raw_line)
            if match is None:
                continue
            observed_scenario, checkpoint, result = match.groups()
            allowed = OBSERVATION_FIELDS.get(observed_scenario, {}).get(checkpoint, set())
            if observed_scenario not in {scenario, "FIXTURE"} or result not in allowed or checkpoint in seen:
                continue
            seen.add(checkpoint)
            observations.append(
                "ANDROID_NAVIGATION_RESULT|observation=" + observed_scenario
                + "|checkpoint=" + checkpoint + "|result=" + result
            )
        lines.extend(observations)
        return "\n".join(lines) + "\n"

    scenario, reader_activity = METHOD_SCENARIOS[method]
    lines.append("ANDROID_NAVIGATION_RESULT|scenario=" + scenario + "|junitTests=1|junitFailures=0")
    lines.append("ANDROID_NAVIGATION_RESULT|fixtureRows=DELETED")
    lines.append("ANDROID_NAVIGATION_RESULT|readerContinuity=PASS|canonicalIdentity=PASS")
    if scenario == "INVALID_INTENT":
        lines.append("ANDROID_NAVIGATION_RESULT|invalidIntent=REJECTED|progress=UNCHANGED|preferences=UNCHANGED")
    else:
        position = "PRESERVED" if "|position=PRESERVED|" in output else "POSITION_NOT_OBSERVABLE"
        lines.append(
            "ANDROID_NAVIGATION_RESULT|sheet=OPENED_ONCE|recreation=PASS|close=PASS"
            "|return=READER|readerActivity=" + reader_activity + "|readerChapter=CANONICAL|task=UNCHANGED"
            "|position=" + position + "|progress=UNCHANGED|preferences=UNCHANGED"
        )
    return "\n".join(lines) + "\n"


def write_junit_report(path: Path, method: str, passed: bool, output: str) -> None:
    executed = (
        len(re.findall(r"(?m)^INSTRUMENTATION_STATUS: numtests=1\s*$", output)) == 1
        and re.search(r"(?m)^INSTRUMENTATION_STATUS: class=" + re.escape(TEST_CLASS) + r"\s*$", output) is not None
        and re.search(r"(?m)^INSTRUMENTATION_STATUS: test=" + re.escape(method) + r"\s*$", output) is not None
    )
    if not executed:
        path.unlink(missing_ok=True)
        return
    test_failed = not passed and "FAILURES!!!" in output
    suite = ET.Element(
        "testsuite",
        attrib={
            "name": TEST_CLASS,
            "tests": "1",
            "failures": "1" if test_failed else "0",
            "errors": "1" if not passed and not test_failed else "0",
            "skipped": "0",
        },
    )
    case = ET.SubElement(suite, "testcase", attrib={"classname": TEST_CLASS, "name": method})
    if not passed:
        tag = "failure" if test_failed else "error"
        failure = ET.SubElement(case, tag, attrib={"message": "Android navigation evidence not proven"})
        failure.text = "Raw runner logs intentionally omitted."
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
    passed = True
    try:
        verify(output, args.method)
    except AndroidNavigationVerificationError:
        passed = False
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(sanitized_summary(output, args.method, passed), encoding="utf-8")
    write_junit_report(args.junit, args.method, passed, output)
    print(args.summary.read_text(encoding="utf-8"), end="")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
