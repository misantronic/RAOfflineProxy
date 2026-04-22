import json
from pathlib import Path
from typing import Optional

from .config import STATE_FILE, ensure_config_dir


def load_patch_state() -> Optional[dict]:
    if not STATE_FILE.exists():
        return None

    with STATE_FILE.open(encoding="utf-8") as handle:
        data = json.load(handle)

    if not isinstance(data, dict):
        raise ValueError(f"Invalid patch state file: {STATE_FILE}")

    return data


def save_patch_state(data: dict) -> Path:
    ensure_config_dir()
    with STATE_FILE.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return STATE_FILE


def clear_patch_state() -> None:
    if STATE_FILE.exists():
        STATE_FILE.unlink()
