import json
import unittest
import tempfile
from pathlib import Path
from types import MethodType

from linux.raofflineproxy import proxy_service
from linux.raofflineproxy import cache_keys
from linux.raofflineproxy import image_cache
from linux.raofflineproxy import storage


class LinuxProxyCacheKeyTests(unittest.TestCase):
    def test_login2_uses_user_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=login2&u=misantronic&p=token",
        )

        self.assertEqual(key, "login2::misantronic")

    def test_login_uses_login2_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=login&u=misantronic&p=token",
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

    def test_achievementsets_prefers_hash_scoped_cache_key(self) -> None:
        key = proxy_service.cache_key_for_request(
            "/dorequest.php",
            "r=achievementsets&u=misantronic&t=token&m=0e5f788550ca1fad8d4e5034d9964307",
        )

        self.assertEqual(
            key,
            "achievementsets:0e5f788550ca1fad8d4e5034d9964307:misantronic",
        )

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
                store.upsert_cache(
                    cache_keys.achievementsets(
                        "0e5f788550ca1fad8d4e5034d9964307", "misantronic"
                    ),
                    '{"Success":true,"GameId":10701,"Achievements":{"52113":{"ID":52113,"Title":"Test"}}}',
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
                achievementsets_response = runtime.handle_offline_request(
                    "/dorequest.php?r=achievementsets&u=misantronic&t=token&m=0e5f788550ca1fad8d4e5034d9964307",
                    "",
                    "achievementsets",
                )

                self.assertIn(b'"GameID":10701', game_id_response)
                self.assertIn(b'"Title":"Tetris"', patch_response)
                self.assertIn(b'"UserUnlocks":[52113]', unlocks_response)
                self.assertIn(b'"GameId":10701', achievementsets_response)
            finally:
                store.close()

    def test_offline_startsession_prefers_cached_live_response(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            try:
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"ServerNow":1700000000,"Unlocks":[{"ID":52113,"When":1700000000}],"HardcoreUnlocks":[]}',
                )

                response = runtime.handle_start_session(
                    "/dorequest.php?r=startsession&u=misantronic&t=token&g=10701&h=0&m=hash&l=12.1",
                    "",
                )

                self.assertIn(b'"ID":52113', response)
            finally:
                store.close()

    def test_offline_startsession_includes_pending_awards(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    '{"Success":true,"PatchData":{"Title":"Tetris","Achievements":{"1":{"ID":1,"Title":"First"},"2":{"ID":2,"Title":"Second"}}}}',
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1]}',
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"ServerNow":1700000000,"Unlocks":[{"ID":1,"When":1700000000}],"HardcoreUnlocks":[]}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 2,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=2&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 1700000000000,
                        "retryCount": 0,
                        "lastError": None,
                        "status": "pending",
                        "payloadHash": "hash-2",
                        "prevHash": "hash-1",
                        "signature": "sig",
                        "signedAt": 1700000000000,
                    }
                )

                response = runtime.handle_start_session(
                    "/dorequest.php?r=startsession&u=misantronic&t=token&g=10701&h=0&m=hash&l=12.1",
                    "",
                )

                self.assertIn(b'"ID":1', response)
                self.assertIn(b'"ID":2', response)
            finally:
                store.close()

    def test_offline_unlocks_includes_pending_awards(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    '{"Success":true,"PatchData":{"Title":"Tetris","Achievements":{"1":{"ID":1,"Title":"First"},"2":{"ID":2,"Title":"Second"}}}}',
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1]}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 2,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=2&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 1700000000000,
                        "retryCount": 0,
                        "lastError": None,
                        "status": "pending",
                        "payloadHash": "hash-2",
                        "prevHash": "hash-1",
                        "signature": "sig",
                        "signedAt": 1700000000000,
                    }
                )

                response = runtime.handle_offline_request(
                    "/dorequest.php?r=unlocks&g=10701&h=0&u=misantronic&t=token",
                    "",
                    "unlocks",
                )

                self.assertIn(b'"UserUnlocks":[1,2]', response)
            finally:
                store.close()

    def test_queue_offline_award_returns_requested_achievement_id(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            try:
                store.upsert_cache(
                    cache_keys.login("misantronic"),
                    '{"Success":true,"Score":1234}',
                )

                response = runtime.queue_offline_award(
                    "/dorequest.php?r=awardachievement&a=52114&u=misantronic&t=token&h=0",
                    "a=52114&u=misantronic&t=token&h=0&v=testhash",
                    {"User-Agent": "RetroArch/1.20.0"},
                )

                _, _, raw_body = response.partition(b"\r\n\r\n")
                payload = json.loads(raw_body.decode("utf-8"))

                self.assertTrue(payload["Success"])
                self.assertEqual(payload["Score"], 1234)
                self.assertEqual(payload["AchievementID"], 52114)
                self.assertEqual(payload["Error"], "queued_offline")
            finally:
                store.close()

    def test_online_startsession_is_cached_even_though_general_policy_excludes_it(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            runtime.config_data = {}
            try:

                def forward_to_upstream_result(_self, method, path, raw_body, headers):
                    self.assertEqual(method, "POST")
                    self.assertIn("r=startsession", raw_body)
                    return (
                        "success",
                        200,
                        "OK",
                        b'{"Success":true,"ServerNow":1700000000,"Unlocks":[{"ID":52113,"When":1700000000}],"HardcoreUnlocks":[]}',
                        "application/json",
                        '{"Success":true,"ServerNow":1700000000,"Unlocks":[{"ID":52113,"When":1700000000}],"HardcoreUnlocks":[]}',
                    )

                runtime.forward_to_upstream_result = MethodType(
                    forward_to_upstream_result, runtime
                )

                response = runtime.handle_online_request(
                    "POST",
                    "/dorequest.php",
                    "r=startsession&u=misantronic&t=token&g=10701&h=0&m=hash&l=12.1",
                    "startsession",
                    {},
                )

                self.assertIn(b'"ID":52113', response)
                cached = store.get_cache(cache_keys.start_session(10701, "misantronic"))
                self.assertIsNotNone(cached)
                self.assertIn('"ID":52113', cached["responseBody"])
                unlocks = store.get_cache(cache_keys.unlocks(10701, "misantronic"))
                self.assertIsNotNone(unlocks)
                self.assertEqual(
                    unlocks["responseBody"],
                    '{"Success":true,"UserUnlocks":[52113]}',
                )
            finally:
                store.close()

    def test_online_startsession_replaces_stale_unlock_cache(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            runtime.config_data = {}
            try:
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1,2,3,4,5,6,7]}',
                )

                def forward_to_upstream_result(_self, method, path, raw_body, headers):
                    self.assertEqual(method, "POST")
                    self.assertIn("r=startsession", raw_body)
                    return (
                        "success",
                        200,
                        "OK",
                        b'{"Success":true,"ServerNow":1700000000,"Unlocks":[{"ID":1,"When":1700000000},{"ID":2,"When":1700000000},{"ID":3,"When":1700000000},{"ID":4,"When":1700000000}],"HardcoreUnlocks":[]}',
                        "application/json",
                        '{"Success":true,"ServerNow":1700000000,"Unlocks":[{"ID":1,"When":1700000000},{"ID":2,"When":1700000000},{"ID":3,"When":1700000000},{"ID":4,"When":1700000000}],"HardcoreUnlocks":[]}',
                    )

                runtime.forward_to_upstream_result = MethodType(
                    forward_to_upstream_result, runtime
                )

                runtime.handle_online_request(
                    "POST",
                    "/dorequest.php",
                    "r=startsession&u=misantronic&t=token&g=10701&h=0&m=hash&l=12.1",
                    "startsession",
                    {},
                )

                unlocks = store.get_cache(cache_keys.unlocks(10701, "misantronic"))
                self.assertIsNotNone(unlocks)
                self.assertEqual(
                    unlocks["responseBody"],
                    '{"Success":true,"UserUnlocks":[1,2,3,4]}',
                )
            finally:
                store.close()

    def test_static_badge_request_serves_cached_asset(self) -> None:
        badge_path = image_cache.static_asset_path("/Badge/test.png")
        badge_path.parent.mkdir(parents=True, exist_ok=True)
        badge_path.write_bytes(b"png")
        runtime = object.__new__(proxy_service.ProxyRuntimeServer)
        try:
            response = runtime.process_proxy_request(
                "GET",
                "/Badge/test.png",
                "",
                {},
            )

            self.assertIn(b"HTTP/1.1 200 OK", response)
            self.assertTrue(response.endswith(b"png"))
        finally:
            if badge_path.exists():
                badge_path.unlink()
            badge_dir = badge_path.parent
            if badge_dir.exists() and not any(badge_dir.iterdir()):
                badge_dir.rmdir()

    def test_login2_tries_upstream_even_when_offline_probe_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            runtime.config_data = {}
            runtime.has_internet = False

            def is_online(_self) -> bool:
                return False

            def forward_to_upstream_result(_self, method, path, raw_body, headers):
                self.assertEqual(method, "POST")
                self.assertIn("r=login2", raw_body)
                return (
                    "success",
                    200,
                    "OK",
                    b'{"Success":true,"User":"misantronic","Token":"abc"}',
                    "application/json",
                    '{"Success":true,"User":"misantronic","Token":"abc"}',
                )

            runtime.is_online = MethodType(is_online, runtime)
            runtime.forward_to_upstream_result = MethodType(
                forward_to_upstream_result, runtime
            )

            try:
                response = runtime.process_proxy_request(
                    "POST",
                    "/dorequest.php",
                    "r=login2&u=misantronic&p=token",
                    {},
                )

                self.assertIn(b'"Success":true', response)
                cached = store.get_cache(cache_keys.login("misantronic"))
                self.assertIsNotNone(cached)
                self.assertIn('"Token":"abc"', cached["responseBody"])
            finally:
                store.close()

    def test_login_tries_upstream_and_caches_as_login2_when_offline_probe_fails(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            runtime = object.__new__(proxy_service.ProxyRuntimeServer)
            runtime.storage = store
            runtime.config_data = {}
            runtime.has_internet = False

            def is_online(_self) -> bool:
                return False

            def forward_to_upstream_result(_self, method, path, raw_body, headers):
                self.assertEqual(method, "POST")
                self.assertIn("r=login", raw_body)
                return (
                    "success",
                    200,
                    "OK",
                    b'{"Success":true,"User":"misantronic","Token":"abc"}',
                    "application/json",
                    '{"Success":true,"User":"misantronic","Token":"abc"}',
                )

            runtime.is_online = MethodType(is_online, runtime)
            runtime.forward_to_upstream_result = MethodType(
                forward_to_upstream_result, runtime
            )

            try:
                response = runtime.process_proxy_request(
                    "POST",
                    "/dorequest.php",
                    "r=login&u=misantronic&p=token",
                    {},
                )

                self.assertIn(b'"Success":true', response)
                cached = store.get_cache(cache_keys.login("misantronic"))
                self.assertIsNotNone(cached)
                self.assertIn('"Token":"abc"', cached["responseBody"])
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
