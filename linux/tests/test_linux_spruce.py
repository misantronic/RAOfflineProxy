import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import config, log_uploader, platform, update


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
