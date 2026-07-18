import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import config, ppsspp_cfg


class LinuxPpssppCfgTests(unittest.TestCase):
    def test_detect_ppsspp_ini_uses_rocknix_default_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_PPSSPP_INI
            try:
                ini_path = Path(temp_dir) / "ppsspp.ini"
                ini_path.write_text("", encoding="utf-8")
                config.DEFAULT_ROCKNIX_PPSSPP_INI = ini_path

                self.assertEqual(config.detect_ppsspp_ini({}), str(ini_path))
            finally:
                config.DEFAULT_ROCKNIX_PPSSPP_INI = original_default

    def test_detect_ppsspp_ini_returns_none_when_not_found(self) -> None:
        original_default = config.DEFAULT_ROCKNIX_PPSSPP_INI
        try:
            config.DEFAULT_ROCKNIX_PPSSPP_INI = Path("/nonexistent/ppsspp.ini")
            self.assertIsNone(config.detect_ppsspp_ini({}))
        finally:
            config.DEFAULT_ROCKNIX_PPSSPP_INI = original_default

    def test_build_patched_ppsspp_ini_sets_host_and_disables_challenge_mode(self) -> None:
        content = (
            "[Achievements]\n"
            "AchievementsEnable = True\n"
            "AchievementsChallengeMode = False\n"
            "AchievementsUserName = misantronic\n"
            "AchievementsHost = \n"
            "[Log]\n"
        )

        result = ppsspp_cfg.build_patched_ppsspp_ini(content, {})

        self.assertIn("AchievementsHost = 127.0.0.1:8080", result)
        self.assertIn("AchievementsChallengeMode = False", result)
        # Untouched keys must survive verbatim.
        self.assertIn("AchievementsUserName = misantronic", result)
        self.assertIn("[Log]", result)

    def test_build_patched_ppsspp_ini_adds_section_when_missing(self) -> None:
        content = "[Log]\nSomeKey = 1\n"

        result = ppsspp_cfg.build_patched_ppsspp_ini(content, {})

        self.assertIn("[Achievements]", result)
        self.assertIn("AchievementsHost = 127.0.0.1:8080", result)
        self.assertIn("[Log]", result)
        self.assertIn("SomeKey = 1", result)

    def test_build_reverted_ppsspp_ini_restores_previous_values(self) -> None:
        content = (
            "[Achievements]\n"
            "AchievementsHost = 127.0.0.1:8080\n"
            "AchievementsChallengeMode = False\n"
            "AchievementsUserName = misantronic\n"
        )

        result = ppsspp_cfg.build_reverted_ppsspp_ini(
            content,
            {
                ppsspp_cfg.HOST_KEY: None,
                ppsspp_cfg.CHALLENGE_MODE_KEY: "True",
            },
        )

        self.assertIn("AchievementsHost = \n", result)
        self.assertIn("AchievementsChallengeMode = True", result)
        self.assertIn("AchievementsUserName = misantronic", result)

    def test_revert_strips_host_when_previous_clobbered_with_proxy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_PPSSPP_INI
            try:
                ini_path = Path(temp_dir) / "ppsspp.ini"
                ini_path.write_text(
                    "[Achievements]\nAchievementsHost = 127.0.0.1:8080\n",
                    encoding="utf-8",
                )
                config.DEFAULT_ROCKNIX_PPSSPP_INI = ini_path

                # A re-patch captured the proxy host itself as the "previous" value.
                clobbered_previous = {ppsspp_cfg.HOST_KEY: "127.0.0.1:8080"}

                ppsspp_cfg.revert_ppsspp_ini({}, clobbered_previous)

                content = ini_path.read_text(encoding="utf-8")
                self.assertIn("AchievementsHost = \n", content)
                self.assertNotIn("127.0.0.1:8080", content)
            finally:
                config.DEFAULT_ROCKNIX_PPSSPP_INI = original_default

    def test_store_ppsspp_previous_preserves_original_on_repatch(self) -> None:
        patch_state: dict = {}

        first = {
            "already_patched": False,
            "path": "/storage/.config/ppsspp/PSP/SYSTEM/ppsspp.ini",
            "previous": {ppsspp_cfg.HOST_KEY: None},
        }
        ppsspp_cfg.store_ppsspp_previous(patch_state, first)

        repatch = {
            "already_patched": True,
            "path": "/storage/.config/ppsspp/PSP/SYSTEM/ppsspp.ini",
            "previous": {ppsspp_cfg.HOST_KEY: "127.0.0.1:8080"},
        }
        ppsspp_cfg.store_ppsspp_previous(patch_state, repatch)

        self.assertIsNone(patch_state["ppsspp_previous"][ppsspp_cfg.HOST_KEY])

    def test_patch_ppsspp_ini_end_to_end_on_disk(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_PPSSPP_INI
            try:
                ini_path = Path(temp_dir) / "ppsspp.ini"
                ini_path.write_text(
                    "[Achievements]\n"
                    "AchievementsUserName = misantronic\n"
                    "AchievementsHost = \n",
                    encoding="utf-8",
                )
                config.DEFAULT_ROCKNIX_PPSSPP_INI = ini_path

                result = ppsspp_cfg.patch_ppsspp_ini({})

                self.assertTrue(result["exists"])
                self.assertTrue(result["changed"])
                content = ini_path.read_text(encoding="utf-8")
                self.assertIn("AchievementsHost = 127.0.0.1:8080", content)
                self.assertIn("AchievementsUserName = misantronic", content)
            finally:
                config.DEFAULT_ROCKNIX_PPSSPP_INI = original_default


if __name__ == "__main__":
    unittest.main()
