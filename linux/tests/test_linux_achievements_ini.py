import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import achievements_ini, duckstation_cfg, flycast_cfg, pcsx2_cfg


class RepatchTests(unittest.TestCase):
    """Hardcore was off; the emulator turned it on while patched. The next
    start finds the proxy host already set and must keep the first capture."""

    def check(self, setting: achievements_ini.HostSetting, hardcore_on: str) -> None:
        ini = Path(tempfile.mkdtemp()) / "emulator.ini"
        original = f"{setting.section}\n{setting.hardcore_key} = {setting.hardcore_off}\n"
        ini.write_text(original, encoding="utf-8")
        config = {"proxy_port": 18080}

        patch_state: dict = {}
        first = achievements_ini.patch(setting, str(ini), config)
        achievements_ini.store_previous(setting, patch_state, first)

        text = ini.read_text(encoding="utf-8")
        hardcore_off = f"{setting.hardcore_key} = {setting.hardcore_off}"
        hardcore_back_on = f"{setting.hardcore_key} = {hardcore_on}"
        ini.write_text(text.replace(hardcore_off, hardcore_back_on), encoding="utf-8")
        second = achievements_ini.patch(setting, str(ini), config)
        self.assertTrue(second["changed"])
        achievements_ini.store_previous(setting, patch_state, second)

        achievements_ini.revert(setting, str(ini), config, patch_state[setting.previous_state_key])
        self.assertEqual(ini.read_text(encoding="utf-8"), original)

    def test_pcsx2(self) -> None:
        self.check(pcsx2_cfg.PCSX2, "true")

    def test_duckstation(self) -> None:
        self.check(duckstation_cfg.DUCKSTATION, "true")

    def test_flycast(self) -> None:
        self.check(flycast_cfg.FLYCAST, "yes")


if __name__ == "__main__":
    unittest.main()
