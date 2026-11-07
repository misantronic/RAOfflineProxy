from __future__ import annotations

from pathlib import Path

from .base import Platform, existing_dir

MARKER_PATH = Path("/opt/muos/script/archive")

INIT_DIR = Path("/run/muos/storage/init")
APP_DIR = Path("/run/muos/storage/application/RAOfflineProxy")
RETROARCH_CFG = Path("/opt/muos/share/info/config/retroarch.cfg")
USER_INIT_CONFIG = Path("/opt/muos/config/settings/advanced/user_init")
STARTUP_SCRIPT = INIT_DIR / "raofflineproxy.sh"
ROMS_ROOT = Path("/mnt/mmc/ROMS")
LAUNCHER = APP_DIR / "launch.sh"


def boot_hook_script(launcher: str) -> str:
    data_dir = Path(launcher).parent / "data"
    boot_log = data_dir / "boot.log"
    return "\n".join(
        [
            "#!/bin/sh",
            "set -eu",
            "",
            f'mkdir -p "{data_dir}"',
            'stamp() { date "+%Y-%m-%d %H:%M:%S"; }',
            f'if [ ! -x "{launcher}" ]; then',
            f'  echo "$(stamp) launcher missing or not executable: {launcher}" >>"{boot_log}"',
            "  exit 0",
            "fi",
            f'echo "$(stamp) boot hook start, uptime $(cut -d" " -f1 /proc/uptime)s" >>"{boot_log}"',
            f'"{launcher}" boot-reconcile >>"{boot_log}" 2>&1 || echo "$(stamp) boot-reconcile failed ($?)" >>"{boot_log}"',
            f'echo "$(stamp) boot hook done" >>"{boot_log}"',
            "",
        ]
    )


class MuosPlatform(Platform):
    id = "muos"
    name = "muOS"

    def detect(self) -> bool:
        return MARKER_PATH.exists()

    def startup_script(self) -> Path | None:
        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return APP_DIR / "data"

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return (RETROARCH_CFG,)


    def preferred_rom_root(self, config_data: dict) -> Path | None:
        return existing_dir(ROMS_ROOT)

    def autostart_launcher(self, config_data: dict) -> str:
        return str(config_data.get("autostart_launcher") or LAUNCHER)

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(
            boot_hook_script(self.autostart_launcher(config_data)), encoding="utf-8"
        )
        startup_script.chmod(0o755)
        if USER_INIT_CONFIG.parent.exists():
            USER_INIT_CONFIG.write_text("1\n", encoding="utf-8")

    def remove_boot_hook(self, startup_script: Path) -> None:
        startup_script.unlink()

    def boot_hook_installed(self, startup_script: Path) -> bool:
        if USER_INIT_CONFIG.exists():
            try:
                if USER_INIT_CONFIG.read_text(encoding="utf-8").strip() != "1":
                    return False
            except OSError:
                pass
        return True


PLATFORM = MuosPlatform()
