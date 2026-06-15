#!/usr/bin/env bash
#
# Axon — test orchestrator. Builds, installs, enables the service on a connected
# device, sets up the port forward, then runs the E2E test suite.
#
#   scripts/test.sh                 full run: unit tests + build + install + all E2E
#   scripts/test.sh --no-build      skip rebuild/reinstall, just run E2E
#   scripts/test.sh --e2e stage1    run only E2E files matching "stage1*"
#   scripts/test.sh --unit          run only JVM unit tests (./gradlew test)
#
# Env: ADB_SERIAL to target a specific device.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
E2E_DIR="$ROOT_DIR/tests/e2e"

DO_BUILD=1
DO_UNIT=1
DO_E2E=1
E2E_FILTER="stage"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) DO_BUILD=0 ;;
        --unit)     DO_E2E=0 ;;
        --e2e)      DO_UNIT=0; DO_BUILD=0; E2E_FILTER="${2:-stage}"; shift ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done

log() { printf '\033[1;32m[test]\033[0m %s\n' "$*"; }

if [[ "$DO_UNIT" == 1 ]]; then
    log "Running JVM unit tests ..."
    "$SCRIPT_DIR/build.sh" :app:testDebugUnitTest
fi

if [[ "$DO_BUILD" == 1 ]]; then
    log "Building debug APK ..."
    "$SCRIPT_DIR/build.sh" :app:assembleDebug
    log "Installing on device ..."
    ADB=(adb); [[ -n "${ADB_SERIAL:-}" ]] && ADB=(adb -s "$ADB_SERIAL")
    "${ADB[@]}" install -r "$APK"
fi

if [[ "$DO_E2E" == 1 ]]; then
    log "Enabling accessibility service + port forward ..."
    "$SCRIPT_DIR/device.sh" enable
    "$SCRIPT_DIR/device.sh" forward

    fail=0
    shopt -s nullglob
    for t in "$E2E_DIR/${E2E_FILTER}"*.py; do
        log "E2E: $(basename "$t")"
        if ADB_SERIAL="${ADB_SERIAL:-}" python3 "$t"; then
            printf '\033[1;32m  PASS\033[0m %s\n' "$(basename "$t")"
        else
            printf '\033[1;31m  FAIL\033[0m %s\n' "$(basename "$t")"
            fail=1
        fi
    done

    if [[ "$fail" == 1 ]]; then
        log "Some E2E tests FAILED."
        exit 1
    fi
    log "All E2E tests passed."
fi
