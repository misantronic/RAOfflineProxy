#!/bin/sh

APP_DIR=/mnt/SDCARD/App/RAOfflineProxy
APP_VERSION=v1.1.0-linux-alpha
APP_MAX_CACHED_GAMES=100
APP_DATA_DIR="$APP_DIR/data"
APP_RUNTIME_DIR="$APP_DIR/runtime"
APP_PACKAGE_DIR="$APP_DIR/app"
APP_RETROARCH_CFG=
APP_LOG_PANEL=/mnt/SDCARD/.tmp_update/bin/infoPanel
APP_CERT_FILE="$APP_RUNTIME_DIR/lib/python3.10/site-packages/pip/_vendor/certifi/cacert.pem"
APP_ONION_STARTUP_DIR=/mnt/SDCARD/.tmp_update/startup
APP_ONION_AUTOSTART_SCRIPT="$APP_ONION_STARTUP_DIR/raofflineproxy.sh"
APP_ONION_CHECKOFF_DIR=/mnt/SDCARD/.tmp_update/checkoff
APP_ONION_CHECKOFF_SCRIPT="$APP_ONION_CHECKOFF_DIR/raofflineproxy.sh"
APP_ACTIVE_RUNTIME_ROOT=
RESOLVED_PYTHON_BIN=
RUNTIME_FAILURE_REASON=
RUNTIME_DETECT_LOG="$APP_DATA_DIR/runtime-detect.log"

show_panel() {
    title="$1"
    message="$2"

    if [ -x "$APP_LOG_PANEL" ]; then
        LD_PRELOAD=/mnt/SDCARD/miyoo/lib/libpadsp.so "$APP_LOG_PANEL" -t "$title" -m "$message" --auto >/dev/null 2>&1 &
        return 0
    fi

    printf '%s\n%s\n' "$title" "$message"
    return 0
}

normalize_display_paths() {
    sed 's#/mnt/SDCARD/#/#g'
}

prepare_env() {
    mkdir -p "$APP_DATA_DIR"
    : > "$RUNTIME_DETECT_LOG"

    if [ -f /mnt/SDCARD/.tmp_update/config/retroarch.cfg ]; then
        APP_RETROARCH_CFG=/mnt/SDCARD/.tmp_update/config/retroarch.cfg
    else
        APP_RETROARCH_CFG=/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg
    fi

    export RAOFFLINEPROXY_CONFIG_DIR="$APP_DATA_DIR"
    export RAOFFLINEPROXY_RETROARCH_CFG="$APP_RETROARCH_CFG"
    export RAOFFLINEPROXY_CACHE_IMAGES=0
    export PYTHONPATH="$APP_PACKAGE_DIR${PYTHONPATH:+:$PYTHONPATH}"
    if [ -f "$APP_CERT_FILE" ]; then
        export SSL_CERT_FILE="$APP_CERT_FILE"
        export RAOFFLINEPROXY_CA_FILE="$APP_CERT_FILE"
    fi
}

activate_runtime_env() {
    runtime_root="$1"
    APP_ACTIVE_RUNTIME_ROOT="$runtime_root"

    export PYTHONHOME="$runtime_root"
    export LD_LIBRARY_PATH="$runtime_root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export PATH="$runtime_root/bin${PATH:+:$PATH}"
    if [ -f "$runtime_root/lib/python3.10/site-packages/pip/_vendor/certifi/cacert.pem" ]; then
        export SSL_CERT_FILE="$runtime_root/lib/python3.10/site-packages/pip/_vendor/certifi/cacert.pem"
        export RAOFFLINEPROXY_CA_FILE="$runtime_root/lib/python3.10/site-packages/pip/_vendor/certifi/cacert.pem"
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

    if command -v python >/dev/null 2>&1; then
        candidate="$(command -v python)"
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
    "$python_bin" -m raofflineproxy.main "$@"
}

log_path() {
    printf '%s\n' "$APP_DATA_DIR/service.log"
}

autostart_script_path() {
    printf '%s\n' "$APP_ONION_AUTOSTART_SCRIPT"
}

checkoff_script_path() {
    printf '%s\n' "$APP_ONION_CHECKOFF_SCRIPT"
}

install_onion_checkoff_script() {
    mkdir -p "$APP_ONION_CHECKOFF_DIR"

    template="$APP_DIR/checkoff-template.sh"
    if [ ! -f "$template" ]; then
        return 1
    fi

    if [ ! -f "$APP_ONION_CHECKOFF_SCRIPT" ] || ! cmp -s "$template" "$APP_ONION_CHECKOFF_SCRIPT"; then
        cp "$template" "$APP_ONION_CHECKOFF_SCRIPT"
        chmod +x "$APP_ONION_CHECKOFF_SCRIPT"
    fi

    return 0
}
