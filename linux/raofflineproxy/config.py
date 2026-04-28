import json
import os
from pathlib import Path

RA_HOST = "https://retroachievements.org"
PROXY_UA_TAG = "RAOfflineProxy/Linux/1.0.0-alpha1"
FALLBACK_USER_AGENT = "rcheevos/11.4.0"

DEFAULT_PROXY_PORT = 8080
MIN_PROXY_PORT = 1024
MAX_PROXY_PORT = 65535

CONFIG_DIR = Path.home() / ".config" / "raofflineproxy"
CONFIG_FILE = CONFIG_DIR / "config.json"
STATE_FILE = CONFIG_DIR / "retroarch_patch_state.json"
DATABASE_FILE = CONFIG_DIR / "proxy.sqlite3"
PID_FILE = CONFIG_DIR / "service.pid"
LOG_FILE = CONFIG_DIR / "service.log"
STATUS_FILE = CONFIG_DIR / "service_status.json"
AWARD_SECRET_FILE = CONFIG_DIR / "award_secret.key"
DEFAULT_BATOCERA_CONF = Path("/userdata/system/batocera.conf")


def ensure_config_dir() -> Path:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    return CONFIG_DIR


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {}

    with CONFIG_FILE.open(encoding="utf-8") as handle:
        data = json.load(handle)

    if not isinstance(data, dict):
        raise ValueError(f"Invalid config file: {CONFIG_FILE}")

    return data


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

    if DEFAULT_BATOCERA_CONF.exists():
        return str(DEFAULT_BATOCERA_CONF)

    return None


def detect_retroarch_cfg() -> str:
    env_override = os.environ.get("RAOFFLINEPROXY_RETROARCH_CFG")
    if env_override:
        return env_override

    candidates = [
        Path("/userdata/system/configs/retroarch/retroarchcustom.cfg"),
        Path("/userdata/system/configs/retroarch/retroarch.cfg"),
        Path("/userdata/system/.config/retroarch/retroarchcustom.cfg"),
        Path("/userdata/system/.config/retroarch/retroarch.cfg"),
        Path("/storage/.config/retroarch/retroarch.cfg"),
        Path.home() / ".config" / "retroarch" / "retroarch.cfg",
    ]

    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    if Path("/userdata").exists():
        return str(Path("/userdata/system/configs/retroarch/retroarchcustom.cfg"))

    if Path("/storage").exists():
        return str(Path("/storage/.config/retroarch/retroarch.cfg"))

    return str(Path.home() / ".config" / "retroarch" / "retroarch.cfg")
