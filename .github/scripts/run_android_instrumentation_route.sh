#!/usr/bin/env bash
# Keep the default workflow_dispatch path offline; live MangaFire work remains explicit.
set -euo pipefail

event_name="${1:-}"
live_probe="${2:-false}"
fixture_marker="${3:-false}"
dry_run="${4:-false}"
extension_profile="${5:-mangafire}"

if [[ "$live_probe" != 'true' && "$live_probe" != 'false' ]]; then
  echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=INVALID_LIVE_PROBE' >&2
  exit 2
fi
if [[ "$fixture_marker" != 'true' && "$fixture_marker" != 'false' ]]; then
  echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=INVALID_FIXTURE_MARKER' >&2
  exit 2
fi
if [[ "$dry_run" != 'true' && "$dry_run" != 'false' ]]; then
  echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=INVALID_DRY_RUN' >&2
  exit 2
fi
if [[ "$extension_profile" != 'mangafire' && "$extension_profile" != 'mangaball' ]]; then
  echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=INVALID_EXTENSION_PROFILE' >&2
  exit 2
fi

case "$event_name" in
  workflow_dispatch)
    if [[ "$extension_profile" == 'mangaball' ]]; then
      if [[ "$live_probe" == 'true' ]]; then
        route='MANGABALL_LIVE'
        provider_calls='1'
      else
        route='MANGABALL_FIXTURE_ONLY'
        provider_calls='0'
      fi
    elif [[ "$live_probe" == 'true' ]]; then
        route='MANGAFIRE_LIVE'
        provider_calls='1'
      else
        route='NAVIGATION_ONLY'
        provider_calls='0'
    fi
    ;;
  push)
    if [[ "$extension_profile" != 'mangafire' ]]; then
      echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=PROFILE_UNSUPPORTED_FOR_PUSH' >&2
      exit 2
    fi
    if [[ "$fixture_marker" != 'true' ]]; then
      echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=PUSH_MARKER_REQUIRED' >&2
      exit 2
    fi
    if [[ "$live_probe" == 'true' ]]; then
      route='MANGAFIRE_LIVE'
      provider_calls='1'
    else
      route='MANGAFIRE_FIXTURE'
      provider_calls='0'
    fi
    ;;
  *)
    echo 'ANDROID_INSTRUMENTATION_ROUTE|outcome=BLOCKED|reason=UNSUPPORTED_EVENT' >&2
    exit 2
    ;;
esac

if [[ "$dry_run" == 'true' ]]; then
  if [[ "$extension_profile" == 'mangaball' ]]; then
    printf 'ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=%s|liveProbe=%s|providerCalls=%s|extensionProfile=%s\n' \
      "$route" "$live_probe" "$provider_calls" "$extension_profile"
  else
    printf 'ANDROID_INSTRUMENTATION_ROUTE|outcome=PASS|mode=%s|liveProbe=%s|providerCalls=%s\n' \
      "$route" "$live_probe" "$provider_calls"
  fi
  exit 0
fi

case "$route" in
  NAVIGATION_ONLY)
    exec bash .github/scripts/run_android_navigation.sh
    ;;
  MANGAFIRE_FIXTURE|MANGAFIRE_LIVE)
    exec bash .github/scripts/run_mangafire_android.sh "$live_probe"
    ;;
  MANGABALL_FIXTURE_ONLY|MANGABALL_LIVE)
    exec bash .github/scripts/run_mangaball_android.sh "$live_probe"
    ;;
esac
