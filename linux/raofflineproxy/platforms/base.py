from __future__ import annotations

from pathlib import Path

from . import boot_hooks

OS_RELEASE_PATH = Path("/etc/os-release")


def existing_dir(path: Path) -> Path | None:
    return path if path.is_dir() else None


def read_os_release() -> str:
    try:
        return OS_RELEASE_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


class Platform:
    """One supported system. Everything that differs between systems belongs on a subclass,
    so the core never has to ask which system it runs on."""

    id: str = "generic"
    name: str = "Linux device"

    def detect(self) -> bool:
        return False

    def startup_script(self) -> Path | None:
        """The file the boot hook is written to, or None where the platform has no boot hook."""
        return None

    def config_dir(self) -> Path | None:
        """Where settings, cached games and awards live, or None for the generic default."""
        return None

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        """retroarch.cfg locations, most specific first. The first one doubles as the fallback
        for a device that has not generated its cfg yet."""
        return ()

    def default_proxy_port(self) -> int | None:
        return None

    def batocera_conf(self, config_data: dict) -> str | None:
        """EmulationStation's own settings file, where the platform has one."""
        return None

    def secondary_retroarch_cfg(self, config_data: dict | None = None) -> str | None:
        """A second RetroArch build's cfg that needs the same patch as the primary one."""
        return None

    def ppsspp_ini(self) -> Path | None:
        return None

    def dolphin_config_dir(self) -> Path | None:
        return None

    def pcsx2_ini(self) -> Path | None:
        return None

    def duckstation_ini(self) -> Path | None:
        return None

    def flycast_cfg(self) -> Path | None:
        return None

    def own_credentials(self) -> dict | None:
        """RetroAchievements credentials kept in the platform's own settings, the only source
        before a game launch copies them into the RetroArch config. Password only."""
        return None

    def remove_stale_files(self) -> None:
        """Deletes files an earlier release installed and no longer wants."""

    def install_service_unit(self) -> bool:
        """Installs the platform's service definition. Returns False where there is none."""
        return False

    def history_source(self) -> Path | None:
        """The platform's own recently-played store, when it keeps one."""
        return None

    def recent_rom_paths(self) -> list[Path] | None:
        """ROMs from history_source(), or None to fall back to RetroArch's content history."""
        return None

    def on_proxy_started(self, config_data: dict) -> None:
        """Called once the proxy listens, for platform-specific setup."""

    def on_proxy_stopped(self) -> None:
        """Called while the proxy shuts down, to undo what on_proxy_started did."""

    def preferred_rom_root(self, config_data: dict) -> Path | None:
        """A ROM library the platform knows for sure, used before consulting RetroArch's cfg."""
        return None

    def default_rom_root(self) -> Path | None:
        """Where ROMs live on this platform when neither the above nor the cfg say."""
        return None

    def autostart_supported(self, startup_script: Path | None) -> bool:
        return startup_script is not None

    def autostart_enabled(self, config_data: dict) -> bool | None:
        """The platform's own answer when something other than the config flag owns the state
        (a systemd unit, the registry), or None to use the flag."""
        return None

    def enable_autostart(self, config_data: dict) -> bool:
        """Returns True when the platform enabled autostart itself, with no boot hook file."""
        return False

    def disable_autostart(self, config_data: dict) -> bool:
        """Returns True when the platform disabled autostart itself."""
        return False

    def on_autostart_disabled(self) -> None:
        """Called after the config flag is cleared, for platforms with something to switch off."""

    def autostart_launcher(self, config_data: dict) -> str:
        configured = config_data.get("autostart_launcher")
        if configured:
            return str(configured)
        raise ValueError("autostart_launcher is not set and this platform has no default")

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        boot_hooks.append_block(startup_script, boot_hooks.autostart_block(self.autostart_launcher(config_data)))

    def remove_boot_hook(self, startup_script: Path) -> None:
        boot_hooks.remove_block(startup_script)

    def boot_hook_installed(self, startup_script: Path) -> bool:
        return boot_hooks.has_block(boot_hooks.read_script(startup_script))
