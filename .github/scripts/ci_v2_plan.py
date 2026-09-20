#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

MODULES = {
    "core_common": {
        "name": "Core Common",
        "task": ":core:common:testDebugUnitTest",
        "report": "core/common/build/reports/tests/testDebugUnitTest/**",
        "timeout": 20,
    },
    "domain": {
        "name": "Domain",
        "task": ":domain:testDebugUnitTest",
        "report": "domain/build/reports/tests/testDebugUnitTest/**",
        "timeout": 25,
    },
    "data": {
        "name": "Data",
        "task": ":data:testDebugUnitTest",
        "report": "data/build/reports/tests/testDebugUnitTest/**",
        "timeout": 25,
    },
    "app": {
        "name": "App",
        "task": ":app:testDebugUnitTest",
        "report": "app/build/reports/tests/testDebugUnitTest/**",
        "timeout": 35,
    },
}

ORDER = tuple(MODULES)
ALL = set(ORDER)


def is_test_path(path: str, module: str) -> bool:
    return path.startswith(f"{module}/src/test/")


def add(selected: set[str], *modules: str) -> None:
    selected.update(modules)


def plan(paths: list[str], mode: str) -> dict[str, object]:
    selected: set[str] = set()
    run_database = False
    meaningful = [
        path.strip().removeprefix("./")
        for path in paths
        if path.strip()
    ]

    if mode == "full":
        selected = set(ALL)
        run_database = True
        run_format = True
    else:
        run_format = False

        for path in meaningful:
            if path.endswith(".md") or path.startswith("fastlane/"):
                continue

            run_format = True

            if (
                path in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
                or path.startswith("gradle/")
                or path.startswith(".github/")
            ):
                selected = set(ALL)
                run_database = True
                continue

            if path.startswith("data/src/main/sqldelight/"):
                add(selected, "data", "app")
                run_database = True
                continue

            if is_test_path(path, "core/common"):
                add(selected, "core_common")
                continue
            if path.startswith("core/common/"):
                add(selected, "core_common", "domain", "data", "app")
                continue

            if path.startswith("core/metro/"):
                add(selected, "core_common", "domain", "data", "app")
                continue

            if is_test_path(path, "domain"):
                add(selected, "domain")
                continue
            if path.startswith("domain/"):
                add(selected, "domain", "data", "app")
                continue

            if is_test_path(path, "data"):
                add(selected, "data")
                continue
            if path.startswith("data/"):
                add(selected, "data", "app")
                if path == "data/build.gradle.kts":
                    run_database = True
                continue

            if is_test_path(path, "app"):
                add(selected, "app")
                continue
            if path.startswith("app/"):
                add(selected, "app")
                continue

            if path.startswith("source-api/"):
                add(selected, "domain", "data", "app")
                continue

            if path.startswith((
                "source-local/",
                "core/archive/",
                "core-metadata/",
                "i18n/",
                "icons/",
                "presentation-core/",
                "presentation-widget/",
                "telemetry/",
                "baseline-profile/",
            )):
                add(selected, "app")
                continue

            # Unknown build-impacting paths fail safe: verify everything.
            selected = set(ALL)
            run_database = True

        if not meaningful:
            # A missing diff should never silently weaken verification.
            selected = set(ALL)
            run_database = True
            run_format = True

    include = []
    for key in ORDER:
        if key not in selected:
            continue
        entry = dict(MODULES[key])
        entry["key"] = key
        include.append(entry)

    return {
        "matrix": {"include": include},
        "run_tests": bool(include),
        "run_format": run_format,
        "run_database": run_database,
        "selected": [entry["name"] for entry in include],
    }


def emit_outputs(result: dict[str, object]) -> None:
    matrix = json.dumps(result["matrix"], separators=(",", ":"))
    selected = ",".join(result["selected"]) or "none"
    print(f"matrix={matrix}")
    print(f"run_tests={str(result['run_tests']).lower()}")
    print(f"run_format={str(result['run_format']).lower()}")
    print(f"run_database={str(result['run_database']).lower()}")
    print(f"selected={selected}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("affected", "full"), default="affected")
    parser.add_argument("--paths-file", type=Path)
    args = parser.parse_args()

    paths = []
    if args.paths_file and args.paths_file.exists():
        paths = args.paths_file.read_text(encoding="utf-8").splitlines()

    emit_outputs(plan(paths, args.mode))


if __name__ == "__main__":
    main()
