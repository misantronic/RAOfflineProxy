#!/usr/bin/env bash
set -euo pipefail

HOST="${1:?usage: deploy-es.sh root@<device-ip> [install|revert]}"
ACTION="${2:-install}"
ES_BINARY="${ES_BINARY:-/Users/dschkalee/src/knulli-linux/output/h700/target/usr/bin/emulationstation}"

case "${ACTION}" in
    install)
        if [ ! -f "${ES_BINARY}" ]; then
            echo "ERROR: built ES binary not found at ${ES_BINARY}" >&2
            exit 1
        fi
        echo "-- Copying ES binary to device"
        scp "${ES_BINARY}" "${HOST}:/userdata/system/emulationstation.raop"
        ssh "${HOST}" '
            set -e
            mount -o remount,rw /
            if [ ! -f /usr/bin/emulationstation.orig ]; then
                cp /usr/bin/emulationstation /usr/bin/emulationstation.orig
            fi
            cp /userdata/system/emulationstation.raop /usr/bin/emulationstation
            chmod 0755 /usr/bin/emulationstation
            rm -f /userdata/system/emulationstation.raop
            SAVE="$(command -v knulli-save-overlay || command -v batocera-save-overlay)"
            "${SAVE}"
            mount -o remount,ro / 2>/dev/null || true
            /etc/init.d/S31emulationstation restart >/dev/null 2>&1 || killall emulationstation || true
            echo "ES binary replaced (backup at /usr/bin/emulationstation.orig), ES restarting."
        '
        ;;
    revert)
        ssh "${HOST}" '
            set -e
            if [ ! -f /usr/bin/emulationstation.orig ]; then
                echo "No backup found - nothing to revert."
                exit 0
            fi
            mount -o remount,rw /
            cp /usr/bin/emulationstation.orig /usr/bin/emulationstation
            rm -f /usr/bin/emulationstation.orig
            SAVE="$(command -v knulli-save-overlay || command -v batocera-save-overlay)"
            "${SAVE}"
            mount -o remount,ro / 2>/dev/null || true
            /etc/init.d/S31emulationstation restart >/dev/null 2>&1 || killall emulationstation || true
            echo "Stock ES binary restored, ES restarting."
        '
        ;;
    *)
        echo "unknown action: ${ACTION} (use install|revert)" >&2
        exit 1
        ;;
esac
