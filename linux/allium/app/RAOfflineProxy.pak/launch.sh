#!/bin/sh
set -eu

appdir="$(cd "$(dirname "$0")" && pwd)"

touch /tmp/stay_awake
cd "$appdir"

. "$appdir/common.sh"
prepare_env

if resolve_python_bin; then
    PYTHON_BIN="$RESOLVED_PYTHON_BIN"
    run_backend_raw "$PYTHON_BIN" probe-online >/dev/null 2>&1 &
    # Reinstalled on every launch: an Allium update rewrites .tmp_update/updater from
    # scratch, and the app directory survives it, so this is the only thing that repairs
    # autostart.
    run_backend "$PYTHON_BIN" ensure-boot-hook >/dev/null 2>&1 || true
    exec "$PYTHON_BIN" -m raofflineproxy.main menu-sdl
fi

{
    printf 'RAOfflineProxy %s\n\n' "$APP_VERSION"
    printf 'No compatible Python runtime was found, the app cannot start.\n\n'
    printf 'Expected one of:\n'
    printf '  - %s/runtime/bin/python3\n' "$appdir"
    printf '  - %s/runtime/python/bin/python3\n' "$appdir"
    printf '  - python3 on PATH\n\n'
    if [ -n "$RUNTIME_FAILURE_REASON" ]; then
        printf 'Last runtime error:\n  %s\n\n' "$RUNTIME_FAILURE_REASON"
    fi
    printf 'RetroArch cfg: %s\n' "$RAOFFLINEPROXY_RETROARCH_CFG"
} | tee "$APP_DATA_DIR/launch-failure.log"

exit 1
