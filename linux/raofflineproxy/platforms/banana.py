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


def _rewrite_gbads_config(content: str, host: str | None) -> str:
    replacement = f"ra_host = {host}" if host is not None else None
    lines: list[str] = []
    replaced = False
    for line in content.splitlines():
        if re.match(r"^\s*#?\s*ra_host\s*=", line):
            if replacement is not None and not replaced:
                lines.append(replacement)
                replaced = True
            continue
        lines.append(line)
    if replacement is not None and not replaced:
        if lines and lines[-1] != "":
            lines.append("")
        lines.append(replacement)
    return "\n".join(lines) + "\n"


def patch_gbads_config(config_path: Path) -> None:
    if not config_path.is_file():
        return

    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    original = config_path.read_text(encoding="utf-8", errors="replace")
    updated = _rewrite_gbads_config(original, GBADS_HOST)
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


def remove_gbads_config(config_path: Path) -> None:
    if not config_path.is_file():
        return

    original = config_path.read_text(encoding="utf-8", errors="replace")
    updated = _rewrite_gbads_config(original, None)
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
        LOGGER.info("Removed GBA DS RetroAchievements host: %s", config_path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise

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
            try:
                remove_gbads_config(config_path)
            except OSError:
                LOGGER.exception("Failed to remove GBA DS configuration: %s", config_path)


PLATFORM = BananaPlatform()
