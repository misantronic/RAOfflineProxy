import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import config, pcsx2_cfg
from linux.raofflineproxy.dolphin_cfg import extract_achievements_value

# The [Achievements] section as PCSX2 writes it: no Host until one is set.
PCSX2_INI = """[UI]
SettingsVersion = 1

[Achievements]
Enabled = true
ChallengeMode = true
EncoreMode = false
Username = player
Token = secret

[Folders]
Bios = bios
"""


class Pcsx2CfgTests(unittest.TestCase):
    def setUp(self) -> None:
        self.folder = Path(tempfile.mkdtemp())
        self.ini = self.folder / "PCSX2.ini"
        self.ini.write_text(PCSX2_INI, encoding="utf-8")
        self.config = {"pcsx2_ini": str(self.ini), "proxy_port": 18080}

    def values(self) -> dict:
        text = self.ini.read_text(encoding="utf-8")
        return {
            key: extract_achievements_value(text, key)
            for key in ("Host", "ChallengeMode", "Enabled", "Token")
        }

    def test_patching_points_it_at_the_proxy_and_turns_hardcore_off(self) -> None:
        result = pcsx2_cfg.patch_pcsx2_ini(self.config)
        self.assertTrue(result["changed"])
        self.assertEqual(result["previous"], {"Host": None, "ChallengeMode": "true"})
        values = self.values()
        self.assertEqual(values["Host"], "127.0.0.1:18080")
        self.assertEqual(values["ChallengeMode"], "false")
        # Everything else in the section and file is untouched.
        self.assertEqual(values["Token"], "secret")
        self.assertIn("[Folders]\nBios = bios", self.ini.read_text(encoding="utf-8"))

    def test_reverting_restores_the_original_file(self) -> None:
        patch_state: dict = {}
        pcsx2_cfg.store_pcsx2_previous(patch_state, pcsx2_cfg.patch_pcsx2_ini(self.config))
        pcsx2_cfg.revert_pcsx2_ini(self.config, patch_state["pcsx2_previous"])
        self.assertEqual(self.ini.read_text(encoding="utf-8"), PCSX2_INI)

    def test_reverting_leaves_out_a_challenge_mode_that_wasnt_there(self) -> None:
        """PCSX2 defaults to hardcore on when ChallengeMode is missing, so
        restoring "false" would change the user's setting."""
        original = PCSX2_INI.replace("ChallengeMode = true\n", "")
        self.ini.write_text(original, encoding="utf-8")
        patch_state: dict = {}
        pcsx2_cfg.store_pcsx2_previous(patch_state, pcsx2_cfg.patch_pcsx2_ini(self.config))
        self.assertEqual(self.values()["ChallengeMode"], "false")
        pcsx2_cfg.revert_pcsx2_ini(self.config, patch_state["pcsx2_previous"])
        self.assertEqual(self.ini.read_text(encoding="utf-8"), original)

    def test_patching_twice_keeps_the_original_values(self) -> None:
        patch_state: dict = {}
        pcsx2_cfg.store_pcsx2_previous(patch_state, pcsx2_cfg.patch_pcsx2_ini(self.config))
        pcsx2_cfg.store_pcsx2_previous(patch_state, pcsx2_cfg.patch_pcsx2_ini(self.config))
        self.assertEqual(patch_state["pcsx2_previous"], {"Host": None, "ChallengeMode": "true"})
        pcsx2_cfg.revert_pcsx2_ini(self.config, patch_state["pcsx2_previous"])
        self.assertEqual(self.ini.read_text(encoding="utf-8"), PCSX2_INI)

    def test_a_previous_value_that_is_the_proxy_is_not_restored(self) -> None:
        pcsx2_cfg.patch_pcsx2_ini(self.config)
        pcsx2_cfg.revert_pcsx2_ini(self.config, {"Host": "127.0.0.1:18080", "ChallengeMode": "false"})
        self.assertIsNone(self.values()["Host"])

    def test_no_pcsx2_means_nothing_happens(self) -> None:
        # Off Windows, as on the handhelds: on a Windows test machine this would
        # otherwise find (and patch) that machine's own PCSX2.ini.
        with mock.patch.object(config, "running_on_windows", return_value=False):
            self.assertIsNone(config.detect_pcsx2_ini({}))
            self.assertFalse(pcsx2_cfg.patch_pcsx2_ini({})["exists"])
        missing = {"pcsx2_ini": str(self.folder / "nowhere" / "PCSX2.ini")}
        self.assertFalse(pcsx2_cfg.patch_pcsx2_ini(missing)["exists"])
        self.assertFalse(pcsx2_cfg.revert_pcsx2_ini(missing)["exists"])


if __name__ == "__main__":
    unittest.main()
