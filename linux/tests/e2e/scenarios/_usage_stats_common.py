from __future__ import annotations

import time

USER = "testuser"
TOKEN = "tok-testuser-000000000001"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"

# The service's UsageReporter first checks 60 s after start, so give it that plus headroom on the
# emulated armv7 legs.
FIRST_REPORT_WAIT_SECONDS = 75
REPORT_TIMEOUT_SECONDS = 180


def _wait_for_pings(installed, timeout: float) -> list:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pings = installed.ra.usage_pings()
        if pings:
            return pings
        time.sleep(5)
    return installed.ra.usage_pings()


class UsageStatsChecks:
    """Shared by every device suite; the importing module provides the `installed` fixture and
    sets EXPECTED_OS to the firmware name its platform detection must report."""

    EXPECTED_OS = ""

    def grant_consent(self, installed) -> None:
        installed.update_config({"usage_stats_consent": True, "usage_stats_consent_version": 1})

    def test_reports_only_with_consent(self, installed):
        installed.update_config(
            {"usage_report_url": "http://127.0.0.1:8181/usage/ping", "usage_stats_consent": None}
        )
        installed.ra.clear_journal()
        installed.cli.run("stop-proxy")
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        time.sleep(FIRST_REPORT_WAIT_SECONDS)
        assert installed.ra.usage_pings() == [], "reported without consent"

        self.grant_consent(installed)
        installed.cli.run("stop-proxy")
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        pings = _wait_for_pings(installed, REPORT_TIMEOUT_SECONDS)
        installed.cli.run("stop-proxy")

        assert len(pings) == 1, pings
        ping = pings[0]
        assert ping["platform"] == "linux"
        assert ping["os"] == self.EXPECTED_OS
        assert ping["build"] == "release"
        assert ping["schema_version"] == 1
        assert ping["consent_version"] == 1
        assert len(ping["client_id"]) == 64
        assert USER not in str(ping).lower(), "username leaked into the report"
        assert TOKEN not in str(ping), "token leaked into the report"
        assert ping["counters"].get("requests_emulator", 0) >= 1, ping["counters"]
        assert ping["gauges"]["cached_games"] != "0", ping["gauges"]
