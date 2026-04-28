import base64
import unittest

from linux.raofflineproxy import config
from linux.raofflineproxy import flusher
from linux.raofflineproxy import utils


class LinuxAwardParityTests(unittest.TestCase):
    def test_proxy_user_agent_uses_linux_suffix(self) -> None:
        user_agent = utils.proxy_user_agent("rcheevos/11.4.0")

        self.assertEqual(
            user_agent,
            f"rcheevos/11.4.0 {config.PROXY_UA_TAG}",
        )

    def test_proxy_user_agent_is_idempotent(self) -> None:
        original = f"rcheevos/11.4.0 {config.PROXY_UA_TAG}"

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
            "userAgent": "rcheevos/11.4.0",
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


if __name__ == "__main__":
    unittest.main()
