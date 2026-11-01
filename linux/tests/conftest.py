import sys

import pytest


@pytest.fixture(autouse=True)
def no_real_windows_emulator_configs(request, monkeypatch):
    """On a Windows machine, config.detect_* falls back to that machine's own
    RetroArch, Dolphin, PPSSPP, PCSX2 and DuckStation configs. Tests that aren't about
    Windows must never find (or patch) those, so there they find nothing. The
    Windows tests (test_windows_*) check those lookups themselves."""
    if sys.platform != "win32" or request.node.path.name.startswith("test_windows"):
        return
    from linux.raofflineproxy.windows import paths

    for name in ("dolphin_config_dir", "ppsspp_ini", "pcsx2_ini", "duckstation_ini"):
        monkeypatch.setattr(paths, name, lambda *args, **kwargs: None)
    monkeypatch.setattr(paths, "retroarch_cfg_candidates", lambda *args, **kwargs: ())
