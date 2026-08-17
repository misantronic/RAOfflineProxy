import json
import os
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import (
    config,
    log_uploader,
    platform,
    retroarch_cfg,
    service,
    update,
)


class SpruceDetectionTests(unittest.TestCase):
    def test_running_on_onion_is_false_when_spruce_marker_present(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=True):
            with patch.object(Path, "exists", return_value=True):
                self.assertFalse(config.running_on_onion())
                self.assertTrue(config.running_on_onion_or_spruce())

    def test_running_on_onion_still_true_without_spruce_marker(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=False):
            with patch.object(Path, "exists", return_value=True):
                self.assertTrue(config.running_on_onion())
                self.assertTrue(config.running_on_onion_or_spruce())

    def test_spruce_platform_reads_cpuinfo_tokens(self) -> None:
        cases = {
            "Hardware\t: sun8i\n": "A30",
            "Hardware\t: TG5040\n": "SmartPro",
            "CPU part\t: 0xd05\n": "Flip",
            "CPU part\t: 0xd03\n": "AnbernicRG_XX-universal",
        }
        for cpuinfo, expected in cases.items():
            with tempfile.TemporaryDirectory() as temp_dir:
                cpuinfo_path = Path(temp_dir) / "cpuinfo"
                cpuinfo_path.write_text(cpuinfo, encoding="utf-8")
                with patch.object(config, "CPUINFO_PATH", cpuinfo_path):
                    self.assertEqual(config.spruce_platform(), expected)

    def test_spruce_platform_defaults_to_miyoo_mini(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cpuinfo_path = Path(temp_dir) / "cpuinfo"
            cpuinfo_path.write_text("Hardware\t: unknown\n", encoding="utf-8")
            with patch.object(config, "CPUINFO_PATH", cpuinfo_path):
                with patch.object(config, "MAGICX_MARKER", Path(temp_dir) / "absent"):
                    self.assertEqual(config.spruce_platform(), "MiyooMini")

    def test_spruce_retroarch_cfg_points_at_platform_file(self) -> None:
        with patch.object(config, "spruce_platform", return_value="MiyooMini"):
            self.assertEqual(
                config.spruce_retroarch_cfg(),
                Path("/mnt/SDCARD/RetroArch/platform/retroarch-MiyooMini.cfg"),
            )

    def test_detect_retroarch_cfg_prefers_spruce_platform_file(self) -> None:
        original = os.environ.pop("RAOFFLINEPROXY_RETROARCH_CFG", None)
        try:
            with patch.object(config, "running_on_spruce", return_value=True):
                with patch.object(config, "spruce_platform", return_value="A30"):
                    self.assertEqual(
                        config.detect_retroarch_cfg(),
                        "/mnt/SDCARD/RetroArch/platform/retroarch-A30.cfg",
                    )
        finally:
            if original is not None:
                os.environ["RAOFFLINEPROXY_RETROARCH_CFG"] = original


class SpruceCredentialTests(unittest.TestCase):
    SETTINGS = {
        "menuOptions": {
            "RetroAchievements Settings": {
                "modeToggle": {"selected": "Softcore"},
                "username": {"selected": "markadia"},
                "password": {"selected": "hunter2"},
            }
        }
    }

    def _with_settings(self, payload):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        settings = Path(temp_dir.name) / "spruce-config.json"
        settings.write_text(json.dumps(payload), encoding="utf-8")
        return patch.object(config, "SPRUCE_CONFIG_JSON", settings)

    def test_reads_credentials_from_spruce_settings(self) -> None:
        with self._with_settings(self.SETTINGS):
            with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                self.assertEqual(
                    retroarch_cfg.load_spruce_credentials(),
                    {"user": "markadia", "password": "hunter2"},
                )

    def test_ignored_when_not_on_spruce(self) -> None:
        with self._with_settings(self.SETTINGS):
            with patch.object(retroarch_cfg, "running_on_spruce", return_value=False):
                self.assertIsNone(retroarch_cfg.load_spruce_credentials())

    def test_blank_credentials_are_not_returned(self) -> None:
        payload = {
            "menuOptions": {
                "RetroAchievements Settings": {
                    "username": {"selected": ""},
                    "password": {"selected": "hunter2"},
                }
            }
        }
        with self._with_settings(payload):
            with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                self.assertIsNone(retroarch_cfg.load_spruce_credentials())

    def test_missing_or_corrupt_settings_file_is_tolerated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "absent.json"
            with patch.object(config, "SPRUCE_CONFIG_JSON", missing):
                self.assertIsNone(config.spruce_setting("username"))

            corrupt = Path(temp_dir) / "corrupt.json"
            corrupt.write_text("{not json", encoding="utf-8")
            with patch.object(config, "SPRUCE_CONFIG_JSON", corrupt):
                self.assertIsNone(config.spruce_setting("username"))

    def test_cfg_credentials_still_win_over_spruce_settings(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = Path(temp_dir) / "retroarch-MiyooMini.cfg"
            cfg.write_text(
                'cheevos_username = "fromcfg"\ncheevos_token = "tok"\n', encoding="utf-8"
            )
            with self._with_settings(self.SETTINGS):
                with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                    self.assertEqual(
                        retroarch_cfg.load_retroarch_credentials(str(cfg)),
                        {"user": "fromcfg", "token": "tok"},
                    )

    def test_spruce_settings_used_when_cfg_has_no_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            # What the device actually looks like before the first game launch: spruce
            # has not yet copied its credentials into the RetroArch config.
            cfg = Path(temp_dir) / "retroarch-MiyooMini.cfg"
            cfg.write_text(
                'cheevos_username = ""\ncheevos_password = ""\n', encoding="utf-8"
            )
            with self._with_settings(self.SETTINGS):
                with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                    self.assertEqual(
                        retroarch_cfg.load_retroarch_credentials(str(cfg)),
                        {"user": "markadia", "password": "hunter2"},
                    )


class SprucePortTests(unittest.TestCase):
    def test_default_port_avoids_the_sftpgo_port_on_spruce(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=True):
            self.assertEqual(config.default_proxy_port(), config.SPRUCE_DEFAULT_PROXY_PORT)
            self.assertNotEqual(config.default_proxy_port(), 8080)
            self.assertEqual(config.proxy_port({}), config.SPRUCE_DEFAULT_PROXY_PORT)

    def test_default_port_unchanged_elsewhere(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=False):
            self.assertEqual(config.proxy_port({}), 8080)

    def test_explicit_port_still_wins_on_spruce(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=True):
            self.assertEqual(config.proxy_port({"proxy_port": 9000}), 9000)

    def test_port_availability_probe_detects_a_live_listener(self) -> None:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        taken_port = listener.getsockname()[1]

        self.assertFalse(service.port_is_available({"proxy_port": taken_port}))

    def test_port_availability_probe_accepts_a_free_port(self) -> None:
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.bind(("127.0.0.1", 0))
        free_port = probe.getsockname()[1]
        probe.close()

        self.assertTrue(service.port_is_available({"proxy_port": free_port}))

    def test_start_reports_a_taken_port_instead_of_dying_silently(self) -> None:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        taken_port = listener.getsockname()[1]

        with patch.object(service, "discover_service_pids", return_value=[]):
            with self.assertRaises(RuntimeError) as caught:
                service.start_service_process({"proxy_port": taken_port})

        self.assertIn(str(taken_port), str(caught.exception))
        self.assertIn("already in use", str(caught.exception))


class SpruceAutostartTests(unittest.TestCase):
    def test_autostart_unsupported_on_spruce(self) -> None:
        with patch.object(platform, "running_on_spruce", return_value=True):
            self.assertIsNone(platform.resolve_startup_script_path({}))
            self.assertFalse(platform.autostart_supported({}))

    def test_explicit_startup_script_still_wins_on_spruce(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            with patch.object(platform, "running_on_spruce", return_value=True):
                self.assertEqual(
                    platform.resolve_startup_script_path(
                        {"startup_script": str(startup_script)}
                    ),
                    startup_script,
                )


class SpruceUpdateTests(unittest.TestCase):
    ASSETS = [
        {
            "name": "RAOfflineProxy-Onion-v1.11.1-alpha1.zip",
            "browser_download_url": "https://example.test/onion.zip",
        },
        {
            "name": "RAOfflineProxy-Spruce-v1.11.1-alpha1.zip",
            "browser_download_url": "https://example.test/spruce.zip",
        },
    ]

    def test_validate_platform_accepts_spruce(self) -> None:
        self.assertEqual(update.validate_platform("Spruce"), update.PLATFORM_SPRUCE)

    def test_asset_lookup_does_not_cross_match_onion_and_spruce(self) -> None:
        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_SPRUCE, self.ASSETS),
            "https://example.test/spruce.zip",
        )
        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_ONION, self.ASSETS),
            "https://example.test/onion.zip",
        )


class SpruceLogMetadataTests(unittest.TestCase):
    def test_platform_label_and_version_use_spruce_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            version_file = Path(temp_dir) / "spruce"
            version_file.write_text("4.3.3\n", encoding="utf-8")
            with patch.object(config, "SPRUCE_VERSION_FILE", version_file):
                with patch.object(config, "running_on_spruce", return_value=True):
                    with patch.object(config, "spruce_platform", return_value="MiyooMini"):
                        self.assertEqual(log_uploader._platform_label(), "spruce")
                        self.assertEqual(log_uploader._os_version_value("spruce"), "4.3.3")
                        self.assertEqual(log_uploader._device_label("spruce"), "MiyooMini")


if __name__ == "__main__":
    unittest.main()
