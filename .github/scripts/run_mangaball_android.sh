#!/usr/bin/env bash
# Opt-in runner for one immutable MangaBall APK on a disposable CI emulator.
set -euo pipefail

live_probe="${1:-false}"
if [[ "$live_probe" != "true" && "$live_probe" != "false" ]]; then
  echo "::error::Invalid live_probe value"
  exit 2
fi

python3 .github/scripts/verify_mangaball_fixture.py
apk='test-fixtures/extensions/manga-ball-1.6.1.apk'
mapfile -t signers < <(find "${ANDROID_HOME:?Missing ANDROID_HOME}/build-tools" -type f -name apksigner | sort -V)
if (( ${#signers[@]} == 0 )); then
  echo '::error::Missing apksigner'
  exit 1
fi
"${signers[${#signers[@]}-1]}" verify --verbose "$apk" >/dev/null
adb install -r "$apk" >/dev/null

# Gradle is intentionally invoked only by this CI runner, never by local scripts.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
if (( ${#target_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo '::error::Expected exactly one app APK and one instrumentation APK'
  exit 1
fi
adb install -r "${target_apks[0]}" >/dev/null
adb install -r "${test_apks[0]}" >/dev/null
runner="$(adb shell pm list instrumentation | tr -d '\r' | sed -n '/target=app\.mihon\.dev/ s/^instrumentation:\([^ ]*\).*/\1/p' | grep '/androidx.test.runner.AndroidJUnitRunner$' | head -n 1 || true)"
if [[ -z "$runner" ]]; then
  echo '::error::AndroidJUnitRunner target app.mihon.dev was not registered'
  exit 1
fi

run_one() {
  local method="$1"
  local report_method="$2"
  shift 2
  local output report_dir='.github/results/mangaball'
  output="$(mktemp)"
  mkdir -p "$report_dir"
  local runner_exit=0
  timeout --foreground 360s adb shell am instrument -w -r \
    -e class "eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaBallRealReadingJourneyInstrumentedTest#${method}" \
    "$@" "$runner" >"$output" 2>&1 || runner_exit=$?
  local summary_exit=0
  python3 .github/scripts/mangaball_android_report.py "$report_method" "$output" \
    >"${report_dir}/${method}.txt" || summary_exit=$?
  cat "${report_dir}/${method}.txt"
  rm -f "$output"
  if (( runner_exit != 0 || summary_exit != 0 )); then
    echo '::error::MangaBall instrumentation did not produce accepted evidence'
    return 1
  fi
  if [[ "$report_method" == 'optionalLivePtBrReadingJourney' ]] &&
    grep -Fxq 'DIAGNOSTIC|journey=INCONCLUSIVE' "${report_dir}/${method}.txt"; then
    echo '::error::MangaBall live journey was inconclusive and is not a passing E2E result'
    return 3
  fi
}

run_one loadsRealExtensionAndRegistersInternalSourcesAndDisabledPeerIsExcluded \
  loadsRealExtensionAndRegistersInternalSourcesAndDisabledPeerIsExcluded
if [[ "$live_probe" == "true" ]]; then
  # Explicitly opted-in single-source live probe; one title batch, one inventory,
  # and at most one page-list operation. CAPTCHA/429 are never bypassed.
  run_one optionalLivePtBrReadingJourney optionalLivePtBrReadingJourney -e allowLiveProvider true
fi
