from __future__ import annotations

import pytest

from app.e2e.harness.session import (
    APP_PACKAGE,
    CLIENT_PACKAGE,
    CONTROL_PERMISSION,
    PROXY_BASE,
    PROXY_VALUE,
    START_LABEL,
    STOP_LABEL,
    TOKEN,
    USER,
    wait_until,
)

HARDCORE_KEY = "cheevos_hardcore_mode_enable"
CUSTOM_HOST_KEY = "cheevos_custom_host"
AUTOSTART_PREF = "autostart_proxy"
BACKGROUND_FGS_RESTRICTED_SDK = 31
STATUS_VERSION = 2
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447


@pytest.fixture
def background(android):
    """The app's process killed, so the provider call starts it in the background."""
    android.device.force_stop(APP_PACKAGE)
    try:
        yield android
    finally:
        android.device.set_battery_unrestricted(APP_PACKAGE, False)


class TestStatus:
    def test_reports_a_stopped_proxy_with_an_empty_queue(self, android):
        status = android.status()

        assert status["version"] == STATUS_VERSION
        assert status["running"] is False
        assert status["shouldBeRunning"] is False
        assert status["queue"] == {"count": 0, "state": "idle", "nextWindowAt": None}
        assert status["pendingAwards"] == {"count": 0, "state": "idle", "error": None}

    def test_reports_a_running_proxy_online(self, android):
        android.start_proxy()
        android.wait_until_online()

        status = android.status()

        assert status["running"] is True
        assert status["shouldBeRunning"] is True
        assert status["online"] is True


@pytest.fixture
def offline_award(android):
    """One award queued while RetroAchievements is unreachable."""
    android.start_proxy()
    android.wait_until_online()
    android.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
    android.go_offline()
    status, payload = android.emulator.award(USER, TOKEN, 22002)
    assert status == 200 and payload.get("Error") == "queued_offline"
    return android


def pending_awards(android) -> dict:
    return android.status()["pendingAwards"]


def pending_awards_in_state(android, state: str):
    awards = pending_awards(android)
    return awards if awards["state"] == state else None


class TestPendingAwards:
    def test_waits_while_offline(self, offline_award):
        assert wait_until(
            lambda: pending_awards(offline_award) == {"count": 1, "state": "waiting", "error": None},
            30,
            message="pending award reported as waiting",
        )

    def test_uploads_and_turns_idle_once_online(self, offline_award):
        offline_award.go_online()

        assert wait_until(
            lambda: pending_awards(offline_award) == {"count": 0, "state": "idle", "error": None},
            180,
            message="pending award uploaded",
        )
        assert 22002 in offline_award.ra.unlocks(USER, MSLUG_GAME_ID)

    def test_is_blocked_while_the_proxy_is_stopped(self, offline_award):
        offline_award.stop_proxy()

        assert pending_awards(offline_award) == {"count": 1, "state": "blocked", "error": None}

    def test_is_blocked_with_a_reason_when_the_upload_fails(self, offline_award):
        offline_award.ra.state.rotate_token(USER)

        offline_award.go_online()

        blocked = wait_until(
            lambda: pending_awards_in_state(offline_award, "blocked"),
            180,
            message="failed upload reported as blocked",
        )
        assert blocked["count"] == 1
        assert blocked["error"] == "auth"


class TestPermission:
    def test_shell_can_read_status(self, android):
        assert '"version":%d' % STATUS_VERSION in android.shell_control("status")

    @pytest.mark.parametrize("method", ["start", "stop"])
    def test_shell_cannot_start_or_stop(self, android, method):
        output = android.shell_control(method)

        assert "SecurityException" in output
        assert not android.proxy_service_running()

    def test_a_client_installed_first_works_once_the_user_allows_it(self, android):
        android.device.revoke(CLIENT_PACKAGE, CONTROL_PERMISSION)
        assert "SecurityException" in android.control_output("stop")

        android.device.grant(CLIENT_PACKAGE, CONTROL_PERMISSION)

        assert android.control("stop")[0] == "ok"


class TestStart:
    def test_patches_like_the_ui_and_runs_the_service(self, android):
        result, status = android.control("start")

        assert result == "ok"
        assert status["shouldBeRunning"] is True
        wait_until(android.proxy_service_running, 60, message="ProxyService running")
        assert android.cfg_value(CUSTOM_HOST_KEY) == PROXY_VALUE
        assert android.cfg_value(HARDCORE_KEY) == "false"
        assert wait_until(
            lambda: android.flycast_host_override() == PROXY_BASE,
            30,
            message="Flycast host override set",
        )

    def test_the_open_app_shows_the_proxy_running(self, android):
        android.control("start")

        android.wait_for_proxy_toggle(STOP_LABEL)

    def test_is_a_no_op_while_running(self, android):
        android.start_proxy()
        backup = android.backup()

        result, status = android.control("start")

        assert result == "ok"
        assert status["running"] is True
        assert android.backup() == backup
        assert android.cfg_value(CUSTOM_HOST_KEY) == PROXY_VALUE

    def test_leaves_autostart_alone(self, android):
        android.control("start")
        wait_until(android.proxy_service_running, 60, message="ProxyService running")
        android.control("stop")

        assert AUTOSTART_PREF not in android.prefs()


class TestStop:
    def test_reverts_like_the_ui(self, android):
        android.start_proxy()

        result, _status = android.control("stop")

        assert result == "ok"
        wait_until(lambda: not android.proxy_service_running(), 60, message="ProxyService stopped")
        assert android.cfg_value(CUSTOM_HOST_KEY) == ""
        assert android.cfg_value(HARDCORE_KEY) == "true"
        assert wait_until(
            lambda: android.flycast_host_override() == "",
            30,
            message="Flycast host override cleared",
        )

    def test_reports_the_proxy_stopped(self, android):
        android.start_proxy()

        android.control("stop")

        wait_until(lambda: not android.status()["running"], 60, message="status reports the proxy stopped")
        status = android.status()
        assert status["shouldBeRunning"] is False
        assert status["online"] is False

    def test_the_open_app_shows_the_proxy_stopped(self, android):
        android.start_proxy()

        android.control("stop")

        android.wait_for_proxy_toggle(START_LABEL)

    def test_is_a_no_op_while_stopped(self, android):
        result, status = android.control("stop")

        assert result == "ok"
        assert status["running"] is False
        assert android.cfg_value(CUSTOM_HOST_KEY) == ""
        assert android.cfg_value(HARDCORE_KEY) == "true"


class TestBackgroundStart:
    def test_is_refused_and_rolled_back_while_battery_optimized(self, background):
        if background.device.sdk_int() < BACKGROUND_FGS_RESTRICTED_SDK:
            pytest.skip("Android only restricts background service starts from API %d" % BACKGROUND_FGS_RESTRICTED_SDK)

        result, status = background.control("start")

        assert result == "foreground_service_not_allowed"
        assert status["running"] is False
        assert status["shouldBeRunning"] is False
        assert background.cfg_value(CUSTOM_HOST_KEY) == ""
        assert background.cfg_value(HARDCORE_KEY) == "true"

    def test_works_with_unrestricted_battery(self, background):
        background.device.set_battery_unrestricted(APP_PACKAGE, True)

        result, _status = background.control("start")

        assert result == "ok"
        wait_until(background.proxy_service_running, 60, message="ProxyService running")
        assert background.cfg_value(CUSTOM_HOST_KEY) == PROXY_VALUE
