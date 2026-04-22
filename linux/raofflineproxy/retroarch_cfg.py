import re
from pathlib import Path
from typing import Optional

from .config import proxy_value
from .state import clear_patch_state, load_patch_state, save_patch_state

HOST_KEY = "cheevos_custom_host"
HARDCORE_KEY = "cheevos_hardcore_mode_enable"


def detect_hardcore_enabled(content: str) -> bool:
    return _extract_config_value(content, HARDCORE_KEY) == "true"


def is_patched_content(content: str, proxy_address: str) -> bool:
    return _extract_config_value(content, HOST_KEY) == proxy_address


def build_patched_content(content: str, proxy_address: str) -> str:
    with_host = _upsert_config_value(content, HOST_KEY, proxy_address)
    return _upsert_config_value(with_host, HARDCORE_KEY, "false")


def build_reverted_content(
    content: str,
    previous_host: Optional[str],
    restore_hardcore: bool,
) -> str:
    restored_host = previous_host if previous_host is not None else ""
    with_host = _upsert_config_value(content, HOST_KEY, restored_host)

    if not restore_hardcore:
        return with_host

    return _upsert_config_value(with_host, HARDCORE_KEY, "true")


def patch_retroarch_cfg(cfg_path: str, config_data: dict) -> dict:
    target = Path(cfg_path)
    if not target.exists():
        raise FileNotFoundError(f"RetroArch config not found: {target}")

    original = target.read_text(encoding="utf-8", errors="replace")
    proxy_address = proxy_value(config_data)
    previous_host = _extract_config_value(original, HOST_KEY)
    hardcore_was_enabled = detect_hardcore_enabled(original)
    transformed = build_patched_content(original, proxy_address)
    was_already_patched = transformed == original and is_patched_content(
        original, proxy_address
    )

    if transformed != original:
        target.write_text(transformed, encoding="utf-8")

    save_patch_state(
        {
            "cfg_path": str(target),
            "hardcore_was_enabled": hardcore_was_enabled,
            "previous_host": previous_host,
            "proxy_host": proxy_address,
        }
    )

    return {
        "cfg_path": str(target),
        "hardcore_was_enabled": hardcore_was_enabled,
        "proxy_host": proxy_address,
        "changed": transformed != original,
        "already_patched": was_already_patched,
    }


def revert_retroarch_cfg(cfg_path: Optional[str] = None) -> dict:
    patch_state = load_patch_state()
    target_path = cfg_path or (patch_state or {}).get("cfg_path")
    if target_path is None:
        raise RuntimeError("Proxy patch state not found")

    target = Path(target_path)
    if not target.exists():
        raise FileNotFoundError(f"RetroArch config not found: {target}")

    current = target.read_text(encoding="utf-8", errors="replace")
    previous_host = patch_state.get("previous_host") if patch_state else ""
    restore_hardcore = (
        bool(patch_state.get("hardcore_was_enabled", False)) if patch_state else False
    )
    transformed = build_reverted_content(
        current,
        previous_host,
        restore_hardcore,
    )

    if transformed != current:
        target.write_text(transformed, encoding="utf-8")

    if patch_state is not None:
        clear_patch_state()
    return {
        "cfg_path": str(target),
        "changed": transformed != current,
        "restored_hardcore": restore_hardcore,
        "previous_host": previous_host,
        "used_saved_state": patch_state is not None,
    }


def status_retroarch_cfg(cfg_path: str, config_data: dict) -> dict:
    target = Path(cfg_path)
    state = load_patch_state()
    proxy_address = proxy_value(config_data)

    if not target.exists():
        return {
            "cfg_path": str(target),
            "exists": False,
            "is_patched": False,
            "state_present": state is not None,
            "proxy_host": proxy_address,
        }

    content = target.read_text(encoding="utf-8", errors="replace")
    return {
        "cfg_path": str(target),
        "exists": True,
        "is_patched": is_patched_content(content, proxy_address),
        "hardcore_enabled": detect_hardcore_enabled(content),
        "state_present": state is not None,
        "proxy_host": proxy_address,
    }


def _extract_config_value(content: str, key: str) -> str | None:
    match = re.search(
        rf'^\s*{re.escape(key)}\s*=\s*"?(.*?)"?\s*$', content, re.MULTILINE
    )
    if match is None:
        return None
    return match.group(1)


def _upsert_config_value(content: str, key: str, value: str) -> str:
    pattern = re.compile(rf"^(\s*{re.escape(key)}\s*=\s*).*$", re.MULTILINE)
    replacement = f'\\1"{value}"'
    if pattern.search(content):
        return pattern.sub(replacement, content)

    stripped = content.rstrip("\n")
    if stripped:
        return f'{stripped}\n{key} = "{value}"\n'
    return f'{key} = "{value}"\n'
