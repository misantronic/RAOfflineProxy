import json
import logging
import os
from pathlib import Path


DEFAULT_ONION_APP_DIR = Path("/mnt/SDCARD/App/RAOfflineProxy")
DEFAULT_ONION_STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh")
DEFAULT_BATOCERA_CONF = Path("/userdata/system/batocera.conf")
DEFAULT_KNULLI_CONF = Path("/userdata/system/knulli.conf")


def resolve_config_dir() -> Path:
    configured = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
    if configured:
        return Path(configured).expanduser()

    xdg_config_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_config_home:
        return Path(xdg_config_home).expanduser() / "raofflineproxy"

    if DEFAULT_ONION_APP_DIR.exists():
        return DEFAULT_ONION_APP_DIR / "data"

    if Path("/userdata/system").exists():
        return Path("/userdata/system/.config/raofflineproxy")

    return Path.home() / ".config" / "raofflineproxy"


RA_HOST = "https://retroachievements.org"
PROXY_UA_TAG = "RAOfflineProxy/Linux/1.2.1-alpha1"
FALLBACK_USER_AGENT = "RetroArch/1.21.0 (Linux)"
APP_VERSION = os.environ.get("RAOFFLINEPROXY_APP_VERSION") or "1.2.1-alpha1"

DEFAULT_PROXY_PORT = 8080
MIN_PROXY_PORT = 1024
MAX_PROXY_PORT = 65535

CONFIG_DIR = resolve_config_dir()
CONFIG_FILE = CONFIG_DIR / "config.json"
STATE_FILE = CONFIG_DIR / "retroarch_patch_state.json"
DATABASE_FILE = CONFIG_DIR / "proxy.sqlite3"
PID_FILE = CONFIG_DIR / "service.pid"
LOG_FILE = CONFIG_DIR / "service.log"
STATUS_FILE = CONFIG_DIR / "service_status.json"
AWARD_SECRET_FILE = CONFIG_DIR / "award_secret.key"
UPDATE_STATUS_FILE = CONFIG_DIR / "update_status.json"


def ensure_config_dir() -> Path:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    return CONFIG_DIR


def configure_logging() -> None:
    ensure_config_dir()
    logging.basicConfig(
        filename=str(LOG_FILE),
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        force=True,
    )


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {}

    with CONFIG_FILE.open(encoding="utf-8") as handle:
        data = json.load(handle)

    if not isinstance(data, dict):
        raise ValueError(f"Invalid config file: {CONFIG_FILE}")

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


def proxy_port(config_data: dict) -> int:
    raw_port = config_data.get("proxy_port", DEFAULT_PROXY_PORT)
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


def detect_batocera_conf(config_data: dict) -> str | None:
    configured = config_data.get("batocera_conf")
    if configured:
        return str(configured)

    env_override = os.environ.get("RAOFFLINEPROXY_BATOCERA_CONF")
    if env_override:
        return env_override

    if DEFAULT_KNULLI_CONF.exists():
        return str(DEFAULT_KNULLI_CONF)

    if DEFAULT_BATOCERA_CONF.exists():
        return str(DEFAULT_BATOCERA_CONF)

    return None


def detect_retroarch_cfg() -> str:
    env_override = os.environ.get("RAOFFLINEPROXY_RETROARCH_CFG")
    if env_override:
        return env_override

    if Path("/userdata").exists():
        return str(Path("/userdata/system/configs/retroarch/retroarchcustom.cfg"))

    candidates = [
        Path("/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg"),
        Path.home() / ".config" / "retroarch" / "retroarch.cfg",
    ]

    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    if Path("/storage").exists():
        return str(Path("/storage/.config/retroarch/retroarch.cfg"))

    if Path("/mnt/SDCARD").exists():
        return str(Path("/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg"))

    return str(Path.home() / ".config" / "retroarch" / "retroarch.cfg")
