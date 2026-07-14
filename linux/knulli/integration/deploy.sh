#!/usr/bin/env bash
set -euo pipefail

HOST="${1:?usage: deploy.sh root@<device-ip> [install|uninstall|purge|status]}"
ACTION="${2:-install}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_DIR=/userdata/system/raop-native-dev

echo "-- Copying integration files to ${HOST}:${REMOTE_DIR}"
ssh "${HOST}" "mkdir -p ${REMOTE_DIR}/services"
scp "${SCRIPT_DIR}/device/dev-install.sh" \
    "${SCRIPT_DIR}/device/dev-uninstall.sh" \
    "${SCRIPT_DIR}/device/patch_configgen.py" \
    "${HOST}:${REMOTE_DIR}/"
scp "${SCRIPT_DIR}/device/services/raofflineproxy" "${HOST}:${REMOTE_DIR}/services/"

case "${ACTION}" in
    install)
        ssh "${HOST}" "bash ${REMOTE_DIR}/dev-install.sh"
        ;;
    uninstall)
        ssh "${HOST}" "bash ${REMOTE_DIR}/dev-uninstall.sh"
        ;;
    purge)
        ssh "${HOST}" "bash ${REMOTE_DIR}/dev-uninstall.sh --purge-overlay"
        ;;
    status)
        ssh "${HOST}" "python3 ${REMOTE_DIR}/patch_configgen.py status; \
            (command -v knulli-services || command -v batocera-services) >/dev/null 2>&1 && \
            \$(command -v knulli-services || command -v batocera-services) list | grep -i raoffline || true; \
            /userdata/system/raofflineproxy/bin/raofflineproxy status || true"
        ;;
    *)
        echo "unknown action: ${ACTION} (use install|uninstall|purge|status)" >&2
        exit 1
        ;;
esac
