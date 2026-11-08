from __future__ import annotations

import re
from pathlib import Path

from . import miyoo, spruce
from .base import Platform, existing_dir, read_stripped

APP_DIR = Path("/mnt/SDCARD/App/RAOfflineProxy")
# Onion's own version marker, used to break a tie when both firmwares have left traces on
# the card. See spruce.SprucePlatform.detect().
VERSION_FILE = Path("/mnt/SDCARD/.tmp_update/onionVersion/version.txt")

STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh")
AUTOSTART_LAUNCHER = APP_DIR / "autostart-launch.sh"
# Confirmed from OnionUI/Onion's own diagnostics script
# (static/build/.tmp_update/script/diagnostics/util_snapshot.sh), which reads the same two files.
DEVICE_MODEL_FILE = Path("/tmp/deviceModel")
# OnionOS v4.3.1-1 ships a bundled RetroArch build whose achievements client is not
# reliably compatible with a custom host, so only the build after it is supported.
SUPPORTED_VERSION_PREFIX = "v4.4.0"
# Builds are versioned "v<major>.<minor>.<patch>[-...]": compare the numbers, so later
# Onion versions (and forks such as RedOnion, "v4.5.0-...") are accepted too.
MIN_SUPPORTED_VERSION = (4, 4, 0)
_VERSION_RE = re.compile(r"v?(\d+)\.(\d+)\.(\d+)")


def boot_hook_script() -> str:
    return "\n".join(
        [
            "#!/bin/sh",
            "set -eu",
            "",
            f"APP_DIR={APP_DIR}",
            'if [ -x "$APP_DIR/autostart-launch.sh" ]; then',
            '  sh "$APP_DIR/autostart-launch.sh"',
            "fi",
            "",
        ]
    )


class OnionPlatform(Platform):
    id = "onion"
    name = "OnionOS"
    support_label = "Onion"
    update_asset_suffix = ".zip"
    update_install_kind = "archive"
    update_archive_root = "App"
    menu_font = "bundled"

    def detect(self) -> bool:
        # spruceOS reuses Onion's /mnt/SDCARD/App layout, so the app directory alone does not
        # identify Onion: without the spruce exclusion every Onion-only branch (most visibly
        # the OnionOS version gate) would also fire on spruce.
        return APP_DIR.exists() and not spruce.PLATFORM.detect()

    def startup_script(self) -> Path | None:
        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return APP_DIR / "data"

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return miyoo.RETROARCH_CFG_CANDIDATES


    def os_version(self) -> str | None:
        # Onion has no /etc/os-release; its own diagnostics tool reads this file instead.
        return read_stripped(VERSION_FILE)

    def device_label(self) -> str:
        # /tmp/deviceModel holds just the raw numeric chip/model code Onion itself uses (283 =
        # Miyoo Mini, 354 = Miyoo Mini Plus/Flip, see OnionUI/Onion's device_model.h). Onion's
        # own System Settings -> About Device screen prefixes it with "MY"; match that.
        raw = read_stripped(DEVICE_MODEL_FILE)
        if raw and raw.isdigit():
            return f"MY{raw}"
        return raw or self.support_label

    def unsupported_notice(self) -> tuple[str, str] | None:
        if self.version_supported():
            return None
        detected = self.os_version() or "unknown"
        return (
            "Unsupported Onion version",
            f"This OnionOS build ({detected}) is not supported.\n"
            f"RAOfflineProxy requires Onion {SUPPORTED_VERSION_PREFIX} or newer \u2014 "
            "older builds ship a RetroArch achievements client that is not reliably "
            "compatible with a custom host.\n"
            "All functionality is disabled until Onion is updated.",
        )

    def version_supported(self) -> bool:
        version = self.os_version()
        match = _VERSION_RE.match(version.strip().lower()) if version else None
        return bool(match) and tuple(int(part) for part in match.groups()) >= MIN_SUPPORTED_VERSION

    def unsupported_reason(self) -> str | None:
        if self.version_supported():
            return None
        detected = self.os_version() or "unknown"
        return (
            f"Unsupported OnionOS build ({detected}). RAOfflineProxy requires Onion "
            "v4.4.0-beta-20260120 or newer."
        )

    def app_dir(self) -> Path | None:
        return APP_DIR

    def open_display(self, pygame, log):
        # No fallback: the vendored SDL2 carries no driver but "Mini", which presents only
        # through this renderer path. A plain surface would come back as a menu that runs but
        # never draws, so failing and returning to the launcher is the better outcome.
        if miyoo.running_on_mini_sdl_stack():
            return miyoo.open_mini_display(pygame, log)
        return None

    def reads_input_directly(self) -> bool:
        return miyoo.running_on_mini_sdl_stack()

    def default_rom_root(self) -> Path | None:
        return existing_dir(miyoo.ROMS_ROOT)

    def autostart_launcher(self, config_data: dict) -> str:
        return str(AUTOSTART_LAUNCHER)

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(boot_hook_script(), encoding="utf-8")

    def remove_boot_hook(self, startup_script: Path) -> None:
        startup_script.unlink()

    def boot_hook_installed(self, startup_script: Path) -> bool:
        return True


PLATFORM = OnionPlatform()
