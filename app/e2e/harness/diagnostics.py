from __future__ import annotations

import re
from pathlib import Path

from app.e2e.harness.device import AndroidDevice
from app.e2e.harness.ui import Ui


def artifact_dir(root: Path, nodeid: str) -> Path:
    return root / re.sub(r"[^A-Za-z0-9_.-]+", "_", nodeid)


def capture(device: AndroidDevice, ui: Ui, target: Path) -> None:
    """Best effort: a failing capture must never mask the test failure itself."""
    target.mkdir(parents=True, exist_ok=True)
    steps = {
        "screenshot.png": device.screenshot,
        "window.xml": lambda: ui.dump_xml() or ui.last_dump_output,
        "foreground.txt": device.foreground,
        "logcat.txt": device.logcat,
    }
    for name, produce in steps.items():
        try:
            content = produce()
        except Exception as error:
            content = "capture failed: %r" % error
        path = target / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content or "", encoding="utf-8")
