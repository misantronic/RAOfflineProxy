from __future__ import annotations

import json
import logging
import logging.handlers
import os
from pathlib import Path

from . import platforms


def resolve_config_dir() -> Path:
    configured = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
    if configured:
        return Path(configured).expanduser()

    xdg_config_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_config_home:
        return Path(xdg_config_home).expanduser() / "raofflineproxy"

    return platforms.current().config_dir() or Path.home() / ".config" / "raofflineproxy"


RA_HOST = "https://retroachievements.org"
RA_MEDIA_HOST = "https://media.retroachievements.org"
APP_VERSION = os.environ.get("RAOFFLINEPROXY_APP_VERSION") or "3.0.0-alpha1"
PROXY_UA_TAG = f"RAOfflineProxy/{platforms.current().user_agent_os}/{APP_VERSION}"
FALLBACK_USER_AGENT = "RetroArch/1.21.0 (Linux)"

DEFAULT_PROXY_PORT = 8080
# spruce ships SFTPGo bound to 0.0.0.0:8080 (its sftpgo.json httpd binding) and starts it
# whenever SFTPGo is enabled in Network Settings, so the usual default can never bind.
MIN_PROXY_PORT = 1024
MAX_PROXY_PORT = 65535

CONFIG_DIR = resolve_config_dir()
CONFIG_FILE = CONFIG_DIR / "config.json"
STATE_FILE = CONFIG_DIR / "retroarch_patch_state.json"
DATABASE_FILE = CONFIG_DIR / "proxy.sqlite3"
PID_FILE = CONFIG_DIR / "service.pid"
LOG_FILE = CONFIG_DIR / "service.log"
STATUS_FILE = CONFIG_DIR / "service_status.json"
ONLINE_STATE_FILE = CONFIG_DIR / "online_state.json"
AWARD_SECRET_FILE = CONFIG_DIR / "award_secret.key"
UPDATE_STATUS_FILE = CONFIG_DIR / "update_status.json"


def ensure_config_dir() -> Path:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    return CONFIG_DIR


def configure_logging() -> None:
    ensure_config_dir()
    backend = platforms.current().process_backend()
    if backend is not None:
        handler = backend.rotating_log_handler(str(LOG_FILE), 2 * 1024 * 1024, 1, "utf-8")
    else:
        handler = logging.handlers.RotatingFileHandler(
            str(LOG_FILE),
            maxBytes=2 * 1024 * 1024,
            backupCount=1,
            encoding="utf-8",
        )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    logging.basicConfig(handlers=[handler], level=logging.INFO, force=True)


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {}

    with CONFIG_FILE.open(encoding="utf-8") as handle:
        try:
            data = json.load(handle)
        except json.JSONDecodeError:
            logging.getLogger("raofflineproxy").warning(
                "Config file %s is empty or corrupt, resetting to defaults", CONFIG_FILE
            )
            return {}

    if not isinstance(data, dict):
        raise ValueError(f"Invalid config file: {CONFIG_FILE}")

    platforms.current().adjust_config(data)

    return data


def image_caching_enabled(config_data: dict | None = None) -> bool:
    config_data = config_data or {}
    configured = config_data.get("cache_images")
    if configured is not None:
        return bool(configured)

    env_value = os.environ.get("RAOFFLINEPROXY_CACHE_IMAGES")
    if env_value is not None:
        return env_value.strip().lower() not in {"0", "false", "no", "off"}

    return True


def save_config(data: dict) -> None:
    ensure_config_dir()
    with CONFIG_FILE.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=True)
        handle.write("\n")


def default_proxy_port() -> int:
    return platforms.current().default_proxy_port() or DEFAULT_PROXY_PORT


def proxy_port(config_data: dict) -> int:
    raw_port = config_data.get("proxy_port", default_proxy_port())
    port = int(raw_port)
    if not (MIN_PROXY_PORT <= port <= MAX_PROXY_PORT):
        raise ValueError(f"Invalid proxy port: {port}")
    return port


def proxy_host(config_data: dict) -> str:
    return str(config_data.get("proxy_host") or "127.0.0.1")


def proxy_value(config_data: dict) -> str:
    return f"{proxy_host(config_data)}:{proxy_port(config_data)}"


def proxy_base(config_data: dict) -> str:
    return f"http://{proxy_value(config_data)}"


def upstream_host(config_data: dict) -> str:
    return str(config_data.get("upstream_host") or RA_HOST).rstrip("/")
