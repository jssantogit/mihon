#!/usr/bin/env bash
# Runs only synthetic Reader source-switch tests on a disposable CI emulator.
set -euo pipefail

readonly target_package='app.mihon.dev'
readonly test_class='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest'
readonly results_root='.github/results/android-reader-source-switch'
readonly run_name="${GITHUB_RUN_ID:-local-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
readonly results_dir="${results_root}/${run_name}"
mkdir -p "$results_dir"
touch "$results_dir/cleanup.txt"

device_state="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$device_state" != '1' ]]; then
  echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=BLOCKED|reason=NON_EMULATOR' > "$results_dir/setup.txt"
  echo '::error::Reader source-switch tests require the disposable CI emulator'
  exit 2
fi

echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=PASS|device=EMULATOR|providerCalls=0' > "$results_dir/setup.txt"
cleanup_status='NOT_RUN'

package_is_installed() {
  adb shell pm list packages "$target_package" | tr -d '\r' | grep -Fxq "package:${target_package}"
}

clear_target_data() {
  if ! package_is_installed; then
    echo 'ANDROID_SOURCE_SWITCH_CLEANUP|outcome=FAIL|reason=TARGET_PACKAGE_NOT_INSTALLED' >&2
    return 1
  fi
  local result
  result="$(adb shell pm clear "$target_package" | tr -d '\r')"
  if [[ "$result" != 'Success' ]]; then
    echo 'ANDROID_SOURCE_SWITCH_CLEANUP|outcome=FAIL|reason=PM_CLEAR_FAILED' >&2
    return 1
  fi
}

finish() {
  local exit_status=$?
  trap - EXIT
  if clear_target_data; then
    cleanup_status='PASS'
  else
    cleanup_status='FAIL'
    exit_status=1
  fi
  printf 'ANDROID_SOURCE_SWITCH_CLEANUP|outcome=%s|targetData=CLEARED\n' "$cleanup_status" >> "$results_dir/cleanup.txt"
  if [[ "$cleanup_status" != 'PASS' ]]; then
    echo '::error::Disposable Android application data cleanup was not proven'
  fi
  exit "$exit_status"
}
trap finish EXIT

mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
if (( ${#target_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=BLOCKED|reason=APK_COUNT' > "$results_dir/setup.txt"
  echo '::error::Expected one debug target APK and one instrumentation APK'
  exit 1
fi

adb install -r "${target_apks[0]}" >/dev/null
adb install -r "${test_apks[0]}" >/dev/null
if ! package_is_installed; then
  echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=BLOCKED|reason=WRONG_TARGET_PACKAGE' > "$results_dir/setup.txt"
  echo '::error::Debug target package is not the isolated app.mihon.dev package'
  exit 1
fi

instrumentation_record="$(adb shell pm list instrumentation | tr -d '\r' | grep '(target=app.mihon.dev)' | grep 'androidx.test.runner.AndroidJUnitRunner' | head -n 1 || true)"
runner="$(printf '%s\n' "$instrumentation_record" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p')"
if [[ "$runner" != 'app.mihon.dev.test/androidx.test.runner.AndroidJUnitRunner' ]]; then
  echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=BLOCKED|reason=RUNNER_NOT_FOUND' > "$results_dir/setup.txt"
  echo '::error::Expected the AndroidJUnitRunner targeting app.mihon.dev was not installed'
  exit 1
fi

# Synthetic fixtures are served inside the app/test process. Keep the emulator
# disconnected so this route cannot accidentally query a third-party provider.
adb shell cmd connectivity airplane-mode enable >/dev/null
airplane_status="$(adb shell settings get global airplane_mode_on | tr -d '\r')"
if [[ "$airplane_status" != '1' ]]; then
  echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=BLOCKED|reason=OFFLINE_MODE_NOT_CONFIRMED' > "$results_dir/setup.txt"
  echo '::error::Could not confirm offline emulator mode'
  exit 1
fi
echo 'ANDROID_SOURCE_SWITCH_SETUP|outcome=PASS|network=OFFLINE|fixture=SYNTHETIC_LOCAL|providerCalls=0' >> "$results_dir/setup.txt"

run_one() {
  local method="$1"
  local raw_output summary_file junit_file runner_exit=0 verifier_exit=0
  raw_output="$(mktemp)"
  summary_file="${results_dir}/${method}.txt"
  junit_file="${results_dir}/TEST-${method}.xml"

  if ! clear_target_data; then
    printf 'ANDROID_SOURCE_SWITCH_RESULT|method=%s|outcome=FAIL|evidence=APP_DATA_RESET_NOT_PROVEN\n' "$method" > "$summary_file"
    rm -f "$raw_output"
    return 1
  fi

  timeout --foreground 360s adb shell am instrument -w -r \
    -e class "${test_class}#${method}" \
    -e androidSourceSwitchOptIn true \
    "$runner" > "$raw_output" 2>&1 || runner_exit=$?

  python3 .github/scripts/verify_android_reader_source_switch.py \
    "$raw_output" "$method" --summary "$summary_file" --junit "$junit_file" || verifier_exit=$?
  rm -f "$raw_output"

  if (( runner_exit != 0 || verifier_exit != 0 )); then
    echo "ANDROID_SOURCE_SWITCH_RESULT|method=${method}|outcome=FAIL|runnerExit=${runner_exit}|verifierExit=${verifier_exit}" >> "$summary_file"
    return 1
  fi

  if ! clear_target_data; then
    echo "ANDROID_SOURCE_SWITCH_CLEANUP|method=${method}|outcome=FAIL|targetData=UNKNOWN" >> "$results_dir/cleanup.txt"
    return 1
  fi
  echo "ANDROID_SOURCE_SWITCH_CLEANUP|method=${method}|outcome=PASS|targetData=CLEARED" >> "$results_dir/cleanup.txt"
}

for method in \
  sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition \
  emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession \
  pageCountDifferenceClampsPositionToValidPage \
  retiredSourceCallbackCannotChangePublishedSession; do
  run_one "$method"
done

exit 0
