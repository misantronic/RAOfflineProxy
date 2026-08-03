import base64
import json
import tempfile
import threading
import unittest
from pathlib import Path

from linux.raofflineproxy import config
from linux.raofflineproxy import flusher
from linux.raofflineproxy import proxy_service
from linux.raofflineproxy import rom_cache
from linux.raofflineproxy import storage
from linux.raofflineproxy import utils


class LinuxAwardParityTests(unittest.TestCase):
    def award(
        self,
        achievement_id: int,
        user: str = "testuser",
        hardcore: int = 0,
        status: str = storage.PENDING_AWARD_STATUS_PENDING,
    ) -> dict:
        return {
            "achievementId": achievement_id,
            "queryString": f"/dorequest.php?r=awardachievement&a={achievement_id}&u={user}&h={hardcore}",
            "requestBody": f"a={achievement_id}&u={user}&h={hardcore}",
            "userAgent": "RetroArch/1.21.0 (Linux)",
            "queuedAt": 1_700_000_000_000,
            "status": status,
        }

    def test_proxy_user_agent_uses_linux_suffix(self) -> None:
        user_agent = utils.proxy_user_agent("RetroArch/1.21.0 (Linux)")

        self.assertEqual(
            user_agent,
            f"RetroArch/1.21.0 (Linux) {config.PROXY_UA_TAG}",
        )

    def test_proxy_user_agent_is_idempotent(self) -> None:
        original = f"RetroArch/1.21.0 (Linux) {config.PROXY_UA_TAG}"

        self.assertEqual(utils.proxy_user_agent(original), original)

    def test_clamp_award_offset_seconds_matches_two_week_limit(self) -> None:
        self.assertEqual(flusher.clamp_award_offset_seconds(-1), 0)
        self.assertEqual(
            flusher.clamp_award_offset_seconds(999999999),
            flusher.MAX_AWARD_OFFSET_SECONDS,
        )

    def test_build_award_request_body_recomputes_offset_and_validation_hash(
        self,
    ) -> None:
        award = {
            "achievementId": 1234,
            "queryString": "/dorequest.php?r=awardachievement",
            "requestBody": "a=1234&u=testuser&h=0&v=oldhash",
            "userAgent": "RetroArch/1.21.0 (Linux)",
            "queuedAt": 1_000,
            "payloadHash": "payload123",
            "prevHash": "genesis",
            "signature": "sig123",
            "signedAt": 1_000,
        }

        body = flusher.build_award_request_body(
            award,
            now_millis=31_000,
        )
        params = utils.parse_form_params(body)

        self.assertEqual(params["o"], "30")
        self.assertEqual(
            params["v"],
            flusher.compute_validation_hash(1234, "testuser", 0, 30),
        )
        self.assertEqual(params["ra_chain_payload_hash"], "payload123")
        self.assertEqual(params["ra_chain_prev_hash"], "genesis")
        self.assertEqual(params["ra_chain_sig"], "sig123")
        self.assertIn("ra_chain_pubkey", params)

    def test_award_offset_seconds_reports_clamp_state(self) -> None:
        award = {"queuedAt": 1_000}

        offset_seconds, was_clamped = flusher.award_offset_seconds(
            award,
            now_millis=(flusher.MAX_AWARD_OFFSET_SECONDS + 100) * 1000 + 1_000,
        )

        self.assertEqual(offset_seconds, flusher.MAX_AWARD_OFFSET_SECONDS)
        self.assertTrue(was_clamped)

    def test_verify_chain_detects_broken_link(self) -> None:
        first = {
            "achievementId": 1,
            "queryString": "/dorequest.php?r=awardachievement",
            "requestBody": "a=1&u=testuser&h=0",
            "queuedAt": 1_000,
        }
        first["payloadHash"] = flusher.sha256_hex(flusher.canonical_payload(first))
        first["prevHash"] = flusher.GENESIS_HASH
        first["signature"] = base64.b64encode(b"sig1").decode("ascii")

        second = {
            "achievementId": 2,
            "queryString": "/dorequest.php?r=awardachievement",
            "requestBody": "a=2&u=testuser&h=0",
            "queuedAt": 2_000,
        }
        second["payloadHash"] = flusher.sha256_hex(flusher.canonical_payload(second))
        second["prevHash"] = "wrong"
        second["signature"] = base64.b64encode(b"sig2").decode("ascii")

        valid, reason, index = flusher.verify_chain(
            [first, second],
            verify_signature=lambda _data, _signature: True,
        )

        self.assertFalse(valid)
        self.assertEqual(reason, "chain link broken")
        self.assertEqual(index, 1)

    def test_verify_chain_accepts_valid_sequence(self) -> None:
        awards = []
        prev_hash = flusher.GENESIS_HASH
        for achievement_id in (1, 2):
            award = {
                "achievementId": achievement_id,
                "queryString": "/dorequest.php?r=awardachievement",
                "requestBody": f"a={achievement_id}&u=testuser&h=0",
                "queuedAt": achievement_id * 1_000,
            }
            payload_hash = flusher.sha256_hex(flusher.canonical_payload(award))
            award["payloadHash"] = payload_hash
            award["prevHash"] = prev_hash
            award["signature"] = "ignored"
            awards.append(award)
            prev_hash = payload_hash

        valid, reason, index = flusher.verify_chain(
            awards,
            verify_signature=lambda _data, _signature: True,
        )

        self.assertTrue(valid)
        self.assertIsNone(reason)
        self.assertIsNone(index)

    def test_repair_pending_chain_rebases_forked_links(self) -> None:
        awards = []
        prev_hash = flusher.GENESIS_HASH
        for achievement_id in (1, 2, 3):
            award = {
                "id": achievement_id,
                "achievementId": achievement_id,
                "queryString": "/dorequest.php?r=awardachievement",
                "requestBody": f"a={achievement_id}&u=testuser&h=0",
                "userAgent": "RetroArch/1.21.0 (Linux)",
                "queuedAt": achievement_id * 1_000,
                "status": storage.PENDING_AWARD_STATUS_PENDING,
                "signedAt": achievement_id * 1_000,
            }
            payload_hash = flusher.sha256_hex(flusher.canonical_payload(award))
            award["payloadHash"] = payload_hash
            award["prevHash"] = prev_hash
            award["signature"] = flusher.sign_award(
                f"{payload_hash}:{prev_hash}".encode("utf-8")
            )
            awards.append(award)
            prev_hash = payload_hash

        awards[2]["prevHash"] = awards[0]["payloadHash"]
        awards[2]["signature"] = flusher.sign_award(
            f"{awards[2]['payloadHash']}:{awards[2]['prevHash']}".encode("utf-8")
        )

        repaired = flusher.repair_pending_chain(awards)

        self.assertIsNotNone(repaired)
        repaired = repaired or []
        self.assertEqual(repaired[2]["prevHash"], awards[1]["payloadHash"])
        valid, reason, index = flusher.verify_chain(repaired)
        self.assertTrue(valid)
        self.assertIsNone(reason)
        self.assertIsNone(index)

    def test_flush_pending_awards_repairs_forked_chain_before_flushing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            original_resolve_credentials = flusher.resolve_credentials
            original_refresh = flusher.refresh_and_load_achievement_ids
            original_send_award = flusher.send_award
            try:
                awards = []
                prev_hash = flusher.GENESIS_HASH
                for achievement_id in (12684, 12609):
                    award = {
                        "achievementId": achievement_id,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": f"a={achievement_id}&u=testuser&h=0",
                        "userAgent": "RetroArch/1.21.0 (Linux)",
                        "queuedAt": 1_700_000_000_000 + len(awards),
                        "status": storage.PENDING_AWARD_STATUS_PENDING,
                    }
                    award["payloadHash"] = flusher.sha256_hex(
                        flusher.canonical_payload(award)
                    )
                    award["prevHash"] = prev_hash
                    award["signature"] = flusher.sign_award(
                        f"{award['payloadHash']}:{award['prevHash']}".encode("utf-8")
                    )
                    award["signedAt"] = award["queuedAt"]
                    awards.append(award)
                    prev_hash = award["payloadHash"]

                awards[1]["prevHash"] = flusher.GENESIS_HASH
                awards[1]["signature"] = flusher.sign_award(
                    f"{awards[1]['payloadHash']}:{awards[1]['prevHash']}".encode("utf-8")
                )

                for award in awards:
                    store.upsert_pending_award(award)

                flusher.resolve_credentials = lambda *_args, **_kwargs: {
                    "user": "testuser",
                    "token": "token",
                }
                flusher.refresh_and_load_achievement_ids = (
                    lambda *_args, **_kwargs: ({12684, 12609}, [], {12684: 1, 12609: 1})
                )
                flusher.send_award = lambda *_args, **_kwargs: ("success", "")

                outcome = flusher.flush_pending_awards(store, {})

                self.assertEqual(outcome.flushed, 2)
                self.assertEqual(outcome.pending_remaining, 0)
                self.assertEqual(store.get_pending_awards(), [])
            finally:
                flusher.resolve_credentials = original_resolve_credentials
                flusher.refresh_and_load_achievement_ids = original_refresh
                flusher.send_award = original_send_award
                store.close()

    def test_merge_start_session_unlock_ids_adds_pending_awards_for_same_game_and_user(
        self,
    ) -> None:
        result = rom_cache.merge_start_session_unlock_ids(
            cached_unlock_ids=[11],
            pending_awards=[self.award(22), self.award(33)],
            achievement_game_ids={22: 42, 33: 42},
            game_id=42,
            user="testuser",
        )

        self.assertEqual(result, [11, 22, 33])

    def test_merge_start_session_unlock_ids_deduplicates_cached_and_pending_ids(
        self,
    ) -> None:
        result = rom_cache.merge_start_session_unlock_ids(
            cached_unlock_ids=[11, 22],
            pending_awards=[self.award(22), self.award(33)],
            achievement_game_ids={22: 42, 33: 42},
            game_id=42,
            user="testuser",
        )

        self.assertEqual(result, [11, 22, 33])

    def test_merge_start_session_unlock_ids_excludes_other_users_games_statuses_and_hardcore(
        self,
    ) -> None:
        result = rom_cache.merge_start_session_unlock_ids(
            cached_unlock_ids=[],
            pending_awards=[
                self.award(22),
                self.award(33, user="other"),
                self.award(
                    44,
                    status=storage.PENDING_AWARD_STATUS_DELETED,
                ),
                self.award(55, hardcore=1),
                self.award(66),
            ],
            achievement_game_ids={22: 42, 33: 42, 44: 42, 55: 42, 66: 99},
            game_id=42,
            user="testuser",
        )

        self.assertEqual(result, [22])

    def test_cache_session_merges_pending_awards_into_cached_startsession(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            try:
                store.upsert_cache(
                    "patch:42:testuser",
                    json.dumps(
                        {
                            "PatchData": {
                                "Title": "Test Game",
                                "Achievements": [
                                    {"ID": 11, "Title": "First"},
                                    {"ID": 22, "Title": "Second"},
                                ],
                            }
                        },
                        separators=(",", ":"),
                    ),
                )
                store.upsert_cache(
                    "unlocks:42:testuser:0",
                    '{"Success":true,"UserUnlocks":[11]}',
                )
                store.upsert_pending_award(self.award(22))

                response_body = rom_cache.cache_session(
                    42,
                    {"user": "testuser", "token": ""},
                    store,
                )
                payload = json.loads(response_body)

                self.assertEqual(
                    [entry["ID"] for entry in payload["Unlocks"]],
                    [11, 22],
                )

                cached = store.get_cache("startsession:42:testuser:0")
                self.assertIsNotNone(cached)
                self.assertEqual(
                    [
                        entry["ID"]
                        for entry in json.loads(cached["responseBody"])["Unlocks"]
                    ],
                    [11, 22],
                )
            finally:
                store.close()

    def test_flush_pending_awards_loads_user_agent_before_resolving_credentials(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            original_resolve_credentials = flusher.resolve_credentials
            original_refresh = flusher.refresh_and_load_achievement_ids
            try:
                store.upsert_pending_award(
                    {
                        "achievementId": 1,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=1&u=testuser&h=0",
                        "userAgent": "RetroArch/1.21.0 (Linux)",
                        "queuedAt": 1_000,
                    }
                )

                observed = {}

                def fake_resolve_credentials(_storage, _config_data, user_agent):
                    observed["user_agent"] = user_agent
                    return None

                flusher.resolve_credentials = fake_resolve_credentials
                flusher.refresh_and_load_achievement_ids = lambda *args, **kwargs: None

                outcome = flusher.flush_pending_awards(store, {})

                self.assertEqual(observed["user_agent"], config.FALLBACK_USER_AGENT)
                self.assertEqual(
                    outcome.last_error,
                    "No RetroAchievements credentials available",
                )
            finally:
                flusher.resolve_credentials = original_resolve_credentials
                flusher.refresh_and_load_achievement_ids = original_refresh
                store.close()

    def test_refresh_and_load_achievement_ids_marks_token_invalid_on_auth_error(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            original_refresh_game_patch = flusher.refresh_game_patch
            try:
                store.upsert_cache(
                    "patch:42:testuser",
                    json.dumps(
                        {"PatchData": {"Achievements": [{"ID": 22}]}},
                        separators=(",", ":"),
                    ),
                )

                def fake_refresh_game_patch(*_args, **_kwargs):
                    raise rom_cache.CacheGameAuthError("HTTP Error 401: Unauthorized")

                flusher.refresh_game_patch = fake_refresh_game_patch
                credentials = {"user": "testuser", "token": "stale-token"}

                result = flusher.refresh_and_load_achievement_ids(
                    store, credentials, "RetroArch/1.21.0 (Linux)", {}, [{**self.award(22), "id": 1}]
                )

                self.assertIsNone(result)
                self.assertTrue(store.is_token_invalid("stale-token"))
            finally:
                flusher.refresh_game_patch = original_refresh_game_patch
                store.close()

    def test_refresh_and_load_achievement_ids_recovers_from_generic_error(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            original_refresh_game_patch = flusher.refresh_game_patch
            try:
                store.upsert_cache(
                    "patch:42:testuser",
                    json.dumps(
                        {"PatchData": {"Achievements": [{"ID": 22}]}},
                        separators=(",", ":"),
                    ),
                )

                def fake_refresh_game_patch(*_args, **_kwargs):
                    raise rom_cache.CacheGameError("patch request failed: boom")

                flusher.refresh_game_patch = fake_refresh_game_patch
                credentials = {"user": "testuser", "token": "some-token"}

                result = flusher.refresh_and_load_achievement_ids(
                    store, credentials, "RetroArch/1.21.0 (Linux)", {}, [{**self.award(22), "id": 1}]
                )

                self.assertIsNone(result)
                self.assertFalse(store.is_token_invalid("some-token"))
            finally:
                flusher.refresh_game_patch = original_refresh_game_patch
                store.close()

    def test_refresh_and_load_achievement_ids_clears_invalid_token_on_success(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            original_refresh_game_patch = flusher.refresh_game_patch
            try:
                store.upsert_cache(
                    "patch:42:testuser",
                    json.dumps(
                        {"PatchData": {"Achievements": [{"ID": 22}]}},
                        separators=(",", ":"),
                    ),
                )
                store.mark_token_invalid("fresh-token")

                def fake_refresh_game_patch(*_args, **_kwargs):
                    return json.dumps(
                        {"PatchData": {"Achievements": [{"ID": 22}]}},
                        separators=(",", ":"),
                    )

                flusher.refresh_game_patch = fake_refresh_game_patch
                credentials = {"user": "testuser", "token": "fresh-token"}

                result = flusher.refresh_and_load_achievement_ids(
                    store, credentials, "RetroArch/1.21.0 (Linux)", {}, [{**self.award(22), "id": 1}]
                )

                self.assertIsNotNone(result)
                self.assertFalse(store.is_token_invalid("fresh-token"))
            finally:
                flusher.refresh_game_patch = original_refresh_game_patch
                store.close()

    def test_send_award_replaces_stale_embedded_token_with_current_credentials(
        self,
    ) -> None:
        award = {
            "achievementId": 22,
            "queryString": "/dorequest.php",
            "requestBody": "r=awardachievement&u=testuser&t=stale-token&a=22&h=0",
            "userAgent": "RetroArch/1.21.0 (Linux)",
            "queuedAt": 1_000,
        }
        original_http_post = flusher.http_post
        try:
            captured = {}

            def fake_http_post(_url, body, headers=None):
                captured["body"] = body
                return 200, "OK", '{"Success":true}'

            flusher.http_post = fake_http_post

            outcome, _message = flusher.send_award(
                award, {}, {"user": "testuser", "token": "fresh-token"}
            )

            self.assertEqual(outcome, "success")
            self.assertEqual(
                utils.parse_form_params(captured["body"])["t"], "fresh-token"
            )
        finally:
            flusher.http_post = original_http_post

    def test_flush_pending_awards_does_not_invalidate_token_on_per_award_auth_error(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            original_resolve_credentials = flusher.resolve_credentials
            original_refresh = flusher.refresh_and_load_achievement_ids
            original_send_award = flusher.send_award
            try:
                store.upsert_pending_award(self.award(22))

                flusher.resolve_credentials = lambda *_args, **_kwargs: {
                    "user": "testuser",
                    "token": "current-valid-token",
                }
                flusher.refresh_and_load_achievement_ids = (
                    lambda *_args, **_kwargs: (set(), [], {})
                )
                flusher.send_award = lambda *_args, **_kwargs: (
                    "auth_error",
                    "Token rejected by server (HTTP 401)",
                )

                outcome = flusher.flush_pending_awards(store, {})

                self.assertEqual(outcome.pending_remaining, 1)
                self.assertFalse(store.is_token_invalid("current-valid-token"))
            finally:
                flusher.resolve_credentials = original_resolve_credentials
                flusher.refresh_and_load_achievement_ids = original_refresh
                flusher.send_award = original_send_award
                store.close()

    def test_queue_award_skips_cached_already_unlocked_achievement(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            server = proxy_service.ProxyRuntimeServer({"proxy_port": 8080}, store)
            try:
                store.upsert_cache(
                    "patch:42:testuser",
                    json.dumps(
                        {
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 22, "Title": "Encore"},
                                ]
                            }
                        },
                        separators=(",", ":"),
                    ),
                )
                store.upsert_cache(
                    "unlocks:42:testuser:0",
                    '{"Success":true,"UserUnlocks":[22]}',
                )

                queued = server.queue_award(
                    "/dorequest.php?r=awardachievement&a=22&u=testuser&h=0",
                    "a=22&u=testuser&h=0",
                    {},
                )

                self.assertTrue(queued)
                self.assertEqual(store.get_pending_awards(), [])
            finally:
                server.server_close()
                store.close()

    def test_queue_award_skips_cached_already_unlocked_from_achievementsets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            server = proxy_service.ProxyRuntimeServer({"proxy_port": 8080}, store)
            try:
                store.upsert_cache(
                    "achievementsets:testhash:testuser",
                    json.dumps(
                        {
                            "Success": True,
                            "GameId": 42,
                            "Sets": [
                                {
                                    "Type": "core",
                                    "GameId": 42,
                                    "Achievements": [
                                        {"ID": 22, "Title": "Encore"},
                                    ],
                                }
                            ],
                        },
                        separators=(",", ":"),
                    ),
                )
                store.upsert_cache(
                    "unlocks:42:testuser:0",
                    '{"Success":true,"UserUnlocks":[22]}',
                )

                queued = server.queue_award(
                    "/dorequest.php?r=awardachievement&a=22&u=testuser&h=0",
                    "a=22&u=testuser&h=0",
                    {},
                )

                self.assertTrue(queued)
                self.assertEqual(store.get_pending_awards(), [])
            finally:
                server.server_close()
                store.close()

    def test_queue_award_serializes_near_simultaneous_appends(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            server = proxy_service.ProxyRuntimeServer({"proxy_port": 8080}, store)
            try:
                barrier = threading.Barrier(2)
                original_is_already_unlocked_award = server.is_already_unlocked_award

                def queue(achievement_id: int) -> None:
                    barrier.wait()
                    server.queue_award(
                        f"/dorequest.php?r=awardachievement&a={achievement_id}&u=testuser&h=0",
                        f"a={achievement_id}&u=testuser&h=0",
                        {},
                    )

                server.is_already_unlocked_award = lambda *_args, **_kwargs: False

                t1 = threading.Thread(target=queue, args=(1001,))
                t2 = threading.Thread(target=queue, args=(1002,))
                t1.start()
                t2.start()
                t1.join()
                t2.join()

                awards = store.get_pending_awards()
                self.assertEqual(len(awards), 2)
                valid, reason, index = flusher.verify_chain(awards)
                self.assertTrue(valid)
                self.assertIsNone(reason)
                self.assertIsNone(index)
            finally:
                server.is_already_unlocked_award = original_is_already_unlocked_award
                server.server_close()
                store.close()


if __name__ == "__main__":
    unittest.main()
