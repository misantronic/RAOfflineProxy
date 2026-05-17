import re
from pathlib import Path
from typing import Optional

from .config import proxy_value
from .state import clear_patch_state, load_patch_state, save_patch_state

HOST_KEY = "cheevos_custom_host"
ENABLE_KEY = "cheevos_enable"
HARDCORE_KEY = "cheevos_hardcore_mode_enable"
USERNAME_KEY = "cheevos_username"
TOKEN_KEY = "cheevos_token"
PASSWORD_KEY = "cheevos_password"


def detect_hardcore_enabled(content: str) -> bool:
    return _extract_config_value(content, HARDCORE_KEY) == "true"


def load_retroarch_credentials(cfg_path: str | None) -> dict | None:
    token_credentials = load_retroarch_token_credentials(cfg_path)
    if token_credentials is not None:
        return token_credentials

    return load_retroarch_password_credentials(cfg_path)


def load_retroarch_token_credentials(cfg_path: str | None) -> dict | None:
    if not cfg_path:
        return None

    target = Path(cfg_path)
    if not target.exists():
        return None

    content = target.read_text(encoding="utf-8", errors="replace")
    user = _extract_config_value(content, USERNAME_KEY)
    token = _extract_config_value(content, TOKEN_KEY)
    if not user or not token:
        return None
    return {"user": user, "token": token}


def load_retroarch_password_credentials(cfg_path: str | None) -> dict | None:
    if not cfg_path:
        return None

    target = Path(cfg_path)
    if not target.exists():
        return None

    content = target.read_text(encoding="utf-8", errors="replace")
    user = _extract_config_value(content, USERNAME_KEY)
    password = _extract_config_value(content, PASSWORD_KEY)
    if not user or not password:
        return None
    return {"user": user, "password": password}


def retroarch_has_token(cfg_path: str | None) -> bool:
    return load_retroarch_credentials(cfg_path) is not None


def is_patched_content(content: str, proxy_address: str) -> bool:
    return _extract_config_value(content, HOST_KEY) == proxy_address


def build_patched_content(content: str, proxy_address: str) -> str:
    sanitized = _remove_orphan_boolean_lines(content)
    with_host = _upsert_config_value(sanitized, HOST_KEY, proxy_address)
    with_enable = _upsert_config_value(with_host, ENABLE_KEY, "true")
    return _upsert_config_value(with_enable, HARDCORE_KEY, "false")


def build_reverted_content(
    content: str,
    previous_host: Optional[str],
    previous_enable: Optional[str],
    restore_hardcore: bool,
) -> str:
    sanitized = _remove_orphan_boolean_lines(content)
    restored_host = previous_host if previous_host is not None else ""
    with_host = _upsert_config_value(sanitized, HOST_KEY, restored_host)
    restored_enable = previous_enable if previous_enable is not None else ""
    with_enable = _upsert_config_value(with_host, ENABLE_KEY, restored_enable)

    if not restore_hardcore:
        return with_enable

    return _upsert_config_value(with_enable, HARDCORE_KEY, "true")


def patch_retroarch_cfg(cfg_path: str, config_data: dict) -> dict:
    target = Path(cfg_path)
    if not target.exists():
        raise FileNotFoundError(f"RetroArch config not found: {target}")

    original = target.read_text(encoding="utf-8", errors="replace")
    proxy_address = proxy_value(config_data)
    previous_host = _extract_config_value(original, HOST_KEY)
    previous_enable = _extract_config_value(original, ENABLE_KEY)
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
            "previous_enable": previous_enable,
            "proxy_host": proxy_address,
            "retroarch_cfg": str(target),
        }
    )

    return {
        "cfg_path": str(target),
        "hardcore_was_enabled": hardcore_was_enabled,
        "previous_enable": previous_enable,
        "previous_host": previous_host,
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
    previous_enable = patch_state.get("previous_enable") if patch_state else ""
    restore_hardcore = (
        bool(patch_state.get("hardcore_was_enabled", False)) if patch_state else False
    )

    if patch_state is None:
        previous_host = ""
        previous_enable = _extract_config_value(current, ENABLE_KEY)

    transformed = build_reverted_content(
        current,
        previous_host,
        previous_enable,
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
        "cheevos_enabled": _extract_config_value(content, ENABLE_KEY) == "true",
        "hardcore_enabled": detect_hardcore_enabled(content),
        "state_present": state is not None,
        "proxy_host": proxy_address,
    }


def enforce_patched_cfg(cfg_path: str, config_data: dict) -> bool:
    target = Path(cfg_path)
    if not target.exists():
        return False

    current = target.read_text(encoding="utf-8", errors="replace")
    transformed = build_patched_content(current, proxy_value(config_data))
    if transformed == current:
        return False

    target.write_text(transformed, encoding="utf-8")
    return True


def _extract_config_value(content: str, key: str) -> str | None:
    key_pattern = re.compile(rf"^\s*{re.escape(key)}\s*=\s*(.*?)\s*$")
    for raw_line in content.splitlines():
        match = key_pattern.match(raw_line)
        if match is None:
            continue

        value = match.group(1).strip()
        if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
            return value[1:-1]
        if value.startswith('"'):
            return value[1:].strip()
        return value
    return None


def _upsert_config_value(content: str, key: str, value: str) -> str:
    pattern = re.compile(rf"^(\s*{re.escape(key)}\s*=\s*).*$", re.MULTILINE)
    if pattern.search(content):
        return pattern.sub(lambda match: f'{match.group(1)}"{value}"', content)

    stripped = content.rstrip("\n")
    if stripped:
        return f'{stripped}\n{key} = "{value}"\n'
    return f'{key} = "{value}"\n'


def _remove_orphan_boolean_lines(content: str) -> str:
    lines = content.splitlines()
    orphan_pattern = re.compile(
        r'^[\x00-\x1f\x7f\s]*"(?:true|false)"[\x00-\x1f\x7f\s]*$'
    )
    kept = [line for line in lines if not orphan_pattern.fullmatch(line)]
    result = "\n".join(kept)
    if content.endswith("\n") and result:
        result += "\n"
    return result
