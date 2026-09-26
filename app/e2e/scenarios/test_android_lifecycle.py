from __future__ import annotations

import pytest

from app.e2e.harness.session import (
    PROXY_BASE,
    PROXY_VALUE,
    TOKEN,
    USER,
    retroarch_cfg,
    wait_until,
)

MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447

HARDCORE_KEY = "cheevos_hardcore_mode_enable"
CUSTOM_HOST_KEY = "cheevos_custom_host"


@pytest.fixture
def running(android):
    android.start_proxy()
    android.wait_until_online()
    return android


class TestProxyLifecycle:
    def test_start_patches_retroarch_cfg_and_keeps_a_backup(self, android):
        assert android.cfg_value(HARDCORE_KEY) == "true"

        android.start_proxy()

        assert android.cfg_value(CUSTOM_HOST_KEY) == PROXY_VALUE
        assert android.cfg_value(HARDCORE_KEY) == "false"
        assert android.backup() == retroarch_cfg(hardcore=True)

    def test_start_sends_the_host_override_broadcast(self, android):
        assert android.flycast_host_override() is None

        android.start_proxy()

        assert wait_until(
            lambda: android.flycast_host_override() == PROXY_BASE,
            30,
            message="Flycast host override set",
        )

    def test_stop_reverts_and_restores_hardcore(self, android):
        android.start_proxy()
        android.stop_proxy()

        assert not android.proxy_service_running()
        assert android.cfg_value(CUSTOM_HOST_KEY) == ""
        assert android.cfg_value(HARDCORE_KEY) == "true"

    def test_stop_clears_the_host_override(self, android):
        android.start_proxy()
        android.stop_proxy()

        assert wait_until(
            lambda: android.flycast_host_override() == "",
            30,
            message="Flycast host override cleared",
        )

    def test_hardcore_off_beforehand_stays_off_after_stop(self, android):
        android.seed_cfg(hardcore=False)

        android.start_proxy()
        android.stop_proxy()

        assert android.cfg_value(HARDCORE_KEY) == "false"
        assert android.cfg_value(CUSTOM_HOST_KEY) == ""

    def test_service_reports_online_against_the_fake_server(self, running):
        title, _text = running.notification()
        assert title == "Online"


class TestOnline:
    def test_launching_a_game_online_forwards_and_tags_the_user_agent(self, running):
        responses = running.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        assert responses["login"]["Success"] is True
        assert responses["gameid"]["GameID"] == MSLUG_GAME_ID
        assert len(responses["patch"]["PatchData"]["Achievements"]) == 3

        patch_requests = running.ra.journal("patch")
        assert patch_requests, "patch never reached RA"
        assert "RAOfflineProxy/" in patch_requests[-1]["userAgent"]
        assert running.ra.violations() == []

    def test_unknown_hash_reports_no_game(self, running):
        _status, payload = running.emulator.game_id("d" * 32)
        assert payload["GameID"] == 0

    def test_online_award_is_forwarded_immediately(self, running):
        running.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        status, payload = running.emulator.award(USER, TOKEN, 22001)

        assert status == 200 and payload["Success"] is True
        assert running.ra.unlocks(USER, MSLUG_GAME_ID) == [22001]
        assert running.ra.violations() == []


class TestHardcoreIsRefused:
    def test_hardcore_award_is_rejected_and_never_forwarded(self, running):
        running.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        running.ra.clear_journal()

        status, payload = running.emulator.award(USER, TOKEN, 22001, hardcore=1)

        assert status == 403
        assert payload["Error"] == "hardcore_not_supported"
        assert running.ra.journal("awardachievement") == []
        assert running.ra.violations() == []


class TestOffline:
    def test_offline_reads_are_served_from_cache(self, running):
        running.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        running.go_offline()
        running.ra.clear_journal()

        _status, payload = running.emulator.game_id(MSLUG_HASH)
        assert payload["GameID"] == MSLUG_GAME_ID

        _status, payload = running.emulator.patch(USER, TOKEN, MSLUG_GAME_ID)
        assert payload["Success"] is True
        assert len(payload["PatchData"]["Achievements"]) == 3

        assert [action for action in running.ra.actions() if action != "image"] == []

    def test_offline_award_queues_and_flushes_when_back_online(self, running):
        running.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        status, payload = running.emulator.award(USER, TOKEN, 22001)
        assert status == 200 and payload["Success"] is True

        running.go_offline()

        status, payload = running.emulator.award(USER, TOKEN, 22002)
        assert status == 200
        assert payload["Success"] is True
        assert payload.get("Error") == "queued_offline"
        running.wait_for_notification_text("1 pending award")
        assert 22002 not in running.ra.unlocks(USER, MSLUG_GAME_ID)

        _status, unlocks = running.emulator.unlocks(USER, TOKEN, MSLUG_GAME_ID)
        assert sorted(unlocks["UserUnlocks"]) == [22001, 22002]

        running.go_online()

        assert wait_until(
            lambda: 22002 in running.ra.unlocks(USER, MSLUG_GAME_ID),
            180,
            message="queued award flushed to RA",
        )
        assert running.ra.violations() == [], "proxy sent something RA would reject"

        flushed = running.ra.journal("awardachievement")[-1]
        assert flushed["params"].get("h", "0") == "0"
        assert "RAOfflineProxy/" in flushed["userAgent"]

