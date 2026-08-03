from __future__ import annotations

from pathlib import Path

from .config import (
    DEFAULT_MUOS_INIT_DIR,
    DEFAULT_ONION_STARTUP_SCRIPT,
    MUOS_USER_INIT_CONFIG,
    detect_retroarch_cfg,
    running_on_rocknix,
    save_config,
)

DEFAULT_KNULLI_ROMS_ROOT = Path("/userdata/roms")
DEFAULT_MUOS_ROMS_ROOT = Path("/mnt/mmc/ROMS")
DEFAULT_ONION_ROMS_ROOT = Path("/mnt/SDCARD/Roms")
DEFAULT_ROCKNIX_ROMS_ROOT = Path("/storage/roms")
DEFAULT_KNULLI_STARTUP_SCRIPT = Path("/userdata/system/custom.sh")
DEFAULT_MUOS_STARTUP_SCRIPT = DEFAULT_MUOS_INIT_DIR / "raofflineproxy.sh"
DEFAULT_ROCKNIX_STARTUP_SCRIPT = Path("/storage/.config/autostart/raofflineproxy.sh")
ROCKNIX_MODULES_DIR = Path("/storage/.config/modules")
ROCKNIX_MODULES_LAUNCHER = ROCKNIX_MODULES_DIR / "RAOfflineProxy.sh"
ROCKNIX_TOOL_SOURCE = Path("/storage/.local/share/raofflineproxy/RAOfflineProxy.sh")
ROM_DIRECTORY_KEYS = [
    "content_directory",
]
AUTOSTART_SENTINEL_START = "# RAOfflineProxy autostart start"
AUTOSTART_SENTINEL_END = "# RAOfflineProxy autostart end"
AUTOSTART_CONFIG_KEY = "autostart_enabled"


def resolve_retroarch_cfg(config_data: dict) -> str:
    return str(config_data.get("retroarch_cfg") or detect_retroarch_cfg())


def resolve_rom_root(config_data: dict) -> Path:
    if DEFAULT_MUOS_ROMS_ROOT.exists() and DEFAULT_MUOS_ROMS_ROOT.is_dir():
        return DEFAULT_MUOS_ROMS_ROOT

    # rgui_browser_directory deliberately isn't consulted here: RetroArch
    # overwrites it with wherever its own file browser was last pointed,
    # including non-ROM folders (e.g. a themes directory), so using it as a
    # stand-in for the ROM library silently redirects the browser there.
    cfg_path = Path(resolve_retroarch_cfg(config_data))
    values = read_retroarch_cfg_values(cfg_path)
    for key in ROM_DIRECTORY_KEYS:
        value = values.get(key)
        if not value:
            continue
        candidate = Path(value).expanduser()
        if candidate.exists() and candidate.is_dir():
            return candidate

    if DEFAULT_KNULLI_ROMS_ROOT.exists() and DEFAULT_KNULLI_ROMS_ROOT.is_dir():
        return DEFAULT_KNULLI_ROMS_ROOT

    if DEFAULT_ONION_ROMS_ROOT.exists() and DEFAULT_ONION_ROMS_ROOT.is_dir():
        return DEFAULT_ONION_ROMS_ROOT

    if DEFAULT_ROCKNIX_ROMS_ROOT.exists() and DEFAULT_ROCKNIX_ROMS_ROOT.is_dir():
        return DEFAULT_ROCKNIX_ROMS_ROOT

    return cfg_path.parent


def read_retroarch_cfg_values(cfg_path: Path) -> dict[str, str]:
    if not cfg_path.exists():
        return {}

    values: dict[str, str] = {}
    for raw_line in cfg_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"')
    return values


def autostart_supported(config_data: dict) -> bool:
    return resolve_startup_script_path(config_data) is not None


def is_autostart_enabled(config_data: dict) -> bool:
    if AUTOSTART_CONFIG_KEY in config_data:
        return bool(config_data[AUTOSTART_CONFIG_KEY])
    return _legacy_autostart_present(config_data)


def autostart_enabled(config_data: dict) -> bool:
    return is_autostart_enabled(config_data)


def _legacy_autostart_present(config_data: dict) -> bool:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script is None or not startup_script.exists():
        return False

    if startup_script == DEFAULT_ONION_STARTUP_SCRIPT:
        return True

    if startup_script == DEFAULT_MUOS_STARTUP_SCRIPT:
        if MUOS_USER_INIT_CONFIG.exists():
            try:
                if MUOS_USER_INIT_CONFIG.read_text(encoding="utf-8").strip() != "1":
                    return False
            except OSError:
                pass
        return True

    content = startup_script.read_text(encoding="utf-8", errors="replace")
    return AUTOSTART_SENTINEL_START in content and AUTOSTART_SENTINEL_END in content


def enable_autostart(config_data: dict) -> None:
    if not autostart_supported(config_data):
        raise ValueError("Autostart is not supported on this platform")

    ensure_boot_hook(config_data)
    config_data[AUTOSTART_CONFIG_KEY] = True
    save_config(config_data)


def disable_autostart(config_data: dict) -> None:
    config_data[AUTOSTART_CONFIG_KEY] = False
    save_config(config_data)


def ensure_boot_hook(config_data: dict) -> None:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script is None:
        raise ValueError("Autostart is not supported on this platform")

    if AUTOSTART_CONFIG_KEY not in config_data:
        config_data[AUTOSTART_CONFIG_KEY] = _legacy_autostart_present(config_data)
        save_config(config_data)

    if startup_script == DEFAULT_ONION_STARTUP_SCRIPT:
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(onion_boot_hook_script(), encoding="utf-8")
        return

    if startup_script == DEFAULT_MUOS_STARTUP_SCRIPT:
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(muos_boot_hook_script(config_data), encoding="utf-8")
        startup_script.chmod(0o755)
        _muos_enable_user_init()
        return

    if startup_script == DEFAULT_ROCKNIX_STARTUP_SCRIPT:
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(rocknix_boot_hook_script(config_data), encoding="utf-8")
        startup_script.chmod(0o755)
        return

    startup_script.parent.mkdir(parents=True, exist_ok=True)
    existing = (
        startup_script.read_text(encoding="utf-8", errors="replace")
        if startup_script.exists()
        else ""
    )
    cleaned = strip_autostart_block(existing).rstrip()
    block = autostart_block(config_data)
    new_content = f"{cleaned}\n\n{block}\n" if cleaned else f"{block}\n"
    startup_script.write_text(new_content, encoding="utf-8")


def remove_boot_hook(config_data: dict) -> None:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script is None or not startup_script.exists():
        return

    if startup_script in (
        DEFAULT_ONION_STARTUP_SCRIPT,
        DEFAULT_MUOS_STARTUP_SCRIPT,
        DEFAULT_ROCKNIX_STARTUP_SCRIPT,
    ):
        startup_script.unlink()
        return

    existing = startup_script.read_text(encoding="utf-8", errors="replace")
    cleaned = strip_autostart_block(existing).strip()
    startup_script.write_text(f"{cleaned}\n" if cleaned else "", encoding="utf-8")


def resolve_startup_script_path(config_data: dict) -> Path | None:
    configured = config_data.get("startup_script")
    if configured:
        return Path(str(configured))

    if Path("/mnt/SDCARD/.tmp_update").exists():
        return DEFAULT_ONION_STARTUP_SCRIPT

    if Path("/opt/muos/script/archive").exists():
        return DEFAULT_MUOS_STARTUP_SCRIPT

    if Path("/userdata/system").exists():
        return DEFAULT_KNULLI_STARTUP_SCRIPT

    if running_on_rocknix():
        return DEFAULT_ROCKNIX_STARTUP_SCRIPT

    return None


def autostart_block(config_data: dict) -> str:
    startup_command = autostart_command(config_data)
    return "\n".join(
        [
            AUTOSTART_SENTINEL_START,
            f'if [ -x "{startup_command[0]}" ]; then',
            f"  {startup_command[0]} boot-reconcile >/dev/null 2>&1 || true",
            "fi",
            AUTOSTART_SENTINEL_END,
        ]
    )


def autostart_command(config_data: dict) -> tuple[str]:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script == DEFAULT_ONION_STARTUP_SCRIPT:
        return ("/mnt/SDCARD/App/RAOfflineProxy/autostart-launch.sh",)

    if startup_script == DEFAULT_MUOS_STARTUP_SCRIPT:
        return (
            str(
                config_data.get("autostart_launcher")
                or "/run/muos/storage/application/RAOfflineProxy/launch.sh"
            ),
        )

    if startup_script == DEFAULT_ROCKNIX_STARTUP_SCRIPT:
        return (
            str(
                config_data.get("autostart_launcher")
                or "/storage/.local/share/raofflineproxy/bin/raofflineproxy"
            ),
        )

    launcher = str(
        config_data.get("autostart_launcher")
        or "/userdata/system/raofflineproxy/bin/raofflineproxy"
    )
    return (launcher,)


def strip_autostart_block(content: str) -> str:
    start = content.find(AUTOSTART_SENTINEL_START)
    if start < 0:
        return content

    end = content.find(AUTOSTART_SENTINEL_END, start)
    if end < 0:
        return content[:start]

    end += len(AUTOSTART_SENTINEL_END)
    return f"{content[:start]}{content[end:]}"


def onion_boot_hook_script() -> str:
    return "\n".join(
        [
            "#!/bin/sh",
            "set -eu",
            "",
            "APP_DIR=/mnt/SDCARD/App/RAOfflineProxy",
            'if [ -x "$APP_DIR/autostart-launch.sh" ]; then',
            '  sh "$APP_DIR/autostart-launch.sh"',
            "fi",
            "",
        ]
    )


def _muos_enable_user_init() -> None:
    if MUOS_USER_INIT_CONFIG.parent.exists():
        MUOS_USER_INIT_CONFIG.write_text("1\n", encoding="utf-8")


def muos_boot_hook_script(config_data: dict) -> str:
    launcher = autostart_command(config_data)[0]
    return "\n".join(
        [
            "#!/bin/sh",
            "set -eu",
            "",
            f'if [ -x "{launcher}" ]; then',
            f'  exec "{launcher}" boot-reconcile >/dev/null 2>&1 || true',
            "fi",
            "",
        ]
    )


def rocknix_boot_hook_script(config_data: dict) -> str:
    launcher = autostart_command(config_data)[0]
    return "\n".join(
        [
            "#!/bin/sh",
            "set -u",
            "",
            "# ROCKNIX re-syncs /storage/.config/modules from a read-only source on",
            "# every boot (rsync --delete), wiping third-party Tools entries. Re-add",
            "# ours so the RAOfflineProxy Tools entry survives reboots.",
            f'if [ -f "{ROCKNIX_TOOL_SOURCE}" ]; then',
            f'  mkdir -p "{ROCKNIX_MODULES_DIR}"',
            f'  cp "{ROCKNIX_TOOL_SOURCE}" "{ROCKNIX_MODULES_LAUNCHER}" || true',
            f'  chmod +x "{ROCKNIX_MODULES_LAUNCHER}" || true',
            "fi",
            "",
            f'if [ -x "{launcher}" ]; then',
            f'  "{launcher}" boot-reconcile >/dev/null 2>&1 || true',
            "fi",
            "",
        ]
    )
