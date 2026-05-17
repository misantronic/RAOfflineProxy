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

DPAD_SELECTION=0

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

read_byte_hex() {
    dd bs=1 count=1 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n'
}

drain_tty() {
    saved_tty="$1"
    stty -echo -icanon min 0 time 0 < /dev/tty
    while :; do
        extra_key="$(read_byte_hex)"
        [ -z "$extra_key" ] && break
    done
    stty "$saved_tty" < /dev/tty
}

read_choice() {
    saved_tty="$(stty -g < /dev/tty)"
    pending_choice=

    printf 'Select an action: '

    while :; do
        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        choice=

        case "$key" in
            31)
                pending_choice=1
                DPAD_SELECTION=1
                ;;
            32)
                pending_choice=2
                DPAD_SELECTION=2
                ;;
            33)
                pending_choice=3
                DPAD_SELECTION=3
                ;;
            34)
                pending_choice=4
                DPAD_SELECTION=4
                ;;
            0d|0a|20|61|41|73|53)
                if [ -n "$pending_choice" ]; then
                    choice="$pending_choice"
                fi
                ;;
            08|7f|62|42|71|51)
                choice=4
                ;;
            1b)
                stty -echo -icanon min 0 time 1 < /dev/tty
                sequence="$(dd bs=1 count=2 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n')"
                case "$sequence" in
                    5b41|4f41)
                        if [ "$DPAD_SELECTION" -ge 4 ]; then
                            DPAD_SELECTION=1
                        else
                            DPAD_SELECTION=$((DPAD_SELECTION + 1))
                        fi
                        pending_choice="$DPAD_SELECTION"
                        ;;
                    5b42|4f42)
                        if [ "$DPAD_SELECTION" -le 1 ]; then
                            DPAD_SELECTION=4
                        else
                            DPAD_SELECTION=$((DPAD_SELECTION - 1))
                        fi
                        pending_choice="$DPAD_SELECTION"
                        ;;
                esac
                ;;
        esac

        printf '\r\033[KSelect an action: %s' "$pending_choice"

        if [ -n "$choice" ]; then
            printf '\n'
            stty "$saved_tty" < /dev/tty
            drain_tty "$saved_tty"
            CHOICE="$choice"
            return 0
        fi
    done
}

while :; do
    clear
    printf 'RAOfflineProxy\n\n'
    show_status || true
    printf '\n'
    printf '1. %s\n' "$(proxy_menu_label | tr -d '\n')"
    printf '2. Cached games\n'
    printf '3. %s\n' "$(autostart_menu_label | tr -d '\n')"
    printf '4. Exit\n\n'
    printf 'Use D-Pad to choose\n'

    read_choice
    choice="$CHOICE"

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
