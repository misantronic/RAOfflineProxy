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


if __name__ == "__main__":
    unittest.main()
