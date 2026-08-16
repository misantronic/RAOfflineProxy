from __future__ import annotations

import json
import logging
import logging.handlers
import os
from pathlib import Path


DEFAULT_ONION_APP_DIR = Path("/mnt/SDCARD/App/RAOfflineProxy")
DEFAULT_ONION_STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh")
# spruceOS keeps its bare version string ("4.3.3") in this file — the same one its own
# updater and spruceRestore upgrade scripts read.
SPRUCE_VERSION_FILE = Path("/mnt/SDCARD/spruce/spruce")
SPRUCE_RETROARCH_PLATFORM_DIR = Path("/mnt/SDCARD/RetroArch/platform")
CPUINFO_PATH = Path("/proc/cpuinfo")
MAGICX_MARKER = Path("/usr/magicx")
DEFAULT_MUOS_APPLICATION_DIR = Path("/run/muos/storage/application/RAOfflineProxy")
DEFAULT_MUOS_INIT_DIR = Path("/run/muos/storage/init")
DEFAULT_MUOS_RETROARCH_CFG = Path("/opt/muos/share/info/config/retroarch.cfg")
MUOS_USER_INIT_CONFIG = Path("/opt/muos/config/settings/advanced/user_init")
DEFAULT_BATOCERA_CONF = Path("/userdata/system/batocera.conf")
DEFAULT_KNULLI_CONF = Path("/userdata/system/knulli.conf")
DEFAULT_ROCKNIX_RETROARCH_CFG = Path("/storage/.config/retroarch/retroarch.cfg")
DEFAULT_ROCKNIX_CONFIG_DIR = Path("/storage/.config/raofflineproxy")
DEFAULT_ROCKNIX_PPSSPP_INI = Path("/storage/.config/ppsspp/PSP/SYSTEM/ppsspp.ini")
DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = Path("/storage/.config/dolphin-emu")
# ROCKNIX launches RetroArch with --appendconfig pointing here, and its setsettings.sh
# strips cheevos_username/cheevos_password out of retroarch.cfg on every launch, writing
# the live values into this file instead. It is the only place those credentials exist.
DEFAULT_ROCKNIX_APPEND_CFG = Path("/tmp/.retroarch.cfg")
OS_RELEASE_PATH = Path("/etc/os-release")


def running_on_rocknix() -> bool:
    try:
        content = OS_RELEASE_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return 'OS_NAME="ROCKNIX"' in content


def running_on_spruce() -> bool:
    return SPRUCE_VERSION_FILE.exists()


def running_on_onion() -> bool:
    # spruceOS reuses Onion's /mnt/SDCARD/App layout, so the app directory alone does not
    # identify Onion — without the spruce exclusion every Onion-only branch (most visibly
    # the OnionOS version gate) would also fire on spruce.
    return DEFAULT_ONION_APP_DIR.exists() and not running_on_spruce()


def running_on_onion_or_spruce() -> bool:
    """These two share the /mnt/SDCARD layout and the hardware, so this app ships one
    bundled stack for both: the "Mini" SDL2 video driver, no fontconfig, and
    gpio-keys-polled raw evdev input."""
    return running_on_onion() or running_on_spruce()


# Mirrors spruce's own device detection (spruce/scripts/helperFunctions.sh). The Anbernic
# 0xd03 branch is collapsed to one label because all its variants share a single RetroArch
# config file.
_SPRUCE_CPUINFO_PLATFORMS = (
    ("sun8i", "A30"),
    ("TG5040", "SmartPro"),
    ("TG3040", "Brick"),
    ("TG5050", "SmartProS"),
    ("TG4040", "BrickPro"),
    ("0xd05", "Flip"),
    ("0xd04", "Pixel2"),
    ("0xd03", "AnbernicRG_XX-universal"),
)


def spruce_platform() -> str:
    try:
        info = CPUINFO_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        info = ""

    for token, name in _SPRUCE_CPUINFO_PLATFORMS:
        if token in info:
            return name

    if MAGICX_MARKER.exists():
        return "Zero28"

    return "MiyooMini"


def spruce_retroarch_cfg() -> Path:
    """spruce launches RetroArch with --config pointing at this per-device file, so its
    .retroarch/retroarch.cfg is never read (see spruce/scripts/emu/lib/ra_functions.sh)."""
    return SPRUCE_RETROARCH_PLATFORM_DIR / f"retroarch-{spruce_platform()}.cfg"


def resolve_config_dir() -> Path:
    configured = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
    if configured:
        return Path(configured).expanduser()

    xdg_config_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_config_home:
        return Path(xdg_config_home).expanduser() / "raofflineproxy"

    if DEFAULT_ONION_APP_DIR.exists():
        return DEFAULT_ONION_APP_DIR / "data"

    if DEFAULT_MUOS_APPLICATION_DIR.exists():
        return DEFAULT_MUOS_APPLICATION_DIR / "data"

    if Path("/userdata/system").exists():
        return Path("/userdata/system/.config/raofflineproxy")

    if running_on_rocknix():
        return DEFAULT_ROCKNIX_CONFIG_DIR

    return Path.home() / ".config" / "raofflineproxy"


RA_HOST = "https://retroachievements.org"
RA_MEDIA_HOST = "https://media.retroachievements.org"
APP_VERSION = os.environ.get("RAOFFLINEPROXY_APP_VERSION") or "1.11.1-alpha1"
PROXY_UA_TAG = f"RAOfflineProxy/Linux/{APP_VERSION}"
FALLBACK_USER_AGENT = "RetroArch/1.21.0 (Linux)"

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
ONLINE_STATE_FILE = CONFIG_DIR / "online_state.json"
AWARD_SECRET_FILE = CONFIG_DIR / "award_secret.key"
UPDATE_STATUS_FILE = CONFIG_DIR / "update_status.json"


def ensure_config_dir() -> Path:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    return CONFIG_DIR


def configure_logging() -> None:
    ensure_config_dir()
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

    if Path("/opt/muos/script/archive").exists():
        return None

    if DEFAULT_KNULLI_CONF.exists():
        return str(DEFAULT_KNULLI_CONF)

    if DEFAULT_BATOCERA_CONF.exists():
        return str(DEFAULT_BATOCERA_CONF)

    return None


def detect_retroarch_cfg() -> str:
    env_override = os.environ.get("RAOFFLINEPROXY_RETROARCH_CFG")
    if env_override:
        return env_override

    if running_on_spruce():
        return str(spruce_retroarch_cfg())

    if DEFAULT_MUOS_RETROARCH_CFG.exists():
        return str(DEFAULT_MUOS_RETROARCH_CFG)

    if Path("/userdata").exists():
        return str(Path("/userdata/system/configs/retroarch/retroarchcustom.cfg"))

    if running_on_rocknix():
        return str(DEFAULT_ROCKNIX_RETROARCH_CFG)

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


def detect_rocknix_append_cfg(config_data: dict | None = None) -> str | None:
    configured = (config_data or {}).get("rocknix_append_cfg")
    if configured:
        return str(configured)

    env_override = os.environ.get("RAOFFLINEPROXY_ROCKNIX_APPEND_CFG")
    if env_override:
        return env_override

    if running_on_rocknix() and DEFAULT_ROCKNIX_APPEND_CFG.exists():
        return str(DEFAULT_ROCKNIX_APPEND_CFG)

    return None


def detect_ppsspp_ini(config_data: dict) -> str | None:
    configured = config_data.get("ppsspp_ini")
    if configured:
        return str(configured)

    env_override = os.environ.get("RAOFFLINEPROXY_PPSSPP_INI")
    if env_override:
        return env_override

    if DEFAULT_ROCKNIX_PPSSPP_INI.exists():
        return str(DEFAULT_ROCKNIX_PPSSPP_INI)

    return None


def detect_dolphin_config_dir(config_data: dict) -> str | None:
    configured = config_data.get("dolphin_config_dir")
    if configured:
        return str(configured)

    env_override = os.environ.get("RAOFFLINEPROXY_DOLPHIN_CONFIG_DIR")
    if env_override:
        return env_override

    if DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR.exists():
        return str(DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR)

    return None


def detect_dolphin_ini(config_data: dict) -> str | None:
    configured = config_data.get("dolphin_ini")
    if configured:
        return str(configured)

    env_override = os.environ.get("RAOFFLINEPROXY_DOLPHIN_INI")
    if env_override:
        return env_override

    config_dir = detect_dolphin_config_dir(config_data)
    if config_dir is None:
        return None

    return str(Path(config_dir) / "RetroAchievements.ini")
