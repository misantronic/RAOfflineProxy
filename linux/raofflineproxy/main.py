import argparse
import json
import logging
import os
import sys
from pathlib import Path

from .auth import resolve_credentials
from .config import (
    CONFIG_FILE,
    configure_logging,
    detect_retroarch_cfg,
    load_config,
    proxy_value,
)
from .platform import (
    autostart_enabled,
    disable_autostart,
    enable_autostart,
    resolve_rom_root,
)
from .batocera_conf import patch_batocera_conf, revert_batocera_conf
from .retroarch_cfg import (
    patch_retroarch_cfg,
    revert_retroarch_cfg,
    status_retroarch_cfg,
)
from .service import (
    run_service_foreground,
    service_status,
    start_service_process,
    stop_service_process,
)
from .menu_sdl import run_menu_sdl
from .network import online_check
from .pending_awards import list_pending_awards
from .rom_browser import (
    add_rom_to_cache,
    cached_unlock_count,
    cached_unlock_counts,
    cached_unlock_titles,
    clear_cached_games,
    describe_browser_entries,
    describe_browser_entries_fast,
    list_cached_games,
    remove_cached_game,
)
from .smart_cache import (
    SMART_CACHE_LIMIT,
    load_content_history_paths,
    run_folder_cache,
    run_smart_cache,
    should_offer_smart_cache,
)
from .storage import Storage
from .state import load_patch_state, save_patch_state
from .ui import write_status_image, write_text_image
from .update import update_status

STALE_HOOK_PATH = Path("/userdata/system/scripts/RAOfflineProxy_game_hook.sh")
LOGGER = logging.getLogger("raofflineproxy")


def should_abort_from_env() -> bool:
    abort_file = os.environ.get("RAOFFLINEPROXY_ABORT_FILE")
    return bool(abort_file) and Path(abort_file).exists()


def remove_stale_hook() -> None:
    if STALE_HOOK_PATH.exists():
        STALE_HOOK_PATH.unlink()


def safe_stop_proxy(config_data: dict, cfg_path: str | None) -> list[str]:
    output: list[str] = []

    remove_stale_hook()
    service = stop_service_process()
    patch_state = load_patch_state() or {}
    revert_cfg_path = patch_state.get("cfg_path") or cfg_path
    previous_batocera = patch_state.get("batocera_previous", {})
    batocera = revert_batocera_conf(config_data, previous_batocera)

    if service.get("already_stopped"):
        output.append("Service already stopped")
    elif service.get("forced"):
        output.append(f"Service stopped forcefully (pid {service['pid']})")
    else:
        output.append(f"Service stopped (pid {service['pid']})")

    if batocera.get("exists"):
        output.append(f"Reverted batocera.conf at {batocera['path']}")

    revert_result = None
    if patch_state:
        revert_result = revert_retroarch_cfg(revert_cfg_path)
    elif revert_cfg_path:
        try:
            revert_result = revert_retroarch_cfg(revert_cfg_path)
        except Exception:
            revert_result = None

    if revert_result is None:
        output.append("retroarch.cfg already reverted")
    elif revert_result["changed"]:
        output.append("Reverted retroarch.cfg")
    else:
        output.append("retroarch.cfg already reverted")

    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="RAOfflineProxy Linux client")
    parser.add_argument(
        "command",
        choices=[
            "start-proxy",
            "stop-proxy",
            "enable-autostart",
            "disable-autostart",
            "autostart-status",
            "cached-games",
            "cached-games-count",
            "cached-unlock-titles",
            "remove-cached-game",
            "clear-cached-games",
            "pending-awards",
            "pending-awards-count",
            "home-status",
            "browser-root",
            "browser-list",
            "browser-list-fast",
            "cache-rom",
            "cache-folder-listing",
            "smart-cache-status",
            "run-smart-cache",
            "update-status",
            "service-status",
            "status",
            "run-service",
            "ui-image",
            "text-image",
            "menu",
            "menu-sdl",
        ],
        help="Action to perform",
    )
    parser.add_argument(
        "--retroarch-cfg",
        dest="retroarch_cfg",
        help="Override RetroArch config path",
    )
    parser.add_argument(
        "--path",
        dest="path",
        help="Filesystem path for browser and cache commands",
    )
    parser.add_argument(
        "--game-id",
        dest="game_id",
        type=int,
        help="Game id for cached game commands",
    )
    parser.add_argument(
        "--output",
        dest="output",
        help="Output file path for commands that write files",
    )
    parser.add_argument(
        "--text",
        dest="text",
        help="Text payload for commands that render text",
    )
    parser.add_argument(
        "--image-width",
        dest="image_width",
        type=int,
        help="Override rendered image width",
    )
    parser.add_argument(
        "--image-height",
        dest="image_height",
        type=int,
        help="Override rendered image height",
    )
    parser.add_argument(
        "--font-scale",
        dest="font_scale",
        type=int,
        help="Override rendered font scale",
    )
    parser.add_argument(
        "--platform",
        dest="platform",
        help="Platform name for platform-specific commands",
    )
    args = parser.parse_args()

    try:
        if args.command != "run-service":
            configure_logging()

        config_data = load_config()
        cfg_path = (
            args.retroarch_cfg
            or config_data.get("retroarch_cfg")
            or detect_retroarch_cfg()
        )

        if args.command == "run-service":
            run_service_foreground(config_data)
            return

        if args.command == "menu":
            run_menu_sdl(sys.argv[0])
            return

        if args.command == "menu-sdl":
            run_menu_sdl(sys.argv[0])
            return

        if args.command == "ui-image":
            if not args.output:
                raise ValueError("ui-image requires --output")
            write_status_image(
                args.output,
                image_width=args.image_width or 0,
                image_height=args.image_height or 0,
            )
            return

        if args.command == "text-image":
            if not args.output:
                raise ValueError("text-image requires --output")
            if args.text is None:
                raise ValueError("text-image requires --text")
            write_text_image(
                args.output,
                args.text,
                image_width=args.image_width or 0,
                image_height=args.image_height or 0,
                font_scale=args.font_scale or 0,
            )
            return

        if args.command == "start-proxy":
            remove_stale_hook()
            result = patch_retroarch_cfg(cfg_path, config_data)
            batocera = patch_batocera_conf(config_data)
            patch_state = load_patch_state() or {}
            patch_state["batocera_previous"] = batocera.get("previous", {})
            patch_state["batocera_conf_path"] = batocera.get("path")
            save_patch_state(patch_state)
            service = start_service_process(config_data)
            if result["already_patched"]:
                print("retroarch.cfg already patched")
            elif result["changed"]:
                print("Patched retroarch.cfg")
            else:
                print("retroarch.cfg already patched")
            if batocera.get("exists"):
                print(f"Patched batocera.conf at {batocera['path']}")
            if service["already_running"]:
                print(f"Service already running (pid {service['pid']})")
            else:
                print(f"Service started (pid {service['pid']})")
            return

        if args.command == "stop-proxy":
            for line in safe_stop_proxy(config_data, args.retroarch_cfg or cfg_path):
                print(line)
            return

        if args.command == "enable-autostart":
            enable_autostart(config_data)
            print("Autostart enabled")
            return

        if args.command == "disable-autostart":
            disable_autostart(config_data)
            print("Autostart disabled")
            return

        if args.command == "autostart-status":
            print("enabled" if autostart_enabled(config_data) else "disabled")
            return

        if args.command == "cached-games":
            storage = Storage()
            try:
                games = list_cached_games(storage)
                if not games:
                    return

                unlock_counts = cached_unlock_counts(storage)

                for game in games:
                    unlock_count = unlock_counts.get(game.game_id)
                    suffix = (
                        f" ({unlock_count} unlocks)" if unlock_count is not None else ""
                    )
                    print(f"{game.title}{suffix} ##GAMEID:{game.game_id}")
            finally:
                storage.close()
            return

        if args.command == "cached-games-count":
            storage = Storage()
            try:
                print(len(list_cached_games(storage)))
            finally:
                storage.close()
            return

        if args.command == "cached-unlock-titles":
            if args.game_id is None or args.game_id <= 0:
                raise ValueError("cached-unlock-titles requires --game-id")

            storage = Storage()
            try:
                titles = cached_unlock_titles(storage, args.game_id)
                for title in titles:
                    print(title)
            finally:
                storage.close()
            return

        if args.command == "remove-cached-game":
            if args.game_id is None or args.game_id <= 0:
                raise ValueError("remove-cached-game requires --game-id")

            storage = Storage()
            try:
                remove_cached_game(storage, args.game_id)
                print(f"Removed cached game {args.game_id}")
            finally:
                storage.close()
            return

        if args.command == "clear-cached-games":
            storage = Storage()
            try:
                clear_cached_games(storage)
                print("Cleared cached games")
            finally:
                storage.close()
            return

        if args.command == "pending-awards":
            storage = Storage()
            try:
                awards = list_pending_awards(storage)
                if not awards:
                    print("No pending awards")
                    return

                for index, award in enumerate(awards, start=1):
                    print(f"{index}. {award.summary_text}")
            finally:
                storage.close()
            return

        if args.command == "pending-awards-count":
            storage = Storage()
            try:
                print(len(list_pending_awards(storage)))
            finally:
                storage.close()
            return

        if args.command == "home-status":
            storage = Storage()
            try:
                service = service_status()
                print(
                    json.dumps(
                        {
                            "cached_games_count": len(list_cached_games(storage)),
                            "pending_awards_count": len(list_pending_awards(storage)),
                            "service_running": bool(service.get("running")),
                            "service_pid": service.get("pid"),
                            "autostart_enabled": autostart_enabled(config_data),
                        },
                        separators=(",", ":"),
                    )
                )
            finally:
                storage.close()
            return

        if args.command == "browser-root":
            print(resolve_rom_root(config_data))
            return

        if args.command == "browser-list":
            if not args.path:
                raise ValueError("browser-list requires --path")

            current_dir = Path(args.path).expanduser()
            if not current_dir.exists() or not current_dir.is_dir():
                raise ValueError(f"Invalid browser directory: {current_dir}")

            storage = Storage()
            try:
                for entry in describe_browser_entries(current_dir, storage):
                    print(
                        "\t".join(
                            [
                                "1" if entry.is_dir else "0",
                                "1" if entry.is_cached else "0",
                                str(entry.path),
                                entry.name,
                            ]
                        )
                    )
            finally:
                storage.close()
            return

        if args.command == "browser-list-fast":
            if not args.path:
                raise ValueError("browser-list-fast requires --path")

            current_dir = Path(args.path).expanduser()
            if not current_dir.exists() or not current_dir.is_dir():
                raise ValueError(f"Invalid browser directory: {current_dir}")

            for entry in describe_browser_entries_fast(current_dir):
                print(
                    "\t".join(
                        [
                            "1" if entry.is_dir else "0",
                            "1" if entry.is_cached else "0",
                            str(entry.path),
                            entry.name,
                        ]
                    )
                )
            return

        if args.command == "cache-rom":
            if not args.path:
                raise ValueError("cache-rom requires --path")

            rom_path = Path(args.path).expanduser()
            if not rom_path.exists() or not rom_path.is_file():
                raise ValueError(f"Invalid ROM path: {rom_path}")

            storage = Storage()
            try:
                result = add_rom_to_cache(rom_path, storage, config_data)
            finally:
                storage.close()

            if not result.success:
                raise RuntimeError(result.message)

            print(result.message)
            return

        if args.command == "cache-folder-listing":
            if not args.path:
                raise ValueError("cache-folder-listing requires --path")

            current_dir = Path(args.path).expanduser()
            if not current_dir.exists() or not current_dir.is_dir():
                raise ValueError(f"Invalid browser directory: {current_dir}")

            storage = Storage()
            try:

                def on_progress(progress) -> None:
                    print(
                        json.dumps(
                            {
                                "type": "progress",
                                "scanned": progress.scanned,
                                "total": progress.total,
                                "cached": progress.cached,
                                "current_label": progress.current_label,
                            },
                            separators=(",", ":"),
                        ),
                        flush=True,
                    )

                result = run_folder_cache(
                    storage,
                    config_data,
                    current_dir,
                    should_abort=should_abort_from_env,
                    on_progress=on_progress,
                )
            finally:
                storage.close()

            if result.total <= 0:
                raise RuntimeError("No ROM files in this folder")

            print(
                json.dumps(
                    {
                        "type": "result",
                        "scanned": result.scanned,
                        "total": result.total,
                        "cached": result.cached,
                        "skipped": result.skipped,
                        "limit_reached": result.limit_reached,
                    },
                    separators=(",", ":"),
                )
            )
            return

        if args.command == "smart-cache-status":
            storage = Storage()
            try:
                cached_games = list_cached_games(storage)
                if cached_games:
                    LOGGER.info(
                        "Smart Cache status skipped: cached games already present count=%s",
                        len(cached_games),
                    )
                    total_candidates = 0
                else:
                    history_paths = load_content_history_paths(config_data)
                    total_candidates = min(len(history_paths), SMART_CACHE_LIMIT)
                    LOGGER.info(
                        "Smart Cache status history candidates=%s capped=%s",
                        len(history_paths),
                        total_candidates,
                    )
                print(
                    json.dumps(
                        {
                            "found_history": total_candidates > 0,
                            "total_candidates": total_candidates,
                        },
                        separators=(",", ":"),
                    )
                )
            finally:
                storage.close()
            return

        if args.command == "run-smart-cache":
            storage = Storage()
            try:
                credentials = resolve_credentials(storage, config_data)
                if credentials is None:
                    LOGGER.info(
                        "Smart Cache run blocked: missing RetroAchievements credentials"
                    )
                    raise RuntimeError("RetroAchievements login required")
                if not online_check(config_data):
                    LOGGER.info("Smart Cache run blocked: online check failed")
                    raise RuntimeError("Smart Cache requires an online connection")
                LOGGER.info("Smart Cache run starting")

                def on_progress(progress) -> None:
                    print(
                        json.dumps(
                            {
                                "type": "progress",
                                "scanned": progress.scanned,
                                "total": progress.total,
                                "cached": progress.cached,
                                "current_label": progress.current_label,
                            },
                            separators=(",", ":"),
                        ),
                        flush=True,
                    )

                result = run_smart_cache(
                    storage,
                    config_data,
                    SMART_CACHE_LIMIT,
                    should_abort=should_abort_from_env,
                    on_progress=on_progress,
                )
                print(
                    json.dumps(
                        {
                            "type": "result",
                            "scanned": result.scanned,
                            "total": result.total,
                            "cached": result.cached,
                            "skipped": result.skipped,
                            "limit_reached": result.limit_reached,
                        },
                        separators=(",", ":"),
                    )
                )
            finally:
                storage.close()
            return

        if args.command == "service-status":
            service = service_status()
            service_line = (
                f"Service running: {'yes' if service.get('running') else 'no'}"
            )
            if service.get("running") and service.get("pid"):
                service_line += f" | PID: {service['pid']}"
            print(service_line)
            return

        if args.command == "update-status":
            if not args.platform:
                raise ValueError("update-status requires --platform")
            print(
                json.dumps(
                    update_status(args.platform).to_dict(),
                    separators=(",", ":"),
                )
            )
            return

        status = status_retroarch_cfg(cfg_path, config_data)
        service = service_status()
        print(f"Config: {status['cfg_path']}")
        print(f"Exists: {'yes' if status['exists'] else 'no'}")
        print(f"Patched: {'yes' if status['is_patched'] else 'no'}")
        print(f"State file: {'present' if status['state_present'] else 'missing'}")
        if status["exists"]:
            print(
                f"Hardcore enabled: {'yes' if status.get('hardcore_enabled', False) else 'no'}"
            )
        service_line = f"Service running: {'yes' if service.get('running') else 'no'}"
        if service.get("running") and service.get("pid"):
            service_line += f" | PID: {service['pid']}"
        print(service_line)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        if not CONFIG_FILE.exists():
            print(
                f"Optional config file: {CONFIG_FILE} (supports proxy_host, proxy_port, retroarch_cfg)",
                file=sys.stderr,
            )
        sys.exit(1)


if __name__ == "__main__":
    main()
