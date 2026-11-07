import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from platform_helpers import use_platform

from linux.raofflineproxy import platform
from linux.raofflineproxy.platforms import boot_hooks
from linux.raofflineproxy.platforms import darkos as darkos_platform
from linux.raofflineproxy.platforms import knulli as knulli_platform
from linux.raofflineproxy.platforms import muos as muos_platform
from linux.raofflineproxy.platforms import onion as onion_platform


class LinuxAutostartTests(unittest.TestCase):
    def setUp(self) -> None:
        patcher = patch.object(platform, "save_config", lambda *_a, **_k: None)
        patcher.start()
        self.addCleanup(patcher.stop)
        use_platform(self, knulli_platform.PLATFORM)

    def test_enable_autostart_writes_boot_reconcile_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}

            platform.enable_autostart(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertIn(boot_hooks.AUTOSTART_SENTINEL_START, content)
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
                f"{boot_hooks.AUTOSTART_SENTINEL_START}\nold\n{boot_hooks.AUTOSTART_SENTINEL_END}\n"
                "line2\n",
                encoding="utf-8",
            )

            platform.ensure_boot_hook(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertEqual(content.count(boot_hooks.AUTOSTART_SENTINEL_START), 1)
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
                boot_hooks.AUTOSTART_SENTINEL_START,
                startup_script.read_text(encoding="utf-8"),
            )

    def test_remove_boot_hook_strips_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}
            platform.ensure_boot_hook(config_data)

            platform.remove_boot_hook(config_data)

            self.assertNotIn(
                boot_hooks.AUTOSTART_SENTINEL_START,
                startup_script.read_text(encoding="utf-8"),
            )

    def test_ensure_boot_hook_seeds_key_from_legacy_sentinel(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            startup_script.write_text(
                f"{boot_hooks.AUTOSTART_SENTINEL_START}\nold\n{boot_hooks.AUTOSTART_SENTINEL_END}\n",
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
                f"{boot_hooks.AUTOSTART_SENTINEL_START}\nold\n{boot_hooks.AUTOSTART_SENTINEL_END}\n",
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

            use_platform(self, onion_platform.PLATFORM)
            self.assertEqual(
                onion_platform.PLATFORM.autostart_launcher(config_data),
                "/mnt/SDCARD/App/RAOfflineProxy/autostart-launch.sh",
            )

    def test_onion_ensure_boot_hook_writes_startup_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            use_platform(self, onion_platform.PLATFORM)
            platform.enable_autostart(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertIn("APP_DIR=/mnt/SDCARD/App/RAOfflineProxy", content)
            self.assertIn('sh "$APP_DIR/autostart-launch.sh"', content)
            self.assertTrue(platform.is_autostart_enabled(config_data))

    def test_onion_disable_autostart_keeps_startup_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            use_platform(self, onion_platform.PLATFORM)
            platform.enable_autostart(config_data)
            platform.disable_autostart(config_data)

            self.assertTrue(startup_script.exists())
            self.assertFalse(platform.is_autostart_enabled(config_data))

    def test_autostart_command_uses_muos_launcher(self) -> None:
        self.assertEqual(
            muos_platform.PLATFORM.autostart_launcher({}),
            "/run/muos/storage/application/RAOfflineProxy/launch.sh",
        )

    def test_muos_ensure_boot_hook_writes_init_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            user_init_config = Path(temp_dir) / "user_init"
            user_init_config.write_text("0\n", encoding="utf-8")
            config_data = {"startup_script": str(startup_script)}

            original_user_init_config = muos_platform.USER_INIT_CONFIG
            self.addCleanup(setattr, muos_platform, "USER_INIT_CONFIG", original_user_init_config)
            muos_platform.USER_INIT_CONFIG = user_init_config
            use_platform(self, muos_platform.PLATFORM)
            platform.enable_autostart(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertIn('"/run/muos/storage/application/RAOfflineProxy/launch.sh"', content)
            self.assertIn(
                '"/run/muos/storage/application/RAOfflineProxy/launch.sh" boot-reconcile'
                ' >>"/run/muos/storage/application/RAOfflineProxy/data/boot.log" 2>&1',
                content,
            )
            self.assertEqual(user_init_config.read_text(encoding="utf-8").strip(), "1")
            self.assertTrue(platform.is_autostart_enabled(config_data))
            self.assertTrue(
                startup_script.stat().st_mode & stat.S_IXUSR,
                "init script must be user-executable",
            )

    def test_muos_disable_autostart_keeps_init_script_and_flag(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            user_init_config = Path(temp_dir) / "user_init"
            user_init_config.write_text("0\n", encoding="utf-8")
            config_data = {"startup_script": str(startup_script)}

            original_user_init_config = muos_platform.USER_INIT_CONFIG
            self.addCleanup(setattr, muos_platform, "USER_INIT_CONFIG", original_user_init_config)
            muos_platform.USER_INIT_CONFIG = user_init_config
            use_platform(self, muos_platform.PLATFORM)
            platform.enable_autostart(config_data)
            platform.disable_autostart(config_data)

            self.assertTrue(startup_script.exists())
            self.assertEqual(user_init_config.read_text(encoding="utf-8").strip(), "1")
            self.assertFalse(platform.is_autostart_enabled(config_data))

    def test_darkos_autostart_state_comes_from_systemd(self) -> None:
        # The unit's enabled state is the truth, so a stale config flag written
        # before the systemd switch must not shadow it.
        config_data = {platform.AUTOSTART_CONFIG_KEY: False}

        use_platform(self, darkos_platform.PLATFORM)
        with patch.object(darkos_platform, "systemd_service_enabled", lambda: True):
            self.assertTrue(platform.is_autostart_enabled(config_data))

    def test_darkos_enable_autostart_enables_the_unit(self) -> None:
        config_data = {"startup_script": str(darkos_platform.SERVICE_UNIT)}
        calls: list[str] = []

        use_platform(self, darkos_platform.PLATFORM)
        with patch.object(
            darkos_platform, "systemd_service_enabled", lambda: True
        ), patch.object(
            darkos_platform, "systemd_enable_service", lambda: calls.append("enable")
        ):
            platform.enable_autostart(config_data)

        self.assertEqual(calls, ["enable"])

    def test_darkos_disable_autostart_disables_the_unit(self) -> None:
        config_data = {"startup_script": str(darkos_platform.SERVICE_UNIT)}
        calls: list[str] = []

        use_platform(self, darkos_platform.PLATFORM)
        with patch.object(
            darkos_platform, "systemd_disable_service", lambda: calls.append("disable")
        ):
            platform.disable_autostart(config_data)

        self.assertEqual(calls, ["disable"])
        self.assertFalse(config_data[platform.AUTOSTART_CONFIG_KEY])

    def test_darkos_remove_boot_hook_removes_the_unit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            unit_path = Path(temp_dir) / "raofflineproxy.service"
            unit_path.write_text("[Unit]\n", encoding="utf-8")
            config_data = {"startup_script": str(unit_path)}
            calls: list[str] = []

            use_platform(self, darkos_platform.PLATFORM)
            with patch.object(
                darkos_platform, "systemd_remove_service", lambda: calls.append("remove")
            ):
                platform.remove_boot_hook(config_data)

            self.assertEqual(calls, ["remove"])


if __name__ == "__main__":
    unittest.main()
