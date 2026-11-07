from __future__ import annotations

from pathlib import Path

from . import rocknix
from .base import Platform


class GenericPlatform(Platform):
    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        # A /storage layout without a recognisable os-release (an unreadable file, or a
        # ROCKNIX-derived distribution under another name) still keeps RetroArch where
        # ROCKNIX does.
        if Path("/storage").exists():
            return (rocknix.RETROARCH_CFG,)
        return (Path.home() / ".config" / "retroarch" / "retroarch.cfg",)


PLATFORM = GenericPlatform()
