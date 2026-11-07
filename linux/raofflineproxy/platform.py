from __future__ import annotations

from pathlib import Path

from . import platforms
from .config import save_config
from .emulators.retroarch_cfg import detect_retroarch_cfg

ROM_DIRECTORY_KEYS = [
    "content_directory",
]
AUTOSTART_CONFIG_KEY = "autostart_enabled"


def resolve_retroarch_cfg(config_data: dict) -> str:
    return str(config_data.get("retroarch_cfg") or detect_retroarch_cfg())


def resolve_rom_root(config_data: dict) -> Path:
    platform = platforms.current()
    preferred = platform.preferred_rom_root(config_data)
    if preferred is not None:
        return preferred

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

    default = platform.default_rom_root()
    if default is not None:
        return default

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


def resolve_startup_script_path(config_data: dict) -> Path | None:
    configured = config_data.get("startup_script")
    if configured:
        return Path(str(configured))

    return platforms.current().startup_script()


def autostart_supported(config_data: dict) -> bool:
    return platforms.current().autostart_supported(resolve_startup_script_path(config_data))


def is_autostart_enabled(config_data: dict) -> bool:
    managed = platforms.current().autostart_enabled(config_data)
    if managed is not None:
        return managed
    if AUTOSTART_CONFIG_KEY in config_data:
        return bool(config_data[AUTOSTART_CONFIG_KEY])
    return _legacy_autostart_present(config_data)


def autostart_enabled(config_data: dict) -> bool:
    return is_autostart_enabled(config_data)


def _legacy_autostart_present(config_data: dict) -> bool:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script is None or not startup_script.exists():
        return False

    return platforms.current().boot_hook_installed(startup_script)


def enable_autostart(config_data: dict) -> None:
    if platforms.current().enable_autostart(config_data):
        return
    if not autostart_supported(config_data):
        raise ValueError("Autostart is not supported on this platform")

    ensure_boot_hook(config_data)
    config_data[AUTOSTART_CONFIG_KEY] = True
    save_config(config_data)


def disable_autostart(config_data: dict) -> None:
    platform = platforms.current()
    if platform.disable_autostart(config_data):
        return
    config_data[AUTOSTART_CONFIG_KEY] = False
    save_config(config_data)
    platform.on_autostart_disabled()


def ensure_boot_hook(config_data: dict) -> None:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script is None:
        raise ValueError("Autostart is not supported on this platform")

    if AUTOSTART_CONFIG_KEY not in config_data:
        config_data[AUTOSTART_CONFIG_KEY] = _legacy_autostart_present(config_data)
        save_config(config_data)

    platforms.current().install_boot_hook(startup_script, config_data)


def remove_boot_hook(config_data: dict) -> None:
    startup_script = resolve_startup_script_path(config_data)
    if startup_script is None or not startup_script.exists():
        return

    platforms.current().remove_boot_hook(startup_script)
