#!/bin/sh
set -eu

appdir=/mnt/SDCARD/App/RAOfflineProxy

touch /tmp/stay_awake
cd "$appdir"

. "$appdir/common.sh"
prepare_env

if resolve_python_bin; then
    PYTHON_BIN="$RESOLVED_PYTHON_BIN"
    run_backend_raw "$PYTHON_BIN" probe-online >/dev/null 2>&1 &
    install_onion_checkoff_script >/dev/null 2>&1 || true
    run_backend "$PYTHON_BIN" ensure-boot-hook >/dev/null 2>&1 || true
    exec "$PYTHON_BIN" -m raofflineproxy.main menu-sdl
fi

exec st -q -e sh "$appdir/onion-menu.sh"
