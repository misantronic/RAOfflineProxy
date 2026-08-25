#!/bin/sh

APP_DIR=/mnt/SDCARD/Apps/RAOfflineProxy.pak
APP_VERSION=v1.11.1-alpha1
APP_MAX_CACHED_GAMES=100
APP_DATA_DIR="$APP_DIR/data"
APP_RUNTIME_DIR="$APP_DIR/runtime"
APP_PACKAGE_DIR="$APP_DIR/app"
APP_LIB_DIR="$APP_DIR/lib"
APP_RETROARCH_CFG=/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg
APP_CERT_FILE="$APP_RUNTIME_DIR/lib/python3.9/site-packages/pip/_vendor/certifi/cacert.pem"
# alliumd itself reads this file and exports TZ before running, but that happens later in
# .tmp_update/updater than our boot hook (which is prepended right after the shebang so it
# survives a dispatch that never returns), so anything started from the hook would
# otherwise inherit an empty TZ and stamp every award timestamp in UTC.
APP_ALLIUM_TZ_FILE=/mnt/SDCARD/.allium/state/timezone
APP_ACTIVE_RUNTIME_ROOT=
RESOLVED_PYTHON_BIN=
RUNTIME_FAILURE_REASON=
RUNTIME_DETECT_LOG="$APP_DATA_DIR/runtime-detect.log"

normalize_display_paths() {
    sed 's#/mnt/SDCARD/#/#g'
}

resolve_allium_timezone() {
    if [ -n "${TZ:-}" ]; then
        return 0
    fi

    if [ ! -r "$APP_ALLIUM_TZ_FILE" ]; then
        return 0
    fi

    # Exported verbatim, matching how .tmp_update/updater itself applies the value.
    allium_tz="$(cat "$APP_ALLIUM_TZ_FILE" 2>/dev/null || true)"
    if [ -n "$allium_tz" ]; then
        export TZ="$allium_tz"
    fi

    return 0
}

prepare_env() {
    mkdir -p "$APP_DATA_DIR"
    : > "$RUNTIME_DETECT_LOG"

    resolve_allium_timezone

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
    # Same "Mini" SDL2 vendor build Onion ships (see linux/onion/README.md): only
    # verified on Miyoo Mini hardware, which is the only Allium target this bundle covers.
    export SDL_VIDEODRIVER=Mini

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
