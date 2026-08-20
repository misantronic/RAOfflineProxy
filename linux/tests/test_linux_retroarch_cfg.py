import tempfile
import unittest
from unittest import mock
from pathlib import Path

from linux.raofflineproxy import retroarch_cfg
from linux.raofflineproxy import state


class LinuxRetroarchMissingCfgTests(unittest.TestCase):
    """A Knulli device that has never launched a libretro game has no cfg yet."""

    def test_patch_skips_missing_cfg_when_conf_carries_the_override(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarchcustom.cfg"
            conf_path = Path(temp_dir) / "knulli.conf"
            conf_path.write_text("", encoding="utf-8")

            with mock.patch.object(
                retroarch_cfg, "detect_batocera_conf", return_value=str(conf_path)
            ):
                result = retroarch_cfg.patch_retroarch_cfg(str(cfg_path), {})

            self.assertFalse(result["exists"])
            self.assertFalse(result["changed"])
            self.assertFalse(result["already_patched"])
            self.assertFalse(cfg_path.exists())

    def test_patch_still_raises_without_a_conf_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"

            with mock.patch.object(
                retroarch_cfg, "detect_batocera_conf", return_value=None
            ):
                with self.assertRaises(FileNotFoundError):
                    retroarch_cfg.patch_retroarch_cfg(str(cfg_path), {})

    def test_patch_still_raises_when_the_conf_itself_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            conf_path = Path(temp_dir) / "knulli.conf"

            with mock.patch.object(
                retroarch_cfg, "detect_batocera_conf", return_value=str(conf_path)
            ):
                with self.assertRaises(FileNotFoundError):
                    retroarch_cfg.patch_retroarch_cfg(str(cfg_path), {})

    def test_revert_clears_state_for_a_missing_cfg_instead_of_failing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarchcustom.cfg"
            conf_path = Path(temp_dir) / "knulli.conf"
            conf_path.write_text("", encoding="utf-8")
            state_path = Path(temp_dir) / "retroarch_patch_state.json"
            state_path.write_text(
                '{"cfg_path": "' + str(cfg_path) + '"}\n', encoding="utf-8"
            )

            original_state_file = state.STATE_FILE
            try:
                state.STATE_FILE = state_path
                with mock.patch.object(
                    retroarch_cfg, "detect_batocera_conf", return_value=str(conf_path)
                ):
                    result = retroarch_cfg.revert_retroarch_cfg()

                self.assertFalse(result["exists"])
                self.assertFalse(result["changed"])
                self.assertIsNone(state.load_patch_state())
            finally:
                state.STATE_FILE = original_state_file

    def test_revert_keeps_state_when_a_patched_cfg_went_missing(self) -> None:
        """No conf fallback means the cfg vanished after being patched: keep the state."""
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            state_path = Path(temp_dir) / "retroarch_patch_state.json"
            state_path.write_text(
                '{"cfg_path": "' + str(cfg_path) + '", "previous_host": "example.org"}\n',
                encoding="utf-8",
            )

            original_state_file = state.STATE_FILE
            try:
                state.STATE_FILE = state_path
                with mock.patch.object(
                    retroarch_cfg, "detect_batocera_conf", return_value=None
                ):
                    with self.assertRaises(FileNotFoundError):
                        retroarch_cfg.revert_retroarch_cfg()

                saved = state.load_patch_state()
                assert saved is not None
                self.assertEqual(saved["previous_host"], "example.org")
            finally:
                state.STATE_FILE = original_state_file


class LinuxRetroarchCfgTests(unittest.TestCase):
    def test_patch_already_patched_cfg_repairs_saved_previous_host(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            state_path = Path(temp_dir) / "retroarch_patch_state.json"

            cfg_path.write_text(
                'cheevos_custom_host = "127.0.0.1:8080"\n'
                'cheevos_enable = "true"\n'
                'cheevos_hardcore_mode_enable = "false"\n',
                encoding="utf-8",
            )
            state_path.write_text(
                '{\n'
                '  "cfg_path": "' + str(cfg_path) + '",\n'
                '  "previous_enable": "true",\n'
                '  "previous_host": "127.0.0.1:8080",\n'
                '  "proxy_host": "127.0.0.1:8080"\n'
                '}\n',
                encoding="utf-8",
            )

            original_state_file = state.STATE_FILE
            try:
                state.STATE_FILE = state_path
                result = retroarch_cfg.patch_retroarch_cfg(str(cfg_path), {})
                saved_state = state.load_patch_state()

                self.assertTrue(result["already_patched"])
                self.assertEqual(result["previous_host"], "")
                assert saved_state is not None
                self.assertEqual(saved_state["previous_host"], "")
            finally:
                state.STATE_FILE = original_state_file

    def test_revert_without_patch_state_removes_proxy_host_and_preserves_cheevos_enable(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            state_path = Path(temp_dir) / "retroarch_patch_state.json"

            cfg_path.write_text(
                'cheevos_custom_host = "127.0.0.1:8080"\n'
                'cheevos_enable = "true"\n'
                'cheevos_hardcore_mode_enable = "false"\n',
                encoding="utf-8",
            )

            original_state_file = state.STATE_FILE
            try:
                state.STATE_FILE = state_path
                result = retroarch_cfg.revert_retroarch_cfg(str(cfg_path))

                self.assertTrue(result["changed"])
                content = cfg_path.read_text(encoding="utf-8")
                self.assertIn('cheevos_custom_host = ""', content)
                self.assertIn('cheevos_enable = "true"', content)
                self.assertIn('cheevos_hardcore_mode_enable = "false"', content)
            finally:
                state.STATE_FILE = original_state_file

    def test_revert_with_corrupted_saved_previous_host_clears_proxy_host(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            state_path = Path(temp_dir) / "retroarch_patch_state.json"

            cfg_path.write_text(
                'cheevos_custom_host = "127.0.0.1:8080"\n'
                'cheevos_enable = "true"\n'
                'cheevos_hardcore_mode_enable = "false"\n',
                encoding="utf-8",
            )
            state_path.write_text(
                '{\n'
                '  "cfg_path": "' + str(cfg_path) + '",\n'
                '  "hardcore_was_enabled": false,\n'
                '  "previous_enable": "true",\n'
                '  "previous_host": "127.0.0.1:8080",\n'
                '  "proxy_host": "127.0.0.1:8080"\n'
                '}\n',
                encoding="utf-8",
            )

            original_state_file = state.STATE_FILE
            try:
                state.STATE_FILE = state_path
                result = retroarch_cfg.revert_retroarch_cfg(str(cfg_path))

                self.assertTrue(result["changed"])
                content = cfg_path.read_text(encoding="utf-8")
                self.assertIn('cheevos_custom_host = ""', content)
            finally:
                state.STATE_FILE = original_state_file


if __name__ == "__main__":
    unittest.main()


class UpsertEmptyValueRegressionTests(unittest.TestCase):
    def test_upsert_on_empty_unquoted_value_does_not_eat_next_line(self) -> None:
        content = (
            "menu_battery_level_enable = false\n"
            "cheevos_custom_host = \n"
            "input_libretro_device_p4 = 1\n"
        )
        result = retroarch_cfg._upsert_config_value(
            content, "cheevos_custom_host", "127.0.0.1:8080"
        )
        self.assertIn('cheevos_custom_host = "127.0.0.1:8080"', result)
        self.assertIn("input_libretro_device_p4 = 1", result)
        self.assertNotIn('\n""\n', result)

    def test_sanitizer_removes_orphan_empty_quote_lines(self) -> None:
        content = (
            "cheevos_custom_host = \n"
            '""\n'
            "input_libretro_device_p4 = 1\n"
        )
        result = retroarch_cfg._remove_orphan_boolean_lines(content)
        self.assertNotIn('""', result)
        self.assertIn("input_libretro_device_p4 = 1", result)


class LinuxRocknixMenuCredentialTests(unittest.TestCase):
    """The menu's status line reads credentials through load_retroarch_credentials,
    a separate path from auth.resolve_credentials. Both have to know about
    ROCKNIX's appendconfig or the menu reports LOGIN REQUIRED while the service
    is perfectly able to log in."""

    def test_falls_back_to_rocknix_append_config(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg = root / "retroarch.cfg"
            append_cfg = root / ".retroarch.cfg"
            # What a real ROCKNIX device looks like: a token with no username in
            # retroarch.cfg (unusable on its own), credentials in the appendconfig.
            cfg.write_text('cheevos_token = "orphan"\n', encoding="utf-8")
            append_cfg.write_text(
                'cheevos_username = "misantronic"\ncheevos_password = "hunter2"\n',
                encoding="utf-8",
            )

            with mock.patch.object(
                retroarch_cfg, "detect_rocknix_append_cfg", return_value=str(append_cfg)
            ):
                credentials = retroarch_cfg.load_retroarch_credentials(str(cfg))

            self.assertIsNotNone(credentials)
            self.assertEqual(credentials["user"], "misantronic")

    def test_orphan_token_without_username_is_not_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = Path(temp_dir) / "retroarch.cfg"
            cfg.write_text('cheevos_token = "orphan"\n', encoding="utf-8")

            with mock.patch.object(
                retroarch_cfg, "detect_rocknix_append_cfg", return_value=None
            ):
                self.assertIsNone(retroarch_cfg.load_retroarch_credentials(str(cfg)))
