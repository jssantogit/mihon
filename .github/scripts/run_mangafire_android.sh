#!/usr/bin/env bash
# Keep this entire sequence in one shell. The emulator runner invokes each YAML
# script line separately; shell variables/if blocks must NOT span YAML lines.
set -euo pipefail

live_probe="${1:-false}"
if [[ "$live_probe" != "true" && "$live_probe" != "false" && -n "$live_probe" ]]; then
  echo "::error::Invalid live_probe value"
  exit 2
fi

python3 .github/scripts/verify_extension_fixture.py

apk="test-fixtures/extensions/mangafire-v1.6.34.apk"
mapfile -t signers < <(find "${ANDROID_HOME:?ANDROID_HOME must be configured}/build-tools" \
  -type f -name apksigner | sort -V)
if (( ${#signers[@]} == 0 )); then
  echo "::error::Android SDK apksigner was not found"
  exit 1
fi
signer="${signers[${#signers[@]}-1]}"
"$signer" verify --verbose "$apk"
adb install -r "$apk"

test_class="eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireFixtureInstrumentedTest"
if [[ "$live_probe" == "true" ]]; then
  ./gradlew :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
    -Pandroid.testInstrumentationRunnerArguments.allowLiveProvider=true
else
  ./gradlew :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
fi
