#!/usr/bin/env python3
"""Sanitize and fail closed on the bounded MangaBall Android journey output."""
from __future__ import annotations

import re
import sys
from pathlib import Path

JOURNEY_CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaBallRealReadingJourneyInstrumentedTest"
FIXTURE_METHOD = "loadsRealExtensionAndRegistersInternalSourcesAndDisabledPeerIsExcluded"
LIVE_METHOD = "optionalLivePtBrReadingJourney"
JOURNEY_STAGES = (
    "EXTENSION_INSTALL",
    "SOURCE_REGISTRATION",
    "SOURCE_ELIGIBILITY",
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
SOURCE_ID = 35546023386335815
MANGABALL_REFERENCE_PATH = "/title-detail/one-punch-man-68515501702284f83417844d/"

OUTCOMES = frozenset(("PASS", "FAIL", "INCONCLUSIVE", "NOT_RUN"))
CATEGORIES = frozenset((
    "NONE", "EXTENSION_LOADED", "SOURCE_REGISTERED", "DISABLED_SOURCE_EXCLUDED",
    "NO_RESULTS", "SOURCE_DISABLED", "SOURCE_NOT_FOUND", "SOURCE_TYPE_MISMATCH",
    "AMBIGUOUS", "LOW_CONFIDENCE", "REFERENCE_NOT_FOUND", "REFERENCE_AMBIGUOUS",
    "EXACT_REFERENCE", "AUTO_SELECTED_EXACT_REFERENCE", "CONFIRMED_EXACT_REFERENCE",
    "HTTP_403", "HTTP_429", "HTTP_5XX", "HTTP_OTHER", "NETWORK", "TIMEOUT", "CAPTCHA",
    "MALFORMED", "EXTENSION", "BINDING", "MATERIALIZATION", "INVENTORY_EMPTY",
    "RECONCILIATION", "CONTENT_UNAVAILABLE", "READER_PREPARATION", "IDENTITY",
    "INSTRUMENTATION", "INDETERMINATE", "PERSISTENCE", "SOURCE_QUERY_MISMATCH",
    "NOT_RUN_AFTER_BLOCKER", "SUCCESS",
))
EVENT_PREFIX = "INSTRUMENTATION_STATUS: stream=MANGABALL_E2E|"
SEARCH_FAILURE_PREFIX = "INSTRUMENTATION_STATUS: stream=MANGABALL_SEARCH_FAILURE|"
SEARCH_FAILURE_FIELDS = frozenset(("failureStage", "failureKind", "httpStatus", "sourceKind", "causeClass"))
SEARCH_FAILURE_STAGES = frozenset(("ADDON_DISCOVERY", "SEARCH", "MATERIALIZATION", "PERSISTENCE", "UNKNOWN"))
SEARCH_FAILURE_KINDS = frozenset((
    "ADDON_NOT_INSTALLED", "ADDON_DISABLED", "NO_ENABLED_SOURCES", "SOURCE_DISABLED",
    "SOURCE_UNAVAILABLE", "HTTP_RESPONSE", "NETWORK_FAILURE", "TIMEOUT", "CAPTCHA_REQUIRED",
    "MALFORMED_RESPONSE", "EXTENSION_FAILURE", "INDETERMINATE", "UNKNOWN",
))
SEARCH_SOURCE_KINDS = frozenset((
    "NONE", "SOURCE_DISABLED", "SOURCE_UNAVAILABLE", "HTTP_RESPONSE", "NETWORK_FAILURE",
    "TIMEOUT", "CAPTCHA_REQUIRED", "MALFORMED_RESPONSE", "EXTENSION_FAILURE", "INDETERMINATE",
))
SEARCH_CAUSE_CLASSES = frozenset((
    "HTTP_EXCEPTION", "SSL_EXCEPTION", "SOCKET_TIMEOUT", "UNKNOWN_HOST", "CONNECT_EXCEPTION",
    "SOCKET_EXCEPTION", "IO_EXCEPTION", "SECURITY_EXCEPTION", "ILLEGAL_STATE", "NULL_POINTER", "OTHER",
))
EVENT_FIELDS = frozenset(("stage", "outcome", "category", "count", "sourceId", "language", "elapsedMs"))
SAFE_LANGUAGE = re.compile(r"^[A-Za-z0-9-]{1,16}$")
SAFE_INTEGER = re.compile(r"^\d{1,20}$")


class MangaBallReportError(ValueError):
    pass


def event(
    stage: str,
    outcome: str,
    *,
    category: str | None = None,
    count: int | None = None,
    source_id: int | None = None,
    language: str | None = None,
    elapsed_ms: int | None = None,
) -> str:
    """Build the exact AndroidJUnitRunner status line for verifier fixtures."""
    values: list[tuple[str, str | None]] = [
        ("stage", stage),
        ("outcome", outcome),
        ("category", category),
        ("count", str(count) if count is not None else None),
        ("sourceId", str(source_id) if source_id is not None else None),
        ("language", language),
        ("elapsedMs", str(elapsed_ms) if elapsed_ms is not None else None),
    ]
    return EVENT_PREFIX + "|".join(key + "=" + value for key, value in values if value is not None)


def _parse_event(line: str) -> dict[str, str] | None:
    line = line.strip()
    if "stream=MANGABALL_E2E|" not in line:
        return None
    if not line.startswith(EVENT_PREFIX):
        raise MangaBallReportError("Malformed MangaBall journey event")
    result: dict[str, str] = {}
    for item in line[len(EVENT_PREFIX):].split("|"):
        key, separator, value = item.partition("=")
        if not separator or key not in EVENT_FIELDS or key in result or not value:
            raise MangaBallReportError("Malformed or unallowlisted MangaBall event field")
        result[key] = value
    if set(result).isdisjoint({"stage"}) or not {"stage", "outcome"} <= result.keys():
        raise MangaBallReportError("Incomplete MangaBall journey event")
    if result["stage"] not in JOURNEY_STAGES or result["outcome"] not in OUTCOMES:
        raise MangaBallReportError("Unknown MangaBall journey stage or outcome")
    if "category" in result and result["category"] not in CATEGORIES:
        raise MangaBallReportError("Unknown MangaBall journey category")
    for key in ("count", "sourceId", "elapsedMs"):
        if key in result and not SAFE_INTEGER.fullmatch(result[key]):
            raise MangaBallReportError("Unsafe numeric MangaBall event value")
    if "language" in result and not SAFE_LANGUAGE.fullmatch(result["language"]):
        raise MangaBallReportError("Unsafe MangaBall language value")
    if "sourceId" in result and int(result["sourceId"]) <= 0:
        raise MangaBallReportError("Invalid MangaBall source identifier")
    return result


def _parse_search_failure(line: str) -> dict[str, str] | None:
    line = line.strip()
    if "stream=MANGABALL_SEARCH_FAILURE|" not in line:
        return None
    if not line.startswith(SEARCH_FAILURE_PREFIX):
        raise MangaBallReportError("Malformed sanitized MangaBall search failure")
    fields: dict[str, str] = {}
    for item in line[len(SEARCH_FAILURE_PREFIX):].split("|"):
        key, separator, value = item.partition("=")
        if not separator or not value or key not in SEARCH_FAILURE_FIELDS or key in fields:
            raise MangaBallReportError("Unallowlisted MangaBall search failure field")
        fields[key] = value
    if set(fields) != SEARCH_FAILURE_FIELDS:
        raise MangaBallReportError("Incomplete sanitized MangaBall search failure")
    if fields["failureStage"] not in SEARCH_FAILURE_STAGES:
        raise MangaBallReportError("Unknown sanitized MangaBall failure stage")
    if fields["failureKind"] not in SEARCH_FAILURE_KINDS:
        raise MangaBallReportError("Unknown sanitized MangaBall failure kind")
    if fields["sourceKind"] not in SEARCH_SOURCE_KINDS:
        raise MangaBallReportError("Unknown sanitized MangaBall source failure")
    if fields["causeClass"] not in SEARCH_CAUSE_CLASSES:
        raise MangaBallReportError("Unknown sanitized MangaBall exception category")
    status = fields["httpStatus"]
    if status != "NONE" and (not SAFE_INTEGER.fullmatch(status) or not 100 <= int(status) <= 599):
        raise MangaBallReportError("Unsafe sanitized MangaBall HTTP status")
    return fields


def _junit_method_ok(output: str, method: str) -> None:
    for label, wanted in (("class", JOURNEY_CLASS), ("test", method), ("numtests", "1")):
        if not re.search(r"(?m)^INSTRUMENTATION_STATUS: " + label + r"=" + re.escape(wanted) + r"\s*$", output):
            raise MangaBallReportError("Expected one completed MangaBall AndroidJUnitRunner method")
    if not re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output):
        raise MangaBallReportError("MangaBall AndroidJUnitRunner did not complete successfully")
    if not re.search(r"(?m)^OK \(1 test\)\s*$", output):
        raise MangaBallReportError("Zero-test or failing MangaBall instrumentation is not accepted")


def _verify_database_cleanup(output: str) -> None:
    context = (
        "INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT|applicationContext=present"
        "|storage=TARGET_DISPOSABLE|databaseContext=wrapped"
    )
    context_count = output.splitlines().count(context)
    if context_count > 1:
        raise MangaBallReportError("Journey did not prove one disposable target database")
    if context_count == 0:
        if any("stream=RUNTIME_SETUP|phase=" + phase in output for phase in ("DRIVER_CLOSE", "DATABASE_CLEANUP")):
            raise MangaBallReportError("Database cleanup evidence exists without disposable database setup")
        return
    for phase in ("DRIVER_CLOSE", "DATABASE_CLEANUP"):
        line = "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=" + phase + "|outcome=PASS"
        if output.splitlines().count(line) != 1:
            raise MangaBallReportError("Journey did not prove disposable database cleanup")


def _parse_fixture(output: str) -> str:
    prefix = "INSTRUMENTATION_STATUS: stream=MANGABALL_FIXTURE|"
    lines = [line.strip() for line in output.splitlines() if "stream=MANGABALL_FIXTURE|" in line]
    expected = (
        prefix + "outcome=PASS|addonGroups=1|internalSources=42|eligibleBefore=42|eligibleAfter=41"
        "|ptBrSourceId=" + str(SOURCE_ID) + "|disabledPeerExcluded=true"
    )
    if lines != [expected]:
        raise MangaBallReportError("MangaBall fixture/source eligibility evidence was incomplete")
    return (
        "DIAGNOSTIC|fixture|outcome=PASS|addonGroups=1|internalSources=42"
        "|eligibleBefore=42|eligibleAfter=41|ptBrSourceId=" + str(SOURCE_ID) + "|disabledPeerExcluded=true"
    )


def verify_and_summarize(output: str, method: str) -> list[str]:
    if method not in (FIXTURE_METHOD, LIVE_METHOD):
        raise MangaBallReportError("Unexpected MangaBall instrumentation method")
    safe_lines: list[str] = []
    parsed_events: list[dict[str, str]] = []
    search_failures: list[dict[str, str]] = []
    for raw in output.splitlines():
        failure = _parse_search_failure(raw)
        if failure is not None:
            search_failures.append(failure)
        parsed = _parse_event(raw)
        if parsed is not None:
            parsed_events.append(parsed)
            safe = "DIAGNOSTIC|stage=" + parsed["stage"] + "|outcome=" + parsed["outcome"]
            for key in ("category", "count", "sourceId", "language", "elapsedMs"):
                if key in parsed:
                    safe += "|" + key + "=" + parsed[key]
            safe_lines.append(safe)

    _junit_method_ok(output, method)
    if method == FIXTURE_METHOD:
        if parsed_events or search_failures:
            raise MangaBallReportError("Offline fixture test must not emit or imply live journey events")
        fixture = _parse_fixture(output)
        return [fixture, "DIAGNOSTIC|providerCalls=0"]

    _verify_database_cleanup(output)
    if len(parsed_events) != len(JOURNEY_STAGES):
        raise MangaBallReportError("MangaBall journey did not report every stage exactly once")
    if [event["stage"] for event in parsed_events] != list(JOURNEY_STAGES):
        raise MangaBallReportError("MangaBall journey stages were missing, duplicated, or out of order")

    if len(search_failures) > 1:
        raise MangaBallReportError("Duplicate sanitized MangaBall search failure")
    search_event = parsed_events[JOURNEY_STAGES.index("LIVE_SEARCH")]
    if search_failures and search_event["outcome"] != "INCONCLUSIVE":
        raise MangaBallReportError("Search failure evidence without inconclusive search")
    if (
        search_event["outcome"] == "INCONCLUSIVE"
        and search_event.get("category") == "INSTRUMENTATION"
        and not search_failures
    ):
        raise MangaBallReportError("Indeterminate search requires sanitized cause evidence")
    if search_failures:
        failure = search_failures[0]
        safe_lines.append(
            "DIAGNOSTIC|searchFailureStage=" + failure["failureStage"]
            + "|searchFailureKind=" + failure["failureKind"]
            + "|httpStatus=" + failure["httpStatus"]
            + "|sourceKind=" + failure["sourceKind"]
            + "|causeClass=" + failure["causeClass"]
        )

    outcomes = [event["outcome"] for event in parsed_events]
    blockers = [index for index, outcome in enumerate(outcomes) if outcome in ("FAIL", "INCONCLUSIVE")]
    if not blockers:
        if any(outcome != "PASS" for outcome in outcomes):
            raise MangaBallReportError("Invalid terminal MangaBall journey outcome")
        safe_lines.append("DIAGNOSTIC|journey=PASS")
        return safe_lines
    if len(blockers) != 1:
        raise MangaBallReportError("MangaBall journey contains contradictory terminal stage outcomes")
    blocker = blockers[0]
    if outcomes[blocker + 1:] != ["NOT_RUN"] * (len(outcomes) - blocker - 1):
        raise MangaBallReportError("MangaBall journey continued after its first blocker")
    if any(outcome != "PASS" for outcome in outcomes[:blocker]):
        raise MangaBallReportError("MangaBall journey has an invalid pre-blocker outcome")
    if outcomes[blocker] == "FAIL":
        raise MangaBallReportError("MangaBall journey contains a verified failure")
    safe_lines.append("DIAGNOSTIC|journey=INCONCLUSIVE")
    return safe_lines


def main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[1] not in (FIXTURE_METHOD, LIVE_METHOD):
        print("usage: mangaball_android_report.py <test-method> <runner-output>", file=sys.stderr)
        return 2
    output = Path(argv[2]).read_text(encoding="utf-8", errors="replace")
    try:
        summary = verify_and_summarize(output, argv[1])
    except MangaBallReportError as error:
        # Print only an allowlisted generic category; never propagate the runner's message.
        safe_partial: list[str] = []
        try:
            for raw in output.splitlines():
                _parse_search_failure(raw)
                parsed = _parse_event(raw)
                if parsed is not None:
                    safe = "DIAGNOSTIC|stage=" + parsed["stage"] + "|outcome=" + parsed["outcome"]
                    for key in ("category", "count", "sourceId", "language", "elapsedMs"):
                        if key in parsed:
                            safe += "|" + key + "=" + parsed[key]
                    safe_partial.append(safe)
        except MangaBallReportError:
            pass
        for line in safe_partial:
            print(line)
        print("DIAGNOSTIC|verification=REJECTED")
        print("::error::MangaBall evidence rejected: " + _safe_error_category(error))
        return 1
    for line in summary:
        print(line)
    return 0


def _safe_error_category(error: MangaBallReportError) -> str:
    message = str(error)
    known = (
        "Expected one completed", "AndroidJUnitRunner did not complete", "Zero-test",
        "disposable target database", "disposable database cleanup", "fixture/source eligibility",
        "Offline fixture", "every stage exactly once", "missing, duplicated, or out of order",
        "contradictory terminal", "continued after", "pre-blocker outcome", "verified failure",
        "Malformed", "Unallowlisted", "Incomplete", "Unknown", "Unsafe numeric", "Unsafe MangaBall",
        "Invalid MangaBall", "Unexpected MangaBall", "journey event",
    )
    return "REPORT_VALIDATION" if any(prefix in message for prefix in known) else "REPORT_VALIDATION"


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
