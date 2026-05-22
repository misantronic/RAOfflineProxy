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
    printf '  %s\n\n' "$(printf '%s' "$RAOFFLINEPROXY_RETROARCH_CFG" | normalize_display_paths)"
    printf 'Debug log:\n'
    printf '  %s\n\n' "$RUNTIME_DETECT_LOG"
    printf 'Press START or A to exit...'
    saved_tty="$(stty -g < /dev/tty)"
    while :; do
        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        case "$key" in
            0d|0a|20|61|41|73|53)
                stty "$saved_tty" < /dev/tty
                drain_tty "$saved_tty"
                break
                ;;
        esac
    done
    exit 1
fi

PYTHON_BIN="$RESOLVED_PYTHON_BIN"

install_onion_checkoff_script >/dev/null 2>&1 || true

DPAD_SELECTION=0
MENU_ITEM_COUNT=5
BROWSER_VISIBLE_COUNT=15
BROWSER_LIST_TOP_ROW=5
BROWSER_TERM_COLUMNS=80
BROWSER_TAB=$(printf '\t')
MAIN_STATUS_TEXT=
MAIN_CACHED_COUNT=0
MAIN_PENDING_COUNT=0
MAIN_PROXY_RUNNING=0
MAIN_AUTOSTART_ENABLED=0
MAIN_PROXY_LABEL=
MAIN_AUTOSTART_LABEL=
BROWSER_DIR=
BROWSER_ROOT=
BROWSER_SELECTED_INDEX=1
BROWSER_SCROLL_OFFSET=0
BROWSER_ENTRY_COUNT=0
BROWSER_FILE_COUNT=0
BROWSER_ENTRIES_FILE=
CACHED_GAMES_COUNT=0
CACHED_GAMES_FILE=
CACHED_GAMES_SELECTED_INDEX=1
CACHED_GAMES_SCROLL_OFFSET=0

browser_cleanup() {
    if [ -n "${BROWSER_ENTRIES_FILE:-}" ] && [ -f "$BROWSER_ENTRIES_FILE" ]; then
        rm -f "$BROWSER_ENTRIES_FILE"
    fi
    if [ -n "${CACHED_GAMES_FILE:-}" ] && [ -f "$CACHED_GAMES_FILE" ]; then
        rm -f "$CACHED_GAMES_FILE"
    fi
}

trap browser_cleanup EXIT INT TERM

cached_games_count() {
    run_backend "$PYTHON_BIN" cached-games-count 2>/dev/null || printf '0\n'
}

pending_awards_count() {
    run_backend "$PYTHON_BIN" pending-awards-count 2>/dev/null || printf '0\n'
}

home_status() {
    run_backend_raw "$PYTHON_BIN" home-status 2>/dev/null || printf '{"cached_games_count":0,"pending_awards_count":0,"service_running":false,"service_pid":null,"autostart_enabled":false}\n'
}

browser_root() {
    printf '/mnt/SDCARD/Roms\n'
}

smart_cache_status() {
    run_backend "$PYTHON_BIN" smart-cache-status 2>/dev/null || printf '{"found_history":false,"total_candidates":0}\n'
}

pause_yes_no_prompt() {
    title_line="$1"
    action_label="$2"
    default_choice="${3:-YES}"
    saved_tty="$(stty -g < /dev/tty)"
    choice="$default_choice"
    YES_NO_ACTION=

    render_prompt() {
        clear
        printf 'RAOfflineProxy\n\n'
        printf '%s\n\n' "$title_line"
        printf '%s: %s\n\n' "$action_label" "$choice"
        printf 'Use D-Pad to choose.\n'
        printf 'Press START or A to continue.\n'
    }

    render_prompt

    while :; do
        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        case "$key" in
            0d|0a|20|61|41|73|53)
                YES_NO_ACTION="$choice"
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

        if [ -n "${YES_NO_ACTION:-}" ]; then
            stty "$saved_tty" < /dev/tty
            drain_tty "$saved_tty"
            return 0
        fi
    done
}

pause_smart_cache_prompt() {
    count="$1"
    pause_yes_no_prompt "Smart Cache found ${count} recent games." 'Cache games' 'YES'
    SMART_CACHE_ACTION="$YES_NO_ACTION"
}

run_cache_progress_flow() {
    title="$1"
    command_name="$2"
    total_count="$3"
    target_path="${4:-}"
    result_line=
    backend_rc=0
    fifo_path="/tmp/raofflineproxy-smart-cache.$$"
    rm -f "$fifo_path"
    mkfifo "$fifo_path"

    clear
    printf 'RAOfflineProxy\n\n'
    printf '%s: 0 / %s\n' "$title" "${total_count:-0}"
    printf 'Preparing...\n'

    if [ -n "$target_path" ]; then
        run_backend_raw "$PYTHON_BIN" "$command_name" --path "$target_path" > "$fifo_path" 2>&1 &
    else
        run_backend_raw "$PYTHON_BIN" "$command_name" > "$fifo_path" 2>&1 &
    fi
    backend_pid=$!

    while IFS= read -r line; do
        case "$line" in
            *'"type":"progress"'*)
                scanned="$(printf '%s' "$line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("scanned", 0))')"
                total="$(printf '%s' "$line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("total", 0))')"
                current_label="$(printf '%s' "$line" | "$PYTHON_BIN" -c 'import json, sys; print(json.loads(sys.stdin.read()).get("current_label", "..."))')"
                clear
                printf 'RAOfflineProxy\n\n'
                printf '%s: %s / %s\n' "$title" "${scanned:-0}" "${total:-0}"
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
        printf '%s complete: %s games cached\n' "$title" "${cached:-0}"
        printf 'Scanned: %s\n' "${scanned:-0}"
    else
        printf '%s complete\n' "$title"
    fi
    pause_prompt
}

run_smart_cache_flow() {
    total_count="$1"
    run_cache_progress_flow 'Smart Cache' 'run-smart-cache' "$total_count"
}

autostart_is_enabled() {
    [ "$(run_backend "$PYTHON_BIN" autostart-status 2>/dev/null)" = "enabled" ]
}

service_is_running() {
    [ "${MAIN_PROXY_RUNNING:-0}" -eq 1 ]
}

proxy_menu_label() {
    if [ "${MAIN_PROXY_RUNNING:-0}" -eq 1 ]; then
        printf 'Stop proxy\n'
    else
        printf 'Start proxy\n'
    fi
}

toggle_proxy() {
    if [ "${MAIN_PROXY_RUNNING:-0}" -eq 1 ]; then
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
        run_smart_cache_flow "${total_candidates:-0}"
    fi

    return 0
}

autostart_menu_label() {
    if [ "${MAIN_AUTOSTART_ENABLED:-0}" -eq 1 ]; then
        printf 'Disable autostart\n'
    else
        printf 'Enable autostart\n'
    fi
}

toggle_autostart() {
    if [ "${MAIN_AUTOSTART_ENABLED:-0}" -eq 1 ]; then
        run_backend "$PYTHON_BIN" disable-autostart || true
        if [ -x /mnt/SDCARD/App/RAOfflineProxy/autostart-cleanup.sh ]; then
            sh /mnt/SDCARD/App/RAOfflineProxy/autostart-cleanup.sh || true
        fi
        return 0
    fi

    run_backend "$PYTHON_BIN" enable-autostart || true
}

pause_prompt() {
    saved_tty="$(stty -g < /dev/tty)"
    printf '\nPress START or A to continue...'
    while :; do
        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        case "$key" in
            0d|0a|20|61|41|73|53)
                stty "$saved_tty" < /dev/tty
                drain_tty "$saved_tty"
                printf '\n'
                return 0
                ;;
        esac
    done
}

show_cached_games_view() {
    cached_games_redraw=full
    set -- $(stty size < /dev/tty)
    BROWSER_TERM_COLUMNS=${2:-80}

    if ! cached_games_reload; then
        return 1
    fi

    saved_tty="$(stty -g < /dev/tty)"
    while :; do
        case "$cached_games_redraw" in
            full)
                render_cached_games_full
                ;;
            selection)
                render_cached_games_selection_change "$CACHED_GAMES_PREVIOUS_SELECTION_INDEX" "$CACHED_GAMES_CURRENT_SELECTION_INDEX"
                ;;
            list)
                render_cached_games_list
                ;;
        esac
        cached_games_redraw=

        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        case "$key" in
            0d|0a|20|61|41|73|53)
                cached_games_remove_selected
                if ! cached_games_reload; then
                    stty "$saved_tty" < /dev/tty
                    drain_tty "$saved_tty"
                    return 0
                fi
                cached_games_redraw=full
                ;;
            08|7f)
                open_rom_browser || true
                if ! cached_games_reload; then
                    stty "$saved_tty" < /dev/tty
                    drain_tty "$saved_tty"
                    return 0
                fi
                cached_games_redraw=full
                ;;
            09)
                stty "$saved_tty" < /dev/tty
                drain_tty "$saved_tty"
                pause_yes_no_prompt 'Do you really want to clear all games?' 'Clear games' 'NO'
                if [ "$YES_NO_ACTION" = 'YES' ]; then
                    clear
                    clear_cached_games
                    pause_prompt
                    return 0
                fi
                cached_games_redraw=full
                ;;
            1b)
                stty -echo -icanon min 0 time 1 < /dev/tty
                sequence="$(dd bs=1 count=2 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n')"
                case "$sequence" in
                    5b41|4f41)
                        cached_games_move_selection up
                        drain_tty "$saved_tty"
                        ;;
                    5b42|4f42)
                        cached_games_move_selection down
                        drain_tty "$saved_tty"
                        ;;
                    5b44|4f44)
                        stty "$saved_tty" < /dev/tty
                        drain_tty "$saved_tty"
                        return 0
                        ;;
                esac
                ;;
        esac
    done
}

show_cached_games() {
    printf 'RAOfflineProxy > Cached games\n\n'
    if ! run_backend "$PYTHON_BIN" cached-games; then
        return 1
    fi
    return 0
}

show_pending_awards() {
    printf 'RAOfflineProxy > Pending awards\n\n'
    if ! run_backend "$PYTHON_BIN" pending-awards; then
        return 1
    fi
    return 0
}

clear_cached_games() {
    if run_backend "$PYTHON_BIN" clear-cached-games; then
        return 0
    fi

    printf 'Failed to clear cached games\n'
    return 1
}

load_cached_games_entries() {
    if [ -n "${CACHED_GAMES_FILE:-}" ] && [ -f "$CACHED_GAMES_FILE" ]; then
        rm -f "$CACHED_GAMES_FILE"
    fi

    CACHED_GAMES_FILE="/tmp/raofflineproxy-cached-games.$$"
    if ! run_backend_raw "$PYTHON_BIN" cached-games > "$CACHED_GAMES_FILE" 2>/dev/null; then
        rm -f "$CACHED_GAMES_FILE"
        CACHED_GAMES_FILE=
        return 1
    fi

    count=$(wc -l < "$CACHED_GAMES_FILE" | tr -d ' ')
    CACHED_GAMES_COUNT=${count:-0}
    return 0
}

cached_games_entry_line() {
    index="$1"
    sed -n "${index}p" "$CACHED_GAMES_FILE"
}

cached_games_reload() {
    if ! load_cached_games_entries; then
        clear
        printf 'RAOfflineProxy > Cached games\n\n'
        printf 'Failed to read cached games\n'
        pause_prompt
        return 1
    fi

    if [ "$CACHED_GAMES_COUNT" -le 0 ]; then
        CACHED_GAMES_SELECTED_INDEX=0
        CACHED_GAMES_SCROLL_OFFSET=0
        return 0
    fi

    if [ "$CACHED_GAMES_SELECTED_INDEX" -le 0 ]; then
        CACHED_GAMES_SELECTED_INDEX=1
    fi
    if [ "$CACHED_GAMES_SELECTED_INDEX" -gt "$CACHED_GAMES_COUNT" ]; then
        CACHED_GAMES_SELECTED_INDEX="$CACHED_GAMES_COUNT"
    fi
    if [ "$CACHED_GAMES_SELECTED_INDEX" -le "$CACHED_GAMES_SCROLL_OFFSET" ]; then
        CACHED_GAMES_SCROLL_OFFSET=$((CACHED_GAMES_SELECTED_INDEX - 1))
    fi
    if [ "$CACHED_GAMES_SELECTED_INDEX" -gt $((CACHED_GAMES_SCROLL_OFFSET + BROWSER_VISIBLE_COUNT)) ]; then
        CACHED_GAMES_SCROLL_OFFSET=$((CACHED_GAMES_SELECTED_INDEX - BROWSER_VISIBLE_COUNT))
    fi
    if [ "$CACHED_GAMES_SCROLL_OFFSET" -lt 0 ]; then
        CACHED_GAMES_SCROLL_OFFSET=0
    fi
    return 0
}

cached_games_selected_game_id() {
    if [ "$CACHED_GAMES_SELECTED_INDEX" -le 0 ] || [ "$CACHED_GAMES_COUNT" -le 0 ]; then
        return 1
    fi

    line="$(cached_games_entry_line "$CACHED_GAMES_SELECTED_INDEX")"
    game_id=$(printf '%s' "$line" | sed -n 's/.*##GAMEID:\([0-9][0-9]*\)$/\1/p')
    if [ -z "$game_id" ]; then
        return 1
    fi

    printf '%s\n' "$game_id"
    return 0
}

cached_games_display_text() {
    line="$1"
    printf '%s' "$line" | sed 's/ ##GAMEID:[0-9][0-9]*$//'
}

cached_games_truncated_text() {
    line="$1"
    display_text="$(cached_games_display_text "$line")"
    suffix=$(printf '%s' "$display_text" | sed -n 's/^.*\( ([0-9][0-9]* unlocks)\)$/\1/p')
    if [ -z "$suffix" ]; then
        truncate_text "$display_text" $((BROWSER_TERM_COLUMNS - 2))
        return 0
    fi

    title=${display_text%$suffix}
    suffix_len=$(printf '%s' "$suffix" | wc -c | tr -d ' ')
    available_title_len=$((BROWSER_TERM_COLUMNS - 2 - suffix_len))
    if [ "$available_title_len" -lt 1 ]; then
        truncate_text "$display_text" $((BROWSER_TERM_COLUMNS - 2))
        return 0
    fi

    printf '%s%s' "$(truncate_text "$title" "$available_title_len")" "$suffix"
}

render_cached_games_row() {
    index="$1"
    screen_row=$((BROWSER_LIST_TOP_ROW + index - CACHED_GAMES_SCROLL_OFFSET - 1))

    if [ "$screen_row" -lt "$BROWSER_LIST_TOP_ROW" ] || [ "$screen_row" -ge $((BROWSER_LIST_TOP_ROW + BROWSER_VISIBLE_COUNT)) ]; then
        return 0
    fi

    printf '\033[%s;1H' "$screen_row"

    if [ "$CACHED_GAMES_COUNT" -eq 0 ] && [ "$index" -eq 1 ]; then
        printf 'No cached games.\033[K'
        return 0
    fi

    line="$(cached_games_entry_line "$index")"
    marker=' '
    if [ "$index" -eq "$CACHED_GAMES_SELECTED_INDEX" ]; then
        marker='>'
    fi
    printf '%s %s\033[K' "$marker" "$(cached_games_truncated_text "$line")"
}

render_cached_games_list() {
    printf '\033[%s;1H' "$BROWSER_LIST_TOP_ROW"
    line_index=1
    printed=0
    start_index=$((CACHED_GAMES_SCROLL_OFFSET + 1))
    end_index=$((CACHED_GAMES_SCROLL_OFFSET + BROWSER_VISIBLE_COUNT))

    if [ "$CACHED_GAMES_COUNT" -eq 0 ]; then
        render_cached_games_row 1
        printf '\n'
        printed=1
    else
        while [ "$line_index" -le "$CACHED_GAMES_COUNT" ]; do
            if [ "$line_index" -ge "$start_index" ] && [ "$line_index" -le "$end_index" ]; then
                render_cached_games_row "$line_index"
                printf '\n'
                printed=$((printed + 1))
            fi
            line_index=$((line_index + 1))
        done
    fi

    while [ "$printed" -lt "$BROWSER_VISIBLE_COUNT" ]; do
        printf '\033[K\n'
        printed=$((printed + 1))
    done
}

render_cached_games_help() {
    printf '\033[%s;1H' "$((BROWSER_LIST_TOP_ROW + BROWSER_VISIBLE_COUNT + 1))"
    printf '\033[K\n'
    printf 'Use D-Pad up/down to move.\033[K\n'
    printf 'Press LEFT to go back.\033[K\n'
    printf 'Press START or A to remove selected game.\033[K\n'
    printf 'Press R2 to add ROMs.\033[K\n'
    printf 'Press L2 to clear cached games...\033[K\n'
    printf '\033[J'
}

render_cached_games_full() {
    printf '\033[2J\033[H'
    printf 'RAOfflineProxy > Cached games\033[K\n\n'
    printf '%s / %s games cached\033[K\n\n' "$CACHED_GAMES_COUNT" "$APP_MAX_CACHED_GAMES"
    render_cached_games_list
    render_cached_games_help
}

render_cached_games_selection_change() {
    previous_index="$1"
    current_index="$2"
    render_cached_games_row "$previous_index"
    render_cached_games_row "$current_index"
}

cached_games_move_selection() {
    direction="$1"
    if [ "$CACHED_GAMES_COUNT" -le 0 ]; then
        cached_games_redraw=list
        return 0
    fi

    previous_index="$CACHED_GAMES_SELECTED_INDEX"
    previous_scroll="$CACHED_GAMES_SCROLL_OFFSET"

    if [ "$direction" = "down" ]; then
        if [ "$CACHED_GAMES_SELECTED_INDEX" -ge "$CACHED_GAMES_COUNT" ]; then
            CACHED_GAMES_SELECTED_INDEX=1
            CACHED_GAMES_SCROLL_OFFSET=0
        else
            CACHED_GAMES_SELECTED_INDEX=$((CACHED_GAMES_SELECTED_INDEX + 1))
            if [ "$CACHED_GAMES_SELECTED_INDEX" -gt $((CACHED_GAMES_SCROLL_OFFSET + BROWSER_VISIBLE_COUNT)) ]; then
                CACHED_GAMES_SCROLL_OFFSET=$((CACHED_GAMES_SELECTED_INDEX - BROWSER_VISIBLE_COUNT))
            fi
        fi
    else
        if [ "$CACHED_GAMES_SELECTED_INDEX" -le 1 ]; then
            CACHED_GAMES_SELECTED_INDEX="$CACHED_GAMES_COUNT"
            if [ "$CACHED_GAMES_COUNT" -gt "$BROWSER_VISIBLE_COUNT" ]; then
                CACHED_GAMES_SCROLL_OFFSET=$((CACHED_GAMES_COUNT - BROWSER_VISIBLE_COUNT))
            else
                CACHED_GAMES_SCROLL_OFFSET=0
            fi
        else
            CACHED_GAMES_SELECTED_INDEX=$((CACHED_GAMES_SELECTED_INDEX - 1))
            if [ "$CACHED_GAMES_SELECTED_INDEX" -le "$CACHED_GAMES_SCROLL_OFFSET" ]; then
                CACHED_GAMES_SCROLL_OFFSET=$((CACHED_GAMES_SELECTED_INDEX - 1))
            fi
        fi
    fi

    if [ "$previous_scroll" -eq "$CACHED_GAMES_SCROLL_OFFSET" ]; then
        cached_games_redraw=selection
        CACHED_GAMES_PREVIOUS_SELECTION_INDEX="$previous_index"
        CACHED_GAMES_CURRENT_SELECTION_INDEX="$CACHED_GAMES_SELECTED_INDEX"
    else
        cached_games_redraw=list
    fi
}

cached_games_remove_selected() {
    game_id="$(cached_games_selected_game_id)" || return 0
    clear
    printf 'RAOfflineProxy > Cached games\n\n'
    if run_backend "$PYTHON_BIN" remove-cached-game --game-id "$game_id"; then
        printf '\n'
        pause_prompt
        return 0
    fi

    pause_prompt
    return 1
}

load_browser_entries() {
    target_dir="$1"

    if [ -n "${BROWSER_ENTRIES_FILE:-}" ] && [ -f "$BROWSER_ENTRIES_FILE" ]; then
        rm -f "$BROWSER_ENTRIES_FILE"
    fi

    BROWSER_ENTRIES_FILE="/tmp/raofflineproxy-browser.$$"
    if ! run_backend_raw "$PYTHON_BIN" browser-list-fast --path "$target_dir" > "$BROWSER_ENTRIES_FILE" 2>/dev/null; then
        rm -f "$BROWSER_ENTRIES_FILE"
        BROWSER_ENTRIES_FILE=
        return 1
    fi

    count=$(wc -l < "$BROWSER_ENTRIES_FILE" | tr -d ' ')
    BROWSER_ENTRY_COUNT=${count:-0}
    BROWSER_FILE_COUNT=0
    if [ "$BROWSER_ENTRY_COUNT" -gt 0 ]; then
        line_index=1
        while [ "$line_index" -le "$BROWSER_ENTRY_COUNT" ]; do
            entry_line="$(browser_entry_line "$line_index")"
            parse_browser_entry_line "$entry_line"
            if [ "$BROWSER_ENTRY_IS_DIR" != "1" ]; then
                BROWSER_FILE_COUNT=$((BROWSER_FILE_COUNT + 1))
            fi
            line_index=$((line_index + 1))
        done
    fi
    return 0
}

browser_entry_line() {
    index="$1"
    sed -n "${index}p" "$BROWSER_ENTRIES_FILE"
}

parse_browser_entry_line() {
    entry_line="$1"
    BROWSER_ENTRY_IS_DIR=${entry_line%%"$BROWSER_TAB"*}
    rest=${entry_line#*"$BROWSER_TAB"}
    BROWSER_ENTRY_IS_CACHED=${rest%%"$BROWSER_TAB"*}
    rest=${rest#*"$BROWSER_TAB"}
    BROWSER_ENTRY_PATH=${rest%%"$BROWSER_TAB"*}
    BROWSER_ENTRY_NAME=${rest#*"$BROWSER_TAB"}
}

truncate_text() {
    text="$1"
    max_len="$2"

    if [ "$max_len" -le 0 ]; then
        printf ''
        return 0
    fi

    printf '%s' "$text" | cut -c1-"$max_len"
}

render_browser_header() {
    printf '\033[H'
    printf 'RAOfflineProxy > Add ROMs\033[K\n'
    printf '\033[K\n'
    printf 'Path: %s\033[K\n' "$(truncate_text "$(printf '%s' "$BROWSER_DIR" | normalize_display_paths)" $((BROWSER_TERM_COLUMNS - 6)))"
    printf '\033[K\n'
}

render_browser_row() {
    index="$1"
    screen_row=$((BROWSER_LIST_TOP_ROW + index - BROWSER_SCROLL_OFFSET - 1))

    if [ "$screen_row" -lt "$BROWSER_LIST_TOP_ROW" ] || [ "$screen_row" -ge $((BROWSER_LIST_TOP_ROW + BROWSER_VISIBLE_COUNT)) ]; then
        return 0
    fi

    printf '\033[%s;1H' "$screen_row"

    if [ "$BROWSER_ENTRY_COUNT" -eq 0 ] && [ "$index" -eq 1 ]; then
        printf 'No ROMs found here.\033[K'
        return 0
    fi

    entry_line="$(browser_entry_line "$index")"
    parse_browser_entry_line "$entry_line"

    marker=' '
    if [ "$index" -eq "$BROWSER_SELECTED_INDEX" ]; then
        marker='>'
    fi

    prefix=''
    if [ "$BROWSER_ENTRY_IS_DIR" = "1" ]; then
        prefix='[DIR] '
    elif [ "$BROWSER_ENTRY_IS_CACHED" = "1" ]; then
        prefix='* '
    fi

    entry_display="$(truncate_text "${prefix}${BROWSER_ENTRY_NAME}" $((BROWSER_TERM_COLUMNS - 2)))"
    printf '%s %s\033[K' "$marker" "$entry_display"
}

render_browser_list() {
    printf '\033[%s;1H' "$BROWSER_LIST_TOP_ROW"

    line_index=1
    printed=0
    start_index=$((BROWSER_SCROLL_OFFSET + 1))
    end_index=$((BROWSER_SCROLL_OFFSET + BROWSER_VISIBLE_COUNT))

    if [ "$BROWSER_ENTRY_COUNT" -eq 0 ]; then
        render_browser_row 1
        printf '\n'
        printed=1
    else
        while [ "$line_index" -le "$BROWSER_ENTRY_COUNT" ]; do
            if [ "$line_index" -ge "$start_index" ] && [ "$line_index" -le "$end_index" ]; then
                render_browser_row "$line_index"
                printf '\n'
                printed=$((printed + 1))
            fi
            line_index=$((line_index + 1))
        done
    fi

    while [ "$printed" -lt "$BROWSER_VISIBLE_COUNT" ]; do
        printf '\033[K\n'
        printed=$((printed + 1))
    done
}

render_browser_help() {
    printf '\033[%s;1H' "$((BROWSER_LIST_TOP_ROW + BROWSER_VISIBLE_COUNT + 1))"
    printf '\033[K\n'
    printf 'Use D-Pad up/down to move.\033[K\n'
    printf 'Press LEFT to go back.\033[K\n'
    printf 'Press START or A to select.\033[K\n'
    if [ "$BROWSER_FILE_COUNT" -gt 0 ]; then
        printf 'Press R2 to add folder.\033[K\n'
    else
        printf '\033[K\n'
    fi
    printf '\033[J'
}

render_browser_full() {
    printf '\033[2J\033[H'
    render_browser_header
    render_browser_list
    render_browser_help
}

render_browser_loading() {
    printf '\033[2J\033[H'
    printf 'RAOfflineProxy > Add ROMs\033[K\n\n'
    printf 'Loading...\033[K\n'
    printf '\033[J'
}

render_browser_content() {
    render_browser_header
    render_browser_list
}

render_browser_selection_change() {
    previous_index="$1"
    current_index="$2"

    render_browser_row "$previous_index"
    render_browser_row "$current_index"
}

render_main_menu_item() {
    index="$1"
    label="$2"
    marker=' '
    if [ "$DPAD_SELECTION" -eq "$index" ]; then
        marker='>'
    fi
    printf '%s %s. %s\033[K\n' "$marker" "$index" "$label"
}

render_main_menu() {
    printf '\033[H'
    printf 'RAOfflineProxy (%s)\033[K\n\n' "$APP_VERSION"

    if [ -n "${MAIN_STATUS_TEXT:-}" ]; then
        printf '%s\n' "$MAIN_STATUS_TEXT" | while IFS= read -r line; do
            printf '%s\033[K\n' "$line"
        done
    else
        printf '\033[K\n'
    fi

    printf '\n'
    render_main_menu_item 1 "$MAIN_PROXY_LABEL"
    render_main_menu_item 2 "Cached games (${MAIN_CACHED_COUNT})"
    render_main_menu_item 3 "Pending awards (${MAIN_PENDING_COUNT})"
    render_main_menu_item 4 "$MAIN_AUTOSTART_LABEL"
    render_main_menu_item 5 'Exit'
    printf '\n'
    printf 'Use D-Pad up/down to move.\033[K\n'
    printf 'Press START or A to select.\033[K\n'
    printf '\033[J'
}

render_main_menu_loading() {
    printf '\033[2J\033[H'
    printf 'RAOfflineProxy (%s)\033[K\n\n' "$APP_VERSION"
    printf 'Loading...\033[K\n'
    printf '\033[J'
}

browser_set_dir() {
    target_dir="$1"
    if ! load_browser_entries "$target_dir"; then
        clear
        printf 'RAOfflineProxy > Add ROMs\n\n'
        printf 'Failed to read: %s\n' "$(printf '%s' "$target_dir" | normalize_display_paths)"
        pause_prompt
        return 1
    fi

    BROWSER_DIR="$target_dir"
    if [ "$BROWSER_ENTRY_COUNT" -le 0 ]; then
        BROWSER_SELECTED_INDEX=0
    else
        BROWSER_SELECTED_INDEX=1
    fi
    BROWSER_SCROLL_OFFSET=0
    return 0
}

browser_go_back() {
    if [ "$BROWSER_DIR" = "$BROWSER_ROOT" ]; then
        return 1
    fi

    parent_dir=$(dirname "$BROWSER_DIR")
    browser_set_dir "$parent_dir"
    return 0
}

browser_move_selection() {
    direction="$1"
    if [ "$BROWSER_ENTRY_COUNT" -le 0 ]; then
        browser_redraw=list
        return 0
    fi

    previous_index="$BROWSER_SELECTED_INDEX"
    previous_scroll="$BROWSER_SCROLL_OFFSET"

    if [ "$direction" = "down" ]; then
        if [ "$BROWSER_SELECTED_INDEX" -ge "$BROWSER_ENTRY_COUNT" ]; then
            BROWSER_SELECTED_INDEX=1
            BROWSER_SCROLL_OFFSET=0
        else
            BROWSER_SELECTED_INDEX=$((BROWSER_SELECTED_INDEX + 1))
            if [ "$BROWSER_SELECTED_INDEX" -gt $((BROWSER_SCROLL_OFFSET + BROWSER_VISIBLE_COUNT)) ]; then
                BROWSER_SCROLL_OFFSET=$((BROWSER_SELECTED_INDEX - BROWSER_VISIBLE_COUNT))
            fi
        fi
    else
        if [ "$BROWSER_SELECTED_INDEX" -le 1 ]; then
            BROWSER_SELECTED_INDEX="$BROWSER_ENTRY_COUNT"
            if [ "$BROWSER_ENTRY_COUNT" -gt "$BROWSER_VISIBLE_COUNT" ]; then
                BROWSER_SCROLL_OFFSET=$((BROWSER_ENTRY_COUNT - BROWSER_VISIBLE_COUNT))
            else
                BROWSER_SCROLL_OFFSET=0
            fi
        else
            BROWSER_SELECTED_INDEX=$((BROWSER_SELECTED_INDEX - 1))
            if [ "$BROWSER_SELECTED_INDEX" -le "$BROWSER_SCROLL_OFFSET" ]; then
                BROWSER_SCROLL_OFFSET=$((BROWSER_SELECTED_INDEX - 1))
            fi
        fi
    fi

    if [ "$previous_scroll" -eq "$BROWSER_SCROLL_OFFSET" ]; then
        browser_redraw=selection
        BROWSER_PREVIOUS_SELECTION_INDEX="$previous_index"
        BROWSER_CURRENT_SELECTION_INDEX="$BROWSER_SELECTED_INDEX"
    else
        browser_redraw=list
    fi
}

browser_activate_selected() {
    if [ "$BROWSER_ENTRY_COUNT" -le 0 ] || [ "$BROWSER_SELECTED_INDEX" -le 0 ]; then
        return 0
    fi

    entry_line="$(browser_entry_line "$BROWSER_SELECTED_INDEX")"
    parse_browser_entry_line "$entry_line"

    if [ "$BROWSER_ENTRY_IS_DIR" = "1" ]; then
        browser_set_dir "$BROWSER_ENTRY_PATH"
        return 0
    fi

    clear
    printf 'RAOfflineProxy > Add ROMs\n\n'
    printf 'Caching: %s\n' "$BROWSER_ENTRY_NAME"
    if run_backend "$PYTHON_BIN" cache-rom --path "$BROWSER_ENTRY_PATH"; then
        printf '\n'
        pause_prompt
        browser_set_dir "$BROWSER_DIR"
        return 0
    fi

    pause_prompt
    browser_set_dir "$BROWSER_DIR"
    return 1
}

browser_cache_folder_listing() {
    if [ "$BROWSER_FILE_COUNT" -le 0 ]; then
        return 0
    fi

    if run_cache_progress_flow 'Folder Cache' 'cache-folder-listing' "$BROWSER_FILE_COUNT" "$BROWSER_DIR"; then
        browser_set_dir "$BROWSER_DIR"
        return 0
    fi

    browser_set_dir "$BROWSER_DIR"
    return 1
}

open_rom_browser() {
    browser_redraw=full
    set -- $(stty size < /dev/tty)
    BROWSER_TERM_COLUMNS=${2:-80}
    render_browser_loading

    BROWSER_ROOT="$(browser_root | tr -d '\n')"
    if [ -z "$BROWSER_ROOT" ]; then
        BROWSER_ROOT=/mnt/SDCARD/Roms
    fi

    if ! browser_set_dir "$BROWSER_ROOT"; then
        return 1
    fi

    saved_tty="$(stty -g < /dev/tty)"
    while :; do
        case "$browser_redraw" in
            full)
                render_browser_full
                ;;
            content)
                render_browser_content
                ;;
            selection)
                render_browser_selection_change "$BROWSER_PREVIOUS_SELECTION_INDEX" "$BROWSER_CURRENT_SELECTION_INDEX"
                ;;
            list)
                render_browser_list
                ;;
        esac
        browser_redraw=

        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"

        case "$key" in
            0d|0a|20|61|41|73|53)
                browser_activate_selected
                browser_redraw=full
                ;;
            08|7f)
                if [ "$BROWSER_FILE_COUNT" -gt 0 ]; then
                    browser_cache_folder_listing
                    browser_redraw=full
                fi
                ;;
            1b)
                stty -echo -icanon min 0 time 1 < /dev/tty
                sequence="$(dd bs=1 count=2 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n')"
                case "$sequence" in
                    5b41|4f41)
                        browser_move_selection up
                        drain_tty "$saved_tty"
                        ;;
                    5b42|4f42)
                        browser_move_selection down
                        drain_tty "$saved_tty"
                        ;;
                    5b44|4f44)
                        if ! browser_go_back; then
                            stty "$saved_tty" < /dev/tty
                            drain_tty "$saved_tty"
                            return 0
                        fi
                        browser_redraw=content
                        ;;
                esac
                ;;
        esac
    done
}

show_status() {
    if ! run_backend "$PYTHON_BIN" service-status; then
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

    render_main_menu

    while :; do
        stty -echo -icanon min 1 time 0 < /dev/tty
        key="$(read_byte_hex)"
        choice=

        case "$key" in
            0d|0a|20|61|41|73|53)
                if [ -n "$pending_choice" ]; then
                    choice="$pending_choice"
                fi
                ;;
            1b)
                stty -echo -icanon min 0 time 1 < /dev/tty
                sequence="$(dd bs=1 count=2 2>/dev/null < /dev/tty | od -An -tx1 | tr -d ' \n')"
                case "$sequence" in
                    5b41|4f41)
                        if [ "$DPAD_SELECTION" -le 1 ]; then
                            DPAD_SELECTION="$MENU_ITEM_COUNT"
                        else
                            DPAD_SELECTION=$((DPAD_SELECTION - 1))
                        fi
                        pending_choice="$DPAD_SELECTION"
                        drain_tty "$saved_tty"
                        ;;
                    5b42|4f42)
                        if [ "$DPAD_SELECTION" -ge "$MENU_ITEM_COUNT" ]; then
                            DPAD_SELECTION=1
                        else
                            DPAD_SELECTION=$((DPAD_SELECTION + 1))
                        fi
                        pending_choice="$DPAD_SELECTION"
                        drain_tty "$saved_tty"
                        ;;
                esac
                ;;
        esac

        if [ -z "$choice" ]; then
            render_main_menu
        fi

        if [ -n "$choice" ]; then
            stty "$saved_tty" < /dev/tty
            drain_tty "$saved_tty"
            CHOICE="$choice"
            return 0
        fi
    done
}

while :; do
    render_main_menu_loading
    status_json="$(home_status)"
    MAIN_CACHED_COUNT="$(printf '%s' "$status_json" | sed -n 's/.*"cached_games_count":\([0-9][0-9]*\).*/\1/p')"
    MAIN_PENDING_COUNT="$(printf '%s' "$status_json" | sed -n 's/.*"pending_awards_count":\([0-9][0-9]*\).*/\1/p')"
    MAIN_PROXY_RUNNING="$(printf '%s' "$status_json" | sed -n 's/.*"service_running":\(true\|false\).*/\1/p' | sed 's/true/1/;s/false/0/')"
    MAIN_PROXY_PID="$(printf '%s' "$status_json" | sed -n 's/.*"service_pid":\([0-9][0-9]*\).*/\1/p')"
    MAIN_AUTOSTART_ENABLED="$(printf '%s' "$status_json" | sed -n 's/.*"autostart_enabled":\(true\|false\).*/\1/p' | sed 's/true/1/;s/false/0/')"
    [ -n "$MAIN_CACHED_COUNT" ] || MAIN_CACHED_COUNT=0
    [ -n "$MAIN_PENDING_COUNT" ] || MAIN_PENDING_COUNT=0
    [ -n "$MAIN_PROXY_RUNNING" ] || MAIN_PROXY_RUNNING=0
    [ -n "$MAIN_AUTOSTART_ENABLED" ] || MAIN_AUTOSTART_ENABLED=0
    MAIN_STATUS_TEXT="Service running: $( [ "$MAIN_PROXY_RUNNING" -eq 1 ] && printf 'yes' || printf 'no' )$( [ -n "$MAIN_PROXY_PID" ] && printf ' | PID: %s' "$MAIN_PROXY_PID" || true)"
    MAIN_PROXY_LABEL="$(proxy_menu_label | tr -d '\n')"
    MAIN_AUTOSTART_LABEL="$(autostart_menu_label | tr -d '\n')"

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
            show_cached_games_view || true
            ;;
        3)
            show_pending_awards || true
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
