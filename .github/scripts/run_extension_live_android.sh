#!/usr/bin/env bash
# Explicitly opt-in ONLY. Four shards cover every internal source, one query each.
# No concurrent provider requests, no retries or CAPTCHA bypass, no raw logs.
set -euo pipefail

shard="$1"
if [[ ! "$shard" =~ ^[0-3]$ ]]; then
  echo "::error::Expected shard 0, 1, 2 or 3"; exit 2
fi
probe="${2:-all}"
case "$probe" in
  all) target_fixture="all" ;;
  animexnovel) target_fixture="animexnovel-1.6.19.apk" ;;
  mangalivreto) target_fixture="manga-livre.to-1.6.57.apk" ;;
  mangafire) target_fixture="mangafire-v1.6.34.apk" ;;
  *) echo "::error::Probe must be all, animexnovel, mangalivreto or mangafire"; exit 2 ;;
esac
target_shard=0
if [[ "$probe" == "mangafire" ]]; then target_shard=3; fi
if [[ "$probe" != "all" && "$shard" != "$target_shard" ]]; then
  echo "::error::Targeted source probe is in shard $target_shard"; exit 2
fi
first=$((shard * 56))
last=$((first + 56))
global_offset=0
expected_total=222
attempted_sources=0
observed_sources=0
infra_failures=0

python3 .github/scripts/verify_extension_matrix_fixture.py
signer="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
if [[ -z "$signer" ]]; then
  echo "::error::Missing Android apksigner"; exit 1
fi
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
target="$(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort | head -n 1)"
test_apk="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "$target" || -z "$test_apk" ]]; then
  echo "::error::App or Android test APK missing"; exit 1
fi
adb install -r "$target" >/dev/null
adb install -r "$test_apk" >/dev/null
available="$(adb shell pm list instrumentation | tr -d '\r')"
android_runner="$(printf '%s\n' "$available" |
  sed -n '/target=app\.mihon\.dev/ s/^instrumentation:\([^ ]*\).*/\1/p' |
  grep '/androidx.test.runner.AndroidJUnitRunner$' | head -n 1)"
if [[ -z "$android_runner" ]]; then
  echo "::error::AndroidJUnitRunner is not installed"; exit 1
fi
class="eu.kanade.tachiyomi.data.tsuzuki.instrumentation.InstalledExtensionFixtureInstrumentedTest"
output="$(mktemp)"
crash="$(mktemp)"
before="$(mktemp)"
after="$(mktemp)"
dump="$(mktemp)"
trap 'rm -f "$output" "$crash" "$before" "$after" "$dump"' EXIT
mkdir -p ".github/results/extension-live/shard-$shard"

# Verified fixture order and source counts from the preceding real offline 8/8 run.
fixtures=(
  "manga-ball-1.6.1.apk:42"
  "animexnovel-1.6.19.apk:1"
  "manga-flix-1.4.4.apk:1"
  "manga-livre.to-1.6.57.apk:1"
  "mangadex-1.6.0.apk:61"
  "mangadot-1.6.23.apk:108"
  "mangafire-v1.6.34.apk:7"
  "mangas-brasuka-1.6.57.apk:1"
)

for entry in "${fixtures[@]}"; do
  filename="$(printf '%s' "$entry" | cut -d: -f1)"
  fixture_count="$(printf '%s' "$entry" | cut -d: -f2)"
  fixture_start=$global_offset
  global_offset=$((global_offset + fixture_count))
  if [[ "$target_fixture" != "all" && "$filename" != "$target_fixture" ]]; then
    continue
  fi
  local_start=$((first - fixture_start))
  local_end=$((last - fixture_start))
  if ((local_start < 0)); then local_start=0; fi
  if ((local_end > fixture_count)); then local_end=$fixture_count; fi
  if ((local_start >= local_end)); then continue; fi
  local_count=$((local_end - local_start))
  attempted_sources=$((attempted_sources + local_count))
  apk="test-fixtures/extensions/$filename"
  echo "::group::Source shard $shard: $filename start=$local_start count=$local_count"

  if ! "$signer" verify "$apk" >/dev/null 2>&1; then
    echo "::error::APK signature invalid for $filename"; infra_failures=$((infra_failures + 1))
    echo "::endgroup::"; continue
  fi
  adb shell pm list packages -3 | tr -d '\r' > "$before"
  if ! adb install -r "$apk" >/dev/null; then
    echo "::error::APK install failed for $filename"; infra_failures=$((infra_failures + 1))
    echo "::endgroup::"; continue
  fi
  adb shell pm list packages -3 | tr -d '\r' > "$after"
  if ! package_name="$(python3 .github/scripts/detect_extension_package.py package "$before" "$after")"; then
    echo "::error::Package identification failed for $filename"; infra_failures=$((infra_failures + 1))
    echo "::endgroup::"; continue
  fi
  adb shell dumpsys package "$package_name" | tr -d '\r' > "$dump"
  if ! version="$(python3 .github/scripts/detect_extension_package.py version "$dump")"; then
    echo "::error::Version detection failed for $filename"; infra_failures=$((infra_failures + 1))
    adb uninstall "$package_name" >/dev/null 2>&1 || true
    echo "::endgroup::"; continue
  fi
  echo "SOURCE_FIXTURE|name=$filename|package=$package_name|version=$version"

  # Always prove that this APK loads and its full inventory is exposed by Tsuzuki.
  if ! adb shell am instrument -w -r -e class "$class#loadsRealExtensionAndRegistersSources" \
      -e fixturePackageName "$package_name" -e fixtureVersionName "$version" \
      "$android_runner" > "$output" 2>&1 ||
      ! grep -Fqx "OK (1 test)" "$output" ||
      ! grep -Fqx "INSTRUMENTATION_CODE: -1" "$output" ||
      ! grep -Eq "EXTENSION_FIXTURE[|]sourceCount=$fixture_count([|]|$)" "$output"; then
    echo "::error::Extension failed offline registration/facade check: $filename"
    adb logcat -d -b crash -v brief > "$crash" 2>/dev/null || true
    python3 .github/scripts/summarize_android_instrumentation.py "$output" "$crash"
    infra_failures=$((infra_failures + 1))
    adb uninstall "$package_name" >/dev/null 2>&1 || true
    echo "::endgroup::"; continue
  fi
  # A single JUnit4 method observes every source for this fixture/shard, with
  # in-method 2.5-second spacing and backoff on HTTP 429 or explicit CAPTCHA.
  runner_exit=0
  if adb shell am instrument -w -r \
      -e class "$class#optionalLiveSearchSourceBatch" \
      -e allowLiveProvider true -e fixturePackageName "$package_name" \
      -e sourceStart "$local_start" -e sourceCount "$local_count" \
      "$android_runner" > "$output" 2>&1; then
    runner_exit=0
  else
    runner_exit=$?
  fi
  if ((runner_exit == 0)) &&
    python3 .github/scripts/verify_extension_live_report.py "$output" "$filename" "$shard" "$local_count"; then
    observed_sources=$((observed_sources + local_count))
  else
    echo "::error::Incomplete live source observations for $filename"
    python3 .github/scripts/summarize_extension_live_batch.py "$output" "$runner_exit" \
      > ".github/results/extension-live/shard-$shard/$filename.diagnostic.txt"
    infra_failures=$((infra_failures + 1))
  fi
  # Never upload, display or persist raw test output containing third-party details.
  adb uninstall "$package_name" >/dev/null 2>&1 || true
  echo "::endgroup::"
done

expected_shard=56
if [[ "$shard" == 3 ]]; then expected_shard=54; fi
if [[ "$probe" == "animexnovel" || "$probe" == "mangalivreto" ]]; then
  expected_shard=1
elif [[ "$probe" == "mangafire" ]]; then
  expected_shard=7
fi
echo "SOURCE_SHARD|shard=$shard|requested=$attempted_sources|observed=$observed_sources|infraFailures=$infra_failures|total=$global_offset"
if ((global_offset != expected_total || attempted_sources != expected_shard ||
     observed_sources != expected_shard || infra_failures != 0)); then exit 1; fi
