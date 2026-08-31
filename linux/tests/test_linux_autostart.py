import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import platform


def _stub_systemctl(*_args, **_kwargs):
    class _Result:
        returncode = 0

    return _Result()


class LinuxAutostartTests(unittest.TestCase):
    def setUp(self) -> None:
        patcher = patch.object(platform, "save_config", lambda *_a, **_k: None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_enable_autostart_writes_boot_reconcile_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}

            platform.enable_autostart(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertIn(platform.AUTOSTART_SENTINEL_START, content)
            self.assertIn("raofflineproxy/bin/raofflineproxy", content)
            self.assertIn("boot-reconcile", content)
            self.assertNotIn("start-proxy", content)
            self.assertTrue(config_data[platform.AUTOSTART_CONFIG_KEY])
            self.assertTrue(platform.is_autostart_enabled(config_data))

    def test_ensure_boot_hook_replaces_existing_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}
            startup_script.write_text(
                "line1\n"
                f"{platform.AUTOSTART_SENTINEL_START}\nold\n{platform.AUTOSTART_SENTINEL_END}\n"
                "line2\n",
                encoding="utf-8",
            )

            platform.ensure_boot_hook(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertEqual(content.count(platform.AUTOSTART_SENTINEL_START), 1)
            self.assertIn("line1", content)
            self.assertIn("line2", content)

    def test_disable_autostart_keeps_hook_clears_key(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}
            platform.enable_autostart(config_data)

            platform.disable_autostart(config_data)

            self.assertFalse(config_data[platform.AUTOSTART_CONFIG_KEY])
            self.assertFalse(platform.is_autostart_enabled(config_data))
            self.assertIn(
                platform.AUTOSTART_SENTINEL_START,
                startup_script.read_text(encoding="utf-8"),
            )

    def test_remove_boot_hook_strips_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}
            platform.ensure_boot_hook(config_data)

            platform.remove_boot_hook(config_data)

            self.assertNotIn(
                platform.AUTOSTART_SENTINEL_START,
                startup_script.read_text(encoding="utf-8"),
            )

    def test_ensure_boot_hook_seeds_key_from_legacy_sentinel(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            startup_script.write_text(
                f"{platform.AUTOSTART_SENTINEL_START}\nold\n{platform.AUTOSTART_SENTINEL_END}\n",
                encoding="utf-8",
            )
            config_data = {"startup_script": str(startup_script)}

            platform.ensure_boot_hook(config_data)

            self.assertTrue(config_data[platform.AUTOSTART_CONFIG_KEY])

    def test_ensure_boot_hook_seeds_key_false_on_fresh_install(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}

            platform.ensure_boot_hook(config_data)

            self.assertFalse(config_data[platform.AUTOSTART_CONFIG_KEY])

    def test_is_autostart_enabled_key_wins_over_legacy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            startup_script.write_text(
                f"{platform.AUTOSTART_SENTINEL_START}\nold\n{platform.AUTOSTART_SENTINEL_END}\n",
                encoding="utf-8",
            )
            config_data = {
                "startup_script": str(startup_script),
                platform.AUTOSTART_CONFIG_KEY: False,
            }

            self.assertFalse(platform.is_autostart_enabled(config_data))

    def test_autostart_command_uses_onion_headless_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_onion_startup = platform.DEFAULT_ONION_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = startup_script
                self.assertEqual(
                    platform.autostart_command(config_data),
                    ("/mnt/SDCARD/App/RAOfflineProxy/autostart-launch.sh",),
                )
            finally:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = original_onion_startup

    def test_onion_ensure_boot_hook_writes_startup_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_onion_startup = platform.DEFAULT_ONION_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)

                content = startup_script.read_text(encoding="utf-8")
                self.assertIn("APP_DIR=/mnt/SDCARD/App/RAOfflineProxy", content)
                self.assertIn('sh "$APP_DIR/autostart-launch.sh"', content)
                self.assertTrue(platform.is_autostart_enabled(config_data))
            finally:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = original_onion_startup

    def test_onion_disable_autostart_keeps_startup_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_onion_startup = platform.DEFAULT_ONION_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)
                platform.disable_autostart(config_data)

                self.assertTrue(startup_script.exists())
                self.assertFalse(platform.is_autostart_enabled(config_data))
            finally:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = original_onion_startup

    def test_autostart_command_uses_muos_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_muos_startup = platform.DEFAULT_MUOS_STARTUP_SCRIPT
            try:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = startup_script
                self.assertEqual(
                    platform.autostart_command(config_data),
                    ("/run/muos/storage/application/RAOfflineProxy/launch.sh",),
                )
            finally:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = original_muos_startup

    def test_muos_ensure_boot_hook_writes_init_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            user_init_config = Path(temp_dir) / "user_init"
            user_init_config.write_text("0\n", encoding="utf-8")
            config_data = {"startup_script": str(startup_script)}

            original_muos_startup = platform.DEFAULT_MUOS_STARTUP_SCRIPT
            original_user_init_config = platform.MUOS_USER_INIT_CONFIG
            try:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = startup_script
                platform.MUOS_USER_INIT_CONFIG = user_init_config
                platform.enable_autostart(config_data)

                content = startup_script.read_text(encoding="utf-8")
                self.assertIn('"/run/muos/storage/application/RAOfflineProxy/launch.sh"', content)
                self.assertIn('boot-reconcile >/dev/null 2>&1 || true', content)
                self.assertEqual(user_init_config.read_text(encoding="utf-8").strip(), "1")
                self.assertTrue(platform.is_autostart_enabled(config_data))
                self.assertTrue(
                    startup_script.stat().st_mode & stat.S_IXUSR,
                    "init script must be user-executable",
                )
            finally:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = original_muos_startup
                platform.MUOS_USER_INIT_CONFIG = original_user_init_config

    def test_muos_disable_autostart_keeps_init_script_and_flag(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            user_init_config = Path(temp_dir) / "user_init"
            user_init_config.write_text("0\n", encoding="utf-8")
            config_data = {"startup_script": str(startup_script)}

            original_muos_startup = platform.DEFAULT_MUOS_STARTUP_SCRIPT
            original_user_init_config = platform.MUOS_USER_INIT_CONFIG
            try:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = startup_script
                platform.MUOS_USER_INIT_CONFIG = user_init_config
                platform.enable_autostart(config_data)
                platform.disable_autostart(config_data)

                self.assertTrue(startup_script.exists())
                self.assertEqual(user_init_config.read_text(encoding="utf-8").strip(), "1")
                self.assertFalse(platform.is_autostart_enabled(config_data))
            finally:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = original_muos_startup
                platform.MUOS_USER_INIT_CONFIG = original_user_init_config

    def test_autostart_command_uses_darkos_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            unit_path = Path(temp_dir) / "raofflineproxy.service"
            config_data = {"startup_script": str(unit_path)}

            original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
            try:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
                self.assertEqual(
                    platform.autostart_command(config_data),
                    ("/home/ark/raofflineproxy/bin/raofflineproxy",),
                )
            finally:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_ensure_boot_hook_writes_unit_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            unit_path = Path(temp_dir) / "raofflineproxy.service"
            config_data = {"startup_script": str(unit_path)}

            original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
            try:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
                with patch.object(platform.subprocess, "run", _stub_systemctl):
                    platform.enable_autostart(config_data)

                content = unit_path.read_text(encoding="utf-8")
                self.assertIn("[Unit]", content)
                self.assertIn(
                    "ExecStart=/usr/bin/python3 -m raofflineproxy.main run-service",
                    content,
                )
                self.assertIn("WantedBy=multi-user.target", content)
                self.assertTrue(platform.is_autostart_enabled(config_data))
            finally:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_ensure_boot_hook_survives_missing_systemctl(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            unit_path = Path(temp_dir) / "raofflineproxy.service"
            config_data = {"startup_script": str(unit_path)}

            original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
            try:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
                platform.enable_autostart(config_data)

                self.assertTrue(unit_path.exists())
                self.assertTrue(platform.is_autostart_enabled(config_data))
            finally:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_ensure_boot_hook_survives_permission_error(self) -> None:
        unit_path = Path("/nonexistent-dir/raofflineproxy.service")
        config_data = {"startup_script": str(unit_path)}

        original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
        try:
            platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
            platform.enable_autostart(config_data)

            self.assertTrue(platform.is_autostart_enabled(config_data))
        finally:
            platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_disable_autostart_keeps_unit_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            unit_path = Path(temp_dir) / "raofflineproxy.service"
            config_data = {"startup_script": str(unit_path)}

            original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
            try:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
                platform.enable_autostart(config_data)
                platform.disable_autostart(config_data)

                self.assertTrue(unit_path.exists())
                self.assertFalse(platform.is_autostart_enabled(config_data))
            finally:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_ensure_boot_hook_falls_back_to_sudo_tee(self) -> None:
        # Simulates the real device case: direct write fails (unprivileged,
        # /etc/systemd/system not writable), so it must fall back to
        # `sudo -n tee`. dArkOS's own ES Tools scripts rely on the same
        # passwordless-sudo assumption for the device user.
        unit_path = Path("/nonexistent-dir/raofflineproxy.service")
        config_data = {"startup_script": str(unit_path)}
        written: dict[str, str] = {}

        def fake_run(args, **kwargs):
            class _Result:
                returncode = 0

            if args[:2] == ["sudo", "-n"] and args[2] == "tee":
                written["content"] = kwargs.get("input", "")
            return _Result()

        original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
        try:
            platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
            with patch.object(platform.subprocess, "run", fake_run):
                platform.enable_autostart(config_data)

            self.assertIn("ExecStart=", written.get("content", ""))
            self.assertTrue(platform.is_autostart_enabled(config_data))
        finally:
            platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_ensure_boot_hook_survives_sudo_denied(self) -> None:
        unit_path = Path("/nonexistent-dir/raofflineproxy.service")
        config_data = {"startup_script": str(unit_path)}

        def fake_run(args, **kwargs):
            class _Result:
                returncode = 1

            return _Result()

        original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
        try:
            platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
            with patch.object(platform.subprocess, "run", fake_run):
                platform.enable_autostart(config_data)

            self.assertFalse(unit_path.exists())
            self.assertTrue(platform.is_autostart_enabled(config_data))
        finally:
            platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit

    def test_darkos_remove_boot_hook_deletes_unit_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            unit_path = Path(temp_dir) / "raofflineproxy.service"
            config_data = {"startup_script": str(unit_path)}

            original_unit = platform.DEFAULT_DARKOS_AUTOSTART_UNIT
            try:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = unit_path
                platform.enable_autostart(config_data)

                platform.remove_boot_hook(config_data)

                self.assertFalse(unit_path.exists())
            finally:
                platform.DEFAULT_DARKOS_AUTOSTART_UNIT = original_unit


if __name__ == "__main__":
    unittest.main()
