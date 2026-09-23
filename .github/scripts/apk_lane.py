#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fnmatch

RELEASE = {
    "lane": "release",
    "task": "assembleRelease",
    "apk": "app/build/outputs/apk/release/app-arm64-v8a-release.apk",
    "mapping": "app/build/outputs/mapping/release",
    "flags": "-Pinclude-telemetry -Penable-updater",
}
DEV_A = {
    "lane": "dev-a",
    "task": "assembleDeva",
    "apk": "app/build/outputs/apk/deva/app-arm64-v8a-deva.apk",
    "mapping": "app/build/outputs/mapping/deva",
    "flags": "",
}
DEV_B = {
    "lane": "dev-b",
    "task": "assembleDevb",
    "apk": "app/build/outputs/apk/devb/app-arm64-v8a-devb.apk",
    "mapping": "app/build/outputs/mapping/devb",
    "flags": "",
}
DEV_C = {
    "lane": "dev-c",
    "task": "assembleDevc",
    "apk": "app/build/outputs/apk/devc/app-arm64-v8a-devc.apk",
    "mapping": "app/build/outputs/mapping/devc",
    "flags": "",
}

RULES = (
    (("main", "tsuzuki/bootstrap", "tsuzuki/diagnostic-chapter-inventory", "tsuzuki/runtime-v2-integration*", "tsuzuki/runtime-v2-torrent*"), RELEASE),
    (("tsuzuki/mvp-v1-*", "tsuzuki/unified-library-dev-a*", "tsuzuki/runtime-v2-dev-a*"), DEV_A),
    (("tsuzuki/mvp-v2-*", "tsuzuki/unified-library-dev-b*", "tsuzuki/runtime-v2-dev-b*"), DEV_B),
    (("tsuzuki/mvp-v3-*", "tsuzuki/unified-library-dev-c*", "tsuzuki/runtime-v2-dev-c*"), DEV_C),
)


def resolve_lane(branch: str) -> dict[str, str]:
    for patterns, lane in RULES:
        if any(fnmatch.fnmatchcase(branch, pattern) for pattern in patterns):
            return dict(lane)
    raise ValueError(f"No APK lane is defined for branch '{branch}'.")


def emit_outputs(result: dict[str, str]) -> None:
    for key in ("lane", "task", "apk", "mapping", "flags"):
        print(f"{key}={result[key]}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("branch")
    args = parser.parse_args()

    try:
        result = resolve_lane(args.branch)
    except ValueError as error:
        parser.error(str(error))
    emit_outputs(result)


if __name__ == "__main__":
    main()
