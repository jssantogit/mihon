#!/usr/bin/env python3
"""Fail closed if Android instrumentation did not actually run a specific test."""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS = "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireFixtureInstrumentedTest"
ALLOWED = frozenset(("loadsRealExtensionAndRegistersInternalSources", "optionalLiveEnglishSearch"))
REPORTS = Path(".github/results/mangafire")

class AndroidTestVerificationError(ValueError):
    pass


def verify(output: str, method: str) -> None:
    if method not in ALLOWED:
        raise AndroidTestVerificationError("Unexpected instrumentation method")
    for label, wanted in (("class", CLASS), ("test", method), ("numtests", "1")):
        if not re.search(r"(?m)^INSTRUMENTATION_STATUS: " + label + "=" + re.escape(wanted) + r"\s*$", output):
            raise AndroidTestVerificationError("Expected instrumentation " + label + " not observed")
    if not re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", output):
        raise AndroidTestVerificationError("Android instrumentation did not complete successfully")
    if not re.search(r"(?m)^OK \(1 test\)\s*$", output):
        raise AndroidTestVerificationError("Zero-test or failing instrumentation is not green")


def write_verified_report(method: str) -> Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    suite = ET.Element("testsuite", attrib={"name": CLASS, "tests": "1", "failures": "0", "errors": "0", "skipped": "0"})
    ET.SubElement(suite, "testcase", attrib={"classname": CLASS, "name": method})
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
