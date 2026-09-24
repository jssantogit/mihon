#!/usr/bin/env python3
"""Print allowlisted AndroidJUnitRunner diagnostics without sensitive raw logs."""
from __future__ import annotations

import re
import sys
from pathlib import Path

KEYS = ("class", "test", "numtests")
SAFE_JAVA_TYPE = re.compile(r"\b(?:[A-Za-z_][\w$]*\.)+(?:[A-Za-z_][\w$]*)(?:Exception|Error)\b")
SAFE_RUNNER_CLASS = re.compile(r"^INSTRUMENTATION_STATUS: (class|test|numtests)=(.*)$")
SAFE_CODE = re.compile(r"^INSTRUMENTATION_(?:STATUS_)?CODE: -?\d+\s*$")
LIVE_RESULT = re.compile(
    r"MANGAFIRE_LIVE\|outcome=(CAPTCHA_REQUIRED|HTTP_RESPONSE|NETWORK_FAILURE|TIMEOUT|INDETERMINATE|EMPTY)"
    r"(?:\|httpStatus=(\d{3}|unknown))?\|elapsedMs=(\d{1,7})"
)
E2E_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=RUNTIME_E2E\|stage="
    r"(EXTENSION_INSTALL|SOURCE_REGISTRATION|LIVE_SEARCH|CANDIDATE_IDENTIFICATION|MATCH_DECISION|"
    r"BINDING_MATERIALIZATION|BINDING_CREATE|BINDING_PERSISTENCE|INVENTORY|CHAPTER_PROBE|RECONCILIATION|CONTENT_RESOLUTION|"
    r"READER_PREPARATION|GET_PAGE_LIST)\|outcome=(PASS|FAIL|INCONCLUSIVE|NOT_RUN)"
    r"(?:\|category=(NONE|NO_RESULTS|AMBIGUOUS|LOW_CONFIDENCE|HTTP_403|HTTP_429|HTTP_5XX|"
    r"HTTP_OTHER|NETWORK|TIMEOUT|CAPTCHA|MALFORMED|EXTENSION|BINDING|IDENTITY|SOURCE_DISABLED|INVENTORY_EMPTY|"
    r"RECONCILIATION|CONTENT_UNAVAILABLE|READER_PREPARATION|INSTRUMENTATION|CANCELLED|"
    r"UNIQUE_REFERENCE_MATCH|EXACT_REFERENCE|MATERIALIZATION|SOURCE_NOT_FOUND|"
    r"SOURCE_REGISTRATION_TIMEOUT|SOURCE_TYPE_MISMATCH))?"
    r"(?:\|count=(\d{1,10}))?(?:\|sourceId=(\d{1,20}))?"
    r"(?:\|language=([A-Za-z0-9-]{1,16}))?(?:\|elapsedMs=(\d{1,10}))?$"
)
CONTEXT_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=RUNTIME_CONTEXT"
    r"\|applicationContext=(present|null|error)"
    r"\|targetIsolation=(isolated|same)"
    r"\|databaseContext=(raw|wrapped)$"
)
SCHEMA_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=RUNTIME_SCHEMA"
    r"\|outcome=(PASS|FAIL)\|titles=(present|missing|unknown)\|outbox=(present|missing|unknown)"
    r"\|dirtyInsertTrigger=(present|missing|unknown)(?:\|sqlCategory=(SQLITE_CONSTRAINT|SQLITE_CORRUPT|"
    r"SQLITE_IO|SQLITE_FULL|SQLITE_READ_ONLY|SQLITE_OPEN|SQLITE_OTHER|NOT_SQLITE))?$"
)
DB_IDENTITY_LINE = re.compile(
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
DB_PATH_PROBE_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=RUNTIME_DB_PATH_PROBE"
    r"\|outcome=(PREEXISTING|CREATED|PARENT_MISSING|PARENT_NOT_DIRECTORY|SECURITY_ERROR|IO_ERROR|OTHER_ERROR)"
    r"\|parentState=(EXISTS|MISSING|NOT_DIRECTORY|ERROR)"
    r"\|parentWritable=(true|false|unknown)"
    r"\|cleanup=(NOT_NEEDED|REMOVED|NOT_REMOVED|NOT_EMPTY|DELETE_FAILED|PARTIAL|UNKNOWN)$"
)
SETUP_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS: stream=RUNTIME_SETUP\|phase="
    r"(CONTEXT_ISOLATION|DB_RESET|SQL_DRIVER|DATABASE_ADAPTERS|COMPOSITION|"
    r"CANONICAL_TITLE_CREATE|CANONICAL_TITLE_PERSIST|TEST_CANONICAL_TITLE_PERSIST|"
    r"DRIVER_CREATE|DATABASE_CREATE|TITLE_INSERT|TITLE_READ|DRIVER_CLOSE)"
    r"\|outcome=(PASS|FAIL)"
    r"(?:\|exception=(IllegalStateException|IllegalArgumentException|SecurityException|"
    r"SQLException|SQLiteException|SQLiteCantOpenDatabaseException|SQLiteReadOnlyDatabaseException|"
    r"SQLiteConstraintException|SQLiteDatabaseCorruptException|SQLiteDiskIOException|SQLiteFullException|"
    r"SQLiteBlobTooBigException|SQLiteDatatypeMismatchException|SQLiteMisuseException|"
    r"SQLiteAccessPermException|SQLiteBindOrColumnIndexOutOfRangeException|"
    r"UnsatisfiedLinkError|NoClassDefFoundError|ExceptionInInitializerError|"
    r"ClassNotFoundException|NullPointerException|IOException|OTHER))?"
    r"(?:\|frame=(TITLE_REPOSITORY_INSERT|SQLDELIGHT_QUERY|SQLDELIGHT_NOTIFY_QUERIES|"
    r"SQLDELIGHT_DRIVER_AWAIT|SQLDELIGHT_RESULT_VALUE|SQLDELIGHT_RUNTIME|"
    r"EYGRABER_ANDROIDX_DRIVER|EYGRABER_SCHEMA_DRIVER|EYGRABER_CONNECTION_POOL|"
    r"EYGRABER_CONFIGURABLE_DRIVER|EYGRABER_CONNECTION_FACTORY|EYGRABER_SQLITE_DRIVER|"
    r"EYGRABER_EXECUTING_DRIVER_KT|EYGRABER_STATEMENT|EYGRABER_SQLITE_UTILS|"
    r"EYGRABER_ACTIVE_TRANSACTION|EYGRABER_EXECUTING_DRIVER|EYGRABER_PREPARED_STATEMENT|EYGRABER_QUERY|"
    r"ANDROIDX_BUNDLED_DRIVER|ANDROIDX_SQLITE_CORE|ANDROIDX_SQLITE_DRIVER))?"
    r"(?:\|sqlCategory=(SQLITE_CONSTRAINT|SQLITE_CORRUPT|SQLITE_IO|SQLITE_FULL|"
    r"SQLITE_READ_ONLY|SQLITE_OPEN|SQLITE_OTHER|NOT_SQLITE))?$"
)
MISSING_QUOTED_CLASS = re.compile(r'Didn.t find class\s*"([A-Za-z_$][A-Za-z0-9_.$]+)"')
MISSING_DIRECT_CLASS = re.compile(r'ClassNotFoundException:\s*(?!Didn.t)([A-Za-z_$][A-Za-z0-9_.$]+)')
PUBLIC_CLASS_PREFIXES = ("androidx.test.", "eu.kanade.tachiyomi.", "mihon.", "org.junit.", "kotlin.")

def summarize(runner: str, crash: str) -> list[str]:
    lines: list[str] = []
    lines.append("DIAGNOSTIC|runnerLines=" + str(len(runner.splitlines())))
    classes: set[str] = set()
    for raw_line in runner.splitlines():
        line = raw_line.strip()
        if SAFE_CODE.fullmatch(line):
            lines.append("DIAGNOSTIC|" + line)
        parsed = SAFE_RUNNER_CLASS.fullmatch(line)
        if parsed:
            key, value = parsed.groups()
            if key == 'numtests' and value.isdigit():
                lines.append("DIAGNOSTIC|numtests=" + value)
            elif key == 'class':
                is_fixture = value.endswith((
                    ".MangaFireFixtureInstrumentedTest",
                    ".MangaFireRealReadingJourneyInstrumentedTest",
                ))
                lines.append("DIAGNOSTIC|classMatchesFixture=" + str(is_fixture))
            elif key == 'test':
                lines.append("DIAGNOSTIC|testMatchesFixture=" + str(value in (
                    "loadsRealExtensionAndRegistersInternalSources",
                    "optionalLiveEnglishSearch",
                    "optionalRealEnglishReadingJourney",
                    "instrumentationContextDatabasePersistsCanonicalTitle",
                    "instrumentationDatabaseIdentityProbe",
                )))
        if line.startswith('INSTRUMENTATION_RESULT: shortMsg='):
            # The short message can include arbitrary values: never echo it.
            lines.append("DIAGNOSTIC|hasShortMsg=true")
        if line.startswith('INSTRUMENTATION_RESULT: stream='):
            lines.append("DIAGNOSTIC|hasRunnerStream=true")
        if 'FAILURES!!!' in line:
            lines.append("DIAGNOSTIC|assertionFailure=true")
        classes.update(SAFE_JAVA_TYPE.findall(line))
    if 'FATAL EXCEPTION' in crash:
        lines.append("DIAGNOSTIC|androidRuntimeCrash=true")
    classes.update(SAFE_JAVA_TYPE.findall(crash))
    # The test itself emits this strictly allowlisted diagnostic on a failed live query.
    # Do not print raw exceptions, URLs, cookies, HTTP bodies, or full runner output.
    for kind, status, elapsed in sorted(set(LIVE_RESULT.findall(runner))):
        lines.append(
            "DIAGNOSTIC|liveOutcome=" + kind +
            "|httpStatus=" + (status or "unknown") +
            "|elapsedMs=" + elapsed
        )
    for raw_line in runner.splitlines():
        parsed = E2E_LINE.fullmatch(raw_line.strip())
        if not parsed:
            continue
        stage, outcome, category, count, source_id, language, elapsed = parsed.groups()
        summary = "DIAGNOSTIC|e2eStage=" + stage + "|outcome=" + outcome
        if category:
            summary += "|category=" + category
        if count:
            summary += "|count=" + count
        if source_id:
            summary += "|sourceId=" + source_id
        if language:
            summary += "|language=" + language
        if elapsed:
            summary += "|elapsedMs=" + elapsed
        lines.append(summary)
    for raw_line in runner.splitlines():
        identity = DB_IDENTITY_LINE.fullmatch(raw_line.strip())
        if identity:
            (
                test_uid, target_uid, test_writable, test_state, target_writable, target_state,
                test_data_writable, test_data_executable, test_data_state,
                target_data_writable, target_data_executable, target_data_state,
            ) = identity.groups()
            lines.append(
                "DIAGNOSTIC|dbIdentity|processIsTestUid=" + test_uid +
                "|processIsTargetUid=" + target_uid +
                "|testDbParentWritable=" + test_writable +
                "|testDbParentState=" + test_state +
                "|targetDbParentWritable=" + target_writable +
                "|targetDbParentState=" + target_state +
                "|testDataDirWritable=" + test_data_writable +
                "|testDataDirExecutable=" + test_data_executable +
                "|testDataDirState=" + test_data_state +
                "|targetDataDirWritable=" + target_data_writable +
                "|targetDataDirExecutable=" + target_data_executable +
                "|targetDataDirState=" + target_data_state
            )
        path_probe = DB_PATH_PROBE_LINE.fullmatch(raw_line.strip())
        if path_probe:
            outcome, state, writable, cleanup = path_probe.groups()
            lines.append(
                "DIAGNOSTIC|dbPathProbe|outcome=" + outcome +
                "|parentState=" + state +
                "|parentWritable=" + writable +
                "|cleanup=" + cleanup
            )
        context = CONTEXT_LINE.fullmatch(raw_line.strip())
        if context:
            application_context, target_isolation, database_context = context.groups()
            lines.append(
                "DIAGNOSTIC|contextApplicationContext=" + application_context +
                "|targetIsolation=" + target_isolation + "|databaseContext=" + database_context
            )
        parsed = SETUP_LINE.fullmatch(raw_line.strip())
        if parsed:
            phase, outcome, exception, frame, sql_category = parsed.groups()
            summary = "DIAGNOSTIC|setupPhase=" + phase + "|outcome=" + outcome
            if exception:
                summary += "|exception=" + exception
            if frame:
                summary += "|frame=" + frame
            if sql_category:
                summary += "|sqlCategory=" + sql_category
            lines.append(summary)
        schema = SCHEMA_LINE.fullmatch(raw_line.strip())
        if schema:
            outcome, titles, outbox, trigger, sql_category = schema.groups()
            summary = (
                "DIAGNOSTIC|schemaOutcome=" + outcome + "|titles=" + titles +
                "|outbox=" + outbox + "|dirtyInsertTrigger=" + trigger
            )
            if sql_category:
                summary += "|sqlCategory=" + sql_category
            lines.append(summary)
    missing_source = runner + "\n" + crash
    missing = set(MISSING_QUOTED_CLASS.findall(missing_source))
    missing.update(MISSING_DIRECT_CLASS.findall(missing_source))
    for cls in sorted(missing):
        # Only package-qualified test/runtime classes. No exception messages or values.
        if cls.startswith(PUBLIC_CLASS_PREFIXES):
            lines.append("DIAGNOSTIC|missingRuntimeClass=" + cls)
        else:
            lines.append("DIAGNOSTIC|missingRuntimeClass=OTHER")
    for cls in sorted(classes)[:10]:
        lines.append("DIAGNOSTIC|exceptionType=" + cls)
    return lines

if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        raise SystemExit("usage: summarize_android_instrumentation.py <runner-output> [crash-log]")
    runner = Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
    crash_path = sys.argv[2] if len(sys.argv) == 3 else ""
    crash = Path(crash_path).read_text(encoding='utf-8', errors='replace') if crash_path else ""
    for line in summarize(runner, crash):
        print(line)
