#!/bin/sh
set -eu

appdir=/mnt/SDCARD/App/RAOfflineProxy
menu_ready_file=/tmp/raofflineproxy-menu-ready
menu_start_seconds=20
sdl_probe_seconds=15

touch /tmp/stay_awake
cd "$appdir"

. "$appdir/common.sh"
prepare_env

log_menu_failure() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$APP_DATA_DIR/menu-sdl.log"
}

if resolve_python_bin; then
    PYTHON_BIN="$RESOLVED_PYTHON_BIN"
    run_backend_raw "$PYTHON_BIN" probe-online >/dev/null 2>&1 &
    # Reinstall the boot hook on every launch: a spruce update wipes .tmp_update, and the
    # app directory survives it, so this is the only thing that repairs autostart.
    run_backend "$PYTHON_BIN" ensure-boot-hook >/dev/null 2>&1 || true
    rm -f "$menu_ready_file"
    RAOFFLINEPROXY_MENU_READY_FILE="$menu_ready_file" LD_PRELOAD="$SPRUCE_SDL_PRELOAD" \
        "$PYTHON_BIN" -m raofflineproxy.main menu-sdl &
    menu_status=0
    wait_with_deadline "$!" "$menu_start_seconds" "$menu_ready_file" || menu_status=$?
    rm -f "$menu_ready_file"
    if [ "$menu_status" -eq 0 ]; then
        exit 0
    fi
    if [ "$menu_status" -eq 124 ]; then
        log_menu_failure "menu drew nothing within ${menu_start_seconds}s, stopped it"
    fi

    # The menu failed to come up. On a device we have not tested, the usual cause is SDL
    # finding no usable video driver, so record which ones this device actually offers
    # instead of leaving only a traceback. Unbuffered, so a driver that hangs during init
    # is still named by the last line written before the deadline stops it.
    APP_SPRUCE_PLATFORM="$APP_SPRUCE_PLATFORM" LD_PRELOAD="$SPRUCE_SDL_PRELOAD" \
        "$PYTHON_BIN" -u - >>"$APP_DATA_DIR/menu-sdl.log" 2>&1 <<'SDL_PROBE' &
import ctypes, os, sys

print("--- SDL video driver probe ---")
print("device:", os.environ.get("APP_SPRUCE_PLATFORM", "unknown"))
print("python:", sys.version.split()[0])
print("preload:", os.environ.get("LD_PRELOAD") or "<none>")

os.environ.pop("SDL_VIDEODRIVER", None)
import pygame

print("sdl:", ".".join(str(part) for part in pygame.get_sdl_version()))

# Ask the SDL that pygame actually loaded which drivers it was built with, rather than
# guessing from a fixed list: a device whose only usable backend is a vendor one (the
# H700's "mali", the Miyoo Mini's "Mini") would otherwise report nothing but failures
# and hide the fact that the bundled SDL2 is the wrong build for the board.
def loaded_sdl():
    # A preloaded SDL2 is the one whose symbols pygame actually resolved, but the bundled
    # copy is still mapped and comes first in /proc/self/maps, so ask the preload first or
    # the report names the wrong build and omits the only driver that works.
    for entry in os.environ.get("LD_PRELOAD", "").split(":"):
        if "libSDL2-2" in entry:
            return ctypes.CDLL(entry), entry

    # Otherwise pygame dlopened its SDL2 privately, so the process-global namespace does
    # not necessarily carry the symbols. Reopen the mapped file by path instead.
    try:
        with open("/proc/self/maps", encoding="utf-8") as handle:
            for line in handle:
                path = line.rsplit(" ", 1)[-1].strip()
                if "libSDL2-2" in path:
                    return ctypes.CDLL(path), path
    except OSError:
        pass
    return ctypes.CDLL(None), "<global>"


drivers = []
try:
    sdl, sdl_path = loaded_sdl()
    print("sdl lib:", sdl_path)
    sdl.SDL_GetVideoDriver.restype = ctypes.c_char_p
    drivers = [
        sdl.SDL_GetVideoDriver(i).decode() for i in range(sdl.SDL_GetNumVideoDrivers())
    ]
    print("compiled-in drivers:", ", ".join(drivers) or "<none>")
except (OSError, AttributeError) as exc:
    print("could not enumerate drivers:", exc)

for driver in drivers:
    os.environ["SDL_VIDEODRIVER"] = driver
    try:
        pygame.display.quit()
        pygame.display.init()
        print(f"  {driver:10s} OK  {pygame.display.get_desktop_sizes()}")
        pygame.display.quit()
    except Exception as exc:
        print(f"  {driver:10s} --  {exc}")
SDL_PROBE
    if ! wait_with_deadline "$!" "$sdl_probe_seconds"; then
        log_menu_failure "driver probe stopped after ${sdl_probe_seconds}s"
    fi

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
