import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import storage, storage_corruption


class LinuxStorageTests(unittest.TestCase):
    def test_clear_cache_preserves_login_and_user_agent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache("login2::misantronic", '{"User":"misantronic"}')
                store.upsert_cache("ua::last", "RetroArch/1.20.0")
                store.upsert_cache("patch:10701:misantronic", "patch")
                store.upsert_cache(
                    "achievementsets:testhash:misantronic",
                    '{"GameId":10701}',
                )
                store.upsert_cache("unlocks:10701:misantronic:0", "unlocks")
                store.upsert_cache("startsession:10701:misantronic:0", "session")
                store.upsert_cache("gameid:abcd", '{"GameID":10701}')

                store.clear_cache()

                self.assertIsNotNone(store.get_cache("login2::misantronic"))
                self.assertIsNotNone(store.get_cache("ua::last"))
                self.assertEqual(store.get_all_cache_by_prefix("patch:"), [])
                self.assertEqual(store.get_all_cache_by_prefix("achievementsets:"), [])
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

    def test_mark_token_invalid_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                self.assertFalse(store.is_token_invalid("token-a"))

                store.mark_token_invalid("token-a")

                self.assertTrue(store.is_token_invalid("token-a"))
                self.assertFalse(store.is_token_invalid("token-b"))

                store.clear_invalid_token()

                self.assertFalse(store.is_token_invalid("token-a"))
            finally:
                store.close()

    def test_json_storage_recovers_from_corrupt_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_dir_path = Path(temp_dir)
            db_path = temp_dir_path / "test.sqlite3"
            json_path = db_path.with_suffix(".json")
            with mock.patch.object(storage, "sqlite3", None):
                store = storage.Storage(database_path=db_path)
                try:
                    store.upsert_cache("gameid:abcd", '{"GameID":10701}')
                finally:
                    store.close()

                with json_path.open("a", encoding="utf-8") as handle:
                    handle.write('{"api_cache": []}')

                store = storage.Storage(database_path=db_path)
                try:
                    self.assertIsNone(store.get_cache("gameid:abcd"))
                    store.upsert_cache("gameid:efgh", '{"GameID":10702}')
                    self.assertIsNotNone(store.get_cache("gameid:efgh"))
                finally:
                    store.close()

                quarantined = list(temp_dir_path.glob("*.corrupt-*"))
                self.assertEqual(len(quarantined), 1)

                incident = storage_corruption.load_incident(temp_dir_path)
                self.assertIsNotNone(incident)
                self.assertEqual(incident["quarantined_path"], str(quarantined[0]))
                self.assertFalse(incident["reported"])
                self.assertFalse(incident["notified"])
                self.assertEqual(incident["lost_pending_awards"], 0)

    def test_reload_json_state_does_not_quarantine_on_transient_os_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_dir_path = Path(temp_dir)
            db_path = temp_dir_path / "test.sqlite3"
            with mock.patch.object(storage, "sqlite3", None):
                store = storage.Storage(database_path=db_path)
                try:
                    store.upsert_cache("gameid:abcd", '{"GameID":10701}')

                    with mock.patch.object(
                        storage.json, "load", side_effect=OSError("simulated read error")
                    ):
                        with self.assertRaises(OSError):
                            store.get_cache("gameid:abcd")

                    self.assertEqual(list(temp_dir_path.glob("*.corrupt-*")), [])
                    self.assertIsNone(storage_corruption.load_incident(temp_dir_path))
                    self.assertEqual(
                        store.get_cache("gameid:abcd")["responseBody"], '{"GameID":10701}'
                    )
                finally:
                    store.close()

    def test_salvage_pending_award_count_recovers_from_extra_data_corruption(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            json_path = db_path.with_suffix(".json")
            with mock.patch.object(storage, "sqlite3", None):
                store = storage.Storage(database_path=db_path)
                try:
                    store.upsert_pending_award(
                        {
                            "achievementId": 52114,
                            "queryString": "/dorequest.php?r=awardachievement",
                            "requestBody": "a=52114&u=misantronic&h=0",
                            "userAgent": "RetroArch/1.21.0 (Linux)",
                            "queuedAt": 1700000000000,
                        }
                    )
                finally:
                    store.close()

                with json_path.open("a", encoding="utf-8") as handle:
                    handle.write("garbage-after-valid-json")

                store = storage.Storage(database_path=db_path)
                store.close()

                incident = storage_corruption.load_incident(Path(temp_dir))
                self.assertEqual(incident["lost_pending_awards"], 1)

    def test_salvage_pending_award_count_unknown_when_unparseable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            json_path = db_path.with_suffix(".json")
            with mock.patch.object(storage, "sqlite3", None):
                store = storage.Storage(database_path=db_path)
                store.close()

                json_path.write_text("not json at all", encoding="utf-8")

                store = storage.Storage(database_path=db_path)
                store.close()

                incident = storage_corruption.load_incident(Path(temp_dir))
                self.assertIsNone(incident["lost_pending_awards"])

    def test_upsert_pending_award_skips_warning_achievement(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_pending_award(
                    {
                        "achievementId": 101000001,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=101000001&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.21.0 (Linux)",
                        "queuedAt": 1700000000000,
                    }
                )

                self.assertEqual(store.get_pending_awards(), [])
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
