#!/usr/bin/env bash
# Destructive fixture is intentionally constrained to one disposable debug emulator.
set -euo pipefail

readonly target_package='app.mihon.dev'
readonly test_class='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalTitleSourceNavigationInstrumentedTest'
readonly results_root='.github/results/android-navigation'
readonly run_name="${GITHUB_RUN_ID:-local-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
readonly results_dir="${results_root}/${run_name}"
mkdir -p "$results_dir"
touch "$results_dir/cleanup.txt"

device_state="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$device_state" != '1' ]]; then
  echo 'ANDROID_NAVIGATION_SETUP|outcome=BLOCKED|reason=NON_EMULATOR' > "$results_dir/setup.txt"
  echo '::error::Android navigation may only run on the dedicated disposable emulator'
  exit 2
fi

echo 'ANDROID_NAVIGATION_SETUP|outcome=PASS|device=EMULATOR' > "$results_dir/setup.txt"
overall_status=0
cleanup_status='NOT_RUN'

package_is_installed() {
  adb shell pm list packages "$target_package" | tr -d '\r' | grep -Fxq "package:${target_package}"
}

clear_target_data() {
  if ! package_is_installed; then
    echo 'ANDROID_NAVIGATION_CLEANUP|outcome=FAIL|reason=TARGET_PACKAGE_NOT_INSTALLED' >&2
    return 1
  fi
  local result
  result="$(adb shell pm clear "$target_package" | tr -d '\r')"
  if [[ "$result" != 'Success' ]]; then
    echo 'ANDROID_NAVIGATION_CLEANUP|outcome=FAIL|reason=PM_CLEAR_FAILED' >&2
    return 1
  fi
  return 0
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
  printf 'ANDROID_NAVIGATION_CLEANUP|outcome=%s|targetData=CLEARED\n' "$cleanup_status" >> "$results_dir/cleanup.txt"
  if [[ "$cleanup_status" != 'PASS' ]]; then
    echo '::error::Disposable Android app data cleanup was not proven'
  fi
  exit "$exit_status"
}
trap finish EXIT

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
if (( ${#target_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo 'ANDROID_NAVIGATION_SETUP|outcome=BLOCKED|reason=APK_COUNT' > "$results_dir/setup.txt"
  echo '::error::Expected one debug target APK and one Android test APK'
  exit 1
fi

adb install -r "${target_apks[0]}" >/dev/null
adb install -r "${test_apks[0]}" >/dev/null
if ! package_is_installed; then
  echo 'ANDROID_NAVIGATION_SETUP|outcome=BLOCKED|reason=WRONG_TARGET_PACKAGE' > "$results_dir/setup.txt"
  echo '::error::Debug target package is not the isolated app.mihon.dev package'
  exit 1
fi

instrumentation_record="$(adb shell pm list instrumentation | tr -d '\r' | grep '(target=app.mihon.dev)' | grep 'androidx.test.runner.AndroidJUnitRunner' | head -n 1 || true)"
runner="$(printf '%s\n' "$instrumentation_record" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p')"
if [[ "$runner" != 'app.mihon.dev.test/androidx.test.runner.AndroidJUnitRunner' ]]; then
  echo 'ANDROID_NAVIGATION_SETUP|outcome=BLOCKED|reason=RUNNER_NOT_FOUND' > "$results_dir/setup.txt"
  echo '::error::Expected the AndroidJUnitRunner targeting app.mihon.dev was not installed'
  exit 1
fi

# This navigation-only lane has no installed extension APK and runs offline.
adb shell cmd connectivity airplane-mode enable >/dev/null
airplane_status="$(adb shell settings get global airplane_mode_on | tr -d '\r')"
if [[ "$airplane_status" != '1' ]]; then
  echo 'ANDROID_NAVIGATION_SETUP|outcome=BLOCKED|reason=OFFLINE_MODE_NOT_CONFIRMED' > "$results_dir/setup.txt"
  echo '::error::Could not confirm offline emulator mode'
  exit 1
fi
echo 'ANDROID_NAVIGATION_SETUP|outcome=PASS|network=OFFLINE|fixture=LOCAL_ONLY' >> "$results_dir/setup.txt"

run_one() {
  local method="$1"
  local raw_output summary_file junit_file
  raw_output="$(mktemp)"
  summary_file="${results_dir}/${method}.txt"
  junit_file="${results_dir}/TEST-${method}.xml"

  if ! clear_target_data; then
    printf 'ANDROID_NAVIGATION_RESULT|method=%s|outcome=FAIL|evidence=APP_DATA_RESET_NOT_PROVEN\n' "$method" > "$summary_file"
    rm -f "$raw_output"
    exit 1
  fi

  local runner_exit=0
  timeout --foreground 240s adb shell am instrument -w -r \
    -e class "${test_class}#${method}" \
    -e androidNavigationOptIn true \
    "$runner" > "$raw_output" 2>&1 || runner_exit=$?

  if (( runner_exit != 0 )); then
    python3 .github/scripts/verify_android_navigation_instrumentation.py \
      /dev/null "$method" --summary "$summary_file" --junit "$junit_file" >/dev/null 2>&1 || true
    # The verifier's sanitized summary is authoritative; never echo the raw runner transcript.
    echo "ANDROID_NAVIGATION_RESULT|method=${method}|outcome=FAIL|evidence=RUNNER_EXIT_${runner_exit}" >> "$summary_file"
    overall_status=1
  elif ! python3 .github/scripts/verify_android_navigation_instrumentation.py \
    "$raw_output" "$method" --summary "$summary_file" --junit "$junit_file"; then
    overall_status=1
  fi

  rm -f "$raw_output"
  if ! clear_target_data; then
    echo "ANDROID_NAVIGATION_CLEANUP|method=${method}|outcome=FAIL|targetData=UNKNOWN" >> "$results_dir/cleanup.txt"
    rm -f "$raw_output"
    exit 1
  else
    echo "ANDROID_NAVIGATION_CLEANUP|method=${method}|outcome=PASS|targetData=CLEARED" >> "$results_dir/cleanup.txt"
  fi
}

for method in \
  coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce \
  warmReaderDiscoveryReusesMainActivityAndOpensCanonicalTitleBindingSheetOnce \
  invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences; do
  run_one "$method"
done

exit "$overall_status"
