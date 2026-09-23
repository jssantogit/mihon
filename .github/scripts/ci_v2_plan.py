#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

TEST_SHARDS = {
    "core_common": {
        "name": "Core Common",
        "task": ":core:common:testDebugUnitTest",
        "filter": "",
        "report": "core/common/build/reports/tests/testDebugUnitTest/**",
        "timeout": 20,
    },
    "domain": {
        "name": "Domain",
        "task": ":domain:testDebugUnitTest",
        "filter": "",
        "report": "domain/build/reports/tests/testDebugUnitTest/**",
        "timeout": 25,
    },
    "domain_tsuzuki": {
        "name": "Domain — Tsuzuki",
        "task": ":domain:testDebugUnitTest",
        "filter": "*tsuzuki*",
        "report": "domain/build/reports/tests/testDebugUnitTest/**",
        "timeout": 20,
    },
    "data": {
        "name": "Data",
        "task": ":data:testDebugUnitTest",
        "filter": "",
        "report": "data/build/reports/tests/testDebugUnitTest/**",
        "timeout": 25,
    },
    "data_tsuzuki": {
        "name": "Data — Tsuzuki",
        "task": ":data:testDebugUnitTest",
        "filter": "*tsuzuki*",
        "report": "data/build/reports/tests/testDebugUnitTest/**",
        "timeout": 20,
    },
    "app": {
        "name": "App",
        "task": ":app:testDebugUnitTest",
        "filter": "",
        "report": "app/build/reports/tests/testDebugUnitTest/**",
        "timeout": 35,
    },
    "app_tsuzuki": {
        "name": "App — Tsuzuki",
        "task": ":app:testDebugUnitTest",
        "filter": "*tsuzuki*",
        "report": "app/build/reports/tests/testDebugUnitTest/**",
        "timeout": 25,
    },
}

FULL_TEST_ORDER = ("core_common", "domain", "data", "app")
TEST_ORDER = (
    "core_common",
    "domain",
    "domain_tsuzuki",
    "data",
    "data_tsuzuki",
    "app",
    "app_tsuzuki",
)

COMPILE_TARGETS = {
    "app": {
        "name": "App Compile",
        "task": ":app:compileDebugKotlin",
        "timeout": 30,
    },
}

COMPILE_ORDER = ("app",)

ROOT_BUILD_FILES = {
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
}

APP_ONLY_MODULE_PREFIXES = (
    "source-local/",
    "core/archive/",
    "core-metadata/",
    "i18n/",
    "icons/",
    "presentation-core/",
    "presentation-widget/",
    "telemetry/",
    "baseline-profile/",
)

RUNTIME_INTEGRATION_PATH_PREFIXES = (
    "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/content/",
    "app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/content/",
    "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/source/SourceResolver",
    "app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/source/SourceResolver",
    "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel",
    "app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel",
)

DOMAIN_RUNTIME_INTEGRATION_MARKERS = (
    "/tachiyomi/domain/tsuzuki/addon/",
    "/tachiyomi/domain/tsuzuki/chapter/",
    "/tachiyomi/domain/tsuzuki/content/",
    "/tachiyomi/domain/tsuzuki/source/",
)


def is_test_path(path: str, module: str) -> bool:
    return path.startswith(f"{module}/src/test/")


def is_tsuzuki_path(path: str) -> bool:
    normalized = f"/{path.lower()}"
    name = Path(path).name.lower()
    return "/tsuzuki/" in normalized or name.startswith("tsuzuki") or "settingstsuzuki" in name


def is_native_path(path: str) -> bool:
    lower = path.lower()
    return "/torrent/" in f"/{lower}" or "torrent" in Path(lower).name


def is_runtime_integration_path(path: str) -> bool:
    normalized = path.lower()
    if normalized.startswith(RUNTIME_INTEGRATION_PATH_PREFIXES):
        return True
    app_runtime_path = (
        normalized.startswith("app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/")
        or normalized.startswith("app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/")
    )
    if app_runtime_path:
        filename = Path(normalized).name
        return any(
            marker in filename
            for marker in (
                "mihonaddon",
                "mihonreading",
                "mihonchapterinventory",
                "mihonchapterprobe",
                "mihoncontentprovider",
                "chaptercontentpreparer",
                "diagnostic",
            )
        )
    return normalized.startswith("domain/src/") and any(
        marker in f"/{normalized}" for marker in DOMAIN_RUNTIME_INTEGRATION_MARKERS
    )


def add_test(selected: set[str], module: str, *, tsuzuki: bool = False) -> None:
    full_key = module
    scoped_key = f"{module}_tsuzuki"
    if tsuzuki and scoped_key in TEST_SHARDS:
        if full_key not in selected:
            selected.add(scoped_key)
        return
    selected.discard(scoped_key)
    selected.add(full_key)


def select_full(result: dict[str, object]) -> None:
    tests = result["tests"]
    assert isinstance(tests, set)
    tests.clear()
    tests.update(FULL_TEST_ORDER)
    compiles = result["compiles"]
    assert isinstance(compiles, set)
    compiles.clear()
    result["run_database"] = True
    result["run_supabase"] = True
    result["run_release"] = True
    result["run_format"] = True


def plan(paths: list[str], mode: str) -> dict[str, object]:
    state: dict[str, object] = {
        "tests": set(),
        "compiles": set(),
        "run_format": False,
        "run_database": False,
        "run_supabase": False,
        "run_native_package": False,
        "run_release": False,
    }

    meaningful = [path.strip().removeprefix("./") for path in paths if path.strip()]

    if mode == "full":
        select_full(state)
    else:
        for path in meaningful:
            if path.endswith(".md") or path.startswith("fastlane/"):
                continue

            if path.startswith(".github/"):
                # The planner tests execute in the plan job itself; CI config edits should not
                # burn a full Android build just because the workflow changed.
                continue

            if is_runtime_integration_path(path):
                add_test(state["tests"], "domain", tsuzuki=True)
                add_test(state["tests"], "app", tsuzuki=True)
                if path.startswith("domain/src/main/"):
                    state["compiles"].add("app")
                if is_native_path(path):
                    state["run_native_package"] = True
                continue

            if path.startswith("supabase/"):
                state["run_supabase"] = True
                continue

            if path in ROOT_BUILD_FILES or path.startswith("gradle/") or path.endswith("/build.gradle.kts"):
                select_full(state)
                continue

            if path in {"app/proguard-rules.pro", "app/proguard-android-optimize.txt"}:
                add_test(state["tests"], "app")
                state["run_release"] = True
                state["run_format"] = True
                continue

            state["run_format"] = True

            if path.startswith("data/src/main/sqldelight/"):
                add_test(
                    state["tests"],
                    "data",
                    tsuzuki=is_tsuzuki_path(path) or "tsuzuki_" in Path(path).name.lower(),
                )
                state["compiles"].add("app")
                state["run_database"] = True
                continue

            if is_test_path(path, "core/common"):
                add_test(state["tests"], "core_common")
                continue
            if path.startswith("core/common/"):
                add_test(state["tests"], "core_common")
                state["compiles"].add("app")
                continue

            if path.startswith("core/metro/"):
                # DI changes are cross-cutting enough that affected mode intentionally fails safe.
                select_full(state)
                continue

            if is_test_path(path, "domain"):
                add_test(state["tests"], "domain", tsuzuki=is_tsuzuki_path(path))
                continue
            if path.startswith("domain/"):
                add_test(state["tests"], "domain", tsuzuki=is_tsuzuki_path(path))
                state["compiles"].add("app")
                if is_native_path(path):
                    state["run_native_package"] = True
                continue

            if is_test_path(path, "data"):
                add_test(state["tests"], "data", tsuzuki=is_tsuzuki_path(path))
                continue
            if path.startswith("data/"):
                add_test(state["tests"], "data", tsuzuki=is_tsuzuki_path(path))
                state["compiles"].add("app")
                if path == "data/build.gradle.kts":
                    select_full(state)
                continue

            if is_test_path(path, "app"):
                add_test(state["tests"], "app", tsuzuki=is_tsuzuki_path(path))
                continue
            if path.startswith("app/"):
                add_test(state["tests"], "app", tsuzuki=is_tsuzuki_path(path))
                if is_native_path(path):
                    state["run_native_package"] = True
                continue

            if path.startswith("source-api/"):
                state["compiles"].add("app")
                continue

            if path.startswith(APP_ONLY_MODULE_PREFIXES):
                state["compiles"].add("app")
                continue

            # Unknown build-impacting paths fail safe.
            select_full(state)

        if not meaningful:
            select_full(state)

    tests = state["tests"]
    compiles = state["compiles"]
    assert isinstance(tests, set)
    assert isinstance(compiles, set)

    # Any App unit-test shard already compiles the app; avoid a redundant compile lane.
    if "app" in tests or "app_tsuzuki" in tests:
        compiles.discard("app")

    test_include = []
    for key in TEST_ORDER:
        if key not in tests:
            continue
        entry = dict(TEST_SHARDS[key])
        entry["key"] = key
        test_include.append(entry)

    compile_include = []
    for key in COMPILE_ORDER:
        if key not in compiles:
            continue
        entry = dict(COMPILE_TARGETS[key])
        entry["key"] = key
        compile_include.append(entry)

    return {
        "test_matrix": {"include": test_include},
        "compile_matrix": {"include": compile_include},
        "run_tests": bool(test_include),
        "run_compile": bool(compile_include),
        "run_format": bool(state["run_format"]),
        "run_database": bool(state["run_database"]),
        "run_supabase": bool(state["run_supabase"]),
        "run_native_package": bool(state["run_native_package"]),
        "run_release": bool(state["run_release"]),
        "selected_tests": [entry["name"] for entry in test_include],
        "selected_compiles": [entry["name"] for entry in compile_include],
    }


def emit_outputs(result: dict[str, object]) -> None:
    print(f"test_matrix={json.dumps(result['test_matrix'], separators=(',', ':'))}")
    print(f"compile_matrix={json.dumps(result['compile_matrix'], separators=(',', ':'))}")
    for key in (
        "run_tests",
        "run_compile",
        "run_format",
        "run_database",
        "run_supabase",
        "run_native_package",
        "run_release",
    ):
        print(f"{key}={str(result[key]).lower()}")
    selected_tests = ",".join(result["selected_tests"]) or "none"
    selected_compiles = ",".join(result["selected_compiles"]) or "none"
    print(f"selected_tests={selected_tests}")
    print(f"selected_compiles={selected_compiles}")


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
