#!/bin/sh

APP_DIR=/mnt/SDCARD/App/RAOfflineProxy
APP_VERSION=v1.11.1-alpha3
APP_MAX_CACHED_GAMES=100
APP_DATA_DIR="$APP_DIR/data"
APP_RUNTIME_DIR="$APP_DIR/runtime"
APP_PACKAGE_DIR="$APP_DIR/app"
APP_LIB_DIR="$APP_DIR/lib"
APP_RETROARCH_CFG=
APP_CERT_FILE="$APP_RUNTIME_DIR/lib/python3.9/site-packages/pip/_vendor/certifi/cacert.pem"
APP_SPRUCE_PLATFORM=
APP_ACTIVE_RUNTIME_ROOT=
RESOLVED_PYTHON_BIN=
RUNTIME_FAILURE_REASON=
RUNTIME_DETECT_LOG="$APP_DATA_DIR/runtime-detect.log"

# Mirrors spruce's own device detection (spruce/scripts/helperFunctions.sh). The Anbernic
# 0xd03 branch is collapsed to one label because all its variants share a single RetroArch
# config file.
detect_spruce_platform() {
    info="$(cat /proc/cpuinfo 2>/dev/null)"

    case "$info" in
        *sun8i*) APP_SPRUCE_PLATFORM=A30 ;;
        *TG5040*) APP_SPRUCE_PLATFORM=SmartPro ;;
        *TG3040*) APP_SPRUCE_PLATFORM=Brick ;;
        *TG5050*) APP_SPRUCE_PLATFORM=SmartProS ;;
        *TG4040*) APP_SPRUCE_PLATFORM=BrickPro ;;
        *0xd05*) APP_SPRUCE_PLATFORM=Flip ;;
        *0xd04*) APP_SPRUCE_PLATFORM=Pixel2 ;;
        *0xd03*) APP_SPRUCE_PLATFORM=AnbernicRG_XX-universal ;;
        *)
            if [ -e /usr/magicx ]; then
                APP_SPRUCE_PLATFORM=Zero28
            else
                APP_SPRUCE_PLATFORM=MiyooMini
            fi
            ;;
    esac
}

normalize_display_paths() {
    sed 's#/mnt/SDCARD/#/#g'
}

prepare_env() {
    mkdir -p "$APP_DATA_DIR"
    : > "$RUNTIME_DETECT_LOG"

    detect_spruce_platform

    # spruce launches RetroArch with --config pointing at this per-device file, so its
    # .retroarch/retroarch.cfg is never read (spruce/scripts/emu/lib/ra_functions.sh).
    APP_RETROARCH_CFG="/mnt/SDCARD/RetroArch/platform/retroarch-${APP_SPRUCE_PLATFORM}.cfg"

    export RAOFFLINEPROXY_CONFIG_DIR="$APP_DATA_DIR"
    export RAOFFLINEPROXY_RETROARCH_CFG="$APP_RETROARCH_CFG"
    export RAOFFLINEPROXY_APP_VERSION="${APP_VERSION#v}"
    export RAOFFLINEPROXY_CACHE_IMAGES=0
    export PYTHONPATH="$APP_PACKAGE_DIR${PYTHONPATH:+:$PYTHONPATH}"
    export LD_LIBRARY_PATH="$APP_LIB_DIR:/config/lib:/customer/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

    # The bundled SDL2 is the same build the Onion package ships; its "Mini" video driver
    # only exists on the hardware it was built for. Elsewhere leave the driver unset so
    # SDL picks its own and menu_sdl falls back to a plain fullscreen surface.
    if [ "$APP_SPRUCE_PLATFORM" = "MiyooMini" ]; then
        export SDL_VIDEODRIVER=Mini
    else
        unset SDL_VIDEODRIVER
    fi

    if [ -f "$APP_CERT_FILE" ]; then
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
    if [ -f "$runtime_root/lib/python3.9/site-packages/pip/_vendor/certifi/cacert.pem" ]; then
        export SSL_CERT_FILE="$runtime_root/lib/python3.9/site-packages/pip/_vendor/certifi/cacert.pem"
        export RAOFFLINEPROXY_CA_FILE="$runtime_root/lib/python3.9/site-packages/pip/_vendor/certifi/cacert.pem"
    fi
}

python_supports_backend() {
    candidate="$1"
    runtime_root="${2:-}"

    if [ -n "$runtime_root" ]; then
        PYTHONHOME="$runtime_root" LD_LIBRARY_PATH="$runtime_root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] >= 3 else 1)' >/dev/null 2>"$RUNTIME_DETECT_LOG"
        return $?
    fi

    "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] >= 3 else 1)' >/dev/null 2>"$RUNTIME_DETECT_LOG"
    return $?
}

capture_runtime_failure_reason() {
    if [ ! -s "$RUNTIME_DETECT_LOG" ]; then
        RUNTIME_FAILURE_REASON=
        return 0
    fi

    if IFS= read -r first_line < "$RUNTIME_DETECT_LOG"; then
        RUNTIME_FAILURE_REASON="$first_line"
        return 0
    fi

    RUNTIME_FAILURE_REASON=
}

resolve_python_bin() {
    if [ -x "$APP_RUNTIME_DIR/bin/python3" ]; then
        if python_supports_backend "$APP_RUNTIME_DIR/bin/python3" "$APP_RUNTIME_DIR"; then
            activate_runtime_env "$APP_RUNTIME_DIR"
            RESOLVED_PYTHON_BIN="$APP_RUNTIME_DIR/bin/python3"
            RUNTIME_FAILURE_REASON=
            return 0
        fi
        capture_runtime_failure_reason
    fi

    if [ -x "$APP_RUNTIME_DIR/python/bin/python3" ]; then
        if python_supports_backend "$APP_RUNTIME_DIR/python/bin/python3" "$APP_RUNTIME_DIR/python"; then
            activate_runtime_env "$APP_RUNTIME_DIR/python"
            RESOLVED_PYTHON_BIN="$APP_RUNTIME_DIR/python/bin/python3"
            RUNTIME_FAILURE_REASON=
            return 0
        fi
        capture_runtime_failure_reason
    fi

    if command -v python3 >/dev/null 2>&1; then
        candidate="$(command -v python3)"
        if python_supports_backend "$candidate"; then
            RESOLVED_PYTHON_BIN="$candidate"
            RUNTIME_FAILURE_REASON=
            return 0
        fi
        capture_runtime_failure_reason
    fi

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
