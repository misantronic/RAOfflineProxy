import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import config, platform, update


class RocknixDetectionTests(unittest.TestCase):
    def test_running_on_rocknix_true_when_os_release_matches(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            os_release = Path(temp_dir) / "os-release"
            os_release.write_text('OS_NAME="ROCKNIX"\nOS_VERSION="20260701"\n', encoding="utf-8")
            original = config.OS_RELEASE_PATH
            try:
                config.OS_RELEASE_PATH = os_release
                self.assertTrue(config.running_on_rocknix())
            finally:
                config.OS_RELEASE_PATH = original

    def test_running_on_rocknix_false_when_os_release_differs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            os_release = Path(temp_dir) / "os-release"
            os_release.write_text('OS_NAME="KNULLI"\n', encoding="utf-8")
            original = config.OS_RELEASE_PATH
            try:
                config.OS_RELEASE_PATH = os_release
                self.assertFalse(config.running_on_rocknix())
            finally:
                config.OS_RELEASE_PATH = original

    def test_running_on_rocknix_false_when_os_release_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original = config.OS_RELEASE_PATH
            try:
                config.OS_RELEASE_PATH = Path(temp_dir) / "missing-os-release"
                self.assertFalse(config.running_on_rocknix())
            finally:
                config.OS_RELEASE_PATH = original


class RocknixConfigPathTests(unittest.TestCase):
    def test_detect_retroarch_cfg_uses_rocknix_path(self) -> None:
        original_muos = config.DEFAULT_MUOS_RETROARCH_CFG
        original_userdata_exists = Path.exists
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                config.DEFAULT_MUOS_RETROARCH_CFG = Path(temp_dir) / "missing-muos.cfg"

                def fake_exists(path: Path) -> bool:
                    if str(path) == "/userdata":
                        return False
                    return original_userdata_exists(path)

                with patch.object(Path, "exists", fake_exists):
                    with patch.object(config, "running_on_rocknix", return_value=True):
                        self.assertEqual(
                            config.detect_retroarch_cfg(),
                            str(config.DEFAULT_ROCKNIX_RETROARCH_CFG),
                        )
        finally:
            config.DEFAULT_MUOS_RETROARCH_CFG = original_muos

    def test_resolve_config_dir_uses_rocknix_dir(self) -> None:
        import os

        original_override = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
        original_xdg = os.environ.get("XDG_CONFIG_HOME")
        try:
            os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            os.environ.pop("XDG_CONFIG_HOME", None)
            with patch.object(config, "running_on_rocknix", return_value=True):
                self.assertEqual(
                    config.resolve_config_dir(),
                    config.DEFAULT_ROCKNIX_CONFIG_DIR,
                )
        finally:
            if original_override is None:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            else:
                os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = original_override
            if original_xdg is None:
                os.environ.pop("XDG_CONFIG_HOME", None)
            else:
                os.environ["XDG_CONFIG_HOME"] = original_xdg


class RocknixRomRootTests(unittest.TestCase):
    def test_resolve_rom_root_falls_back_to_rocknix_roms_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "retroarch.cfg"
            rocknix_roms = root / "roms"
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            rocknix_roms.mkdir()
            original_rocknix_root = platform.DEFAULT_ROCKNIX_ROMS_ROOT
            try:
                platform.DEFAULT_ROCKNIX_ROMS_ROOT = rocknix_roms

                resolved = platform.resolve_rom_root({"retroarch_cfg": str(cfg_path)})

                self.assertEqual(resolved, rocknix_roms)
            finally:
                platform.DEFAULT_ROCKNIX_ROMS_ROOT = original_rocknix_root


class RocknixAutostartTests(unittest.TestCase):
    def setUp(self) -> None:
        patcher = patch.object(platform, "save_config", lambda *_a, **_k: None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_autostart_command_uses_rocknix_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_rocknix_startup = platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = startup_script
                self.assertEqual(
                    platform.autostart_command(config_data),
                    ("/storage/.local/share/raofflineproxy/bin/raofflineproxy",),
                )
            finally:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = original_rocknix_startup

    def test_rocknix_ensure_boot_hook_writes_executable_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "autostart" / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_rocknix_startup = platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)

                content = startup_script.read_text(encoding="utf-8")
                self.assertIn("/storage/.local/share/raofflineproxy/bin/raofflineproxy", content)
                self.assertIn("boot-reconcile", content)
                self.assertTrue(platform.is_autostart_enabled(config_data))
                self.assertTrue(
                    startup_script.stat().st_mode & stat.S_IXUSR,
                    "autostart script must be user-executable",
                )
            finally:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = original_rocknix_startup

    def test_rocknix_boot_hook_reinjects_tools_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "autostart" / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_rocknix_startup = platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = startup_script
                platform.ensure_boot_hook(config_data)

                content = startup_script.read_text(encoding="utf-8")
                self.assertIn(str(platform.ROCKNIX_TOOL_SOURCE), content)
                self.assertIn(str(platform.ROCKNIX_MODULES_LAUNCHER), content)
                self.assertIn("cp ", content)
                self.assertIn("boot-reconcile", content)
            finally:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = original_rocknix_startup

    def test_rocknix_remove_boot_hook_deletes_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "autostart" / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_rocknix_startup = platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = startup_script
                platform.ensure_boot_hook(config_data)
                self.assertTrue(startup_script.exists())

                platform.remove_boot_hook(config_data)

                self.assertFalse(startup_script.exists())
            finally:
                platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT = original_rocknix_startup

    def test_resolve_startup_script_path_uses_rocknix_when_detected(self) -> None:
        with patch.object(platform, "running_on_rocknix", return_value=True):
            with patch("pathlib.Path.exists", return_value=False):
                self.assertEqual(
                    platform.resolve_startup_script_path({}),
                    platform.DEFAULT_ROCKNIX_STARTUP_SCRIPT,
                )


class RocknixUpdateTests(unittest.TestCase):
    def test_find_platform_asset_url_matches_rocknix_installer(self) -> None:
        assets = [
            {"name": "RAOfflineProxy-Knulli-v1.5.5-alpha1-Install.sh", "browser_download_url": "knulli-url"},
            {"name": "RAOfflineProxy-Rocknix-v1.5.5-alpha1-Install.sh", "browser_download_url": "rocknix-url"},
        ]

        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_ROCKNIX, assets),
            "rocknix-url",
        )

    def test_validate_platform_accepts_rocknix(self) -> None:
        self.assertEqual(update.validate_platform("rocknix"), update.PLATFORM_ROCKNIX)


if __name__ == "__main__":
    unittest.main()
