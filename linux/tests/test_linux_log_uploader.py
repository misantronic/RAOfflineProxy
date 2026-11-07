import tempfile
import unittest
from contextlib import ExitStack, contextmanager
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import log_uploader
from linux.raofflineproxy import platforms
from linux.raofflineproxy.platforms import base as platform_base
from linux.raofflineproxy.platforms import darkos as darkos_platform
from linux.raofflineproxy.platforms import knulli as knulli_platform
from linux.raofflineproxy.platforms import muos as muos_platform
from linux.raofflineproxy.platforms import onion as onion_platform
from linux.raofflineproxy.platforms import rocknix as rocknix_platform


@contextmanager
def patched_config_paths(tmp_path: Path):
    with ExitStack() as stack:
        stack.enter_context(mock.patch.object(log_uploader.config, "LOG_FILE", tmp_path / "service.log"))
        stack.enter_context(mock.patch.object(log_uploader.config, "CONFIG_DIR", tmp_path))
        stack.enter_context(
            mock.patch.object(log_uploader.config, "UPDATE_STATUS_FILE", tmp_path / "update_status.json")
        )
        stack.enter_context(
            mock.patch.object(log_uploader.config, "STATUS_FILE", tmp_path / "service_status.json")
        )
        yield


class LinuxLogUploaderTests(unittest.TestCase):
    def test_read_redacted_log_files_keeps_all_files_separate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            (tmp_path / "service.log.1").write_text("older line one\nolder line two\n", encoding="utf-8")
            (tmp_path / "service.log").write_text("newer line\n", encoding="utf-8")
            (tmp_path / "menu-sdl.log").write_text("run_menu_sdl start python=3.12.8\n", encoding="utf-8")
            (tmp_path / "menu-stdout.log").write_text(
                "=== attempt driver=wayland preload=none ===\n", encoding="utf-8"
            )
            (tmp_path / "update_status.json").write_text('{"status": "up_to_date"}\n', encoding="utf-8")
            (tmp_path / "service_status.json").write_text('{"running": true}\n', encoding="utf-8")

            with patched_config_paths(tmp_path):
                files = log_uploader._read_redacted_log_files()

            self.assertEqual(
                files,
                {
                    "service.log.1": "older line one\nolder line two",
                    "service.log": "newer line",
                    "menu-sdl.log": "run_menu_sdl start python=3.12.8",
                    "menu-stdout.log": "=== attempt driver=wayland preload=none ===",
                    "update_status.json": '{"status": "up_to_date"}',
                    "service_status.json": '{"running": true}',
                },
            )

    def test_read_redacted_log_files_collects_rocknix_menu_stdout_crash_output(self) -> None:
        """A native SDL crash never reaches Python, so the launcher's captured
        stdout is the only record of which driver/preload combination died."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            (tmp_path / "menu-stdout.log").write_text(
                "=== attempt driver=wayland preload=none ===\n"
                "Segmentation fault\n"
                "=== exit_code=139 ===\n",
                encoding="utf-8",
            )

            with patched_config_paths(tmp_path):
                files = log_uploader._read_redacted_log_files()

            self.assertIn("menu-stdout.log", files)
            self.assertIn("exit_code=139", files["menu-stdout.log"])

    def test_read_redacted_log_files_skips_menu_stdout_on_other_platforms(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            (tmp_path / "service.log").write_text("newer line\n", encoding="utf-8")

            with patched_config_paths(tmp_path):
                files = log_uploader._read_redacted_log_files()

            self.assertNotIn("menu-stdout.log", files)

    def test_read_redacted_log_files_redacts_secrets_from_all_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            (tmp_path / "service.log.1").write_text(
                "GET /dorequest.php?r=login2&u=user&p=hunter2\n", encoding="utf-8"
            )
            (tmp_path / "service.log").write_text(
                "GET /dorequest.php?r=patch&u=user&t=secret\n", encoding="utf-8"
            )
            (tmp_path / "menu-sdl.log").write_text("key_logger 305 0x131 BTN_EAST\n", encoding="utf-8")
            (tmp_path / "update_status.json").write_text('{"status": "up_to_date"}\n', encoding="utf-8")
            (tmp_path / "service_status.json").write_text('{"running": true}\n', encoding="utf-8")

            with patched_config_paths(tmp_path):
                files = log_uploader._read_redacted_log_files()

            self.assertNotIn("hunter2", files["service.log.1"])
            self.assertNotIn("secret", files["service.log"])
            self.assertEqual(files["service.log.1"], "GET /dorequest.php?r=login2&u=user&p=<token>")
            self.assertEqual(files["service.log"], "GET /dorequest.php?r=patch&u=user&t=<token>")
            self.assertEqual(files["menu-sdl.log"], "key_logger 305 0x131 BTN_EAST")
            self.assertEqual(files["update_status.json"], '{"status": "up_to_date"}')
            self.assertEqual(files["service_status.json"], '{"running": true}')

    def test_read_redacted_log_files_attaches_redacted_quarantined_storage(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            quarantined = tmp_path / "proxy.json.corrupt-1700000000000"
            quarantined.write_text(
                '{\n'
                '  "api_cache": [\n'
                '    {\n'
                '      "cacheKey": "login2::misantronic",\n'
                '      "responseBody": "{\\"Success\\":true,\\"User\\":\\"misantronic\\",\\"Token\\":\\"abc123\\"}"\n'
                '    },\n'
                '    {\n'
                '      "cacheKey": "auth::invalid_token",\n'
                '      "responseBody": "rawinvalidtoken456"\n'
                '    },\n'
                '    {\n'
                '      "cacheKey": "gameid:abcd",\n'
                '      "responseBody": "{\\"GameID\\":10701}"\n'
                '    }\n'
                '  ],\n'
                '  "pending_awards": [\n'
                '    {\n'
                '      "achievementId": 52114,\n'
                '      "queryString": "/dorequest.php?r=awardachievement&t=secrettoken&h=0"\n'
                '    }\n'
                '  ]\n'
                '}\n',
                encoding="utf-8",
            )

            with patched_config_paths(tmp_path):
                files = log_uploader._read_redacted_log_files()

            attached = files["proxy.json.corrupt-1700000000000"]
            self.assertNotIn("abc123", attached)
            self.assertNotIn("rawinvalidtoken456", attached)
            self.assertNotIn("secrettoken", attached)
            self.assertIn("<redacted>", attached)
            self.assertIn('"cacheKey": "gameid:abcd"', attached)
            self.assertIn('GameID', attached)
            self.assertIn('10701', attached)

    def test_read_redacted_log_files_tolerates_missing_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)

            with patched_config_paths(tmp_path):
                files = log_uploader._read_redacted_log_files()

            self.assertEqual(files, {})

    def test_zip_logs_writes_one_entry_per_file(self) -> None:
        import io
        import zipfile

        zip_bytes = log_uploader._zip_logs({"service.log.1": "old", "service.log": "new"})

        with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
            self.assertEqual(set(archive.namelist()), {"service.log.1", "service.log"})
            self.assertEqual(archive.read("service.log.1").decode("utf-8"), "old")
            self.assertEqual(archive.read("service.log").decode("utf-8"), "new")

    def _detecting(self, *matching):
        stack = ExitStack()
        self.addCleanup(stack.close)
        for platform in platforms.PLATFORMS:
            stack.enter_context(
                mock.patch.object(platform, "detect", return_value=platform in matching)
            )

    def _stub_platform(self, label: str, device: str, version: str | None):
        class Stub(platform_base.Platform):
            support_label = label

            def os_version(self):
                return version

            def device_label(self):
                return device

        stub = Stub()
        patcher = mock.patch.object(platforms, "current", return_value=stub)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _patched_metadata(self, retroarch: bool, ppsspp: bool) -> dict:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.config, "load_config", return_value={}))
            stack.enter_context(
                mock.patch.object(log_uploader.retroarch_cfg, "is_retroarch_patched", return_value=retroarch)
            )
            stack.enter_context(
                mock.patch.object(log_uploader.ppsspp_cfg, "is_ppsspp_patched", return_value=ppsspp)
            )
            return log_uploader.device_metadata()

    def test_support_labels_follow_the_detected_platform(self) -> None:
        # Without its own label a dArkOS device would be filed against another firmware.
        cases = (
            (None, "Linux"),
            (darkos_platform.PLATFORM, "dArkOS"),
            (onion_platform.PLATFORM, "Onion"),
            (rocknix_platform.PLATFORM, "ROCKNIX"),
            (muos_platform.PLATFORM, "muOS"),
            (knulli_platform.PLATFORM, "Knulli"),
        )
        for platform, label in cases:
            with self.subTest(label=label):
                with ExitStack() as stack:
                    for candidate in platforms.PLATFORMS:
                        stack.enter_context(
                            mock.patch.object(candidate, "detect", return_value=candidate is platform)
                        )
                    self.assertEqual(platforms.current().support_label, label)

    def test_os_release_field_prefers_first_matching_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            os_release = Path(tmp_dir) / "os-release"
            os_release.write_text('PRETTY_NAME="Knulli 24.1"\nVERSION_ID=24.1\n', encoding="utf-8")

            with mock.patch.object(platform_base, "OS_RELEASE_PATH", os_release):
                self.assertEqual(platform_base.os_release_field("VERSION", "PRETTY_NAME"), "Knulli 24.1")
                self.assertIsNone(platform_base.os_release_field("VERSION"))

    def test_os_release_field_tolerates_missing_file(self) -> None:
        with mock.patch.object(platform_base, "OS_RELEASE_PATH", Path("/nonexistent/os-release")):
            self.assertIsNone(platform_base.os_release_field("VERSION"))

    def test_the_default_os_version_prefers_the_os_version_key(self) -> None:
        # ROCKNIX writes a real firmware version into a custom OS_VERSION key, not the
        # standard VERSION/VERSION_ID/PRETTY_NAME keys, so it has to be checked first.
        with tempfile.TemporaryDirectory() as tmp_dir:
            os_release = Path(tmp_dir) / "os-release"
            os_release.write_text(
                'OS_VERSION="20250517"\nVERSION="generic"\nPRETTY_NAME="ROCKNIX"\n', encoding="utf-8"
            )
            with mock.patch.object(platform_base, "OS_RELEASE_PATH", os_release):
                self.assertEqual(rocknix_platform.PLATFORM.os_version(), "20250517")

    def test_upload_metadata_omits_os_version_when_undetectable(self) -> None:
        self._stub_platform("Knulli", "Knulli", None)
        self.assertEqual(
            self._patched_metadata(retroarch=True, ppsspp=False),
            {
                "system": "Linux",
                "os": "Knulli",
                "device": "Knulli",
                "app_version": "1.8.0-alpha1",
                "emulator": ["RetroArch"],
            },
        )

    def test_upload_metadata_keeps_os_and_os_version_separate(self) -> None:
        self._stub_platform("Knulli", "Anbernic RG35XX Plus", "42 2025/05/24 18:15:00")
        metadata = self._patched_metadata(retroarch=False, ppsspp=False)
        self.assertEqual(metadata["os"], "Knulli")
        self.assertEqual(metadata["os_version"], "42 2025/05/24 18:15:00")
        self.assertEqual(metadata["device"], "Anbernic RG35XX Plus")

    def test_upload_metadata_omits_emulator_when_not_patched(self) -> None:
        self._stub_platform("Onion", "Onion", "v4.4.0-beta")
        metadata = self._patched_metadata(retroarch=False, ppsspp=False)
        self.assertNotIn("emulator", metadata)
        self.assertEqual(metadata["os"], "Onion")
        self.assertEqual(metadata["os_version"], "v4.4.0-beta")
        self.assertEqual(metadata["device"], "Onion")

    def test_upload_metadata_includes_ppsspp_when_patched(self) -> None:
        self._stub_platform("ROCKNIX", "ROCKNIX", None)
        self.assertEqual(self._patched_metadata(retroarch=False, ppsspp=True)["emulator"], ["PPSSPP"])

    def test_upload_metadata_includes_both_emulators_when_both_patched(self) -> None:
        self._stub_platform("ROCKNIX", "ROCKNIX", None)
        self.assertEqual(
            self._patched_metadata(retroarch=True, ppsspp=True)["emulator"], ["RetroArch", "PPSSPP"]
        )

    def test_read_stripped_trims_whitespace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "model"
            path.write_text("  Miyoo Mini Plus\n", encoding="utf-8")
            self.assertEqual(platform_base.read_stripped(path), "Miyoo Mini Plus")

    def test_read_stripped_returns_none_for_missing_or_empty_file(self) -> None:
        self.assertIsNone(platform_base.read_stripped(Path("/nonexistent")))
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "model"
            path.write_text("\n", encoding="utf-8")
            self.assertIsNone(platform_base.read_stripped(path))

    def test_onion_device_label_prefixes_numeric_model_with_my(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "deviceModel"
            path.write_text("354\n", encoding="utf-8")
            with mock.patch.object(onion_platform, "DEVICE_MODEL_FILE", path):
                self.assertEqual(onion_platform.PLATFORM.device_label(), "MY354")

    def test_onion_device_label_passes_through_non_numeric_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "deviceModel"
            path.write_text("Miyoo Mini Plus\n", encoding="utf-8")
            with mock.patch.object(onion_platform, "DEVICE_MODEL_FILE", path):
                self.assertEqual(onion_platform.PLATFORM.device_label(), "Miyoo Mini Plus")

    def test_onion_device_label_falls_back_when_file_missing(self) -> None:
        with mock.patch.object(onion_platform, "DEVICE_MODEL_FILE", Path("/nonexistent")):
            self.assertEqual(onion_platform.PLATFORM.device_label(), "Onion")

    def _onion_version(self, content: str | None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "version.txt"
        if content is not None:
            path.write_text(content, encoding="utf-8")
        return mock.patch.object(onion_platform, "VERSION_FILE", path)

    def test_onion_os_version_reads_version_file(self) -> None:
        with self._onion_version("v4.4.0-beta-20260120-07505ea5\n"):
            self.assertEqual(onion_platform.PLATFORM.os_version(), "v4.4.0-beta-20260120-07505ea5")

    def test_onion_os_version_returns_none_when_file_missing(self) -> None:
        with self._onion_version(None):
            self.assertIsNone(onion_platform.PLATFORM.os_version())

    def test_onion_version_supported_accepts_matching_build(self) -> None:
        with self._onion_version("v4.4.0-beta-20260120\n"):
            self.assertTrue(onion_platform.PLATFORM.version_supported())
            self.assertIsNone(onion_platform.PLATFORM.unsupported_reason())

    def test_onion_version_supported_rejects_older_build(self) -> None:
        with self._onion_version("v4.3.1-1\n"):
            self.assertFalse(onion_platform.PLATFORM.version_supported())
            self.assertIn("v4.3.1-1", onion_platform.PLATFORM.unsupported_reason())

    def test_onion_version_supported_rejects_missing_file(self) -> None:
        with self._onion_version(None):
            self.assertFalse(onion_platform.PLATFORM.version_supported())
            self.assertIn("unknown", onion_platform.PLATFORM.unsupported_reason())

    def test_other_platforms_have_no_unsupported_reason(self) -> None:
        self.assertIsNone(platform_base.Platform().unsupported_reason())

    def test_knulli_os_version_reads_full_version_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "knulli.version"
            path.write_text("42 2025/05/24 18:15:00\n", encoding="utf-8")
            with mock.patch.object(knulli_platform, "VERSION_FILE", path):
                self.assertEqual(knulli_platform.PLATFORM.os_version(), "42 2025/05/24 18:15:00")

    def test_knulli_os_version_falls_back_to_os_release(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            os_release = Path(tmp_dir) / "os-release"
            os_release.write_text('OS_VERSION="42"\n', encoding="utf-8")
            with ExitStack() as stack:
                stack.enter_context(
                    mock.patch.object(knulli_platform, "VERSION_FILE", Path("/nonexistent"))
                )
                stack.enter_context(mock.patch.object(platform_base, "OS_RELEASE_PATH", os_release))
                self.assertEqual(knulli_platform.PLATFORM.os_version(), "42")

    def _hardware_files(self, stack, devicetree, vendor=None, product=None):
        stack.enter_context(mock.patch.object(platform_base, "DEVICETREE_MODEL_FILE", devicetree))
        stack.enter_context(
            mock.patch.object(platform_base, "DMI_SYS_VENDOR_FILE", vendor or Path("/nonexistent"))
        )
        stack.enter_context(
            mock.patch.object(platform_base, "DMI_PRODUCT_NAME_FILE", product or Path("/nonexistent"))
        )

    def test_hardware_device_label_prefers_devicetree_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir, ExitStack() as stack:
            devicetree = Path(tmp_dir) / "model"
            devicetree.write_text("Anbernic RG35XX Plus\0", encoding="utf-8")
            self._hardware_files(stack, devicetree)
            self.assertEqual(platform_base.hardware_device_label("ROCKNIX"), "Anbernic RG35XX Plus")

    def test_hardware_device_label_falls_back_to_dmi(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir, ExitStack() as stack:
            vendor = Path(tmp_dir) / "sys_vendor"
            product = Path(tmp_dir) / "product_name"
            vendor.write_text("ANBERNIC\n", encoding="utf-8")
            product.write_text("Win600\n", encoding="utf-8")
            self._hardware_files(stack, Path("/nonexistent"), vendor, product)
            self.assertEqual(platform_base.hardware_device_label("ROCKNIX"), "ANBERNIC Win600")

    def test_hardware_device_label_falls_back_to_platform(self) -> None:
        with ExitStack() as stack:
            self._hardware_files(stack, Path("/nonexistent"))
            self.assertEqual(platform_base.hardware_device_label("ROCKNIX"), "ROCKNIX")

    def test_other_platforms_use_the_hardware_probe(self) -> None:
        with mock.patch.object(platform_base, "hardware_device_label", return_value="Anbernic RG35XX Plus"):
            self.assertEqual(knulli_platform.PLATFORM.device_label(), "Anbernic RG35XX Plus")

    def test_muos_device_label_reads_board_name_uppercased(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "name"
            path.write_text("rg40xx-h\n", encoding="utf-8")
            with mock.patch.object(muos_platform, "BOARD_NAME_FILE", path):
                self.assertEqual(muos_platform.PLATFORM.device_label(), "RG40XX-H")

    def test_muos_device_label_falls_back_to_hardware_probe_when_board_name_missing(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(muos_platform, "BOARD_NAME_FILE", Path("/nonexistent")))
            stack.enter_context(
                mock.patch.object(muos_platform, "hardware_device_label", return_value="sun50iw9")
            )
            self.assertEqual(muos_platform.PLATFORM.device_label(), "sun50iw9")


if __name__ == "__main__":
    unittest.main()
