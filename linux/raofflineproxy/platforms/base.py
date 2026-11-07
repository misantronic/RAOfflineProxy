from __future__ import annotations

from pathlib import Path

OS_RELEASE_PATH = Path("/etc/os-release")


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

    def on_proxy_started(self, config_data: dict) -> None:
        """Called once the proxy listens, for platform-specific setup."""

    def on_proxy_stopped(self) -> None:
        """Called while the proxy shuts down, to undo what on_proxy_started did."""
