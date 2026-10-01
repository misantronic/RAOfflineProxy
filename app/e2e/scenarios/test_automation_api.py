from __future__ import annotations

import pytest

from app.e2e.harness.session import (
    APP_PACKAGE,
    PROXY_BASE,
    PROXY_VALUE,
    START_LABEL,
    STOP_LABEL,
    wait_until,
)

HARDCORE_KEY = "cheevos_hardcore_mode_enable"
CUSTOM_HOST_KEY = "cheevos_custom_host"
AUTOSTART_PREF = "autostart_proxy"
BACKGROUND_FGS_RESTRICTED_SDK = 31


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

        assert status["version"] == 1
        assert status["running"] is False
        assert status["shouldBeRunning"] is False
        assert status["queue"] == {"count": 0, "state": "idle", "nextWindowAt": None}

    def test_reports_a_running_proxy_online(self, android):
        android.start_proxy()
        android.wait_until_online()

        status = android.status()

        assert status["running"] is True
        assert status["shouldBeRunning"] is True
        assert status["online"] is True


class TestPermission:
    def test_shell_can_read_status(self, android):
        assert '"version":1' in android.shell_control("status")

    @pytest.mark.parametrize("method", ["start", "stop"])
    def test_shell_cannot_start_or_stop(self, android, method):
        output = android.shell_control(method)

        assert "SecurityException" in output
        assert not android.proxy_service_running()


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
