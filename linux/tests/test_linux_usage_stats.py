from __future__ import annotations

import json
import tempfile
import unittest
from io import StringIO
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import main, usage_report, usage_stats

DAY_MS = 24 * 60 * 60 * 1000
WINDOW_MS = usage_stats.WINDOW_MS


class UsageIsolation(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.config: dict = {}
        patches = [
            mock.patch.object(usage_stats, "USAGE_STATS_FILE", root / "usage_stats.json"),
            mock.patch.object(usage_stats, "USAGE_STATS_LOCK_FILE", root / "usage_stats.lock"),
            mock.patch.object(usage_stats, "ensure_config_dir", lambda: root),
            mock.patch.object(usage_stats, "load_config", lambda: dict(self.config)),
            mock.patch.object(usage_stats, "save_config", self.config.update),
            mock.patch.object(usage_report, "REPORT_LOCK_FILE", root / "usage_report.lock"),
            mock.patch.object(usage_report, "ensure_config_dir", lambda: root),
            mock.patch.object(usage_report, "load_config", lambda: dict(self.config)),
            mock.patch.object(usage_stats, "_recorder", usage_stats._Recorder()),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.addCleanup(self.tmp.cleanup)

    def grant(self) -> None:
        usage_stats.save_consent(True)


class ConsentTests(UsageIsolation):
    def test_unanswered_is_none(self) -> None:
        self.assertIsNone(usage_stats.load_consent())

    def test_grant_and_decline_are_stored_with_the_current_version(self) -> None:
        usage_stats.save_consent(True)
        self.assertTrue(usage_stats.load_consent())
        self.assertEqual(self.config["usage_stats_consent_version"], usage_stats.USAGE_STATS_CONSENT_VERSION)
        usage_stats.save_consent(False)
        self.assertFalse(usage_stats.load_consent())

    def test_consent_for_an_older_scope_is_asked_again_but_a_decline_is_kept(self) -> None:
        self.assertIsNone(usage_stats.resolve_consent(True, 1, current_version=2))
        self.assertTrue(usage_stats.resolve_consent(True, 2, current_version=2))
        self.assertFalse(usage_stats.resolve_consent(False, 1, current_version=2))

    def test_nothing_is_counted_without_consent(self) -> None:
        usage_stats.record_request(usage_stats.SOURCE_EMULATOR, 200)
        usage_stats.flush()
        self.assertEqual(usage_stats.snapshot(), {})

    def test_declining_deletes_collected_counters(self) -> None:
        self.grant()
        usage_stats.record_request(usage_stats.SOURCE_EMULATOR, 200)
        usage_stats.flush()
        usage_stats.save_consent(False)
        self.assertEqual(usage_stats.snapshot(), {})


class PendingCounterTests(unittest.TestCase):
    def test_requests_are_counted_per_source_and_failures_by_status(self) -> None:
        pending = usage_stats.PendingCounters()
        pending.record_request(usage_stats.SOURCE_EMULATOR, 200, 1_000)
        pending.record_request(usage_stats.SOURCE_BACKGROUND, 429, 1_000)
        pending.record_request(usage_stats.SOURCE_BACKGROUND, 503, 1_000)
        pending.record_request(usage_stats.SOURCE_APP, None, 1_000)
        pending.record_request(usage_stats.SOURCE_APP, 404, 1_000)

        values = pending.values
        self.assertEqual(values["requests_emulator"], 1)
        self.assertEqual(values["requests_background"], 2)
        self.assertEqual(values["requests_app"], 2)
        self.assertEqual(values["rate_limited"], 1)
        self.assertEqual(values["failures_server"], 1)
        self.assertEqual(values["failures_network"], 1)

    def test_busiest_window_is_kept(self) -> None:
        pending = usage_stats.PendingCounters()
        for _ in range(5):
            pending.record_request(usage_stats.SOURCE_BACKGROUND, 200, 1_000)
        for _ in range(2):
            pending.record_request(usage_stats.SOURCE_BACKGROUND, 200, 1_000 + WINDOW_MS)
        self.assertEqual(pending.values["max_requests_per_window"], 5)

    def test_batch_without_requests_is_ignored(self) -> None:
        pending = usage_stats.PendingCounters()
        pending.record_batch(0, 0, "paused", False, 1_000)
        self.assertEqual(pending.values, {})

    def test_batch_outcomes(self) -> None:
        pending = usage_stats.PendingCounters()
        pending.record_batch(40, 3, "budget_exhausted", True, 600_000)
        pending.record_batch(5, 0, "empty", False, 30_000)
        pending.record_batch(0, 0, "failed", False, 2_000)
        values = pending.values
        self.assertEqual(values["batches"], 3)
        self.assertEqual(values["batches_time_limited"], 1)
        self.assertEqual(values["batch_ms_total"], 632_000)
        self.assertEqual(values["queue_cached"], 45)
        self.assertEqual(values["queue_no_match"], 3)
        self.assertEqual(values["queue_emptied"], 1)
        self.assertEqual(values["queue_failed"], 1)


class MergeTests(unittest.TestCase):
    def test_counts_add_up_and_maxima_keep_the_highest(self) -> None:
        merged = usage_stats.merge_counters(
            {"requests_app": 3, "max_requests_per_window": 10},
            {"requests_app": 2, "max_requests_per_window": 4},
        )
        self.assertEqual(merged, {"requests_app": 5, "max_requests_per_window": 10})

    def test_after_report_keeps_what_arrived_in_flight(self) -> None:
        stored = {"requests_app": 5, "max_requests_per_window": 9, "last_reported_at": 1}
        remaining = usage_stats.after_report(stored, {"requests_app": 3, "max_requests_per_window": 9}, 42)
        self.assertEqual(remaining, {"requests_app": 2, "last_reported_at": 42})

    def test_reportable_hides_bookkeeping(self) -> None:
        self.assertEqual(
            usage_stats.reportable({"requests_app": 2, "last_reported_at": 7, "queue_failed": 0}),
            {"requests_app": 2},
        )


class SharedFileTests(UsageIsolation):
    def test_two_processes_merge_into_one_file(self) -> None:
        self.grant()
        menu = usage_stats._Recorder()
        service = usage_stats._Recorder()
        menu.record_request(usage_stats.SOURCE_APP, 200)
        service.record_request(usage_stats.SOURCE_EMULATOR, 200)
        service.record_request(usage_stats.SOURCE_EMULATOR, 200)
        menu.flush()
        service.flush()
        self.assertEqual(
            usage_stats.reportable(usage_stats.snapshot()),
            {"requests_app": 1, "requests_emulator": 2, "max_requests_per_window": 2},
        )


class ReportTests(UsageIsolation):
    def storage(self, user: str | None = "Scott"):
        fake = mock.Mock()
        fake.load_login_credentials.return_value = None if user is None else {"user": user, "token": "t"}
        return fake

    def run_report(self, storage, sent: list, accepted: bool = True, now_ms: int = 20_000 * DAY_MS) -> bool:
        def send(payload: dict, _url: str) -> bool:
            sent.append(payload)
            return accepted

        with mock.patch.object(usage_report, "_send", send), mock.patch.object(
            usage_report, "load_gauges", lambda _storage, _now: {"cached_games": 120}
        ), mock.patch.object(
            usage_report.log_uploader,
            "device_metadata",
            lambda: {"os": "ROCKNIX", "device": "Retroid Pocket 5", "app_version": "1.13.0", "emulator": ["RetroArch"]},
        ):
            return usage_report.report_if_due(storage, now_ms)

    def test_no_report_without_consent(self) -> None:
        sent: list = []
        self.assertFalse(self.run_report(self.storage(), sent))
        self.assertEqual(sent, [])

    def test_report_once_per_utc_day_and_counters_are_cleared(self) -> None:
        self.grant()
        usage_stats.record_request(usage_stats.SOURCE_EMULATOR, 200)
        sent: list = []
        day = 20_000 * DAY_MS

        self.assertTrue(self.run_report(self.storage(), sent, now_ms=day + 10))
        self.assertFalse(self.run_report(self.storage(), sent, now_ms=day + DAY_MS - 1))
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0]["counters"], {"requests_emulator": 1, "max_requests_per_window": 1})
        self.assertEqual(usage_stats.reportable(usage_stats.snapshot()), {})

        self.assertTrue(self.run_report(self.storage(), sent, now_ms=day + DAY_MS))
        self.assertEqual(len(sent), 2)

    def test_rejected_report_keeps_the_counters(self) -> None:
        self.grant()
        usage_stats.record_request(usage_stats.SOURCE_APP, 200)
        sent: list = []
        self.assertFalse(self.run_report(self.storage(), sent, accepted=False))
        self.assertEqual(usage_stats.reportable(usage_stats.snapshot())["requests_app"], 1)
        self.assertEqual(usage_stats.last_reported_at(), 0)

    def test_send_failure_never_raises(self) -> None:
        self.grant()

        def boom(_payload: dict, _url: str) -> bool:
            raise OSError("no route to host")

        with mock.patch.object(usage_report, "_send", boom), mock.patch.object(
            usage_report, "load_gauges", lambda _storage, _now: {}
        ), mock.patch.object(usage_report.log_uploader, "device_metadata", lambda: {}):
            self.assertFalse(usage_report.report_if_due(self.storage(), 20_000 * DAY_MS))

    def test_no_report_without_credentials(self) -> None:
        self.grant()
        sent: list = []
        self.assertFalse(self.run_report(self.storage(user=None), sent))
        self.assertEqual(sent, [])

    def test_payload_contains_no_username(self) -> None:
        self.grant()
        sent: list = []
        self.run_report(self.storage(), sent)
        payload = sent[0]
        self.assertNotIn("scott", str(payload).lower())
        self.assertEqual(payload["platform"], "linux")
        self.assertEqual(payload["os"], "ROCKNIX")
        self.assertEqual(payload["device"], "Retroid Pocket 5")
        self.assertEqual(payload["gauges"]["cached_games"], "100-249")
        self.assertEqual(payload["schema_version"], 1)
        self.assertEqual(payload["consent_version"], 1)


class CliTests(UsageIsolation):
    """The commands spruce's own UI uses to ask for consent, since spruce has no menu of ours."""

    def cli(self, *args: str) -> str:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", *args]), mock.patch.object(
            main, "load_config", lambda: dict(self.config)
        ), mock.patch.object(main, "configure_logging", lambda: None), mock.patch("sys.stdout", stdout):
            main.main()
        return stdout.getvalue().strip()

    def test_status_is_unanswered_until_the_user_decides(self) -> None:
        self.assertEqual(self.cli("usage-stats-status"), "unanswered")

    def test_enable_and_disable(self) -> None:
        self.assertEqual(self.cli("enable-usage-stats"), "Usage stats enabled")
        self.assertEqual(self.cli("usage-stats-status"), "enabled")
        self.assertEqual(self.config["usage_stats_consent_version"], usage_stats.USAGE_STATS_CONSENT_VERSION)
        self.assertEqual(self.cli("disable-usage-stats"), "Usage stats disabled")
        self.assertEqual(self.cli("usage-stats-status"), "disabled")

    def test_disable_deletes_collected_counters(self) -> None:
        self.cli("enable-usage-stats")
        usage_stats.record_request(usage_stats.SOURCE_EMULATOR, 200)
        usage_stats.flush()
        self.cli("disable-usage-stats")
        self.assertEqual(usage_stats.snapshot(), {})

    def test_json_status_carries_the_prompt_texts(self) -> None:
        status = json.loads(self.cli("usage-stats-status", "--json"))
        self.assertIsNone(status["consent"])
        self.assertEqual(status["consent_version"], usage_stats.USAGE_STATS_CONSENT_VERSION)
        self.assertEqual(status["title"], usage_stats.CONSENT_TITLE)
        self.assertEqual(status["accept"], "Share statistics")
        self.assertEqual(status["decline"], "No thanks")
        self.assertIn("Never sent", status["message"])
        self.assertTrue(status["privacy_policy_url"].startswith("https://"))

    def test_consent_for_an_older_version_reads_as_unanswered(self) -> None:
        self.config.update({"usage_stats_consent": True, "usage_stats_consent_version": 0})
        self.assertEqual(self.cli("usage-stats-status"), "unanswered")


class PureHelperTests(unittest.TestCase):
    def test_client_id_matches_android(self) -> None:
        # Same value the Android app sends for this user, so one person is counted once.
        self.assertEqual(
            usage_report.client_id(" Scott "),
            "6648e8c819fd429cd930c968c7cd68b899ff1363a23eff58a21de8e42125fbe5",
        )

    def test_report_is_due_once_per_utc_day(self) -> None:
        day = 20_000 * DAY_MS
        self.assertTrue(usage_report.is_report_due(0, day))
        self.assertFalse(usage_report.is_report_due(day, day + DAY_MS - 1))
        self.assertTrue(usage_report.is_report_due(day + DAY_MS - 1, day + DAY_MS))
        self.assertTrue(usage_report.is_report_due(day, day - 1))

    def test_report_url_prefers_env_then_config_then_default(self) -> None:
        with mock.patch.dict(usage_report.os.environ, {}, clear=False):
            usage_report.os.environ.pop(usage_report.REPORT_URL_ENV, None)
            self.assertEqual(usage_report.report_url({}), usage_report.DEFAULT_REPORT_URL)
            self.assertEqual(usage_report.report_url({"usage_report_url": "http://x/usage/ping"}), "http://x/usage/ping")
            usage_report.os.environ[usage_report.REPORT_URL_ENV] = ""
            self.assertEqual(usage_report.report_url({"usage_report_url": "http://x"}), "")

    def test_buckets(self) -> None:
        self.assertEqual(usage_report.count_bucket(0), "0")
        self.assertEqual(usage_report.count_bucket(9), "1-9")
        self.assertEqual(usage_report.count_bucket(100), "100-249")
        self.assertEqual(usage_report.count_bucket(10_000), "2500+")
        self.assertEqual(usage_report.age_bucket(None), "none")
        self.assertEqual(usage_report.age_bucket(59 * 60_000), "<1h")
        self.assertEqual(usage_report.age_bucket(8 * DAY_MS), "7d+")


if __name__ == "__main__":
    unittest.main()
