import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import config, dolphin_cfg


class LinuxDolphinCfgTests(unittest.TestCase):
    def test_detect_dolphin_ini_uses_rocknix_default_config_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR
            try:
                config_dir = Path(temp_dir) / "dolphin-emu"
                config_dir.mkdir()
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = config_dir

                self.assertEqual(
                    config.detect_dolphin_ini({}),
                    str(config_dir / "RetroAchievements.ini"),
                )
            finally:
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = original_default

    def test_detect_dolphin_ini_returns_none_when_not_found(self) -> None:
        original_default = config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR
        try:
            config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = Path("/nonexistent/dolphin-emu")
            self.assertIsNone(config.detect_dolphin_ini({}))
        finally:
            config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = original_default

    def test_build_patched_dolphin_ini_sets_host_and_disables_hardcore(self) -> None:
        content = (
            "[Achievements]\n"
            "Enabled = True\n"
            "HardcoreEnabled = False\n"
            "Username = misantronic\n"
            "ApiToken = secret\n"
        )

        result = dolphin_cfg.build_patched_dolphin_ini(content, {})

        self.assertIn("HostUrl = 127.0.0.1:8080", result)
        self.assertIn("HardcoreEnabled = False", result)
        # Untouched keys must survive verbatim.
        self.assertIn("Username = misantronic", result)
        self.assertIn("ApiToken = secret", result)

    def test_build_patched_dolphin_ini_adds_section_when_missing(self) -> None:
        content = "[General]\nSomeKey = 1\n"

        result = dolphin_cfg.build_patched_dolphin_ini(content, {})

        self.assertIn("[Achievements]", result)
        self.assertIn("HostUrl = 127.0.0.1:8080", result)
        self.assertIn("[General]", result)
        self.assertIn("SomeKey = 1", result)

    def test_build_reverted_dolphin_ini_restores_previous_values(self) -> None:
        content = (
            "[Achievements]\n"
            "HostUrl = 127.0.0.1:8080\n"
            "HardcoreEnabled = False\n"
            "Username = misantronic\n"
        )

        result = dolphin_cfg.build_reverted_dolphin_ini(
            content,
            {
                dolphin_cfg.HOST_KEY: None,
                dolphin_cfg.HARDCORE_KEY: "True",
            },
        )

        # None means the key never existed before patching, so revert removes
        # it entirely rather than leaving a spurious empty HostUrl line.
        self.assertNotIn("HostUrl", result)
        self.assertIn("HardcoreEnabled = True", result)
        self.assertIn("Username = misantronic", result)

    def test_build_reverted_dolphin_ini_restores_previously_set_host(self) -> None:
        content = "[Achievements]\nHostUrl = 127.0.0.1:8080\nHardcoreEnabled = False\n"

        result = dolphin_cfg.build_reverted_dolphin_ini(
            content, {dolphin_cfg.HOST_KEY: "custom.example.com"}
        )

        self.assertIn("HostUrl = custom.example.com", result)

    def test_revert_removes_host_key_when_it_never_existed_before(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR
            try:
                config_dir = Path(temp_dir) / "dolphin-emu"
                config_dir.mkdir()
                ini_path = config_dir / "RetroAchievements.ini"
                # HostUrl was never present, matching Dolphin's real default ini.
                ini_path.write_text(
                    "[Achievements]\nUsername = misantronic\nHostUrl = 127.0.0.1:8080\n",
                    encoding="utf-8",
                )
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = config_dir

                dolphin_cfg.revert_dolphin_ini({}, {dolphin_cfg.HOST_KEY: None})

                content = ini_path.read_text(encoding="utf-8")
                self.assertNotIn("HostUrl", content)
                self.assertIn("Username = misantronic", content)
            finally:
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = original_default

    def test_revert_strips_host_when_previous_clobbered_with_proxy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR
            try:
                config_dir = Path(temp_dir) / "dolphin-emu"
                config_dir.mkdir()
                ini_path = config_dir / "RetroAchievements.ini"
                ini_path.write_text(
                    "[Achievements]\nHostUrl = 127.0.0.1:8080\n", encoding="utf-8"
                )
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = config_dir

                clobbered_previous = {dolphin_cfg.HOST_KEY: "127.0.0.1:8080"}

                dolphin_cfg.revert_dolphin_ini({}, clobbered_previous)

                content = ini_path.read_text(encoding="utf-8")
                self.assertNotIn("HostUrl", content)
                self.assertNotIn("127.0.0.1:8080", content)
            finally:
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = original_default

    def test_store_dolphin_previous_preserves_original_on_repatch(self) -> None:
        patch_state: dict = {}

        first = {
            "already_patched": False,
            "path": "/storage/.config/dolphin-emu/RetroAchievements.ini",
            "previous": {dolphin_cfg.HOST_KEY: None},
        }
        dolphin_cfg.store_dolphin_previous(patch_state, first)

        repatch = {
            "already_patched": True,
            "path": "/storage/.config/dolphin-emu/RetroAchievements.ini",
            "previous": {dolphin_cfg.HOST_KEY: "127.0.0.1:8080"},
        }
        dolphin_cfg.store_dolphin_previous(patch_state, repatch)

        self.assertIsNone(patch_state["dolphin_previous"][dolphin_cfg.HOST_KEY])

    def test_patch_dolphin_ini_end_to_end_on_disk(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_default = config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR
            try:
                config_dir = Path(temp_dir) / "dolphin-emu"
                config_dir.mkdir()
                ini_path = config_dir / "RetroAchievements.ini"
                ini_path.write_text(
                    "[Achievements]\nUsername = misantronic\nHostUrl = \n",
                    encoding="utf-8",
                )
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = config_dir

                result = dolphin_cfg.patch_dolphin_ini({})

                self.assertTrue(result["exists"])
                self.assertTrue(result["changed"])
                content = ini_path.read_text(encoding="utf-8")
                self.assertIn("HostUrl = 127.0.0.1:8080", content)
                self.assertIn("Username = misantronic", content)
            finally:
                config.DEFAULT_ROCKNIX_DOLPHIN_CONFIG_DIR = original_default


if __name__ == "__main__":
    unittest.main()
