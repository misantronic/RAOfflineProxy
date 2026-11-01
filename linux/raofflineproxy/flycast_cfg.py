from __future__ import annotations

"""Flycast (Windows). Its emu.cfg has an [achievements] section, and Flycast
passes the section's HostUrl value to rc_client_set_host, so pointing it at the
proxy needs no change to Flycast. The Android app uses Flycast's broadcast
receiver instead, which also turns hardcore off; writing the file doesn't, so
HardcoreMode is set here."""

from pathlib import Path

from .config import detect_flycast_cfg, proxy_value
from .dolphin_cfg import _extract_achievements_value, _update_achievements_section
from .utils import write_emulator_config

ACHIEVEMENTS_SECTION = "[achievements]"
HOST_KEY = "HostUrl"
HARDCORE_KEY = "HardcoreMode"


def patch_flycast_cfg(config_data: dict) -> dict:
    cfg_path = detect_flycast_cfg(config_data)
    if cfg_path is None:
        return {"exists": False, "changed": False, "path": None, "previous": {}}

    target = Path(cfg_path)
    if not target.exists():
        return {"exists": False, "changed": False, "path": str(target), "previous": {}}

    original = target.read_text(encoding="utf-8", errors="replace")
    previous = {
        HOST_KEY: _extract_achievements_value(original, HOST_KEY, ACHIEVEMENTS_SECTION),
        HARDCORE_KEY: _extract_achievements_value(original, HARDCORE_KEY, ACHIEVEMENTS_SECTION),
    }
    transformed = _update_achievements_section(
        original,
        {HOST_KEY: proxy_value(config_data), HARDCORE_KEY: "no"},
        ACHIEVEMENTS_SECTION,
    )
    if transformed != original:
        write_emulator_config(target, transformed)

    already_patched = transformed == original and previous[HOST_KEY] == proxy_value(config_data)
    return {
        "exists": True,
        "changed": transformed != original,
        "already_patched": already_patched,
        "path": str(target),
        "previous": previous,
    }


def store_flycast_previous(patch_state: dict, flycast: dict) -> None:
    """Keeps the first-captured values, as for PCSX2."""
    if not (flycast.get("already_patched") and "flycast_previous" in patch_state):
        patch_state["flycast_previous"] = flycast.get("previous", {})
    patch_state["flycast_cfg_path"] = flycast.get("path")


def revert_flycast_cfg(config_data: dict, previous: dict | None = None) -> dict:
    cfg_path = detect_flycast_cfg(config_data)
    if cfg_path is None:
        return {"exists": False, "changed": False, "path": None}

    target = Path(cfg_path)
    if not target.exists():
        return {"exists": False, "changed": False, "path": str(target)}

    current = target.read_text(encoding="utf-8", errors="replace")
    sanitized = dict(previous or {})
    if sanitized.get(HOST_KEY) == proxy_value(config_data):
        sanitized[HOST_KEY] = None  # the proxy's own value, captured by a re-patch
    # A captured None (the key wasn't there) removes it.
    transformed = _update_achievements_section(
        current,
        {HOST_KEY: sanitized.get(HOST_KEY), HARDCORE_KEY: sanitized.get(HARDCORE_KEY)},
        ACHIEVEMENTS_SECTION,
    )
    if transformed != current:
        write_emulator_config(target, transformed)
    return {"exists": True, "changed": transformed != current, "path": str(target)}
