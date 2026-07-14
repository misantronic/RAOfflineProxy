import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import es_export, storage


class LinuxEsExportTests(unittest.TestCase):
    def _read_ids(self, ids_file: Path) -> list[str]:
        if not ids_file.exists():
            return []
        return [line for line in ids_file.read_text().splitlines() if line]

    def test_cache_mutations_keep_cached_ids_file_in_sync(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            ids_file = root / "cached_game_ids.txt"
            with (
                mock.patch.object(es_export, "CACHED_IDS_FILE", ids_file),
                mock.patch.object(es_export, "CONFIG_DIR", root),
            ):
                store = storage.Storage(database_path=root / "test.sqlite3")
                try:
                    store.upsert_cache("patch:10701:misantronic", "patch")
                    self.assertEqual(self._read_ids(ids_file), ["10701"])

                    store.upsert_cache(
                        "achievementsets:testhash:misantronic",
                        '{"GameId":515}',
                    )
                    self.assertEqual(self._read_ids(ids_file), ["515", "10701"])

                    store.upsert_cache("ua::last", "RetroArch/1.20.0")
                    self.assertEqual(self._read_ids(ids_file), ["515", "10701"])

                    store.delete_cache_by_prefix("patch:10701:")
                    self.assertEqual(self._read_ids(ids_file), ["515"])

                    store.clear_cache()
                    self.assertEqual(self._read_ids(ids_file), [])
                finally:
                    store.close()

    def test_export_skips_rewrite_when_content_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            ids_file = root / "cached_game_ids.txt"
            with (
                mock.patch.object(es_export, "CACHED_IDS_FILE", ids_file),
                mock.patch.object(es_export, "CONFIG_DIR", root),
            ):
                store = storage.Storage(database_path=root / "test.sqlite3")
                try:
                    store.upsert_cache("patch:10701:misantronic", "patch")
                    first_stat = ids_file.stat()

                    store.upsert_cache("patch:10701:misantronic", "patch-updated")
                    self.assertEqual(ids_file.stat().st_mtime_ns, first_stat.st_mtime_ns)
                    self.assertEqual(self._read_ids(ids_file), ["10701"])
                finally:
                    store.close()


if __name__ == "__main__":
    unittest.main()
