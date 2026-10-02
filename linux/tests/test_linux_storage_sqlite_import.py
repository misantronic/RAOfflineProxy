import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import storage

AWARD = {
    "achievementId": 4242,
    "queryString": "r=awardachievement&a=4242",
    "requestBody": "body",
    "userAgent": "RetroArch/1.21",
    "queuedAt": 1_700_000_000_000,
    "retryCount": 2,
    "lastError": "timeout",
    "status": "pending",
    "payloadHash": "ph",
    "prevHash": "prev",
    "signature": "sig",
    "signedAt": 1_700_000_000_500,
}


class JsonStoreImportTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self._temp_dir.name)
        self.db_path = self.root / "test.sqlite3"

    def tearDown(self) -> None:
        self._temp_dir.cleanup()

    def json_store(self) -> storage.Storage:
        with mock.patch.object(storage, "sqlite3", None):
            return storage.Storage(database_path=self.db_path)

    def test_a_json_store_is_taken_over_by_sqlite(self) -> None:
        old = self.json_store()
        old.upsert_cache("patch:1:user", '{"a":1}', source_rom_path="/gb/tetris.gb")
        old.upsert_cache("unlocks:1:user:0", '{"b":2}')
        old.upsert_pending_award(dict(AWARD))
        first_cached_at = old.get_cache("patch:1:user")["firstCachedAt"]
        old.close()

        new = storage.Storage(database_path=self.db_path)
        try:
            self.assertEqual("sqlite", new.backend)
            patch = new.get_cache("patch:1:user")
            self.assertEqual('{"a":1}', patch["responseBody"])
            self.assertEqual("/gb/tetris.gb", patch["sourceRomPath"])
            self.assertEqual(first_cached_at, patch["firstCachedAt"])
            self.assertEqual('{"b":2}', new.get_cache("unlocks:1:user:0")["responseBody"])
            awards = new.get_pending_awards()
            self.assertEqual(1, len(awards))
            for key, value in AWARD.items():
                self.assertEqual(value, awards[0][key], key)
        finally:
            new.close()

    def test_the_json_file_is_kept_as_a_backup_and_imported_once(self) -> None:
        old = self.json_store()
        old.upsert_cache("patch:1:user", "v1")
        old.close()
        json_path = self.db_path.with_suffix(".json")

        storage.Storage(database_path=self.db_path).close()

        self.assertFalse(json_path.exists())
        backups = list(self.root.glob("test.json.migrated-*"))
        self.assertEqual(1, len(backups))
        self.assertEqual("v1", json.loads(backups[0].read_text())["api_cache"][0]["responseBody"])

        again = storage.Storage(database_path=self.db_path)
        try:
            self.assertEqual(1, again.count_cache_by_prefix("patch:"))
        finally:
            again.close()
        self.assertEqual(1, len(list(self.root.glob("test.json.migrated-*"))))

    def test_rows_already_in_sqlite_win_over_the_old_file(self) -> None:
        old = self.json_store()
        old.upsert_cache("patch:1:user", "from-json")
        old.upsert_cache("patch:2:user", "only-in-json")
        old.close()
        with mock.patch.object(storage.Storage, "_import_legacy_json", lambda self: None):
            sqlite_store = storage.Storage(database_path=self.db_path)
        sqlite_store.upsert_cache("patch:1:user", "from-sqlite")
        sqlite_store.close()

        merged = storage.Storage(database_path=self.db_path)
        try:
            self.assertEqual("from-sqlite", merged.get_cache("patch:1:user")["responseBody"])
            self.assertEqual("only-in-json", merged.get_cache("patch:2:user")["responseBody"])
        finally:
            merged.close()

    def test_a_corrupt_json_file_is_quarantined_not_imported(self) -> None:
        self.db_path.with_suffix(".json").write_text("{not json", encoding="utf-8")

        with mock.patch.object(storage.storage_corruption, "record_incident"):
            store = storage.Storage(database_path=self.db_path)
        try:
            self.assertEqual(0, store.count_cache_by_prefix(""))
        finally:
            store.close()
        self.assertEqual(1, len(list(self.root.glob("test.json.corrupt-*"))))

    def test_a_failed_import_keeps_the_json_file(self) -> None:
        old = self.json_store()
        old.upsert_cache("patch:1:user", "v1")
        old.close()
        json_path = self.db_path.with_suffix(".json")

        class FailingConnection(storage.sqlite3.Connection):
            def executemany(self, *_args, **_kwargs):
                raise storage.sqlite3.OperationalError("disk full")

        real_connect = storage.sqlite3.connect

        def connect(*args, **kwargs):
            return real_connect(*args, factory=FailingConnection, **kwargs)

        with mock.patch.object(storage.sqlite3, "connect", connect):
            storage.Storage(database_path=self.db_path)

        self.assertTrue(json_path.exists())
        self.assertEqual([], list(self.root.glob("test.json.migrated-*")))


class VendoredSqliteTests(unittest.TestCase):
    """Uses a module name the standard library doesn't have, so the first import fails the way
    it does on a firmware without sqlite3."""

    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.vendor = Path(self._temp_dir.name)
        (self.vendor / "vendored_sqlite_probe").mkdir()

    def tearDown(self) -> None:
        sys.modules.pop("vendored_sqlite_probe", None)
        self._temp_dir.cleanup()

    def load(self):
        with mock.patch.object(storage, "VENDORED_SQLITE_DIR", self.vendor):
            return storage._import_sqlite3("vendored_sqlite_probe")

    def test_the_vendored_module_is_used_when_the_firmware_has_none(self) -> None:
        (self.vendor / "vendored_sqlite_probe" / "__init__.py").write_text("MARKER = 'vendored'\n")

        module = self.load()

        self.assertEqual("vendored", module.MARKER)
        self.assertEqual(str(self.vendor), sys.path[-1])
        sys.path.remove(str(self.vendor))

    def test_a_vendored_module_that_does_not_load_means_json(self) -> None:
        (self.vendor / "vendored_sqlite_probe" / "__init__.py").write_text("import _no_such_extension\n")

        self.assertIsNone(self.load())
        self.assertNotIn(str(self.vendor), sys.path)

    def test_no_vendored_directory_means_json(self) -> None:
        missing = self.vendor / "absent"
        with mock.patch.object(storage, "VENDORED_SQLITE_DIR", missing):
            self.assertIsNone(storage._import_sqlite3("vendored_sqlite_probe"))

    def test_the_firmwares_sqlite_is_used_when_it_has_one(self) -> None:
        with mock.patch.object(storage, "VENDORED_SQLITE_DIR", self.vendor):
            module = storage._import_sqlite3()

        self.assertEqual("sqlite3", module.__name__)
        self.assertNotIn(str(self.vendor), sys.path)


if __name__ == "__main__":
    unittest.main()
