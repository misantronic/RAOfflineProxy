#!/bin/sh

APP_DIR=/mnt/SDCARD/App/RAOfflineProxy
APP_VERSION=v1.13.0-alpha1
APP_MAX_CACHED_GAMES=100
APP_DATA_DIR="$APP_DIR/data"
APP_RUNTIME_DIR="$APP_DIR/runtime"
APP_PACKAGE_DIR="$APP_DIR/app"
APP_LIB_DIR="$APP_DIR/lib"
APP_RETROARCH_CFG=
APP_CERT_FILE=
APP_SPRUCE_PLATFORM=
APP_SPRUCE_BASEOS=
APP_SPRUCE_ZONEINFO_DIR=/mnt/SDCARD/spruce/zoneinfo
# Every spruce device stores its settings in /mnt/SDCARD/Saves/<device>-system.json.
# Globbed rather than mapped per device so this stays device-agnostic.
APP_SPRUCE_SYSTEM_JSON_GLOB='/mnt/SDCARD/Saves/*-system.json'
APP_ACTIVE_RUNTIME_ROOT=
RESOLVED_PYTHON_BIN=
RUNTIME_FAILURE_REASON=
RUNTIME_DETECT_LOG="$APP_DATA_DIR/runtime-detect.log"
RUNTIME_PROBE_ERR="$APP_DATA_DIR/.runtime-probe.err"

# Mirrors spruce's own device detection (spruce/scripts/helperFunctions.sh). The name has
# to match exactly: it selects RetroArch/platform/retroarch-<name>.cfg, and spruce ships
# one config per panel and pad layout rather than one per SoC.
detect_h700_platform() {
    APP_SPRUCE_BASEOS=1

    case "$(sed -n 's/^BASEOS_TARGET=//p' /etc/baseos-release 2>/dev/null)" in
        rg28xx) APP_SPRUCE_PLATFORM=AnbernicRG28XX ;;
        rgcubexx) APP_SPRUCE_PLATFORM=AnbernicRGCubeXX ;;
        rg34xxsp) APP_SPRUCE_PLATFORM=AnbernicXX720480 ;;
        rg34xx|rgsp) APP_SPRUCE_PLATFORM=AnbernicXX720480NoStick ;;
        rg35xxplus|rg35xxsp) APP_SPRUCE_PLATFORM=AnbernicXX640480NoStick ;;
        rg40xxv) APP_SPRUCE_PLATFORM=AnbernicXX640480OneStick ;;
        *) APP_SPRUCE_PLATFORM=AnbernicXX640480 ;;
    esac
}

# The RK3566 boards share a Cortex-A55 part id, so cpuinfo alone cannot separate them.
detect_rk3566_platform() {
    if grep -q '^OS_NAME="DARKMOSS"' /etc/os-release 2>/dev/null; then
        APP_SPRUCE_PLATFORM=RGB30
    elif [ -x /loong/loong_daemon ]; then
        APP_SPRUCE_PLATFORM=Miniloong
    else
        APP_SPRUCE_PLATFORM=Flip
    fi
}

detect_spruce_platform() {
    info="$(cat /proc/cpuinfo 2>/dev/null)"

    case "$info" in
        *sun8i*) APP_SPRUCE_PLATFORM=A30 ;;
        *TG5040*) APP_SPRUCE_PLATFORM=SmartPro ;;
        *TG3040*) APP_SPRUCE_PLATFORM=Brick ;;
        *TG5050*) APP_SPRUCE_PLATFORM=SmartProS ;;
        *TG4040*) APP_SPRUCE_PLATFORM=BrickPro ;;
        *0xd05*) detect_rk3566_platform ;;
        *0xd04*) APP_SPRUCE_PLATFORM=Pixel2 ;;
        *0xd03*) detect_h700_platform ;;
        *)
            if [ -e /usr/magicx ]; then
                APP_SPRUCE_PLATFORM=Zero28
            else
                APP_SPRUCE_PLATFORM=MiyooMini
            fi
            ;;
    esac
}

# The armv7 bundle ships CPython 3.9 and the arm64 one 3.11, so the runtime's own
# site-packages path is resolved rather than hardcoded.
resolve_cert_file() {
    runtime_root="$1"

    for candidate in "$runtime_root"/lib/python3.*/site-packages/pip/_vendor/certifi/cacert.pem; do
        if [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

resolve_spruce_timezone() {
    # spruce's PyUI applies the chosen zone by exporting TZ into its own environment, so
    # anything it launches inherits it. The boot hook runs from .tmp_update/updater long
    # before PyUI exists, so an autostarted proxy would otherwise stamp every award
    # timestamp in UTC.
    if [ -n "${TZ:-}" ]; then
        return 0
    fi

    if [ ! -d "$APP_SPRUCE_ZONEINFO_DIR" ]; then
        return 0
    fi

    for spruce_system_json in $APP_SPRUCE_SYSTEM_JSON_GLOB; do
        [ -r "$spruce_system_json" ] || continue

        spruce_tz="$(sed -n 's/.*"timezone"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$spruce_system_json" 2>/dev/null | head -n 1)"
        [ -n "$spruce_tz" ] || continue

        spruce_zone_file="$APP_SPRUCE_ZONEINFO_DIR/$spruce_tz"
        if [ -r "$spruce_zone_file" ]; then
            # glibc reads a tz file from an absolute path when TZ starts with a colon,
            # which is exactly how spruce itself applies the setting.
            export TZ=":$spruce_zone_file"
            return 0
        fi
    done

    return 0
}

log_runtime_detect() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$RUNTIME_DETECT_LOG"
}

# Waits for "$1" to exit, but kills it if "$2" seconds pass first. With a ready file as
# "$3", reaching it ends the deadline and the wait continues without one. An SDL driver
# that blocks during init never returns, and inside a spruce app nothing else can close
# it: the only way out would be forcing the device off.
wait_with_deadline() {
    deadline_pid="$1"
    deadline_seconds="$2"
    deadline_ready_file="${3:-}"
    deadline_elapsed=0

    while kill -0 "$deadline_pid" 2>/dev/null; do
        if [ -n "$deadline_ready_file" ] && [ -f "$deadline_ready_file" ]; then
            wait "$deadline_pid"
            return $?
        fi

        if [ "$deadline_elapsed" -ge "$deadline_seconds" ]; then
            kill -9 "$deadline_pid" 2>/dev/null
            wait "$deadline_pid" 2>/dev/null
            return 124
        fi

        sleep 1
        deadline_elapsed=$((deadline_elapsed + 1))
    done

    wait "$deadline_pid"
}

normalize_display_paths() {
    sed 's#/mnt/SDCARD/#/#g'
}

# spruce stages a mali-fbdev SDL2 next to PyUI on the Anbernic H700 line, because the
# stock build it ships elsewhere speaks only KMSDRM and wayland and those boards have
# neither libdrm/libgbm nor a compositor (see App/PyUI/launch.sh and
# App/PyUI/dll-mali/PROVENANCE.md). Our arm64 bundle carries the same stock SDL2 and so
# has the same problem: without this it initialises the dummy driver and renders nowhere.
# Both are 2.28.x, so the vendored pygame links against it unchanged.
SPRUCE_MALI_SDL2=/mnt/SDCARD/App/PyUI/dll-mali/libSDL2-2.0.so.0
# The bundled SDL2 has no KMSDRM either, which is the only display the Flip and the
# TrimUI line offer, so there it falls back to the dummy driver too. spruce's own UI
# loads a KMSDRM-capable SDL2 on each: the Flip one spruce ships in App/PyUI/dll, while
# on TrimUI spruce/brick/sdl2 holds only SDL2_image and the core comes from the firmware.
SPRUCE_FLIP_SDL2=/mnt/SDCARD/App/PyUI/dll/libSDL2-2.0.so
TRIMUI_FIRMWARE_SDL2="/usr/lib/libSDL2-2.0.so.0 /usr/lib/libSDL2.so"
SPRUCE_SDL_PRELOAD=
# The directory the preloaded SDL2 came from. Its own NEEDED libraries live beside it and
# nowhere else: spruce's mali build hard-links libsamplerate.so.0, which is shipped only in
# dll-mali. The preload otherwise resolves purely by luck, whenever spruce's own launcher
# happens to have put that directory on LD_LIBRARY_PATH before running us.
SPRUCE_SDL_PRELOAD_DIR=

# Preloads the first of "$@" that exists. Left unexported so only the menu gets it: the
# proxy has no use for SDL, and a stray preload would follow every emulator this app
# launches.
preload_device_sdl2() {
    for candidate in "$@"; do
        if [ -f "$candidate" ]; then
            SPRUCE_SDL_PRELOAD="$candidate"
            SPRUCE_SDL_PRELOAD_DIR="$(dirname "$candidate")"
            log_runtime_detect "using device sdl2 $candidate"
            return 0
        fi
    done

    log_runtime_detect "no device sdl2 found at $*, using the bundled one"
    return 1
}

select_sdl_video_driver() {
    # The bundled SDL2 is the same build the Onion package ships; its "Mini" video driver
    # only exists on the hardware it was built for.
    if [ "$APP_SPRUCE_PLATFORM" = "MiyooMini" ]; then
        export SDL_VIDEODRIVER=Mini
        return 0
    fi

    unset SDL_VIDEODRIVER

    case "$APP_SPRUCE_PLATFORM" in
        Flip)
            preload_device_sdl2 "$SPRUCE_FLIP_SDL2" || true
            return 0
            ;;
        Brick | BrickPro | SmartPro | SmartProS)
            preload_device_sdl2 $TRIMUI_FIRMWARE_SDL2 || true
            return 0
            ;;
    esac

    [ "$APP_SPRUCE_BASEOS" = "1" ] || return 0

    if [ ! -f "$SPRUCE_MALI_SDL2" ]; then
        log_runtime_detect "mali sdl2 not found at $SPRUCE_MALI_SDL2, leaving driver unset"
        return 0
    fi

    # The mangled soname of the bundled manylinux SDL2 means LD_LIBRARY_PATH cannot
    # shadow it; preloading spruce's build resolves pygame's SDL symbols to it instead.
    SPRUCE_SDL_PRELOAD="$SPRUCE_MALI_SDL2"
    SPRUCE_SDL_PRELOAD_DIR="$(dirname "$SPRUCE_MALI_SDL2")"
    export SDL_VIDEODRIVER=mali
    # BaseOS runs neither udev nor mdev, and SDL's joystick layer blocks waiting for udev
    # during SDL_Init. spruce sets the same variable for this device family.
    export SDL_JOYSTICK_DISABLE_UDEV=1
    log_runtime_detect "using spruce mali sdl2 $SPRUCE_MALI_SDL2 driver=mali"
}

# Kept out of the exported LD_LIBRARY_PATH for the same reason as the preload itself: only
# the menu needs these, and dll-mali carries its own libpng/libtiff/webp that would
# otherwise shadow the bundled ones for the proxy and anything it launches.
menu_library_path() {
    if [ -n "$SPRUCE_SDL_PRELOAD_DIR" ]; then
        printf '%s:%s\n' "$SPRUCE_SDL_PRELOAD_DIR" "$LD_LIBRARY_PATH"
        return 0
    fi

    printf '%s\n' "$LD_LIBRARY_PATH"
}

prepare_env() {
    mkdir -p "$APP_DATA_DIR"
    : > "$RUNTIME_DETECT_LOG"
    : > "$RUNTIME_PROBE_ERR"

    detect_spruce_platform
    resolve_spruce_timezone

    # spruce launches RetroArch with --config pointing at this per-device file, so its
    # .retroarch/retroarch.cfg is never read (spruce/scripts/emu/lib/ra_functions.sh).
    APP_RETROARCH_CFG="/mnt/SDCARD/RetroArch/platform/retroarch-${APP_SPRUCE_PLATFORM}.cfg"

    export RAOFFLINEPROXY_CONFIG_DIR="$APP_DATA_DIR"
    export RAOFFLINEPROXY_RETROARCH_CFG="$APP_RETROARCH_CFG"
    export RAOFFLINEPROXY_APP_VERSION="${APP_VERSION#v}"
    export RAOFFLINEPROXY_CACHE_IMAGES=0
    export PYTHONPATH="$APP_PACKAGE_DIR${PYTHONPATH:+:$PYTHONPATH}"
    # glibc hands every allocating thread its own heap and grows each in 1MB chunks it
    # never returns. The proxy runs a thread per connection plus background workers, which
    # on a 103MB device cost ~15MB of arenas — about as much as the interpreter itself.
    export MALLOC_ARENA_MAX=2
    export LD_LIBRARY_PATH="$APP_LIB_DIR:/config/lib:/customer/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

    log_runtime_detect "device=$APP_SPRUCE_PLATFORM machine=$(uname -m 2>/dev/null) version=$APP_VERSION"

    select_sdl_video_driver

    if APP_CERT_FILE="$(resolve_cert_file "$APP_RUNTIME_DIR")"; then
        export SSL_CERT_FILE="$APP_CERT_FILE"
        export RAOFFLINEPROXY_CA_FILE="$APP_CERT_FILE"
    fi
}

activate_runtime_env() {
    runtime_root="$1"
    APP_ACTIVE_RUNTIME_ROOT="$runtime_root"

    export PYTHONHOME="$runtime_root"
    export LD_LIBRARY_PATH="$APP_LIB_DIR:$runtime_root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export PATH="$runtime_root/bin${PATH:+:$PATH}"
    if cert_file="$(resolve_cert_file "$runtime_root")"; then
        export SSL_CERT_FILE="$cert_file"
        export RAOFFLINEPROXY_CA_FILE="$cert_file"
    fi
}

python_supports_backend() {
    candidate="$1"
    runtime_root="${2:-}"

    if [ -n "$runtime_root" ]; then
        PYTHONHOME="$runtime_root" LD_LIBRARY_PATH="$APP_LIB_DIR:$runtime_root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] >= 3 else 1)' >/dev/null 2>"$RUNTIME_PROBE_ERR"
        return $?
    fi

    "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] >= 3 else 1)' >/dev/null 2>"$RUNTIME_PROBE_ERR"
    return $?
}

capture_runtime_failure_reason() {
    if [ ! -s "$RUNTIME_PROBE_ERR" ]; then
        RUNTIME_FAILURE_REASON=
        return 0
    fi

    if IFS= read -r first_line < "$RUNTIME_PROBE_ERR"; then
        RUNTIME_FAILURE_REASON="$first_line"
        return 0
    fi

    RUNTIME_FAILURE_REASON=
}

reject_runtime_candidate() {
    candidate="$1"

    if [ ! -e "$candidate" ]; then
        log_runtime_detect "rejected $candidate: not present"
        return 0
    fi

    if [ ! -x "$candidate" ]; then
        log_runtime_detect "rejected $candidate: not executable"
        return 0
    fi

    capture_runtime_failure_reason
    log_runtime_detect "rejected $candidate: ${RUNTIME_FAILURE_REASON:-exited non-zero with no output}"
}

resolve_python_bin() {
    # A rejected bundle runtime is the interesting event even when a later candidate
    # works: falling through to the system python3 yields an interpreter without our
    # vendored pygame, so the menu fails far from the real cause.
    bundled_rejected=0

    if [ -x "$APP_RUNTIME_DIR/bin/python3" ] &&
        python_supports_backend "$APP_RUNTIME_DIR/bin/python3" "$APP_RUNTIME_DIR"; then
        activate_runtime_env "$APP_RUNTIME_DIR"
        RESOLVED_PYTHON_BIN="$APP_RUNTIME_DIR/bin/python3"
        RUNTIME_FAILURE_REASON=
        log_runtime_detect "selected $RESOLVED_PYTHON_BIN (bundled)"
        return 0
    fi
    reject_runtime_candidate "$APP_RUNTIME_DIR/bin/python3"
    bundled_rejected=1

    if [ -x "$APP_RUNTIME_DIR/python/bin/python3" ] &&
        python_supports_backend "$APP_RUNTIME_DIR/python/bin/python3" "$APP_RUNTIME_DIR/python"; then
        activate_runtime_env "$APP_RUNTIME_DIR/python"
        RESOLVED_PYTHON_BIN="$APP_RUNTIME_DIR/python/bin/python3"
        RUNTIME_FAILURE_REASON=
        log_runtime_detect "selected $RESOLVED_PYTHON_BIN (bundled)"
        return 0
    fi
    reject_runtime_candidate "$APP_RUNTIME_DIR/python/bin/python3"

    if command -v python3 >/dev/null 2>&1; then
        candidate="$(command -v python3)"
        if python_supports_backend "$candidate"; then
            RESOLVED_PYTHON_BIN="$candidate"
            capture_runtime_failure_reason
            if [ "$bundled_rejected" -eq 1 ]; then
                log_runtime_detect "selected $candidate (system fallback; bundled runtime unusable, pygame will be missing)"
            else
                log_runtime_detect "selected $candidate (system fallback)"
            fi
            RUNTIME_FAILURE_REASON=
            return 0
        fi
        reject_runtime_candidate "$candidate"
    fi

    log_runtime_detect "no usable python found"
    RESOLVED_PYTHON_BIN=
    return 1
}

run_backend() {
    python_bin="$1"
    shift
    run_backend_raw "$python_bin" "$@" | normalize_display_paths
}

run_backend_raw() {
    python_bin="$1"
    shift
    case "${1:-}" in
        boot-reconcile | start-proxy)
            # raofflineproxy.boot opens the proxy port before loading the rest
            # of the package, so an emulator started alongside this hook is not
            # refused.
            "$python_bin" -m raofflineproxy.boot "$@"
            ;;
        *)
            "$python_bin" -m raofflineproxy.main "$@"
            ;;
    esac
}

log_path() {
    printf '%s\n' "$APP_DATA_DIR/service.log"
}
