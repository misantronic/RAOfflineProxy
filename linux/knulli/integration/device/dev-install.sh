#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_BIN=/userdata/system/raofflineproxy/bin/raofflineproxy
SERVICE_DST=/userdata/system/services/raofflineproxy

echo "== RAOfflineProxy native integration (dev install) =="

if [ ! -d /userdata/system ]; then
    echo "ERROR: /userdata/system not found - this does not look like a Knulli device" >&2
    exit 1
fi

if [ ! -x "${APP_BIN}" ]; then
    echo "ERROR: RAOfflineProxy app not found at ${APP_BIN}" >&2
    echo "Install the regular sideload bundle first, then re-run this script." >&2
    exit 1
fi

SERVICES_BIN="$(command -v knulli-services || command -v batocera-services || true)"
if [ -z "${SERVICES_BIN}" ]; then
    echo "ERROR: neither knulli-services nor batocera-services found" >&2
    exit 1
fi

SAVE_OVERLAY_BIN="$(command -v knulli-save-overlay || command -v batocera-save-overlay || true)"
if [ -z "${SAVE_OVERLAY_BIN}" ]; then
    echo "ERROR: neither knulli-save-overlay nor batocera-save-overlay found" >&2
    exit 1
fi

echo "-- Stopping legacy mode (reverts retroarch.cfg/batocera.conf patching)"
"${APP_BIN}" stop-proxy || true
"${APP_BIN}" disable-autostart || true

echo "-- Installing user service to ${SERVICE_DST}"
mkdir -p /userdata/system/services
install -m 0755 "${SCRIPT_DIR}/services/raofflineproxy" "${SERVICE_DST}"

echo "-- Patching configgen on the rootfs overlay"
mount -o remount,rw /
python3 "${SCRIPT_DIR}/patch_configgen.py" apply

echo "-- Persisting rootfs overlay (${SAVE_OVERLAY_BIN})"
"${SAVE_OVERLAY_BIN}"
mount -o remount,ro / 2>/dev/null || true

echo "-- Enabling and starting the service"
"${SERVICES_BIN}" enable raofflineproxy
"${SERVICES_BIN}" start raofflineproxy

sleep 1
echo "-- Proxy status"
"${APP_BIN}" status || true

echo ""
echo "Done. The RAOfflineProxy toggle now appears in"
echo "EmulationStation -> System Settings -> Services."
echo "Games route RetroAchievements through the proxy whenever it is running."
