#!/bin/sh
# HELP: RAOfflineProxy
# ICON: raofflineproxy
# GRID: RAOfflineProxy

. /opt/muos/script/var/func.sh

APP_BIN="raofflineproxy"
SETUP_APP "$APP_BIN" ""

SETUP_STAGE_OVERLAY

# -----------------------------------------------------------------------------

APP_DIR="/run/muos/storage/application/RAOfflineProxy"
LOG_DIR="${APP_DIR}/data"
LOG_FILE="${LOG_DIR}/mux-launch.log"

mkdir -p "$LOG_DIR"

printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "mux_launch start" >>"$LOG_FILE"

if [ ! -f "${APP_DIR}/launch.sh" ]; then
    printf '%s\n' "launch.sh missing" >>"$LOG_FILE"
    exit 1
fi

printf '%s\n' "calling FRONTEND stop" >>"$LOG_FILE"
FRONTEND stop

exec /bin/sh "${APP_DIR}/launch.sh"
