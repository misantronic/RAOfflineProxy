from __future__ import annotations

from pathlib import Path

# Onion, spruce and Allium share this card layout, but spruce launches RetroArch with its own
# per-device --config file, so only Onion and Allium read this one.
RETROARCH_CFG_CANDIDATES = (Path("/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg"),)
