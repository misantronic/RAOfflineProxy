from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

from .base import Platform, existing_dir, read_stripped

USERDATA_SYSTEM = Path("/userdata/system")
STARTUP_SCRIPT = Path("/userdata/system/custom.sh")
CONFIG_DIR = Path("/userdata/system/.config/raofflineproxy")
BATOCERA_CONF = Path("/userdata/system/batocera.conf")
KNULLI_CONF = Path("/userdata/system/knulli.conf")
ROMS_ROOT = Path("/userdata/roms")
LAUNCHER = Path("/userdata/system/raofflineproxy/bin/raofflineproxy")
STALE_HOOK_PATH = Path("/userdata/system/scripts/RAOfflineProxy_game_hook.sh")
# /etc/os-release's OS_VERSION is deliberately truncated to just the leading number by Knulli's
# own build script (board/scripts/post-build-script.sh in knulli-cfw/knulli-linux); this
# untruncated file (version + build date + time) is what Knulli's own knulli-report-stats tool
# reads instead.
VERSION_FILE = Path("/usr/share/knulli/knulli.version")
# Knulli/Batocera generate retroarchcustom.cfg at the first libretro launch, so a
# freshly flashed device has none of these yet. Keep the canonical path as the
# fallback: /userdata already established the platform, and falling
# through to the other platforms' branches would hand back a path Knulli never uses.
RETROARCH_CFG_CANDIDATES = (
    Path("/userdata/system/configs/retroarch/retroarchcustom.cfg"),
    Path("/userdata/system/configs/retroarch/retroarch.cfg"),
    Path("/userdata/system/.config/retroarch/retroarchcustom.cfg"),
    Path("/userdata/system/.config/retroarch/retroarch.cfg"),
)

SERVICE_NAME = "raofflineproxy"
USER_SERVICE_FILE = Path("/userdata/system/services/raofflineproxy")
SYSTEM_SERVICE_FILES = (
    Path("/usr/share/knulli/services/raofflineproxy"),
    Path("/usr/share/batocera/services/raofflineproxy"),
)


class KnulliPlatform(Platform):
    id = "knulli"
    name = "Knulli"
    support_label = "Knulli"
    update_asset_suffix = ".sh"

    def detect(self) -> bool:
        return USERDATA_SYSTEM.exists()

    def startup_script(self) -> Path | None:
        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return CONFIG_DIR

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return RETROARCH_CFG_CANDIDATES

    def batocera_conf(self, config_data: dict) -> str | None:
        if KNULLI_CONF.exists():
            return str(KNULLI_CONF)

        if BATOCERA_CONF.exists():
            return str(BATOCERA_CONF)

        return None

    def os_version(self) -> str | None:
        return read_stripped(VERSION_FILE) or super().os_version()

    def default_rom_root(self) -> Path | None:
        return existing_dir(ROMS_ROOT)

    def remove_stale_files(self) -> None:
        if STALE_HOOK_PATH.exists():
            STALE_HOOK_PATH.unlink()

    def autostart_launcher(self, config_data: dict) -> str:
        return str(config_data.get("autostart_launcher") or LAUNCHER)


def services_binary() -> str | None:
    for name in ("knulli-services", "batocera-services"):
        found = shutil.which(name)
        if found:
            return found
    return None


def service_mode_active() -> bool:
    if services_binary() is None:
        return False
    if USER_SERVICE_FILE.exists():
        return True
    return any(path.exists() for path in SYSTEM_SERVICE_FILES)


def _run(action: str) -> None:
    binary = services_binary()
    if binary is None:
        raise RuntimeError("knulli-services not available")
    subprocess.run(
        [binary, action, SERVICE_NAME],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )


def service_autostart_enabled() -> bool:
    binary = services_binary()
    if binary is None:
        return False
    try:
        output = subprocess.check_output([binary, "list"], text=True, timeout=30)
    except (subprocess.SubprocessError, OSError):
        return False
    for line in output.splitlines():
        parts = line.split(";")
        if len(parts) == 2 and parts[0].strip() == SERVICE_NAME:
            return parts[1].strip() == "*"
    return False


def enable_service_autostart() -> None:
    _run("enable")


def disable_service_autostart() -> None:
    _run("disable")


def start_service() -> None:
    _run("start")


def stop_service() -> None:
    _run("stop")


PLATFORM = KnulliPlatform()
