from __future__ import annotations

import sys
from pathlib import Path

from .base import Platform


class WindowsPlatform(Platform):
    """The raofflineproxy.windows package is imported only inside these hooks, so it never
    loads on the other devices."""

    id = "windows"
    name = "Windows"
    support_label = "Windows"
    # SDL's GameController mapping already puts A/B at BTN_SOUTH/BTN_EAST, which the menu's
    # confirm and cancel keys default to.
    needs_controller_calibration = False
    user_agent_os = "Windows"
    reuse_listen_address = False

    def detect(self) -> bool:
        return sys.platform == "win32"

    def config_dir(self) -> Path | None:
        from ..windows import paths

        return paths.config_dir()

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        from ..windows import paths

        # The placeholder comes last, so it's used only when no RetroArch exists.
        return (*paths.retroarch_cfg_candidates(), paths.retroarch_placeholder(config_dir))

    def ppsspp_ini(self) -> Path | None:
        from ..windows import paths

        return paths.ppsspp_ini()

    def dolphin_config_dir(self) -> Path | None:
        from ..windows import paths

        return paths.dolphin_config_dir()

    def pcsx2_ini(self) -> Path | None:
        from ..windows import paths

        return paths.pcsx2_ini()

    def open_display(self, pygame, log):
        from ..config import load_config
        from ..windows import input

        return input.open_menu_window(pygame, load_config())

    def menu_window_backend(self):
        # No /dev/input on Windows: gamepads come through SDL instead, as the same codes.
        from ..windows import input

        return input

    def menu_session_class(self):
        from ..windows.menu import WindowsMenuSession

        return WindowsMenuSession

    def write_emulator_config(self, target: Path, content: str) -> None:
        from ..windows.paths import write_preserving_newlines

        write_preserving_newlines(target, content)

    def adjust_config(self, config_data: dict) -> None:
        # Points each emulator at its real config, or at a placeholder when it's turned off
        # or its config doesn't exist yet (e.g. never launched).
        from ..windows.emulators import apply_state

        apply_state(config_data)

    def extra_log_files(self) -> list[Path]:
        from ..config import CONFIG_DIR

        # The in-place updater's transcript.
        return [CONFIG_DIR / "update.log"]

    def metadata_overrides(self, config_data: dict) -> dict:
        from ..windows.account import metadata_overrides

        return metadata_overrides(config_data)

    def native_library_dirs(self) -> tuple[Path, ...]:
        # Windows has no system libzstd/libcrypto: zstd.dll ships next to the app, and
        # libcrypto comes from the Python install's DLLs directory (the copy its ssl module uses).
        return (Path(__file__).resolve().parent.parent, Path(sys.base_prefix) / "DLLs")

    def watch_folder_prefix(self, folder: str) -> str:
        # Scanned paths are built with os.path.join, which uses backslashes there.
        return folder.rstrip("\\/") + "\\"

    def is_test_build(self) -> bool:
        from ..windows.paths import test_channel

        return bool(test_channel())

    def on_award_synced(self, storage, award: dict) -> None:
        from ..windows.history import record_synced_award

        record_synced_award(storage, award)

    def process_backend(self):
        from ..windows import process

        return process

    def preferred_rom_root(self, config_data: dict) -> Path | None:
        # A Windows ROM library can be anywhere, so it is configured ("rom_root" in config.json).
        configured = config_data.get("rom_root")
        if configured and Path(str(configured)).is_dir():
            return Path(str(configured))
        return None

    def autostart_supported(self, startup_script: Path | None) -> bool:
        return True

    def autostart_enabled(self, config_data: dict) -> bool | None:
        from ..windows import process

        return process.autostart_enabled(config_data)

    def enable_autostart(self, config_data: dict) -> bool:
        from ..windows import process

        process.enable_autostart()
        return True

    def disable_autostart(self, config_data: dict) -> bool:
        from ..windows import process

        process.disable_autostart()
        return True

    def duckstation_ini(self) -> Path | None:
        from ..windows import paths

        return paths.duckstation_ini()

    def flycast_cfg(self) -> Path | None:
        from ..windows import paths

        return paths.flycast_cfg()


PLATFORM = WindowsPlatform()
