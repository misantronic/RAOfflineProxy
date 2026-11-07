from __future__ import annotations

import logging
import os
import subprocess
from pathlib import Path

from .base import Platform, existing_dir, read_os_release

LOGGER = logging.getLogger("raofflineproxy")

STARTUP_SCRIPT = Path("/storage/.config/autostart/raofflineproxy.sh")
CONFIG_DIR = Path("/storage/.config/raofflineproxy")
RETROARCH_CFG = Path("/storage/.config/retroarch/retroarch.cfg")
PPSSPP_INI = Path("/storage/.config/ppsspp/PSP/SYSTEM/ppsspp.ini")
DOLPHIN_CONFIG_DIR = Path("/storage/.config/dolphin-emu")
# ROCKNIX's setsettings.sh strips cheevos_username/cheevos_password out of retroarch.cfg
# on every game launch, so this is where the credentials the user entered actually live,
# in Batocera's key format (global.retroachievements.username/password/token).
SYSTEM_CFG = Path("/storage/.config/system/configs/system.cfg")
ROMS_ROOT = Path("/storage/roms")
LAUNCHER = Path("/storage/.local/share/raofflineproxy/bin/raofflineproxy")
MODULES_DIR = Path("/storage/.config/modules")
MODULES_LAUNCHER = MODULES_DIR / "RAOfflineProxy.sh"
TOOL_SOURCE = Path("/storage/.local/share/raofflineproxy/RAOfflineProxy.sh")

# Chains ROCKNIX's bottom-screen RetroAchievements proxy through ours.
#
# On devices where ROCKNIX detects two connected displays, runemu.sh appends
# `cheevos_custom_host = "http://127.0.0.1:4874"` to the --appendconfig file and
# starts /usr/share/lowerdeck/ra_proxy.py. That layer outranks retroarch.cfg, so
# RetroArch talks to their proxy no matter what we patch, and theirs is a verbatim
# pass-through with no cache — offline it answers 502 with an empty body, which
# surfaces in RetroArch as "RetroAchievements game load failed: Invalid JSON".
#
# Rather than fight for the port, we start their proxy ourselves with its own
# --upstream flag pointed at us:
#
#     RetroArch -> lowerdeck ra_proxy :4874 -> RAOfflineProxy -> retroachievements.org
#
# Their bottom-screen UI still sees every response, and offline its upstream is our
# proxy, which is always reachable. runemu.sh only starts ra_proxy when its pgrep
# finds none running, so ours stays in charge once it is up.
DUAL_SCREEN_FLAG_FILE = Path("/storage/.config/profile.d/080-dual_screen_mode")
RA_PROXY_SCRIPT = Path("/usr/share/lowerdeck/ra_proxy.py")
# Mirrors the pattern runemu.sh greps for, so we can tell whether starting one
# would collide with an instance ROCKNIX already launched.
RA_PROXY_PROCESS_PATTERN = r"python3 .*/lowerdeck/ra_proxy\.py"


def detect_system_cfg(config_data: dict | None = None) -> str | None:
    configured = (config_data or {}).get("rocknix_system_cfg")
    if configured:
        return str(configured)

    env_override = os.environ.get("RAOFFLINEPROXY_ROCKNIX_SYSTEM_CFG")
    if env_override:
        return env_override

    if PLATFORM.detect() and SYSTEM_CFG.exists():
        return str(SYSTEM_CFG)

    return None


def is_dual_screen() -> bool:
    try:
        content = DUAL_SCREEN_FLAG_FILE.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return "DEVICE_HAS_DUAL_SCREEN=true" in content


def ra_proxy_running() -> bool:
    try:
        result = subprocess.run(
            ["pgrep", "-f", RA_PROXY_PROCESS_PATTERN],
            capture_output=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0


def should_chain_ra_proxy() -> bool:
    return is_dual_screen() and RA_PROXY_SCRIPT.exists()


def build_ra_proxy_command(config_data: dict) -> list[str]:
    from ..config import proxy_base

    return [
        "python3",
        str(RA_PROXY_SCRIPT),
        "--upstream",
        proxy_base(config_data),
        # Empty needle disables their watchdog. Left on, it calls srv.shutdown()
        # once RetroArch has been gone for its grace period, and the next game
        # launch would have ROCKNIX restart it pointed straight at RA again.
        "--retroarch-process-match",
        "",
    ]


_chained_process: subprocess.Popen | None = None


def ensure_ra_proxy_chained(config_data: dict) -> bool:
    """Starts ROCKNIX's ra_proxy pointed at us. Returns True if we started it."""
    global _chained_process

    from ..config import proxy_base

    if not should_chain_ra_proxy():
        return False

    if ra_proxy_running():
        LOGGER.info(
            "lowerdeck ra_proxy already running; leaving it alone "
            "(it may be pointed straight at RetroAchievements until it exits)"
        )
        return False

    command = build_ra_proxy_command(config_data)
    try:
        _chained_process = subprocess.Popen(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        LOGGER.warning("Failed to start lowerdeck ra_proxy chained through us: %s", exc)
        return False

    LOGGER.info("Started lowerdeck ra_proxy chained through %s", proxy_base(config_data))
    return True


def stop_ra_proxy_chain() -> bool:
    """Stops the ra_proxy we started. Returns True if we stopped one.

    Its RetroArch watchdog is disabled so it never exits on its own. Leaving it
    behind would point RetroArch at a port we no longer serve, breaking
    achievements outright — worse than the bug this works around. ROCKNIX starts
    its own unchained instance on the next game launch, so stopping ours simply
    restores stock behaviour.
    """
    global _chained_process

    process = _chained_process
    _chained_process = None
    if process is None or process.poll() is not None:
        return False

    try:
        process.terminate()
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
    except (OSError, subprocess.SubprocessError) as exc:
        LOGGER.warning("Failed to stop chained lowerdeck ra_proxy: %s", exc)
        return False

    LOGGER.info("Stopped chained lowerdeck ra_proxy")
    return True


def boot_hook_script(launcher: str) -> str:
    return "\n".join(
        [
            "#!/bin/sh",
            "set -u",
            "",
            "# ROCKNIX re-syncs /storage/.config/modules from a read-only source on",
            "# every boot (rsync --delete), wiping third-party Tools entries. Re-add",
            "# ours so the RAOfflineProxy Tools entry survives reboots.",
            f'if [ -f "{TOOL_SOURCE}" ]; then',
            f'  mkdir -p "{MODULES_DIR}"',
            f'  cp "{TOOL_SOURCE}" "{MODULES_LAUNCHER}" || true',
            f'  chmod +x "{MODULES_LAUNCHER}" || true',
            "fi",
            "",
            f'if [ -x "{launcher}" ]; then',
            f'  "{launcher}" boot-reconcile >/dev/null 2>&1 || true',
            "fi",
            "",
        ]
    )


class RocknixPlatform(Platform):
    id = "rocknix"
    name = "ROCKNIX"
    support_label = "ROCKNIX"
    update_asset_suffix = ".sh"
    menu_font = "system"

    def detect(self) -> bool:
        return 'OS_NAME="ROCKNIX"' in read_os_release()

    def startup_script(self) -> Path | None:
        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return CONFIG_DIR

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return (RETROARCH_CFG,)

    def batocera_conf(self, config_data: dict) -> str | None:
        # ROCKNIX has no batocera.conf/knulli.conf, but its system.cfg is the same thing
        # under a different name: EmulationStation writes the identical
        # global.retroachievements[.hardcore] keys there, and setsettings.sh feeds them
        # into RetroArch. Without this, hardcore stays on and every unlock the proxy
        # forwards is rejected with hardcore_not_supported.
        return detect_system_cfg(config_data)

    def ppsspp_ini(self) -> Path | None:
        return PPSSPP_INI if PPSSPP_INI.exists() else None

    def dolphin_config_dir(self) -> Path | None:
        return DOLPHIN_CONFIG_DIR if DOLPHIN_CONFIG_DIR.exists() else None

    def on_proxy_started(self, config_data: dict) -> None:
        ensure_ra_proxy_chained(config_data)

    def on_proxy_stopped(self) -> None:
        stop_ra_proxy_chain()


    def uninstall_launcher(self, service_mode: bool) -> Path | None:
        return LAUNCHER.parent / "raofflineproxy-uninstall"

    def default_rom_root(self) -> Path | None:
        return existing_dir(ROMS_ROOT)

    def autostart_launcher(self, config_data: dict) -> str:
        return str(config_data.get("autostart_launcher") or LAUNCHER)

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(
            boot_hook_script(self.autostart_launcher(config_data)), encoding="utf-8"
        )
        startup_script.chmod(0o755)

    def remove_boot_hook(self, startup_script: Path) -> None:
        startup_script.unlink()


PLATFORM = RocknixPlatform()
