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
MENU_ITEM_COUNT=5

smart_cache_status() {
    run_backend "$PYTHON_BIN" smart-cache-status 2>/dev/null || printf '{"found_history":false,"total_candidates":0}\n'
}

pause_smart_cache_prompt() {
    count="$1"
    saved_tty="$(stty -g < /dev/tty)"
    choice=YES
    SMART_CACHE_ACTION=

    render_prompt() {
        clear
        printf 'RAOfflineProxy\n\n'
        printf 'Smart Cache found %s recent games.\n\n' "$count"
        printf 'Cache games: %s\n\n' "$choice"
        printf 'Use D-Pad to choose.\n'
        printf 'Press START to continue.\n'
    }

    render_prompt

    while :; do
        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        case "$key" in
            0d|0a|20|61|41|73|53)
                SMART_CACHE_ACTION="$choice"
                ;;
            1b)
                stty -echo -icanon min 0 time 1 < /dev/tty
                sequence="$(dd bs=1 count=2 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n')"
                case "$sequence" in
                    5b41|4f41|5b42|4f42|5b43|4f43|5b44|4f44)
                        if [ "$choice" = "YES" ]; then
                            choice=NO
                        else
                            choice=YES
                        fi
                        render_prompt
                        ;;
                esac
                ;;
        esac

        if [ -n "${SMART_CACHE_ACTION:-}" ]; then
            stty "$saved_tty" < /dev/tty
            drain_tty "$saved_tty"
            return 0
        fi
    done
}

run_smart_cache_flow() {
    result_line=
    backend_rc=0
    fifo_path="/tmp/raofflineproxy-smart-cache.$$"
    rm -f "$fifo_path"
    mkfifo "$fifo_path"

    run_backend "$PYTHON_BIN" run-smart-cache > "$fifo_path" 2>&1 &
    backend_pid=$!

    while IFS= read -r line; do
        case "$line" in
            *'"type":"progress"'*)
                scanned="$(printf '%s' "$line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("scanned", 0))')"
                total="$(printf '%s' "$line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("total", 0))')"
                current_label="$(printf '%s' "$line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("current_label", "..."))')"
                clear
                printf 'RAOfflineProxy\n\n'
                printf 'Smart Cache: %s / %s\n' "${scanned:-0}" "${total:-0}"
                printf 'Current: %s\n' "${current_label:-...}"
                ;;
            *'"type":"result"'*)
                result_line="$line"
                ;;
            *)
                clear
                printf 'RAOfflineProxy\n\n'
                printf '%s\n' "$line"
                ;;
        esac
    done < "$fifo_path"

    if ! wait "$backend_pid"; then
        backend_rc=$?
    fi
    rm -f "$fifo_path"

    if [ "$backend_rc" -ne 0 ]; then
        pause_prompt
        return 1
    fi

    clear
    printf 'RAOfflineProxy\n\n'
    if [ -n "$result_line" ]; then
        cached="$(printf '%s' "$result_line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("cached", 0))')"
        scanned="$(printf '%s' "$result_line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("scanned", 0))')"
        printf 'Smart Cache complete: %s games cached\n' "${cached:-0}"
        printf 'Scanned: %s\n' "${scanned:-0}"
    else
        printf 'Smart Cache complete\n'
    fi
    pause_prompt
}

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

maybe_offer_smart_cache() {
    status_json="$(smart_cache_status)"
    found_history="$(printf '%s' "$status_json" | sed -n 's/.*"found_history":\(true\|false\).*/\1/p')"
    total_candidates="$(printf '%s' "$status_json" | sed -n 's/.*"total_candidates":\([0-9][0-9]*\).*/\1/p')"

    if [ "$found_history" != "true" ]; then
        return 1
    fi

    pause_smart_cache_prompt "${total_candidates:-0}"
    if [ "$SMART_CACHE_ACTION" = "YES" ]; then
        run_smart_cache_flow
    fi

    return 0
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

clear_cached_games() {
    if run_backend "$PYTHON_BIN" clear-cached-games; then
        printf 'Cleared cached games\n'
        return 0
    fi

    printf 'Failed to clear cached games\n'
    return 1
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
    if [ "$DPAD_SELECTION" -ge 1 ] && [ "$DPAD_SELECTION" -le "$MENU_ITEM_COUNT" ]; then
        pending_choice="$DPAD_SELECTION"
    else
        pending_choice=1
        DPAD_SELECTION=1
    fi

    printf 'Select an action: %s' "$pending_choice"

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
            35)
                pending_choice=5
                DPAD_SELECTION=5
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
                        if [ "$DPAD_SELECTION" -ge "$MENU_ITEM_COUNT" ]; then
                            DPAD_SELECTION=1
                        else
                            DPAD_SELECTION=$((DPAD_SELECTION + 1))
                        fi
                        pending_choice="$DPAD_SELECTION"
                        ;;
                    5b42|4f42)
                        if [ "$DPAD_SELECTION" -le 1 ]; then
                            DPAD_SELECTION="$MENU_ITEM_COUNT"
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
    printf '3. Clear cached games\n'
    printf '4. %s\n' "$(autostart_menu_label | tr -d '\n')"
    printf '5. Exit\n\n'
    printf 'Use D-Pad to choose\n'

    read_choice
    choice="$CHOICE"

    clear
    case "$choice" in
        1)
            toggle_proxy
            if service_is_running; then
                if ! maybe_offer_smart_cache; then
                    pause_prompt
                fi
            else
                pause_prompt
            fi
            ;;
        2)
            show_cached_games || true
            pause_prompt
            ;;
        3)
            clear_cached_games
            pause_prompt
            ;;
        4)
            toggle_autostart
            pause_prompt
            ;;
        5)
            exit 0
            ;;
        *)
            printf 'Unknown selection: %s\n' "$choice"
            pause_prompt
            ;;
    esac
done
