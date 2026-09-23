#!/usr/bin/env bash
# Run in one Bash process: emulator action runs YAML lines in separate shells.
set -euo pipefail
live_probe="${1:-false}"
if [[ "$live_probe" != "true" && "$live_probe" != "false" ]]; then
  echo "::error::Invalid live_probe value"; exit 2
fi
python3 .github/scripts/verify_extension_fixture.py
apk="test-fixtures/extensions/mangafire-v1.6.34.apk"
mapfile -t signers < <(find "${ANDROID_HOME:?Missing ANDROID_HOME}/build-tools" -type f -name apksigner | sort -V)
if (( ${#signers[@]} == 0 )); then echo "::error::Missing apksigner"; exit 1; fi
"${signers[${#signers[@]}-1]}" verify --verbose "$apk"
adb install -r "$apk"

# Gradle previously exited green with an XML report containing 0 tests.
# Explicitly install both APKs and select the AndroidJUnitRunner method.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
if (( ${#target_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo "::error::Expected exactly one debug target APK and instrumentation APK"; exit 1
fi
adb install -r "${target_apks[0]}"
adb install -r "${test_apks[0]}"
available="$(adb shell pm list instrumentation | tr -d '\r')"
runner="$(printf '%s\n' "$available" | sed -n '/target=app\.mihon\.dev/ s/^instrumentation:\([^ ]*\).*/\1/p' | grep '/androidx.test.runner.AndroidJUnitRunner$' | head -n 1)"
if [[ -z "$runner" ]]; then echo "::error::Test runner app.mihon.dev unavailable"; exit 1; fi

test_class="eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireFixtureInstrumentedTest"
output="$(mktemp)"
trap 'rm -f "$output"' EXIT
run_one() {
  local method="$1"
  shift
  if ! adb shell am instrument -w -r -e class "${test_class}#${method}" "$@" "$runner" > "$output" 2>&1; then
    echo "::error::AndroidJUnitRunner failed to start"; exit 1
  fi
  python3 .github/scripts/verify_android_instrumentation.py "$output" "$method"
}
run_one loadsRealExtensionAndRegistersInternalSources
if [[ "$live_probe" == "true" ]]; then
  # Explicit manual opt-in only. No CAPTCHA circumvention.
  run_one optionalLiveEnglishSearch -e allowLiveProvider true
fi
