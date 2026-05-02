import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import storage


class LinuxStorageTests(unittest.TestCase):
    def test_clear_cache_preserves_login_and_user_agent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache("login2::misantronic", '{"User":"misantronic"}')
                store.upsert_cache("ua::last", "RetroArch/1.20.0")
                store.upsert_cache("patch:10701:misantronic", "patch")
                store.upsert_cache("unlocks:10701:misantronic:0", "unlocks")
                store.upsert_cache("startsession:10701:misantronic:0", "session")
                store.upsert_cache("gameid:abcd", '{"GameID":10701}')

                store.clear_cache()

                self.assertIsNotNone(store.get_cache("login2::misantronic"))
                self.assertIsNotNone(store.get_cache("ua::last"))
                self.assertEqual(store.get_all_cache_by_prefix("patch:"), [])
                self.assertEqual(store.get_all_cache_by_prefix("unlocks:"), [])
                self.assertEqual(store.get_all_cache_by_prefix("startsession:"), [])
                self.assertEqual(store.get_all_cache_by_prefix("gameid:"), [])
            finally:
                store.close()

    def test_load_login_credentials_prefers_retroarch_cfg_token(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    "login2::cached-user",
                    '{"User":"cached-user","Token":"cached-token"}',
                )

                credentials = store.load_login_credentials()

                self.assertEqual(
                    credentials,
                    {"user": "cached-user", "token": "cached-token"},
                )
            finally:
                store.close()

    def test_json_storage_shares_cache_updates_across_instances(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            with mock.patch.object(storage, "sqlite3", None):
                writer = storage.Storage(database_path=db_path)
                reader = storage.Storage(database_path=db_path)
                try:
                    writer.upsert_cache("gameid:abcd", '{"GameID":10701}')

                    self.assertEqual(
                        reader.get_cache("gameid:abcd")["responseBody"],
                        '{"GameID":10701}',
                    )
                finally:
                    writer.close()
                    reader.close()

    def test_json_storage_shares_pending_award_updates_across_instances(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            with mock.patch.object(storage, "sqlite3", None):
                writer = storage.Storage(database_path=db_path)
                reader = storage.Storage(database_path=db_path)
                try:
                    writer.upsert_pending_award(
                        {
                            "achievementId": 52114,
                            "queryString": "/dorequest.php?r=awardachievement",
                            "requestBody": "a=52114&u=misantronic&h=0",
                            "userAgent": "RetroArch/1.21.0 (Linux)",
                            "queuedAt": 1700000000000,
                        }
                    )

                    awards = reader.get_pending_awards()

                    self.assertEqual(len(awards), 1)
                    self.assertEqual(awards[0]["achievementId"], 52114)
                finally:
                    writer.close()
                    reader.close()

    def test_json_storage_preserves_interleaved_cache_writes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            with mock.patch.object(storage, "sqlite3", None):
                first = storage.Storage(database_path=db_path)
                second = storage.Storage(database_path=db_path)
                try:
                    first.upsert_cache("gameid:abcd", '{"GameID":10701}')
                    second.upsert_cache("patch:10701:misantronic", "patch")

                    self.assertIsNotNone(first.get_cache("gameid:abcd"))
                    self.assertIsNotNone(second.get_cache("gameid:abcd"))
                    self.assertIsNotNone(first.get_cache("patch:10701:misantronic"))
                    self.assertIsNotNone(second.get_cache("patch:10701:misantronic"))
                finally:
                    first.close()
                    second.close()


if __name__ == "__main__":
    unittest.main()
