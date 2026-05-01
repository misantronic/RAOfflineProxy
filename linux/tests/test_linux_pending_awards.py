import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import pending_awards
from linux.raofflineproxy import storage


class LinuxPendingAwardsTests(unittest.TestCase):
    def test_list_pending_awards_resolves_game_and_achievement_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    "patch:10701:misantronic",
                    '{"PatchData":{"Title":"Tetris","Achievements":[{"ID":52113,"Title":"First Line","Points":5}]}}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 52113,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=52113&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 1700000000000,
                    }
                )

                entries = pending_awards.list_pending_awards(store)

                self.assertEqual(len(entries), 1)
                self.assertEqual(entries[0].game_title, "Tetris")
                self.assertEqual(entries[0].achievement_title, "First Line")
                self.assertEqual(entries[0].points, 5)
            finally:
                store.close()

    def test_delete_pending_award_marks_entry_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_pending_award(
                    {
                        "achievementId": 52114,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=52114&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 1700000000000,
                    }
                )

                pending_awards.delete_pending_award(store, 52114)

                awards = store.get_pending_awards()
                self.assertEqual(len(awards), 1)
                self.assertEqual(
                    awards[0]["status"], storage.PENDING_AWARD_STATUS_DELETED
                )
                self.assertEqual(pending_awards.list_pending_awards(store), [])
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
