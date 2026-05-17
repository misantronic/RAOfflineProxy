#!/bin/sh
set -eu

. /mnt/SDCARD/App/RAOfflineProxy/common.sh

prepare_env

if ! resolve_python_bin; then
    clear
    printf 'RAOfflineProxy\n\n'
    printf 'No compatible runtime was found.\n\n'
    printf 'Expected one of:\n'
    printf '  - /mnt/SDCARD/App/RAOfflineProxy/runtime/bin/python3\n'
    printf '  - /mnt/SDCARD/App/RAOfflineProxy/runtime/python/bin/python3\n'
    printf '  - python3 on PATH\n\n'
    if [ -n "$RUNTIME_FAILURE_REASON" ]; then
        printf 'Last runtime error:\n'
        printf '  %s\n\n' "$RUNTIME_FAILURE_REASON"
    fi
    printf 'RetroArch cfg default:\n'
    printf '  %s\n\n' "$RAOFFLINEPROXY_RETROARCH_CFG"
    printf 'Debug log:\n'
    printf '  %s\n\n' "$RUNTIME_DETECT_LOG"
    printf 'Press START to exit...'
    read _
    exit 1
fi

PYTHON_BIN="$RESOLVED_PYTHON_BIN"

install_onion_checkoff_script >/dev/null 2>&1 || true

autostart_is_enabled() {
    [ "$(run_backend "$PYTHON_BIN" autostart-status 2>/dev/null)" = "enabled" ]
}

service_is_running() {
    [ "$(run_backend "$PYTHON_BIN" status 2>/dev/null | grep '^Service running:' | awk '{print $3}')" = "yes" ]
}

proxy_menu_label() {
    if service_is_running; then
        printf 'Stop proxy\n'
    else
        printf 'Start proxy\n'
    fi
}

toggle_proxy() {
    if service_is_running; then
        run_backend "$PYTHON_BIN" stop-proxy || true
        return 0
    fi

    run_backend "$PYTHON_BIN" start-proxy || true
}

autostart_menu_label() {
    if autostart_is_enabled; then
        printf 'Disable autostart\n'
    else
        printf 'Enable autostart\n'
    fi
}

toggle_autostart() {
    if autostart_is_enabled; then
        run_backend "$PYTHON_BIN" disable-autostart || true
        if [ -x /mnt/SDCARD/App/RAOfflineProxy/autostart-cleanup.sh ]; then
            sh /mnt/SDCARD/App/RAOfflineProxy/autostart-cleanup.sh || true
        fi
        return 0
    fi

    run_backend "$PYTHON_BIN" enable-autostart || true
}

pause_prompt() {
    printf '\nPress START to continue...'
    read _
}

show_cached_games() {
    if ! run_backend "$PYTHON_BIN" cached-games; then
        return 1
    fi
    return 0
}

show_status() {
    if ! run_backend "$PYTHON_BIN" status | grep -v -E '^(Exists:|State file:)' | sed 's#^Config: /mnt/SDCARD/#Config: /#' ; then
        return 1
    fi
    return 0
}

while :; do
    clear
    printf 'RAOfflineProxy\n'
    printf 'Onion app wrapper\n\n'
    show_status || true
    printf '\n'
    printf '1. %s\n' "$(proxy_menu_label | tr -d '\n')"
    printf '2. Cached games\n'
    printf '3. %s\n' "$(autostart_menu_label | tr -d '\n')"
    printf '4. Exit\n\n'
    printf 'Select an action: '

    if ! read choice; then
        exit 0
    fi

    clear
    case "$choice" in
        1)
            toggle_proxy
            pause_prompt
            ;;
        2)
            show_cached_games || true
            pause_prompt
            ;;
        3)
            toggle_autostart
            pause_prompt
            ;;
        4)
            exit 0
            ;;
        *)
            printf 'Unknown selection: %s\n' "$choice"
            pause_prompt
            ;;
    esac
done
