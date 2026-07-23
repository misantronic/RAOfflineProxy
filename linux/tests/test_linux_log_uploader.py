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

    def test_upload_metadata_includes_emulator_when_patched(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_platform_label", return_value="Knulli"))
            stack.enter_context(mock.patch.object(log_uploader, "_os_release_field", return_value=None))
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.state, "load_patch_state", return_value={"cfg_path": "x"}))

            metadata = log_uploader._upload_metadata()

            self.assertEqual(
                metadata,
                {
                    "system": "Linux",
                    "device": "Knulli",
                    "os_version": "Knulli",
                    "app_version": "1.8.0-alpha1",
                    "emulator": ["RetroArch"],
                },
            )

    def test_upload_metadata_omits_emulator_when_not_patched(self) -> None:
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(log_uploader, "_platform_label", return_value="Onion"))
            stack.enter_context(mock.patch.object(log_uploader, "_os_release_field", return_value="Onion v4.3"))
            stack.enter_context(mock.patch.object(log_uploader.config, "APP_VERSION", "1.8.0-alpha1"))
            stack.enter_context(mock.patch.object(log_uploader.state, "load_patch_state", return_value=None))

            metadata = log_uploader._upload_metadata()

            self.assertNotIn("emulator", metadata)
            self.assertEqual(metadata["os_version"], "Onion v4.3")


if __name__ == "__main__":
    unittest.main()
