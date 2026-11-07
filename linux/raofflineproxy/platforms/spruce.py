from __future__ import annotations

import json
import os
from pathlib import Path

from . import boot_hooks, miyoo, onion
from .base import Platform, existing_dir, read_os_release

# spruceOS keeps its bare version string ("4.3.3") in this file, the same one its own
# updater and spruceRestore upgrade scripts read.
VERSION_FILE = Path("/mnt/SDCARD/spruce/spruce")

# spruce reuses Onion's /mnt/SDCARD/App layout, and sits behind it on a shared card.
DEFAULT_PROXY_PORT = 8099
RA_CONFIG_DIR = Path("/mnt/SDCARD/Saves/ra-configs")
# spruce keeps the RetroAchievements credentials entered in its own settings here, and
# only writes them into the RetroArch config when a game launches (its prepare_ra_config
# seds them in). Before the first launch the config's cheevos_username is still empty, so
# this file is the only place the credentials exist.
CONFIG_JSON = Path("/mnt/SDCARD/Saves/spruce/spruce-config.json")
SETTINGS_MENU = "RetroAchievements Settings"

CPUINFO_PATH = Path("/proc/cpuinfo")
MAGICX_MARKER = Path("/usr/magicx")
BASEOS_RELEASE_PATH = Path("/etc/baseos-release")
LOONG_DAEMON = Path("/loong/loong_daemon")

# spruce has no drop-in boot directory: its boot entry point is one script that dispatches
# straight into the per-device startup path without returning. So the hook is inserted into
# that file, above the dispatch, rather than appended.
#
# Which file that is depends on what boots the device. Most spruce hardware comes up
# through .tmp_update/updater, but the Anbernic H700 line runs under BaseOS, which execs
# .system/h700/paks/MinUI.pak/launch.sh and reaches .tmp_update/anbernic.sh, and the RGB30
# comes up under MossySpruce via .tmp_update/rgb30.sh. Neither of those ever reads
# "updater", so a hook placed there is installed, reported as enabled, and never runs.
# One spruce card also boots all of these devices, so the hook goes into every entry point
# present rather than only the current device's (see SprucePlatform.install_boot_hook).
STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/updater")
H700_STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/anbernic.sh")
RGB30_STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/rgb30.sh")
STARTUP_SCRIPTS = (
    STARTUP_SCRIPT,
    H700_STARTUP_SCRIPT,
    RGB30_STARTUP_SCRIPT,
)
AUTOSTART_LAUNCHER = Path("/mnt/SDCARD/App/RAOfflineProxy/autostart-launch.sh")

# Mirrors spruce's own device detection (spruce/scripts/helperFunctions.sh). The name has
# to match exactly: it selects Saves/ra-configs/retroarch-<name>.cfg, and spruce ships
# one config per panel and pad layout rather than one per SoC.
_CPUINFO_PLATFORMS = (
    ("sun8i", "A30"),
    ("TG5040", "SmartPro"),
    ("TG3040", "Brick"),
    ("TG5050", "SmartProS"),
    ("TG4040", "BrickPro"),
    ("0xd04", "Pixel2"),
)

# BASEOS_TARGET -> spruce platform for the Allwinner H700 (0xd03) Anbernic line.
_H700_PLATFORMS = {
    "rg28xx": "AnbernicRG28XX",
    "rgcubexx": "AnbernicRGCubeXX",
    "rg34xxsp": "AnbernicXX720480",
    "rg34xx": "AnbernicXX720480NoStick",
    "rgsp": "AnbernicXX720480NoStick",
    "rg35xxplus": "AnbernicXX640480NoStick",
    "rg35xxsp": "AnbernicXX640480NoStick",
    "rg40xxv": "AnbernicXX640480OneStick",
}
_H700_DEFAULT = "AnbernicXX640480"


def _baseos_target() -> str:
    try:
        content = BASEOS_RELEASE_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""

    for line in content.splitlines():
        if line.startswith("BASEOS_TARGET="):
            return line.split("=", 1)[1].strip()

    return ""


def _rk3566_platform() -> str:
    """The RK3566 boards share a Cortex-A55 part id, so cpuinfo alone cannot separate
    them."""
    if 'OS_NAME="DARKMOSS"' in read_os_release():
        return "RGB30"

    if LOONG_DAEMON.exists():
        return "Miniloong"

    return "Flip"


def device_name() -> str:
    # appEnv.sh exports the platform spruce detected; the table below is the fallback.
    platform_name = os.environ.get("SPRUCE_PLATFORM", "").strip()
    if platform_name:
        return platform_name

    try:
        info = CPUINFO_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        info = ""

    for token, name in _CPUINFO_PLATFORMS:
        if token in info:
            return name

    if "0xd05" in info:
        return _rk3566_platform()

    if "0xd03" in info:
        return _H700_PLATFORMS.get(_baseos_target(), _H700_DEFAULT)

    if MAGICX_MARKER.exists():
        return "Zero28"

    return "MiyooMini"


def retroarch_cfg() -> Path:
    """The live per-device config spruce launches RetroArch with. RetroArch/platform holds
    only .cfg.bak seeds since spruce 4.4.2."""
    return RA_CONFIG_DIR / f"retroarch-{device_name()}.cfg"


def setting(name: str) -> str | None:
    """Reads .menuOptions."<menu>".<name>.selected out of spruce's settings file, the same
    path its own get_config_value helper uses."""
    try:
        with CONFIG_JSON.open(encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError):
        return None

    if not isinstance(data, dict):
        return None

    menu = data.get("menuOptions")
    if not isinstance(menu, dict):
        return None

    section = menu.get(SETTINGS_MENU)
    if not isinstance(section, dict):
        return None

    entry = section.get(name)
    if not isinstance(entry, dict):
        return None

    value = entry.get("selected")
    if not isinstance(value, str):
        return None

    return value.strip() or None


class SprucePlatform(Platform):
    id = "spruce"
    name = "spruce"

    def detect(self) -> bool:
        if not VERSION_FILE.exists():
            return False

        # Reinstalling Onion over a card that once ran spruce leaves /mnt/SDCARD/spruce in
        # place, which would otherwise make an Onion device answer yes here and then get
        # spruce's config path, port and boot hook. Onion's own version file settles it: the
        # reverse case cannot happen, because spruce's updater deletes .tmp_update wholesale.
        return not onion.VERSION_FILE.exists()

    def startup_script(self) -> Path | None:
        """spruce names the Anbernic H700 family "Anbernic*" in its own device table
        (helperFunctions.sh), so the prefix is spruce's own idiom rather than ours."""
        name = device_name()

        if name.startswith("Anbernic"):
            return H700_STARTUP_SCRIPT

        if name == "RGB30":
            return RGB30_STARTUP_SCRIPT

        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return onion.APP_DIR / "data"

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        # spruce launches RetroArch with --config pointing at a per-device file, so it never
        # reads the shared .retroarch/retroarch.cfg.
        return (retroarch_cfg(),)

    def default_proxy_port(self) -> int | None:
        return DEFAULT_PROXY_PORT

    def default_rom_root(self) -> Path | None:
        return existing_dir(miyoo.ROMS_ROOT)

    def own_credentials(self) -> dict | None:
        # spruce's own settings hold the credentials before any game launch has copied them
        # into the RetroArch config. Password-only: spruce stores no token.
        user = setting("username")
        password = setting("password")
        if not user or not password:
            return None

        return {"user": user, "password": password}

    def autostart_launcher(self, config_data: dict) -> str:
        return str(AUTOSTART_LAUNCHER)

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        """Prepends the hook to spruce's boot entry points, straight after the shebang.

        It cannot be appended: each file ends by dispatching into a per-device startup script
        that never returns. Prepending also keeps this independent of what that dispatch looks
        like, since the hook only backgrounds our launcher and needs nothing spruce sets up first.

        The device's own entry point is required. Every other spruce entry point present on
        the card gets the hook too, because one spruce card boots many devices: a card moved
        from a Miyoo Mini to an RG40XX boots through anbernic.sh instead of updater, and a hook
        in only one of them leaves autostart dead on the other. Each file only ever runs on its
        own device family, and the block is guarded and backgrounded, so the copies a device
        never executes are inert.
        """
        block = boot_hooks.background_block(AUTOSTART_LAUNCHER)
        boot_hooks.prepend_block(startup_script, block, "spruce")

        for sibling in STARTUP_SCRIPTS:
            if sibling == startup_script or not sibling.exists():
                continue

            try:
                boot_hooks.prepend_block(sibling, block, "spruce")
            except (OSError, ValueError):
                continue

    def remove_boot_hook(self, startup_script: Path) -> None:
        for script in dict.fromkeys((startup_script, *STARTUP_SCRIPTS)):
            if not script.exists():
                continue

            try:
                existing = script.read_text(encoding="utf-8", errors="replace")
                cleaned = boot_hooks.strip_block(existing)
                if cleaned != existing:
                    script.write_text(cleaned.replace("\n\n\n", "\n\n"), encoding="utf-8")
            except OSError:
                continue


PLATFORM = SprucePlatform()
