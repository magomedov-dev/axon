#!/usr/bin/env bash
#
# Axon — device helper. Manages the accessibility service and port forward on a
# connected device, so E2E tests need no manual toggling in the UI.
#
#   scripts/device.sh enable     enable the Axon accessibility service (append, keep others)
#   scripts/device.sh disable    remove the Axon service from the enabled list
#   scripts/device.sh forward    adb forward tcp:9008 -> device 9008
#   scripts/device.sh status     show enabled services + foreground package
#   scripts/device.sh logcat     stream the agent's logs
#
# Honors ADB_SERIAL to target a specific device (defaults to the only one attached).

set -euo pipefail

PKG="com.axon.agent"
SVC="$PKG/$PKG.AutomationAccessibilityService"
PORT=9008

ADB=(adb)
if [[ -n "${ADB_SERIAL:-}" ]]; then
    ADB=(adb -s "$ADB_SERIAL")
fi

enable_service() {
    local current
    current="$("${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
    [[ "$current" == "null" ]] && current=""
    if [[ ":$current:" == *":$SVC:"* ]]; then
        echo "[device] Axon service already enabled."
    else
        local updated
        if [[ -z "$current" ]]; then
            updated="$SVC"
        else
            updated="$current:$SVC"
        fi
        "${ADB[@]}" shell settings put secure enabled_accessibility_services "$updated"
        echo "[device] enabled_accessibility_services -> $updated"
    fi
    "${ADB[@]}" shell settings put secure accessibility_enabled 1
    echo "[device] accessibility_enabled = 1"
}

disable_service() {
    local current updated
    current="$("${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
    [[ "$current" == "null" ]] && current=""
    # remove our SVC from the colon-separated list
    updated="$(printf '%s' "$current" | tr ':' '\n' | grep -vF "$SVC" | paste -sd ':' -)"
    "${ADB[@]}" shell settings put secure enabled_accessibility_services "$updated"
    echo "[device] enabled_accessibility_services -> ${updated:-<empty>}"
}

case "${1:-}" in
    enable)  enable_service ;;
    disable) disable_service ;;
    forward)
        "${ADB[@]}" forward "tcp:$PORT" "tcp:$PORT"
        echo "[device] forwarding localhost:$PORT -> device:$PORT"
        ;;
    status)
        echo "[device] enabled_accessibility_services:"
        "${ADB[@]}" shell settings get secure enabled_accessibility_services | tr ':' '\n' | sed 's/^/    /'
        echo "[device] foreground:"
        "${ADB[@]}" shell dumpsys activity activities | grep -m1 'mResumedActivity' | sed 's/^/    /' || true
        ;;
    logcat)
        "${ADB[@]}" logcat -s AxonService AxonServer
        ;;
    *)
        echo "usage: scripts/device.sh {enable|disable|forward|status|logcat}" >&2
        exit 2
        ;;
esac
