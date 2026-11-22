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
GBADS_HOST = "http://127.0.0.1:18080"
GBADS_CONFIGS = (
    Path("/data/store/apps/gbads/gba-ds.cfg"),
    Path("/data/roms/ports/gba-ds/gba-ds.cfg"),
)


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


def _backup_path(config_path: Path) -> Path:
    name = config_path.parent.name or "gbads"
    return CONFIG_DIR / f"{name}.gba-ds.cfg.raofflineproxy-original"


def patch_gbads_config(config_path: Path) -> None:
    if not config_path.is_file():
        return

    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    backup_path = _backup_path(config_path)
    if not backup_path.exists():
        shutil.copy2(config_path, backup_path)

    original = config_path.read_text(encoding="utf-8", errors="replace")
    updated = _rewrite_gbads_config(original)
    if updated == original:
        return

    fd, temporary = tempfile.mkstemp(
        prefix=".gba-ds.cfg.", dir=str(config_path.parent), text=True
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(updated)
        shutil.copymode(config_path, temporary)
        os.replace(temporary, config_path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise

    LOGGER.info("Patched GBA DS RetroAchievements host: %s", config_path)


def restore_gbads_config(config_path: Path) -> None:
    backup_path = _backup_path(config_path)
    if not backup_path.is_file():
        return
    try:
        shutil.copy2(backup_path, config_path)
        backup_path.unlink()
        LOGGER.info("Restored GBA DS RetroAchievements config: %s", config_path)
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
        for config_path in GBADS_CONFIGS:
            try:
                patch_gbads_config(config_path)
            except OSError:
                LOGGER.exception("Failed to patch GBA DS configuration: %s", config_path)

    def on_proxy_stopped(self) -> None:
        for config_path in GBADS_CONFIGS:
            restore_gbads_config(config_path)


PLATFORM = BananaPlatform()
