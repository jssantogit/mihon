#!/usr/bin/env bash
# One disposable API 35 emulator; each user APK installed, tested, then uninstalled.
# Emulator action must call this file as ONE script (not split into YAML lines).
set -euo pipefail
python3 .github/scripts/verify_extension_matrix_fixture.py

signer="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
if [[ -z "$signer" ]]; then
  echo "::error::Android build-tools apksigner missing"; exit 1
fi
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
target="$(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort | head -n 1)"
test_apk="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "$target" || -z "$test_apk" ]]; then
  echo "::error::Android target/test APK missing"; exit 1
fi
adb install -r "$target" >/dev/null
adb install -r "$test_apk" >/dev/null

available="$(adb shell pm list instrumentation | tr -d '\r')"
android_runner="$(printf '%s\n' "$available" |
  sed -n '/target=app\.mihon\.dev/ s/^instrumentation:\([^ ]*\).*/\1/p' |
  grep '/androidx.test.runner.AndroidJUnitRunner$' | head -n 1)"
if [[ -z "$android_runner" ]]; then
  echo "::error::Target app AndroidJUnitRunner missing"; exit 1
fi

class="eu.kanade.tachiyomi.data.tsuzuki.instrumentation.InstalledExtensionFixtureInstrumentedTest"
method="loadsRealExtensionAndRegistersSources"
output="$(mktemp)"
crash="$(mktemp)"
before="$(mktemp)"
after="$(mktemp)"
dump="$(mktemp)"
trap 'rm -f "$output" "$crash" "$before" "$after" "$dump"' EXIT
failures=0
successes=0
mkdir -p .github/results/extension-matrix

# MangaBall first: the user's reported missing alternative reading source.
for filename in manga-ball-1.6.1.apk animexnovel-1.6.19.apk \
  manga-flix-1.4.4.apk manga-livre.to-1.6.57.apk mangadex-1.6.0.apk \
  mangadot-1.6.23.apk mangafire-v1.6.34.apk mangas-brasuka-1.6.57.apk; do
  apk="test-fixtures/extensions/$filename"
  echo "::group::Offline Android extension test: $filename"
  if ! "$signer" verify "$apk" >/dev/null 2>&1; then
    echo "::error::APK signature invalid: $filename"
    ((failures+=1))
    echo "::endgroup::"
    continue
  fi
  # Identify the package Android actually installed, rather than parsing an
  # aapt badging line that failed identically for all eight uploaded APKs.
  adb shell pm list packages -3 | tr -d '\r' > "$before"
  if ! adb install -r "$apk" >/dev/null; then
    echo "::error::APK install failed: $filename"
    ((failures+=1))
    echo "::endgroup::"
    continue
  fi
  adb shell pm list packages -3 | tr -d '\r' > "$after"
  if ! package_name="$(python3 .github/scripts/detect_extension_package.py package "$before" "$after")"; then
    echo "::error::Could not identify the installed extension: $filename"
    ((failures+=1))
    echo "::endgroup::"
    continue
  fi
  adb shell dumpsys package "$package_name" | tr -d '\r' > "$dump"
  if ! version_name="$(python3 .github/scripts/detect_extension_package.py version "$dump")"; then
    echo "::error::Could not identify extension version: $filename"
    adb uninstall "$package_name" >/dev/null 2>&1 || true
    ((failures+=1))
    echo "::endgroup::"
    continue
  fi
  echo "EXTENSION_METADATA|fixture=$filename|package=$package_name|version=$version_name"
  if ! adb shell am instrument -w -r -e class "$class#$method" \
      -e fixturePackageName "$package_name" -e fixtureVersionName "$version_name" \
      "$android_runner" > "$output" 2>&1; then
    echo "::error::AndroidJUnitRunner did not start: $filename"
    ((failures+=1))
  elif grep -Fqx "INSTRUMENTATION_STATUS: class=$class" "$output" &&
       grep -Fqx "INSTRUMENTATION_STATUS: test=$method" "$output" &&
       grep -Fqx "INSTRUMENTATION_STATUS: numtests=1" "$output" &&
       grep -Fqx "INSTRUMENTATION_CODE: -1" "$output" &&
       grep -Fqx "OK (1 test)" "$output" &&
       grep -Eq 'EXTENSION_FIXTURE\|sourceCount=[1-9][0-9]*' "$output"; then
    count="$(grep -Eo 'EXTENSION_FIXTURE\|sourceCount=[0-9]+' "$output" | tail -n 1 | cut -d = -f 2)"
    echo "EXTENSION_LOAD_OK|$filename|registeredSources=$count"
    printf 'fixture=%s\nregisteredSources=%s\nresult=PASS\n' "$filename" "$count" \
      > ".github/results/extension-matrix/$filename.txt"
    ((successes+=1))
  else
    echo "::error::No proven passing JUnit method or registered sources: $filename"
    ((failures+=1))
  fi
  if ! grep -Fqx "OK (1 test)" "$output"; then
    adb logcat -d -b crash -v brief > "$crash" 2>/dev/null || true
    python3 .github/scripts/summarize_android_instrumentation.py "$output" "$crash"
  fi
  adb uninstall "$package_name" >/dev/null 2>&1 || true
  echo "::endgroup::"
done
echo "EXTENSION_MATRIX|passed=$successes|failed=$failures|expected=8"
if ((failures > 0 || successes != 8)); then exit 1; fi
