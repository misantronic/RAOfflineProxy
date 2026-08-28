from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

from linux.tests.e2e.fake_ra.server import build_servers
from linux.tests.e2e.fake_ra.state import RaState, compute_validation_hash

CLIENT_UA = "RetroArch/1.21.0 (Linux) RAOfflineProxy/Linux/1.12.0-alpha1"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
TOKEN = "tok-testuser-000000000001"


def post(url: str, body: str, user_agent: str = CLIENT_UA):
    request = urllib.request.Request(
        url,
        data=body.encode("utf-8"),
        headers={
            "User-Agent": user_agent,
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


class FakeRaTests(unittest.TestCase):
    def setUp(self) -> None:
        self.state = RaState(None)
        self.ra, self.ctl = build_servers(self.state, "127.0.0.1", 0, 0)
        self.ra_url = "http://127.0.0.1:%d" % self.ra.server_address[1]
        self.ctl_url = "http://127.0.0.1:%d" % self.ctl.server_address[1]
        for server in (self.ra, self.ctl):
            threading.Thread(
                target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True
            ).start()

    def tearDown(self) -> None:
        for server in (self.ra, self.ctl):
            server.shutdown()
            server.server_close()

    def dorequest(self, body: str, user_agent: str = CLIENT_UA):
        return post(self.ra_url + "/dorequest.php", body, user_agent)

    def ctl_get(self, route: str):
        with urllib.request.urlopen(self.ctl_url + route, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))

    def ctl_post(self, route: str, payload: dict):
        request = urllib.request.Request(
            self.ctl_url + route,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))

    def test_login_returns_token_and_score(self) -> None:
        status, payload = self.dorequest("r=login2&u=testuser&p=testpass")
        self.assertEqual(status, 200)
        self.assertTrue(payload["Success"])
        self.assertEqual(payload["Token"], TOKEN)
        self.assertEqual(payload["Score"], 0)

    def test_login_rejects_bad_password(self) -> None:
        _status, payload = self.dorequest("r=login2&u=testuser&p=wrong")
        self.assertFalse(payload["Success"])

    def test_gameid_resolves_known_hash_and_zero_for_unknown(self) -> None:
        _status, payload = self.dorequest("r=gameid&m=" + MSLUG_HASH)
        self.assertEqual(payload["GameID"], 1447)

        _status, payload = self.dorequest("r=gameid&m=" + "0" * 32)
        self.assertTrue(payload["Success"])
        self.assertEqual(payload["GameID"], 0)

    def test_patch_shape_matches_proxy_expectations(self) -> None:
        _status, payload = self.dorequest(
            "r=patch&u=testuser&t=%s&g=1447" % TOKEN
        )
        patch_data = payload["PatchData"]
        self.assertEqual(patch_data["ID"], 1447)
        self.assertEqual(patch_data["Title"], "Metal Slug")
        self.assertEqual(
            [a["ID"] for a in patch_data["Achievements"]], [22001, 22002, 22003]
        )

    def test_achievementsets_uses_lowercase_gameid_key(self) -> None:
        _status, payload = self.dorequest(
            "r=achievementsets&u=testuser&t=%s&m=%s" % (TOKEN, MSLUG_HASH)
        )
        self.assertEqual(payload["GameId"], 1447)
        self.assertEqual(len(payload["Sets"][0]["Achievements"]), 3)

    def test_patch_rejects_stale_token_with_401(self) -> None:
        status, payload = self.dorequest("r=patch&u=testuser&t=stale&g=1447")
        self.assertEqual(status, 401)
        self.assertFalse(payload["Success"])
        self.assertIn("token", payload["Error"].lower())

    def test_award_unlocks_and_appears_in_unlocks_and_startsession(self) -> None:
        _status, payload = self.dorequest(
            "r=awardachievement&u=testuser&t=%s&a=22001&h=0" % TOKEN
        )
        self.assertTrue(payload["Success"])
        self.assertEqual(payload["Score"], 5)
        self.assertEqual(payload["AchievementsRemaining"], 2)

        _status, payload = self.dorequest(
            "r=unlocks&u=testuser&t=%s&g=1447&h=0" % TOKEN
        )
        self.assertEqual(payload["UserUnlocks"], [22001])

        _status, payload = self.dorequest(
            "r=startsession&u=testuser&t=%s&g=1447&h=0" % TOKEN
        )
        self.assertEqual([u["ID"] for u in payload["Unlocks"]], [22001])

    def test_award_is_idempotent(self) -> None:
        body = "r=awardachievement&u=testuser&t=%s&a=22001&h=0" % TOKEN
        _status, first = self.dorequest(body)
        _status, second = self.dorequest(body)
        self.assertTrue(first["NewlyUnlocked"])
        self.assertFalse(second["NewlyUnlocked"])
        self.assertEqual(second["Score"], 5)

    def test_award_accepts_matching_validation_hash(self) -> None:
        offset = 120
        validation = compute_validation_hash(22002, "testuser", 0, offset)
        _status, payload = self.dorequest(
            "r=awardachievement&u=testuser&t=%s&a=22002&h=0&o=%d&v=%s"
            % (TOKEN, offset, validation)
        )
        self.assertTrue(payload["Success"])
        self.assertEqual(self.ctl_get("/_ctl/violations")["count"], 0)

    def test_award_rejects_bad_validation_hash_and_records_violation(self) -> None:
        _status, payload = self.dorequest(
            "r=awardachievement&u=testuser&t=%s&a=22002&h=0&o=120&v=deadbeef" % TOKEN
        )
        self.assertFalse(payload["Success"])
        violations = self.ctl_get("/_ctl/violations")
        self.assertEqual(violations["count"], 1)
        self.assertEqual(violations["violations"][0]["kind"], "validation_hash")

    def test_hardcore_request_records_violation(self) -> None:
        self.dorequest("r=awardachievement&u=testuser&t=%s&a=22001&h=1" % TOKEN)
        violations = self.ctl_get("/_ctl/violations")
        self.assertEqual(violations["violations"][0]["kind"], "hardcore_request")

    def test_unsupported_client_user_agent_is_rejected(self) -> None:
        status, payload = self.dorequest(
            "r=gameid&m=" + MSLUG_HASH, user_agent="curl/8.4.0"
        )
        self.assertEqual(status, 403)
        self.assertEqual(payload["Error"], "unsupported_client")

    def test_unparseable_client_version_is_rejected(self) -> None:
        status, _payload = self.dorequest(
            "r=gameid&m=" + MSLUG_HASH, user_agent="RetroArch/unknown (Linux)"
        )
        self.assertEqual(status, 403)

    def test_proxy_self_user_agent_is_accepted(self) -> None:
        status, _payload = self.dorequest(
            "r=gameid&m=" + MSLUG_HASH,
            user_agent="RetroArch/1.21.0 (Linux) RAOfflineProxy/Linux/1.12.0-alpha1",
        )
        self.assertEqual(status, 200)

    def test_degraded_mode_reports_unreachable_head(self) -> None:
        self.ctl_post("/_ctl/mode", {"mode": "degraded"})
        request = urllib.request.Request(self.ra_url + "/", method="HEAD")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=5)
        self.assertEqual(caught.exception.code, 503)

    def test_offline_mode_drops_the_connection(self) -> None:
        self.ctl_post("/_ctl/mode", {"mode": "offline"})
        request = urllib.request.Request(self.ra_url + "/", method="HEAD")
        with self.assertRaises(Exception):
            urllib.request.urlopen(request, timeout=5)

    def test_reset_clears_progress_and_score(self) -> None:
        self.dorequest("r=awardachievement&u=testuser&t=%s&a=22001&h=0" % TOKEN)
        self.assertEqual(self.ctl_get("/_ctl/score?u=testuser")["score"], 5)

        self.ctl_post("/_ctl/reset", {})
        self.assertEqual(self.ctl_get("/_ctl/score?u=testuser")["score"], 0)
        _status, payload = self.dorequest(
            "r=unlocks&u=testuser&t=%s&g=1447&h=0" % TOKEN
        )
        self.assertEqual(payload["UserUnlocks"], [])

    def test_rotate_token_invalidates_the_old_one(self) -> None:
        self.ctl_post("/_ctl/rotate-token", {"user": "testuser"})
        status, _payload = self.dorequest("r=patch&u=testuser&t=%s&g=1447" % TOKEN)
        self.assertEqual(status, 401)

    def test_map_hash_registers_a_runtime_hash(self) -> None:
        self.ctl_post("/_ctl/map-hash", {"hash": "A" * 32, "game_id": 1447})
        _status, payload = self.dorequest("r=gameid&m=" + "a" * 32)
        self.assertEqual(payload["GameID"], 1447)

    def test_journal_records_actions_in_order(self) -> None:
        self.dorequest("r=login2&u=testuser&p=testpass")
        self.dorequest("r=gameid&m=" + MSLUG_HASH)
        self.dorequest("r=patch&u=testuser&t=%s&g=1447" % TOKEN)
        actions = [e["action"] for e in self.ctl_get("/_ctl/journal")["requests"]]
        self.assertEqual(actions, ["login2", "gameid", "patch"])

    def test_journal_redacts_tokens_and_passwords(self) -> None:
        self.dorequest("r=login2&u=testuser&p=testpass")
        entry = self.ctl_get("/_ctl/journal")["requests"][0]
        self.assertNotIn("p", entry["params"])
        self.assertNotIn("t", entry["params"])

    def test_ping_and_postactivity_succeed(self) -> None:
        for action in ("ping", "postactivity"):
            _status, payload = self.dorequest("r=%s&u=testuser&t=%s" % (action, TOKEN))
            self.assertTrue(payload["Success"])

    def test_badge_requests_return_a_png(self) -> None:
        with urllib.request.urlopen(self.ra_url + "/Badge/22001.png", timeout=5) as r:
            self.assertEqual(r.headers["Content-Type"], "image/png")
            self.assertTrue(r.read().startswith(b"\x89PNG"))


if __name__ == "__main__":
    unittest.main()
