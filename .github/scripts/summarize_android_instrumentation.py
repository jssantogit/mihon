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
                lines.append("DIAGNOSTIC|classMatchesFixture=" + str(value.endswith(".MangaFireFixtureInstrumentedTest")))
            elif key == 'test':
                lines.append("DIAGNOSTIC|testMatchesFixture=" + str(value in ("loadsRealExtensionAndRegistersInternalSources", "optionalLiveEnglishSearch")))
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
    if len(sys.argv) != 3:
        raise SystemExit("usage: summarize_android_instrumentation.py <runner-output> <crash-log>")
    for line in summarize(Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace'),
                          Path(sys.argv[2]).read_text(encoding='utf-8', errors='replace')):
        print(line)
