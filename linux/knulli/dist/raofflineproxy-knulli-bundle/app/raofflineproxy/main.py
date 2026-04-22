import argparse
import sys

from .config import CONFIG_FILE, detect_retroarch_cfg, load_config, proxy_value
from .retroarch_cfg import (
    patch_retroarch_cfg,
    revert_retroarch_cfg,
    status_retroarch_cfg,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="RAOfflineProxy Linux client")
    parser.add_argument(
        "command",
        choices=["start-proxy", "stop-proxy", "status"],
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

        if args.command == "start-proxy":
            result = patch_retroarch_cfg(cfg_path, config_data)
            if result["already_patched"]:
                print(f"Proxy config already active in {result['cfg_path']}")
            elif result["changed"]:
                print(
                    f"Patched {result['cfg_path']} with cheevos_custom_host={result['proxy_host']}"
                )
            else:
                print(f"Proxy config active in {result['cfg_path']}")
            return

        if args.command == "stop-proxy":
            result = revert_retroarch_cfg(args.retroarch_cfg)
            if result["changed"]:
                print(f"Reverted proxy config in {result['cfg_path']}")
            else:
                print(f"Proxy config already reverted in {result['cfg_path']}")
            return

        status = status_retroarch_cfg(cfg_path, config_data)
        print(f"Config: {status['cfg_path']}")
        print(f"Exists: {'yes' if status['exists'] else 'no'}")
        print(f"Patched: {'yes' if status['is_patched'] else 'no'}")
        print(f"State file: {'present' if status['state_present'] else 'missing'}")
        if status["exists"]:
            print(
                f"Hardcore enabled: {'yes' if status.get('hardcore_enabled', False) else 'no'}"
            )
        print(f"Proxy target: {status['proxy_host']}")
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
