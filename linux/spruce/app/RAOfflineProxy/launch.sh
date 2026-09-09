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
    # Reinstall the boot hook on every launch: a spruce update wipes .tmp_update, and the
    # app directory survives it, so this is the only thing that repairs autostart.
    run_backend "$PYTHON_BIN" ensure-boot-hook >/dev/null 2>&1 || true
    if "$PYTHON_BIN" -m raofflineproxy.main menu-sdl; then
        exit 0
    fi

    # The menu failed to come up. On a device we have not tested, the usual cause is SDL
    # finding no usable video driver, so record which ones this device actually offers
    # instead of leaving only a traceback.
    APP_SPRUCE_PLATFORM="$APP_SPRUCE_PLATFORM" "$PYTHON_BIN" - >>"$APP_DATA_DIR/menu-sdl.log" 2>&1 <<'SDL_PROBE'
import os, sys

print("--- SDL video driver probe ---")
print("device:", os.environ.get("APP_SPRUCE_PLATFORM", "unknown"))
print("python:", sys.version.split()[0])
for driver in ("kmsdrm", "fbcon", "directfb", "x11", "wayland", "offscreen", "dummy"):
    os.environ["SDL_VIDEODRIVER"] = driver
    try:
        import pygame
        pygame.display.quit()
        pygame.display.init()
        sizes = pygame.display.get_desktop_sizes()
        print(f"  {driver:10s} OK  {sizes}")
        pygame.display.quit()
    except Exception as exc:
        print(f"  {driver:10s} --  {exc}")
SDL_PROBE

    exit 1
fi

{
    printf 'RAOfflineProxy %s\n\n' "$APP_VERSION"
    printf 'No compatible Python runtime was found, the app cannot start.\n\n'
    printf 'Detected spruce device: %s\n' "$APP_SPRUCE_PLATFORM"
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
