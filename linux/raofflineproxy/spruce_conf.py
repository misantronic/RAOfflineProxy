from __future__ import annotations

import json
from pathlib import Path

from . import config
from .config import running_on_spruce

MODE_KEY = "modeToggle"
SELECTED_KEY = "selected"
# The only mode the proxy can work with. spruce rewrites cheevos_enable and
# cheevos_hardcore_mode_enable into the RetroArch config on every game launch, after our
# own patch has run, so its mode — not ours — decides whether achievements are on:
# "Disabled" switches them off, and "Hardcore" turns on a mode this app does not support.
# "Manual" leaves the config alone but then never writes the account credentials into it.
SUPPORTED_MODE = "Softcore"


def _load_settings() -> tuple[Path, dict] | None:
    if not running_on_spruce():
        return None

    try:
        with config.SPRUCE_CONFIG_JSON.open(encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError):
        return None

    if not isinstance(data, dict):
        return None
    return config.SPRUCE_CONFIG_JSON, data


def _mode_entry(data: dict) -> dict | None:
    menu = data.get("menuOptions")
    if not isinstance(menu, dict):
        return None
    section = menu.get(config.SPRUCE_SETTINGS_MENU)
    if not isinstance(section, dict):
        return None
    entry = section.get(MODE_KEY)
    return entry if isinstance(entry, dict) else None


def _write_settings(path: Path, data: dict) -> None:
    # 4-space indent matches how spruce writes this file, keeping the diff to the one
    # value we change rather than reformatting the whole thing.
    path.write_text(json.dumps(data, indent=4) + "\n", encoding="utf-8")


def spruce_mode() -> str | None:
    loaded = _load_settings()
    if loaded is None:
        return None
    entry = _mode_entry(loaded[1])
    if entry is None:
        return None
    value = entry.get(SELECTED_KEY)
    return value if isinstance(value, str) else None


def patch_spruce_mode(config_data: dict | None = None) -> dict:
    loaded = _load_settings()
    if loaded is None:
        return {"exists": False, "changed": False, "path": None, "previous": None}

    path, data = loaded
    entry = _mode_entry(data)
    if entry is None:
        return {"exists": False, "changed": False, "path": str(path), "previous": None}

    previous = entry.get(SELECTED_KEY)
    previous = previous if isinstance(previous, str) else None
    if previous == SUPPORTED_MODE:
        return {
            "exists": True,
            "changed": False,
            "already_patched": True,
            "path": str(path),
            "previous": previous,
        }

    entry[SELECTED_KEY] = SUPPORTED_MODE
    try:
        _write_settings(path, data)
    except OSError:
        return {"exists": True, "changed": False, "path": str(path), "previous": previous}

    return {
        "exists": True,
        "changed": True,
        "already_patched": False,
        "path": str(path),
        "previous": previous,
    }


def store_spruce_previous(patch_state: dict, spruce: dict) -> None:
    """Record the pre-patch mode without poisoning it on re-patch, mirroring the other
    patchers: re-running while already patched must not capture "Softcore" as previous."""
    if not (spruce.get("already_patched") and "spruce_previous_mode" in patch_state):
        patch_state["spruce_previous_mode"] = spruce.get("previous")
    patch_state["spruce_config_path"] = spruce.get("path")


def revert_spruce_mode(config_data: dict | None = None, previous: str | None = None) -> dict:
    if not previous or previous == SUPPORTED_MODE:
        return {"exists": False, "changed": False, "path": None}

    loaded = _load_settings()
    if loaded is None:
        return {"exists": False, "changed": False, "path": None}

    path, data = loaded
    entry = _mode_entry(data)
    if entry is None:
        return {"exists": False, "changed": False, "path": str(path)}

    if entry.get(SELECTED_KEY) == previous:
        return {"exists": True, "changed": False, "path": str(path)}

    entry[SELECTED_KEY] = previous
    try:
        _write_settings(path, data)
    except OSError:
        return {"exists": True, "changed": False, "path": str(path)}

    return {"exists": True, "changed": True, "path": str(path)}
