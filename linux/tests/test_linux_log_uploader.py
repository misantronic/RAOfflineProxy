import tempfile
import unittest
from contextlib import ExitStack, contextmanager
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import log_uploader


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
                    "update_status.json": '{"status": "up_to_date"}',
                    "service_status.json": '{"running": true}',
                },
            )

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

    def test_platform_label_falls_back_to_knulli(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader.config, "running_on_onion", return_value=False))
            stack.enter_context(mock.patch.object(log_uploader.config, "running_on_rocknix", return_value=False))
            stack.enter_context(mock.patch.object(log_uploader, "MUOS_MARKER_PATH", Path("/nonexistent")))
            self.assertEqual(log_uploader._platform_label(), "Knulli")

    def test_platform_label_detects_onion_first(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader.config, "running_on_onion", return_value=True))
            stack.enter_context(mock.patch.object(log_uploader.config, "running_on_rocknix", return_value=True))
            self.assertEqual(log_uploader._platform_label(), "Onion")

    def test_platform_label_detects_rocknix(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader.config, "running_on_onion", return_value=False))
            stack.enter_context(mock.patch.object(log_uploader.config, "running_on_rocknix", return_value=True))
            stack.enter_context(mock.patch.object(log_uploader, "MUOS_MARKER_PATH", Path("/nonexistent")))
            self.assertEqual(log_uploader._platform_label(), "ROCKNIX")

    def test_os_release_field_prefers_first_matching_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            os_release = Path(tmp_dir) / "os-release"
            os_release.write_text('PRETTY_NAME="Knulli 24.1"\nVERSION_ID=24.1\n', encoding="utf-8")

            with mock.patch.object(log_uploader.config, "OS_RELEASE_PATH", os_release):
                self.assertEqual(log_uploader._os_release_field("VERSION", "PRETTY_NAME"), "Knulli 24.1")
                self.assertIsNone(log_uploader._os_release_field("VERSION"))

    def test_os_release_field_tolerates_missing_file(self) -> None:
        with mock.patch.object(log_uploader.config, "OS_RELEASE_PATH", Path("/nonexistent/os-release")):
            self.assertIsNone(log_uploader._os_release_field("VERSION"))

    def test_upload_metadata_omits_os_version_when_undetectable(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_platform_label", return_value="Knulli"))
            stack.enter_context(mock.patch.object(log_uploader, "_device_label", return_value="Knulli"))
            stack.enter_context(mock.patch.object(log_uploader, "_os_version_value", return_value=None))
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.state, "load_patch_state", return_value={"cfg_path": "x"}))

            metadata = log_uploader._upload_metadata()

            self.assertEqual(
                metadata,
                {
                    "system": "Linux",
                    "os": "Knulli",
                    "device": "Knulli",
                    "app_version": "1.8.0-alpha1",
                    "emulator": ["RetroArch"],
                },
            )

    def test_upload_metadata_prefers_os_version_key_over_generic_ones(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_platform_label", return_value="ROCKNIX"))
            stack.enter_context(mock.patch.object(log_uploader, "_device_label", return_value="ROCKNIX"))
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.state, "load_patch_state", return_value=None))
            os_release = mock.patch.object(
                log_uploader,
                "_os_release_field",
                side_effect=lambda *keys: {"OS_VERSION": "20250517"}.get(keys[0]),
            )
            stack.enter_context(os_release)

            metadata = log_uploader._upload_metadata()

            self.assertEqual(metadata["os"], "ROCKNIX")
            self.assertEqual(metadata["os_version"], "20250517")

    def test_upload_metadata_omits_emulator_when_not_patched(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_platform_label", return_value="Onion"))
            stack.enter_context(mock.patch.object(log_uploader, "_onion_os_version", return_value="v4.4.0-beta"))
            stack.enter_context(mock.patch.object(log_uploader, "_device_label", return_value="Onion"))
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.state, "load_patch_state", return_value=None))

            metadata = log_uploader._upload_metadata()

            self.assertNotIn("emulator", metadata)
            self.assertEqual(metadata["os"], "Onion")
            self.assertEqual(metadata["os_version"], "v4.4.0-beta")
            self.assertEqual(metadata["device"], "Onion")

    def test_read_stripped_trims_whitespace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "value"
            path.write_text("  Miyoo Mini Plus  \n", encoding="utf-8")
            self.assertEqual(log_uploader._read_stripped(path), "Miyoo Mini Plus")

    def test_read_stripped_returns_none_for_missing_or_empty_file(self) -> None:
        self.assertIsNone(log_uploader._read_stripped(Path("/nonexistent")))

        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "empty"
            path.write_text("   \n", encoding="utf-8")
            self.assertIsNone(log_uploader._read_stripped(path))

    def test_onion_device_label_prefixes_numeric_model_with_my(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "deviceModel"
            path.write_text("354\n", encoding="utf-8")

            with mock.patch.object(log_uploader, "ONION_DEVICE_MODEL_FILE", path):
                self.assertEqual(log_uploader._onion_device_label(), "MY354")

    def test_onion_device_label_passes_through_non_numeric_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "deviceModel"
            path.write_text("Miyoo Mini Plus\n", encoding="utf-8")

            with mock.patch.object(log_uploader, "ONION_DEVICE_MODEL_FILE", path):
                self.assertEqual(log_uploader._onion_device_label(), "Miyoo Mini Plus")

    def test_onion_device_label_falls_back_when_file_missing(self) -> None:
        with mock.patch.object(log_uploader, "ONION_DEVICE_MODEL_FILE", Path("/nonexistent")):
            self.assertEqual(log_uploader._onion_device_label(), "Onion")

    def test_onion_os_version_reads_version_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "version.txt"
            path.write_text("v4.4.0-beta-20260120-07505ea5\n", encoding="utf-8")

            with mock.patch.object(log_uploader, "ONION_VERSION_FILE", path):
                self.assertEqual(log_uploader._onion_os_version(), "v4.4.0-beta-20260120-07505ea5")

    def test_onion_os_version_returns_none_when_file_missing(self) -> None:
        with mock.patch.object(log_uploader, "ONION_VERSION_FILE", Path("/nonexistent")):
            self.assertIsNone(log_uploader._onion_os_version())

    def test_knulli_os_version_reads_full_version_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "knulli.version"
            path.write_text("42 2025/05/24 18:15:00\n", encoding="utf-8")

            with mock.patch.object(log_uploader, "KNULLI_VERSION_FILE", path):
                self.assertEqual(log_uploader._knulli_os_version(), "42 2025/05/24 18:15:00")

    def test_knulli_os_version_returns_none_when_file_missing(self) -> None:
        with mock.patch.object(log_uploader, "KNULLI_VERSION_FILE", Path("/nonexistent")):
            self.assertIsNone(log_uploader._knulli_os_version())

    def test_os_version_value_prefers_knulli_dedicated_file_over_os_release(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_knulli_os_version", return_value="42 2025/05/24 18:15:00"))
            stack.enter_context(mock.patch.object(log_uploader, "_os_release_field", return_value="42"))
            self.assertEqual(log_uploader._os_version_value("Knulli"), "42 2025/05/24 18:15:00")

    def test_os_version_value_falls_back_to_os_release_for_knulli(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_knulli_os_version", return_value=None))
            stack.enter_context(mock.patch.object(log_uploader, "_os_release_field", return_value="42"))
            self.assertEqual(log_uploader._os_version_value("Knulli"), "42")

    def test_upload_metadata_keeps_os_and_os_version_separate(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_platform_label", return_value="Knulli"))
            stack.enter_context(mock.patch.object(log_uploader, "_device_label", return_value="Anbernic RG35XX Plus"))
            stack.enter_context(mock.patch.object(log_uploader, "_os_version_value", return_value="42 2025/05/24 18:15:00"))
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.state, "load_patch_state", return_value=None))

            metadata = log_uploader._upload_metadata()

            self.assertEqual(metadata["os"], "Knulli")
            self.assertEqual(metadata["os_version"], "42 2025/05/24 18:15:00")

    def test_hardware_device_label_prefers_devicetree_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            devicetree = Path(tmp_dir) / "model"
            devicetree.write_text("Anbernic RG35XX Plus\0", encoding="utf-8")

            with ExitStack() as stack:
                stack.enter_context(mock.patch.object(log_uploader, "DEVICETREE_MODEL_FILE", devicetree))
                self.assertEqual(log_uploader._hardware_device_label("ROCKNIX"), "Anbernic RG35XX Plus")

    def test_hardware_device_label_falls_back_to_dmi(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            vendor = Path(tmp_dir) / "sys_vendor"
            product = Path(tmp_dir) / "product_name"
            vendor.write_text("ANBERNIC\n", encoding="utf-8")
            product.write_text("Win600\n", encoding="utf-8")

            with ExitStack() as stack:
                stack.enter_context(mock.patch.object(log_uploader, "DEVICETREE_MODEL_FILE", Path("/nonexistent")))
                stack.enter_context(mock.patch.object(log_uploader, "DMI_SYS_VENDOR_FILE", vendor))
                stack.enter_context(mock.patch.object(log_uploader, "DMI_PRODUCT_NAME_FILE", product))
                self.assertEqual(log_uploader._hardware_device_label("ROCKNIX"), "ANBERNIC Win600")

    def test_hardware_device_label_falls_back_to_platform(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "DEVICETREE_MODEL_FILE", Path("/nonexistent")))
            stack.enter_context(mock.patch.object(log_uploader, "DMI_SYS_VENDOR_FILE", Path("/nonexistent")))
            stack.enter_context(mock.patch.object(log_uploader, "DMI_PRODUCT_NAME_FILE", Path("/nonexistent")))
            self.assertEqual(log_uploader._hardware_device_label("ROCKNIX"), "ROCKNIX")

    def test_device_label_routes_onion_to_its_own_file(self) -> None:
        with mock.patch.object(log_uploader, "_onion_device_label", return_value="Miyoo Mini Flip"):
            self.assertEqual(log_uploader._device_label("Onion"), "Miyoo Mini Flip")

    def test_device_label_routes_others_to_hardware_probe(self) -> None:
        with mock.patch.object(log_uploader, "_hardware_device_label", return_value="Anbernic RG35XX Plus"):
            self.assertEqual(log_uploader._device_label("Knulli"), "Anbernic RG35XX Plus")


if __name__ == "__main__":
    unittest.main()
