from __future__ import annotations

"""DuckStation (Windows). Its settings.ini has a [Cheevos] section, and
DuckStation passes the section's Host value to rc_client_set_host, so pointing
it at the proxy needs no change to DuckStation. ChallengeMode is hardcore,
which the proxy doesn't support."""

from pathlib import Path

from .config import detect_duckstation_ini, proxy_value
from .dolphin_cfg import _extract_achievements_value, _update_achievements_section
from .utils import write_emulator_config

CHEEVOS_SECTION = "[Cheevos]"
HOST_KEY = "Host"
HARDCORE_KEY = "ChallengeMode"


def patch_duckstation_ini(config_data: dict) -> dict:
    ini_path = detect_duckstation_ini(config_data)
    if ini_path is None:
        return {"exists": False, "changed": False, "path": None, "previous": {}}

    target = Path(ini_path)
    if not target.exists():
        return {"exists": False, "changed": False, "path": str(target), "previous": {}}

    original = target.read_text(encoding="utf-8", errors="replace")
    previous = {
        HOST_KEY: _extract_achievements_value(original, HOST_KEY, CHEEVOS_SECTION),
        HARDCORE_KEY: _extract_achievements_value(original, HARDCORE_KEY, CHEEVOS_SECTION),
    }
    transformed = _update_achievements_section(
        original,
        {HOST_KEY: proxy_value(config_data), HARDCORE_KEY: "false"},
        CHEEVOS_SECTION,
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


def store_duckstation_previous(patch_state: dict, duckstation: dict) -> None:
    """Keeps the first-captured values, as for PCSX2."""
    if not (duckstation.get("already_patched") and "duckstation_previous" in patch_state):
        patch_state["duckstation_previous"] = duckstation.get("previous", {})
    patch_state["duckstation_ini_path"] = duckstation.get("path")


def revert_duckstation_ini(config_data: dict, previous: dict | None = None) -> dict:
    ini_path = detect_duckstation_ini(config_data)
    if ini_path is None:
        return {"exists": False, "changed": False, "path": None}

    target = Path(ini_path)
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
        CHEEVOS_SECTION,
    )
    if transformed != current:
        write_emulator_config(target, transformed)
    return {"exists": True, "changed": transformed != current, "path": str(target)}
