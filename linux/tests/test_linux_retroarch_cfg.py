import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import retroarch_cfg
from linux.raofflineproxy import state


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
