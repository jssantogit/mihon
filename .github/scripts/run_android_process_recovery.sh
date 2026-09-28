#!/usr/bin/env bash
# Proves migration-33 projection recovery across a force-stopped app process.
set -euo pipefail
umask 077

readonly target_package='app.mihon.dev'
readonly test_class='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReadingProcessRecoveryInstrumentedTest'
readonly runner='app.mihon.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly result_root='.github/results/android-process-recovery'
readonly run_name="${GITHUB_RUN_ID:-local-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
readonly result_dir="${result_root}/${run_name}"
readonly stage_file="${result_dir}/stage.txt"
readonly temp_root="${RUNNER_TEMP:-/tmp}"
mkdir -p "$result_dir"

readonly phase1_raw="$(mktemp "${temp_root}/cr02-phase1.XXXXXX")"
readonly phase1_ready_raw="$(mktemp "${temp_root}/cr02-phase1-ready.XXXXXX")"
readonly phase2_raw="$(mktemp "${temp_root}/cr02-phase2.XXXXXX")"
readonly stopped_db_dir="$(mktemp -d "${temp_root}/cr02-stopped-db.XXXXXX")"
cleanup_status='NOT_RUN'
current_stage='SCRIPT_START'
phase1_runner_pid=''

adb_device_counts() {
  local output
  output="$(adb devices 2>/dev/null || true)"
  printf '%s\n' "$output" | awk '
    NR > 1 && NF >= 2 {
      if ($2 == "device") online++
      else if ($2 == "offline") offline++
      else if ($2 == "unauthorized") unauthorized++
      else other++
    }
    END {
      printf "online=%d,offline=%d,unauthorized=%d,other=%d", online, offline, unauthorized, other
    }
  '
}

record_stage() {
  local name="$1" outcome="$2" exit_code="$3" reason="$4" devices
  devices="$(adb_device_counts)"
  printf 'CR02_PROCESS_RECOVERY_STAGE|name=%s|outcome=%s|exitCode=%s|adbDevices=%s|reason=%s\n' \
    "$name" "$outcome" "$exit_code" "$devices" "$reason" | tee -a "$stage_file"
}

stage_start() {
  current_stage="$1"
  record_stage "$current_stage" 'STARTED' '0' 'NONE'
}

stage_pass() {
  record_stage "$current_stage" 'PASS' '0' 'NONE'
}

safe_adb_failure_reason() {
  local output_file="$1" reason
  reason="$(grep -Eo 'INSTALL_FAILED_[A-Z0-9_]+' "$output_file" | head -n 1 || true)"
  if [[ -n "$reason" ]]; then
    printf '%s' "$reason"
    return
  fi
  if grep -Eqi 'device offline' "$output_file"; then
    printf 'ADB_DEVICE_OFFLINE'
  elif grep -Eqi 'no devices/emulators found' "$output_file"; then
    printf 'ADB_NO_DEVICE'
  elif grep -Eqi 'more than one device/emulator' "$output_file"; then
    printf 'ADB_AMBIGUOUS_DEVICE'
  elif grep -Eqi 'connection refused|cannot connect to daemon' "$output_file"; then
    printf 'ADB_DAEMON_UNAVAILABLE'
  elif grep -Eqi 'Failure \[' "$output_file"; then
    printf 'ADB_INSTALL_OR_PACKAGE_FAILURE'
  else
    printf 'ADB_COMMAND_FAILED'
  fi
}

safe_instrumentation_failure_reason() {
  local exit_code="$1" output_file="$2"
  if (( exit_code == 124 || exit_code == 137 )); then
    printf 'INSTRUMENTATION_TIMEOUT'
  elif grep -q '^FAILURES!!!' "$output_file" || grep -q '^INSTRUMENTATION_STATUS_CODE: -2$' "$output_file"; then
    printf 'ANDROID_TEST_FAILURE'
  elif grep -q '^INSTRUMENTATION_FAILED:' "$output_file"; then
    printf 'ANDROID_INSTRUMENTATION_FAILED'
  elif grep -Eqi 'device offline' "$output_file"; then
    printf 'ADB_DEVICE_OFFLINE'
  else
    printf 'ANDROID_RUNNER_EXITED_NONZERO'
  fi
}

host_process_is_active() {
  local process_id="$1" process_state
  process_state="$(ps -o stat= -p "$process_id" 2>/dev/null | tr -d ' ' || true)"
  [[ -n "$process_state" && "$process_state" != Z* && "$process_state" != X* ]]
}

host_instrumentation_command_is_active() {
  local supervisor_pid="$1" child_pids child_pid child_state child_command
  host_process_is_active "$supervisor_pid" || return 1
  child_pids="$(pgrep -P "$supervisor_pid" 2>/dev/null || true)"
  [[ -n "$child_pids" ]] || return 1
  while IFS= read -r child_pid; do
    [[ -n "$child_pid" ]] || continue
    child_state="$(ps -o stat= -p "$child_pid" 2>/dev/null | tr -d ' ' || true)"
    [[ -n "$child_state" && "$child_state" != Z* && "$child_state" != X* ]] || continue
    child_command="$(ps -o args= -p "$child_pid" 2>/dev/null || true)"
    if [[ "$child_command" == *'adb shell am instrument -w -r'* ]]; then
      return 0
    fi
  done <<< "$child_pids"
  return 1
}

wait_for_ready_marker() {
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    if grep -Fq 'INSTRUMENTATION_STATUS: stream=CR02_PROCESS_RECOVERY|phase=PRE_RESTART_READY|' "$phase1_raw"; then
      return 0
    fi
    if ! host_instrumentation_command_is_active "$phase1_runner_pid"; then
      return 1
    fi
    sleep 0.25
  done
  return 124
}

run_adb_stage() {
  local name="$1" output_file exit_code reason
  shift
  stage_start "$name"
  output_file="$(mktemp "${temp_root}/cr02-adb.XXXXXX")"
  set +e
  adb "$@" >"$output_file" 2>&1
  exit_code=$?
  set -e
  if (( exit_code != 0 )); then
    reason="$(safe_adb_failure_reason "$output_file")"
    rm -f "$output_file"
    record_stage "$current_stage" 'FAIL' "$exit_code" "$reason"
    echo "::error::Android stage ${current_stage} failed (exit ${exit_code}; ${reason})"
    return "$exit_code"
  fi
  rm -f "$output_file"
  stage_pass
}

cleanup() {
  local exit_status=$?
  trap - EXIT
  if (( exit_status != 0 )); then
    record_stage "$current_stage" 'ABORTED' "$exit_status" 'STAGE_ABORTED'
  fi
  if [[ -n "$phase1_runner_pid" ]]; then
    adb shell am force-stop "$target_package" >/dev/null 2>&1 || true
    wait "$phase1_runner_pid" 2>/dev/null || true
  fi
  rm -f "$phase1_raw" "$phase1_ready_raw" "$phase2_raw"
  rm -rf "$stopped_db_dir"
  if package_is_installed && clear_target_data; then
    cleanup_status='PASS'
  else
    cleanup_status='FAIL'
    exit_status=1
  fi
  printf 'CR02_PROCESS_RECOVERY_CLEANUP|outcome=%s|targetData=CLEARED\n' "$cleanup_status" > "$result_dir/cleanup.txt"
  if [[ "$cleanup_status" != 'PASS' ]]; then
    echo '::error::Disposable Android app data cleanup was not proven'
  fi
  exit "$exit_status"
}
trap cleanup EXIT

package_is_installed() {
  adb shell pm list packages "$target_package" 2>/dev/null | tr -d '\r' | grep -Fxq "package:${target_package}"
}

clear_target_data() {
  local result
  result="$(adb shell pm clear "$target_package" 2>/dev/null | tr -d '\r')"
  [[ "$result" == 'Success' ]]
}

pid_list() {
  local output exit_code
  if output="$(adb shell pidof "$target_package" 2>/dev/null)"; then
    printf '%s\n' "$output" | tr -d '\r' | tr -s ' ' | sed 's/^ //; s/ $//'
    return 0
  else
    exit_code=$?
  fi
  if (( exit_code == 1 )) && [[ "$(adb_device_counts)" == 'online=1,offline=0,unauthorized=0,other=0' ]]; then
    return 0
  fi
  return "$exit_code"
}

copy_stopped_database() {
  local destination="$1" suffix
  if ! adb exec-out run-as "$target_package" cat databases/tachiyomi.db > "$destination/tachiyomi.db" 2>/dev/null; then
    return 1
  fi
  for suffix in -wal -shm -journal; do
    if ! adb exec-out run-as "$target_package" cat "databases/tachiyomi.db${suffix}" > "$destination/tachiyomi.db${suffix}" 2>/dev/null; then
      rm -f "$destination/tachiyomi.db${suffix}"
    fi
  done
}

record_inconclusive() {
  local reason="$1"
  printf 'CR02_PROCESS_RECOVERY|outcome=INCONCLUSIVE|reason=%s\n' "$reason" > "$result_dir/result.txt"
  echo "::error::Android process-recovery evidence is inconclusive: ${reason}"
}

stage_start 'CONFIRM_DISPOSABLE_EMULATOR'
device_state="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$device_state" != '1' ]]; then
  echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=BLOCKED|reason=NON_EMULATOR' > "$result_dir/setup.txt"
  echo '::error::CR-02 requires the disposable CI emulator'
  exit 2
fi
echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=PASS|emulator=CONFIRMED|network=OFFLINE|providerCalls=0' > "$result_dir/setup.txt"
stage_pass

stage_start 'DISCOVER_APKS'
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
if (( ${#target_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=BLOCKED|reason=APK_COUNT' > "$result_dir/setup.txt"
  echo '::error::Expected one debug target APK and one Android test APK'
  exit 1
fi
stage_pass

run_adb_stage 'INSTALL_TARGET_APK' install -r "${target_apks[0]}"
run_adb_stage 'INSTALL_INSTRUMENTATION_APK' install -r "${test_apks[0]}"

stage_start 'VERIFY_TARGET_PACKAGE'
if ! package_is_installed; then
  record_stage "$current_stage" 'FAIL' '1' 'TARGET_PACKAGE_MISSING'
  echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=BLOCKED|reason=WRONG_TARGET_PACKAGE' > "$result_dir/setup.txt"
  echo '::error::Debug app package is not app.mihon.dev'
  exit 1
fi
stage_pass

stage_start 'VERIFY_ANDROID_RUNNER'
instrumentation_record="$(adb shell pm list instrumentation | tr -d '\r' | grep '(target=app.mihon.dev)' | grep 'androidx.test.runner.AndroidJUnitRunner' | head -n 1 || true)"
installed_runner="$(printf '%s\n' "$instrumentation_record" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p')"
if [[ "$installed_runner" != "$runner" ]]; then
  record_stage "$current_stage" 'FAIL' '1' 'EXPECTED_RUNNER_MISSING'
  echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=BLOCKED|reason=RUNNER_NOT_FOUND' > "$result_dir/setup.txt"
  echo '::error::Expected AndroidJUnitRunner targeting app.mihon.dev is not installed'
  exit 1
fi
stage_pass

# The fixture is entirely local. Disable network access before either phase.
run_adb_stage 'ENABLE_OFFLINE_MODE' shell cmd connectivity airplane-mode enable
stage_start 'VERIFY_OFFLINE_MODE'
if [[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '1' ]]; then
  record_stage "$current_stage" 'FAIL' '1' 'OFFLINE_MODE_NOT_CONFIRMED'
  echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=BLOCKED|reason=OFFLINE_MODE_NOT_CONFIRMED' > "$result_dir/setup.txt"
  echo '::error::Could not confirm offline emulator mode'
  exit 1
fi
stage_pass
stage_start 'INITIAL_APP_DATA_RESET'
if ! clear_target_data; then
  record_stage "$current_stage" 'FAIL' '1' 'APP_DATA_RESET_NOT_CONFIRMED'
  echo 'CR02_PROCESS_RECOVERY_SETUP|outcome=BLOCKED|reason=INITIAL_APP_DATA_RESET' > "$result_dir/setup.txt"
  echo '::error::Could not initialize isolated app data'
  exit 1
fi
stage_pass

stage_start 'START_PRE_RESTART_INSTRUMENTATION'
timeout --foreground 180s adb shell am instrument -w -r \
  -e class "${test_class}#failedAcknowledgementLeavesDurableProjectionForProcessRestart" \
  -e cr02ProcessRecoveryOptIn true \
  "$runner" > "$phase1_raw" 2>&1 &
phase1_runner_pid=$!
stage_pass

stage_start 'WAIT_FOR_PRE_RESTART_READY'
if wait_for_ready_marker; then
  stage_pass
else
  ready_wait_exit=$?
  phase1_failure_exit="$ready_wait_exit"
  if host_instrumentation_command_is_active "$phase1_runner_pid"; then
    phase1_failure_reason='READY_MARKER_TIMEOUT'
  else
    if wait "$phase1_runner_pid"; then
      phase1_exit=0
    else
      phase1_exit=$?
    fi
    phase1_runner_pid=''
    phase1_failure_exit="$phase1_exit"
    if (( phase1_exit == 0 )); then
      phase1_failure_reason='READY_MARKER_MISSING'
    else
      phase1_failure_reason="$(safe_instrumentation_failure_reason "$phase1_exit" "$phase1_raw")"
    fi
  fi
  record_stage "$current_stage" 'FAIL' "$phase1_failure_exit" "$phase1_failure_reason"
  record_inconclusive 'PHASE1_READY_MARKER_NOT_OBSERVED'
  echo '::error::PRE_RESTART AndroidJUnitRunner did not remain active at READY'
  exit 1
fi

stage_start 'VERIFY_PRE_RESTART_PROCESS'
if phase1_live_pids="$(pid_list)"; then
  :
else
  pid_query_exit=$?
  record_stage "$current_stage" 'INCONCLUSIVE' "$pid_query_exit" 'ADB_PID_QUERY_FAILED'
  record_inconclusive 'PHASE1_APP_PID_QUERY_FAILED'
  exit 1
fi
if [[ -z "$phase1_live_pids" ]]; then
  record_stage "$current_stage" 'INCONCLUSIVE' '0' 'APP_PROCESS_NOT_LIVE_AT_READY'
  record_inconclusive 'PHASE1_APP_PROCESS_NOT_LIVE_AT_READY'
  exit 1
fi
stage_pass
stage_start 'VERIFY_PRE_RESTART_FIXTURE'
if ! python3 .github/scripts/verify_android_process_recovery.py \
  --phase1-output "$phase1_raw" \
  --phase1-live-pids "$phase1_live_pids" \
  --summary "$result_dir/phase1.txt"; then
  echo '::error::PRE_RESTART fixture evidence was invalid'
  exit 1
fi
stage_pass

stage_start 'SNAPSHOT_PRE_RESTART_READY_OUTPUT'
cp "$phase1_raw" "$phase1_ready_raw"
stage_pass
stage_start 'VERIFY_INSTRUMENTATION_ACTIVE_AT_FORCE_STOP'
if ! host_instrumentation_command_is_active "$phase1_runner_pid"; then
  if wait "$phase1_runner_pid"; then
    phase1_exit=0
  else
    phase1_exit=$?
  fi
  phase1_runner_pid=''
  record_stage "$current_stage" 'INCONCLUSIVE' "$phase1_exit" 'INSTRUMENTATION_ENDED_BEFORE_FORCE_STOP'
  record_inconclusive 'INSTRUMENTATION_NOT_ACTIVE_AT_FORCE_STOP'
  exit 1
fi
stage_pass

run_adb_stage 'FORCE_STOP_PHASE1_PROCESS' shell am force-stop "$target_package"
post_force_stop_pids=''
for _ in $(seq 1 40); do
  post_force_stop_pids="$(pid_list)"
  [[ -z "$post_force_stop_pids" ]] && break
  sleep 0.25
done
if [[ -n "$post_force_stop_pids" ]]; then
  record_stage "$current_stage" 'FAIL' '1' 'FORCE_STOP_LEFT_APP_PROCESS'
  printf 'CR02_PROCESS_RECOVERY|outcome=FAIL|reason=FORCE_STOP_DID_NOT_REMOVE_PID\n' > "$result_dir/result.txt"
  echo '::error::am force-stop did not remove the phase-one app process'
  exit 1
fi
stage_pass
stage_start 'WAIT_FOR_PHASE1_INSTRUMENTATION_EXIT'
phase1_runner_exit=0
if wait "$phase1_runner_pid"; then
  phase1_runner_exit=0
else
  phase1_runner_exit=$?
fi
phase1_runner_pid=''
record_stage "$current_stage" 'EXPECTED_STOP' "$phase1_runner_exit" 'FORCE_STOP_RETURNED'
stage_start 'INSPECT_STOPPED_DATABASE'
if ! copy_stopped_database "$stopped_db_dir"; then
  record_stage "$current_stage" 'INCONCLUSIVE' '1' 'STOPPED_DATABASE_UNAVAILABLE'
  record_inconclusive 'STOPPED_APP_DATABASE_UNAVAILABLE'
  exit 1
fi
stage_pass
stage_start 'VERIFY_PROCESS_ABSENT_BEFORE_PHASE2'
if [[ -n "$(pid_list)" ]]; then
  record_stage "$current_stage" 'FAIL' '1' 'APP_PROCESS_RETURNED_BEFORE_PHASE2'
  printf 'CR02_PROCESS_RECOVERY|outcome=FAIL|reason=APP_PROCESS_RETURNED_BEFORE_PHASE2\n' > "$result_dir/result.txt"
  echo '::error::The app process returned before phase-two startup'
  exit 1
fi
stage_pass

stage_start 'POST_RESTART_INSTRUMENTATION'
phase2_exit=0
timeout --foreground 180s adb shell am instrument -w -r \
  -e class "${test_class}#newProcessStartupReplaysProjectionAndAcknowledgesExactlyOnce" \
  -e cr02ProcessRecoveryOptIn true \
  "$runner" > "$phase2_raw" 2>&1 || phase2_exit=$?
if (( phase2_exit != 0 )); then
  phase2_failure_reason="$(safe_instrumentation_failure_reason "$phase2_exit" "$phase2_raw")"
  record_stage "$current_stage" 'FAIL' "$phase2_exit" "$phase2_failure_reason"
  echo '::error::POST_RESTART AndroidJUnitRunner phase failed'
  exit 1
fi
stage_pass

stage_start 'VERIFY_POST_RESTART_RECOVERY'
if ! python3 .github/scripts/verify_android_process_recovery.py \
  --phase1-output "$phase1_ready_raw" \
  --phase1-live-pids "$phase1_live_pids" \
  --post-force-stop-pids "$post_force_stop_pids" \
  --stopped-database "$stopped_db_dir/tachiyomi.db" \
  --phase2-output "$phase2_raw" \
  --phase2-runner-exit "$phase2_exit" \
  --summary "$result_dir/result.txt"; then
  echo '::error::Two-process recovery evidence was incomplete'
  exit 1
fi
stage_pass

cat "$result_dir/result.txt"
