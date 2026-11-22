from __future__ import annotations

import logging
import os
import re
import shutil
import tempfile
from pathlib import Path

from .base import Platform

LOGGER = logging.getLogger("raofflineproxy")

BANANA_MARKER = Path("/etc/banana")
CONFIG_DIR = Path("/data/banana/raofflineproxy")
RETROARCH_CFG = Path("/data/retroarch/retroarch.cfg")
ROMS_ROOT = Path("/data/roms")
GBADS_CFG = Path("/data/store/apps/gbads/gba-ds.cfg")
GBADS_BACKUP = CONFIG_DIR / "gba-ds.cfg.raofflineproxy-original"
GBADS_HOST = "http://127.0.0.1:18080"


def _rewrite_gbads_config(content: str) -> str:
    replacement = f"ra_host = {GBADS_HOST}"
    lines: list[str] = []
    replaced = False
    for line in content.splitlines():
        if re.match(r"^\s*#?\s*ra_host\s*=", line):
            if not replaced:
                lines.append(replacement)
                replaced = True
            continue
        lines.append(line)
    if not replaced:
        if lines and lines[-1] != "":
            lines.append("")
        lines.append(replacement)
    return "\n".join(lines) + "\n"


def patch_gbads_config() -> None:
    if not GBADS_CFG.is_file():
        return

    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if not GBADS_BACKUP.exists():
        shutil.copy2(GBADS_CFG, GBADS_BACKUP)

    original = GBADS_CFG.read_text(encoding="utf-8", errors="replace")
    updated = _rewrite_gbads_config(original)
    if updated == original:
        return

    fd, temporary = tempfile.mkstemp(
        prefix=".gba-ds.cfg.", dir=str(GBADS_CFG.parent), text=True
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(updated)
        shutil.copymode(GBADS_CFG, temporary)
        os.replace(temporary, GBADS_CFG)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise

    LOGGER.info("Patched GBA DS RetroAchievements host: %s", GBADS_CFG)


def restore_gbads_config() -> None:
    if not GBADS_BACKUP.is_file():
        return
    try:
        shutil.copy2(GBADS_BACKUP, GBADS_CFG)
        GBADS_BACKUP.unlink()
        LOGGER.info("Restored GBA DS RetroAchievements config: %s", GBADS_CFG)
    except OSError:
        LOGGER.exception("Failed to restore GBA DS configuration")


class BananaPlatform(Platform):
    id = "bananaos"
    name = "BananaOS"
    support_label = "BananaOS"
    menu_font = "default"
    needs_controller_calibration = False
    user_agent_os = "BananaOS"

    def detect(self) -> bool:
        return BANANA_MARKER.is_dir()

    def config_dir(self) -> Path | None:
        return CONFIG_DIR

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return (RETROARCH_CFG,)

    def default_rom_root(self) -> Path | None:
        return ROMS_ROOT if ROMS_ROOT.is_dir() else None

    def on_proxy_started(self, config_data: dict) -> None:
        try:
            patch_gbads_config()
        except OSError:
            LOGGER.exception("Failed to patch GBA DS configuration")

    def on_proxy_stopped(self) -> None:
        restore_gbads_config()


PLATFORM = BananaPlatform()
