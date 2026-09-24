#!/usr/bin/env python3
"""Fail closed if Android instrumentation did not actually run a specific test."""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireFixtureInstrumentedTest"
E2E_CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireRealReadingJourneyInstrumentedTest"
METHOD_CLASS = {
    "loadsRealExtensionAndRegistersInternalSources": CLASS,
    "optionalLiveEnglishSearch": CLASS,
    "optionalRealEnglishReadingJourney": E2E_CLASS,
    "instrumentationContextDatabasePersistsCanonicalTitle": E2E_CLASS,
    "instrumentationDatabaseIdentityProbe": E2E_CLASS,
}
ALLOWED = frozenset((
    "loadsRealExtensionAndRegistersInternalSources",
    "optionalLiveEnglishSearch",
    "optionalRealEnglishReadingJourney",
    "instrumentationContextDatabasePersistsCanonicalTitle",
    "instrumentationDatabaseIdentityProbe",
))
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
E2E_OUTCOMES = frozenset(("PASS", "FAIL", "INCONCLUSIVE", "NOT_RUN"))
CONTEXT_DB_PHASES = ("DRIVER_CREATE", "DATABASE_CREATE", "TITLE_INSERT", "TITLE_READ", "DRIVER_CLOSE")
SCHEMA_EVENT = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA"
    r"\|outcome=PASS\|titles=(present|missing)\|outbox=(present|missing)"
    r"\|dirtyInsertTrigger=(present|missing)$"
)
E2E_CATEGORIES = frozenset((
    "NONE", "NO_RESULTS", "SOURCE_DISABLED", "AMBIGUOUS", "LOW_CONFIDENCE", "HTTP_403", "HTTP_429",
    "HTTP_5XX", "HTTP_OTHER", "NETWORK", "TIMEOUT", "CAPTCHA", "MALFORMED",
    "EXTENSION", "BINDING", "IDENTITY", "INVENTORY_EMPTY", "RECONCILIATION",
    "CONTENT_UNAVAILABLE", "READER_PREPARATION", "INSTRUMENTATION", "CANCELLED", "MATERIALIZATION",
    "UNIQUE_REFERENCE_MATCH", "EXACT_REFERENCE", "SOURCE_NOT_FOUND", "SOURCE_REGISTRATION_TIMEOUT",
    "SOURCE_TYPE_MISMATCH",
))
REPORTS = Path(".github/results/mangafire")

class AndroidTestVerificationError(ValueError):
    pass


def verify(output: str, method: str) -> None:
    if method not in ALLOWED:
        raise AndroidTestVerificationError("Unexpected instrumentation method")
    for label, wanted in (("class", METHOD_CLASS[method]), ("test", method), ("numtests", "1")):
        if not re.search(r"(?m)^INSTRUMENTATION_STATUS: " + label + "=" + re.escape(wanted) + r"\s*$", output):
            raise AndroidTestVerificationError("Expected instrumentation " + label + " not observed")
    if not re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output):
        raise AndroidTestVerificationError("Android instrumentation did not complete successfully")
    if not re.search(r"(?m)^OK \(1 test\)\s*$", output):
        raise AndroidTestVerificationError("Zero-test or failing instrumentation is not green")
    if method == "optionalRealEnglishReadingJourney":
        verify_e2e_stages(output)
    elif method == "instrumentationContextDatabasePersistsCanonicalTitle":
        verify_context_database_diagnostic(output)
    elif method == "instrumentationDatabaseIdentityProbe":
        verify_database_identity_probe(output)


def verify_database_identity_probe(output: str) -> None:
    pattern = re.compile(
        r"^INSTRUMENTATION_STATUS: stream=RUNTIME_DB_IDENTITY"
        r"\|processIsTestUid=(true|false|unknown)"
        r"\|processIsTargetUid=(true|false|unknown)"
        r"\|testDbParentWritable=(true|false|unknown)"
        r"\|testDbParentState=(EXISTS|MISSING|NOT_DIRECTORY|ERROR)"
        r"\|targetDbParentWritable=(true|false|unknown)"
        r"\|targetDbParentState=(EXISTS|MISSING|NOT_DIRECTORY|ERROR)"
        r"\|testDataDirWritable=(true|false|unknown)"
        r"\|testDataDirExecutable=(true|false|unknown)"
        r"\|testDataDirState=(EXISTS|MISSING|NOT_DIRECTORY|ERROR)"
        r"\|targetDataDirWritable=(true|false|unknown)"
        r"\|targetDataDirExecutable=(true|false|unknown)"
        r"\|targetDataDirState=(EXISTS|MISSING|NOT_DIRECTORY|ERROR)$"
    )
    events = [line for line in output.splitlines() if "RUNTIME_DB_IDENTITY|" in line]
    if len(events) != 1 or not pattern.fullmatch(events[0]):
        raise AndroidTestVerificationError("Expected one sanitized database identity observation")
    path_probe = re.compile(
        r"^INSTRUMENTATION_STATUS: stream=RUNTIME_DB_PATH_PROBE"
        r"\|outcome=(PREEXISTING|CREATED|PARENT_MISSING|PARENT_NOT_DIRECTORY|SECURITY_ERROR|IO_ERROR|OTHER_ERROR)"
        r"\|parentState=(EXISTS|MISSING|NOT_DIRECTORY|ERROR)"
        r"\|parentWritable=(true|false|unknown)"
        r"\|parentExecutable=(true|false|unknown)"
        r"\|cleanup=(NOT_NEEDED|REMOVED|NOT_REMOVED|NOT_EMPTY|DELETE_FAILED|PARTIAL|UNKNOWN)$"
    )
    path_events = [line for line in output.splitlines() if "RUNTIME_DB_PATH_PROBE|" in line]
    if len(path_events) != 1 or not path_probe.fullmatch(path_events[0]):
        raise AndroidTestVerificationError("Expected one sanitized database path creation observation")


def verify_context_database_diagnostic(output: str) -> None:
    context_pattern = re.compile(
        r"^INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT"
        r"\|applicationContext=(present|null)"
        r"\|targetIsolation=isolated"
        r"\|databaseContext=(raw|wrapped)$"
    )
    context_events = [line for line in output.splitlines() if "RUNTIME_CONTEXT|" in line]
    if len(context_events) != 1 or not context_pattern.fullmatch(context_events[0]):
        raise AndroidTestVerificationError("Expected one sanitized context-isolation observation")
    phase_events = [
        line for line in output.splitlines()
        if "RUNTIME_SETUP|phase=" in line
        and any("|phase=" + phase + "|" in line for phase in CONTEXT_DB_PHASES)
    ]
    expected = [
        "INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP|phase=" + phase + "|outcome=PASS"
        for phase in CONTEXT_DB_PHASES
    ]
    if phase_events != expected:
        raise AndroidTestVerificationError("Isolated database diagnostic phases were not proven exactly once")
    schema_events = [line for line in output.splitlines() if "RUNTIME_SCHEMA|" in line]
    if len(schema_events) != 1 or not SCHEMA_EVENT.fullmatch(schema_events[0]):
        raise AndroidTestVerificationError("Expected one sanitized database schema observation")
    ordered_events = [line for line in output.splitlines() if "RUNTIME_SCHEMA|" in line or "RUNTIME_SETUP|phase=" in line]
    if not (
        ordered_events.index(expected[1]) < ordered_events.index(schema_events[0]) <
        ordered_events.index(expected[2])
    ):
        raise AndroidTestVerificationError("Schema observation must precede title persistence")


def verify_e2e_stages(output: str) -> None:
    seen: dict[str, tuple[str, str]] = {}
    marker = "INSTRUMENTATION_STATUS: stream=RUNTIME_E2E|"
    allowed_fields = frozenset(("stage", "outcome", "category", "count", "sourceId", "language", "elapsedMs"))
    safe_language = re.compile(r"^[A-Za-z0-9-]{1,16}$")
    safe_count_or_elapsed = re.compile(r"^\d{1,10}$")
    safe_source_id = re.compile(r"^\d{1,20}$")
    for line in output.splitlines():
        if "RUNTIME_E2E|" not in line:
            continue
        if not line.startswith(marker):
            raise AndroidTestVerificationError("Malformed runtime E2E stage event")
        fields: dict[str, str] = {}
        for component in line[len(marker):].split("|"):
            if "=" not in component:
                raise AndroidTestVerificationError("Malformed runtime E2E stage event")
            key, value = component.split("=", 1)
            if key not in allowed_fields or key in fields:
                raise AndroidTestVerificationError("Unexpected runtime E2E event field")
            fields[key] = value
        stage = fields.get("stage", "")
        outcome = fields.get("outcome", "")
        category = fields.get("category", "NONE")
        if stage not in E2E_STAGES or outcome not in E2E_OUTCOMES or category not in E2E_CATEGORIES:
            raise AndroidTestVerificationError("Unrecognized runtime E2E stage event")
        if stage in seen:
            raise AndroidTestVerificationError("Duplicate runtime E2E stage event")
        for key, pattern in (
            ("count", safe_count_or_elapsed),
            ("sourceId", safe_source_id),
            ("elapsedMs", safe_count_or_elapsed),
        ):
            if key in fields and not pattern.fullmatch(fields[key]):
                raise AndroidTestVerificationError("Invalid numeric runtime E2E field")
        if "language" in fields and not safe_language.fullmatch(fields["language"]):
            raise AndroidTestVerificationError("Invalid language runtime E2E field")
        if "category" not in fields and outcome != "PASS":
            raise AndroidTestVerificationError("Non-pass runtime E2E event lacks category")
        if outcome == "PASS" and category not in ("NONE", "UNIQUE_REFERENCE_MATCH", "EXACT_REFERENCE"):
            raise AndroidTestVerificationError("Passing runtime E2E stage has a failure category")
        seen[stage] = (outcome, category)
    if tuple(seen.keys()) != E2E_STAGES:
        raise AndroidTestVerificationError("Required runtime E2E stages are missing or out of order")
    if any(outcome != "PASS" for outcome, _ in seen.values()):
        raise AndroidTestVerificationError("Runtime E2E includes a non-pass required stage")


def write_verified_report(method: str) -> Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    test_class = METHOD_CLASS[method]
    suite = ET.Element("testsuite", attrib={"name": test_class, "tests": "1", "failures": "0", "errors": "0", "skipped": "0"})
    ET.SubElement(suite, "testcase", attrib={"classname": test_class, "name": method})
    path = REPORTS / ("TEST-" + method + ".xml")
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
    return path


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_instrumentation.py <runner-output> <method>")
    try:
        verify(Path(sys.argv[1]).read_text(encoding="utf-8"), sys.argv[2])
    except AndroidTestVerificationError as error:
        print("::error::MangaFire instrumentation not proven: " + str(error), file=sys.stderr)
        raise SystemExit(1) from None
    print("Verified AndroidJUnitRunner method: " + sys.argv[2])
    print(write_verified_report(sys.argv[2]))
