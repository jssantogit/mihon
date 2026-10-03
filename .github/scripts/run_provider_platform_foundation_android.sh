#!/usr/bin/env bash
set -euo pipefail

readonly target_package='app.tsuzuki.dev'
readonly provider_test='eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeIsolationTest#providerRuntime_isIsolatedAndInterruptible'
readonly extension_test='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaFireFixtureInstrumentedTest#loadsRealExtensionAndRegistersInternalSources'
readonly fixture='test-fixtures/extensions/mangafire-v1.6.34.apk'

python3 .github/scripts/verify_extension_fixture.py

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)

if (( ${#target_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo 'PROVIDER_FOUNDATION|outcome=FAIL|reason=APK_COUNT'
  exit 1
fi

adb install -r "${target_apks[0]}" >/dev/null
adb install -r "${test_apks[0]}" >/dev/null
adb install -r "$fixture" >/dev/null

runner_record="$(adb shell pm list instrumentation | tr -d '\r' | grep "(target=${target_package})" | grep 'androidx.test.runner.AndroidJUnitRunner' | head -n 1 || true)"
runner="$(printf '%s\n' "$runner_record" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p')"
if [[ -z "$runner" ]]; then
  echo 'PROVIDER_FOUNDATION|outcome=FAIL|reason=RUNNER_NOT_FOUND'
  exit 1
fi

dump_android_diagnostics() {
  echo '--- provider foundation process snapshot ---'
  adb shell ps -A | grep -E "${target_package}|provider_runtime" || true
  echo '--- provider foundation relevant logcat ---'
  adb logcat -d -v brief -t 400 |
    grep -E 'AndroidRuntime|ActivityManager|ProviderRuntime|provider_runtime|app\.tsuzuki' |
    tail -n 160 || true
}

run_test() {
  local test_name="$1"
  local output
  output="$(mktemp)"
  local exit_code=0
  adb logcat -c || true
  timeout --foreground 180s adb shell am instrument -w -r -e class "$test_name" "$runner" > "$output" 2>&1 || exit_code=$?
  if (( exit_code != 0 )) || ! grep -Fq 'OK (1 test)' "$output"; then
    echo "PROVIDER_FOUNDATION|outcome=FAIL|test=${test_name##*#}|runnerExit=$exit_code"
    tail -n 80 "$output"
    dump_android_diagnostics
    rm -f "$output"
    exit 1
  fi
  echo "PROVIDER_FOUNDATION|outcome=PASS|test=${test_name##*#}"
  rm -f "$output"
}

run_test "$provider_test"
run_test "$extension_test"
