import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import config, flycast_cfg

EMU_CFG = """[achievements]
Enabled = yes
HardcoreMode = yes
UserName = player

[audio]
Volume = 100
"""


class FlycastCfgTests(unittest.TestCase):
    def setUp(self) -> None:
        self.cfg = Path(tempfile.mkdtemp()) / "emu.cfg"
        self.cfg.write_text(EMU_CFG, encoding="utf-8")
        self.config = {"flycast_cfg": str(self.cfg), "proxy_port": 18080}

    def test_patching_points_it_at_the_proxy_and_turns_hardcore_off(self) -> None:
        result = flycast_cfg.patch_flycast_cfg(self.config)
        self.assertEqual(result["previous"], {"HostUrl": None, "HardcoreMode": "yes"})
        section = self.cfg.read_text(encoding="utf-8").split("[audio]")[0]
        self.assertIn("HostUrl = 127.0.0.1:18080", section)
        self.assertIn("HardcoreMode = no", section)
        self.assertIn("UserName = player", section)

    def test_reverting_after_patching_twice_restores_the_file(self) -> None:
        patch_state: dict = {}
        for _ in range(2):
            flycast_cfg.store_flycast_previous(patch_state, flycast_cfg.patch_flycast_cfg(self.config))
        flycast_cfg.revert_flycast_cfg(self.config, patch_state["flycast_previous"])
        self.assertEqual(self.cfg.read_text(encoding="utf-8"), EMU_CFG)

    def test_no_flycast_means_nothing_happens(self) -> None:
        with mock.patch.object(config, "running_on_windows", return_value=False):
            self.assertIsNone(config.detect_flycast_cfg({}))
            self.assertFalse(flycast_cfg.patch_flycast_cfg({})["exists"])


if __name__ == "__main__":
    unittest.main()
