import unittest
import tempfile
from pathlib import Path

from linux.raofflineproxy import proxy_service
from linux.raofflineproxy import cache_keys
from linux.raofflineproxy import storage


class LinuxProxyCacheKeyTests(unittest.TestCase):
    def test_login2_uses_user_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=login2&u=misantronic&p=token",
        )

        self.assertEqual(key, "login2::misantronic")

    def test_patch_uses_patch_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=patch&u=misantronic&t=token&g=10701",
        )

        self.assertEqual(key, "patch:10701:misantronic")

    def test_unlocks_uses_softcore_unlocks_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php?r=unlocks&g=10701&h=0&u=misantronic&t=token",
            "",
        )

        self.assertEqual(key, "unlocks:10701:misantronic:0")

    def test_should_cache_action_allows_login2(self) -> None:
        self.assertTrue(proxy_service.should_cache_action("login2", "/dorequest.php"))

    def test_should_cache_action_allows_non_allowlisted_dorequest_actions(self) -> None:
        self.assertTrue(
            proxy_service.should_cache_action("somefutureaction", "/dorequest.php")
        )

    def test_should_cache_action_rejects_startsession(self) -> None:
        self.assertFalse(
            proxy_service.should_cache_action("startsession", "/dorequest.php")
        )

    def test_should_cache_action_rejects_non_dorequest_path(self) -> None:
        self.assertFalse(proxy_service.should_cache_action("badge", "/Badge/12345.png"))

    def test_offline_requests_hit_manual_cache_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            try:
                store.upsert_cache(
                    cache_keys.game_id("ABCDEF"),
                    '{"GameID":10701}',
                )
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[52113]}',
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":52113,"When":1700000000}]}',
                )

                game_id_response = runtime.handle_offline_request(
                    "/dorequest.php?r=gameid&m=abcdef&u=misantronic&t=token",
                    "",
                    "gameid",
                )
                patch_response = runtime.handle_offline_request(
                    "/dorequest.php?r=patch&g=10701&u=misantronic&t=token",
                    "",
                    "patch",
                )
                unlocks_response = runtime.handle_offline_request(
                    "/dorequest.php?r=unlocks&g=10701&h=0&u=misantronic&t=token",
                    "",
                    "unlocks",
                )

                self.assertIn(b'"GameID":10701', game_id_response)
                self.assertIn(b'"Title":"Tetris"', patch_response)
                self.assertIn(b'"UserUnlocks":[52113]', unlocks_response)
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
