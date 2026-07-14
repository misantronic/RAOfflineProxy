#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_DST=/userdata/system/services/raofflineproxy
OVERLAY_FILE=/boot/boot/overlay
PURGE_OVERLAY=0

if [ "$1" = "--purge-overlay" ]; then
    PURGE_OVERLAY=1
fi

echo "== RAOfflineProxy native integration (dev uninstall) =="

SERVICES_BIN="$(command -v knulli-services || command -v batocera-services || true)"
SAVE_OVERLAY_BIN="$(command -v knulli-save-overlay || command -v batocera-save-overlay || true)"

echo "-- Stopping and disabling the service"
if [ -n "${SERVICES_BIN}" ]; then
    "${SERVICES_BIN}" stop raofflineproxy || true
    "${SERVICES_BIN}" disable raofflineproxy || true
fi
rm -f "${SERVICE_DST}"

echo "-- Reverting configgen patch"
mount -o remount,rw /
python3 "${SCRIPT_DIR}/patch_configgen.py" revert

if [ "${PURGE_OVERLAY}" -eq 1 ]; then
    echo "-- Purging rootfs overlay file (${OVERLAY_FILE})"
    echo "   WARNING: this discards ALL rootfs customizations, not just RAOfflineProxy's."
    mount -o remount,rw /boot
    rm -f "${OVERLAY_FILE}"
    mount -o remount,ro /boot 2>/dev/null || true
    echo "   Rootfs is pristine after the next reboot."
else
    if [ -n "${SAVE_OVERLAY_BIN}" ]; then
        echo "-- Persisting reverted rootfs overlay (${SAVE_OVERLAY_BIN})"
        "${SAVE_OVERLAY_BIN}"
    fi
fi
mount -o remount,ro / 2>/dev/null || true

echo ""
echo "Done. Native integration removed."
echo "The sideloaded app itself is untouched - the pygame menu and its"
echo "legacy Start/Stop (retroarch.cfg patching) mode work as before."
echo "Re-run dev-install.sh at any time to switch back to native mode."
