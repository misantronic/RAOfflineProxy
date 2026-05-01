from pathlib import Path

from .config import detect_retroarch_cfg

DEFAULT_KNULLI_ROMS_ROOT = Path("/userdata/roms")
ROM_DIRECTORY_KEYS = [
    "rgui_browser_directory",
    "content_directory",
]


def resolve_retroarch_cfg(config_data: dict) -> str:
    return str(config_data.get("retroarch_cfg") or detect_retroarch_cfg())


def resolve_rom_root(config_data: dict) -> Path:
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
