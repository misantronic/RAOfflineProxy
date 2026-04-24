import argparse
import sys
from pathlib import Path

from .config import CONFIG_FILE, detect_retroarch_cfg, load_config, proxy_value
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
from .state import load_patch_state, save_patch_state

STALE_HOOK_PATH = Path("/userdata/system/scripts/RAOfflineProxy_game_hook.sh")


def remove_stale_hook() -> None:
    if STALE_HOOK_PATH.exists():
        STALE_HOOK_PATH.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description="RAOfflineProxy Linux client")
    parser.add_argument(
        "command",
        choices=["start-proxy", "stop-proxy", "status", "run-service"],
        help="Action to perform",
    )
    parser.add_argument(
        "--retroarch-cfg",
        dest="retroarch_cfg",
        help="Override RetroArch config path",
    )
    args = parser.parse_args()

    try:
        config_data = load_config()
        cfg_path = (
            args.retroarch_cfg
            or config_data.get("retroarch_cfg")
            or detect_retroarch_cfg()
        )

        if args.command == "run-service":
            run_service_foreground(config_data)
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
                print(f"Proxy config already active in {result['cfg_path']}")
            elif result["changed"]:
                print(
                    f"Patched {result['cfg_path']} with cheevos_custom_host={result['proxy_host']}"
                )
            else:
                print(f"Proxy config active in {result['cfg_path']}")
            if batocera.get("exists"):
                print(f"Patched batocera.conf at {batocera['path']}")
            if service["already_running"]:
                print(f"Service already running (pid {service['pid']})")
            else:
                print(f"Service started (pid {service['pid']})")
            return

        if args.command == "stop-proxy":
            remove_stale_hook()
            service = stop_service_process()
            patch_state = load_patch_state() or {}
            previous_batocera = patch_state.get("batocera_previous", {})
            batocera = revert_batocera_conf(config_data, previous_batocera)
            result = revert_retroarch_cfg(args.retroarch_cfg)
            if service.get("already_stopped"):
                print("Service already stopped")
            elif service.get("forced"):
                print(f"Service stopped forcefully (pid {service['pid']})")
            else:
                print(f"Service stopped (pid {service['pid']})")
            if batocera.get("exists"):
                print(f"Reverted batocera.conf at {batocera['path']}")
            if result["changed"]:
                print(f"Reverted proxy config in {result['cfg_path']}")
            else:
                print(f"Proxy config already reverted in {result['cfg_path']}")
            return

        status = status_retroarch_cfg(cfg_path, config_data)
        service = service_status()
        print(f"Config: {status['cfg_path']}")
        print(f"Exists: {'yes' if status['exists'] else 'no'}")
        print(f"Patched: {'yes' if status['is_patched'] else 'no'}")
        print(f"State file: {'present' if status['state_present'] else 'missing'}")
        if status["exists"]:
            print(
                f"Cheevos enabled: {'yes' if status.get('cheevos_enabled', False) else 'no'}"
            )
            print(
                f"Hardcore enabled: {'yes' if status.get('hardcore_enabled', False) else 'no'}"
            )
        print(f"Proxy target: {status['proxy_host']}")
        print(f"Service running: {'yes' if service.get('running') else 'no'}")
        if service.get("pid"):
            print(f"Service PID: {service['pid']}")
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
