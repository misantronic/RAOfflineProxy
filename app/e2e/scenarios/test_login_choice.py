from __future__ import annotations

import time

from app.e2e.harness.session import (
    PROXY_BASE,
    START_LABEL,
    TOKEN,
    USER,
    wait_until,
)

MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"

LOG_IN_NOW = (1, "Log in now")
START_WITHOUT = (2, "Start without")
CHOICE_CANCEL = (3, "Cancel")
FORM_SAVE = (1, "Save")
FORM_CANCEL = (2, "Cancel")


class TestLoginChoice:
    def test_start_without_a_login_offers_a_choice(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()

        broadcast_only.wait_for_dialog_button(*LOG_IN_NOW)
        broadcast_only.wait_for_dialog_button(*CHOICE_CANCEL)
        assert not broadcast_only.proxy_service_running()
        assert broadcast_only.flycast_host_override() is None

    def test_cancel_starts_nothing(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()

        broadcast_only.tap_dialog_button(*CHOICE_CANCEL)

        broadcast_only.ui.wait_gone("android:id/button2", text=START_WITHOUT[1])
        broadcast_only.wait_for_proxy_toggle(START_LABEL)
        assert not broadcast_only.proxy_service_running()
        assert broadcast_only.flycast_host_override() is None

    def test_start_without_a_login_starts_the_proxy(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()

        broadcast_only.tap_dialog_button(*START_WITHOUT)

        broadcast_only.wait_for_proxy_started()
        assert wait_until(
            lambda: broadcast_only.flycast_host_override() == PROXY_BASE,
            30,
            message="Flycast host override set",
        )
        assert broadcast_only.cached_login() is None

    def test_the_choice_is_not_repeated_in_the_same_session(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()
        broadcast_only.tap_dialog_button(*START_WITHOUT)
        broadcast_only.wait_for_proxy_started()
        broadcast_only.stop_proxy()

        broadcast_only.tap_proxy_toggle(START_LABEL)

        broadcast_only.wait_for_proxy_started()
        assert broadcast_only.ui.is_absent("android:id/button2", text=START_WITHOUT[1])

    def test_the_login_is_learned_from_the_emulators_own_login(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()
        broadcast_only.tap_dialog_button(*START_WITHOUT)
        broadcast_only.wait_for_proxy_started()
        broadcast_only.wait_until_online()

        broadcast_only.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        login = wait_until(broadcast_only.cached_login, 30, message="login learned from traffic")
        assert login == {"user": USER, "token": TOKEN}


class TestLogInNow:
    def password(self, session) -> str:
        return session.ra.state.find_user(USER)["password"]

    def test_logging_in_starts_the_proxy_and_stores_the_token(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()
        broadcast_only.tap_dialog_button(*LOG_IN_NOW)
        assert not broadcast_only.proxy_service_running()

        broadcast_only.fill_login_form(USER, self.password(broadcast_only))
        broadcast_only.tap_dialog_button(*FORM_SAVE)

        broadcast_only.wait_for_proxy_started()
        login = wait_until(broadcast_only.cached_login, 30, message="login stored")
        assert login == {"user": USER, "token": TOKEN}

    def test_a_wrong_password_reopens_the_form_and_does_not_start(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()
        broadcast_only.tap_dialog_button(*LOG_IN_NOW)
        broadcast_only.fill_login_form(USER, "not-the-password")
        broadcast_only.tap_dialog_button(*FORM_SAVE)

        wait_until(lambda: broadcast_only.ra.journal("login2"), 30, message="login attempt")
        broadcast_only.wait_for_dialog_button(*FORM_SAVE)
        assert not broadcast_only.proxy_service_running()
        assert broadcast_only.cached_login() is None

    def test_cancelling_the_form_starts_nothing(self, broadcast_only):
        broadcast_only.tap_start_and_wait_for_login_choice()
        broadcast_only.tap_dialog_button(*LOG_IN_NOW)
        broadcast_only.wait_for_dialog_button(*FORM_CANCEL)

        broadcast_only.tap_dialog_button(*FORM_CANCEL)

        broadcast_only.wait_for_proxy_toggle(START_LABEL)
        assert not broadcast_only.proxy_service_running()


class TestRejectedToken:
    def test_the_form_opens_once_for_a_revoked_token(self, android):
        android.start_proxy()
        android.wait_until_online()
        android.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        wait_until(android.cached_login, 30, message="login cached from the import")
        android.ra.state.rotate_token(USER)
        android.ra.clear_journal()

        android.background_and_return()

        android.wait_for_dialog_button(*FORM_SAVE)
        android.tap_dialog_button(*FORM_CANCEL)
        android.ui.wait_gone("android:id/button1", text=FORM_SAVE[1])
        rejected_checks = len(android.ra.journal("patch"))

        android.background_and_return()

        wait_until(
            lambda: len(android.ra.journal("patch")) > rejected_checks,
            60,
            message="second token check",
        )
        time.sleep(3.0)
        assert android.ui.is_absent("android:id/button1", text=FORM_SAVE[1])
        assert android.proxy_service_running()

    def test_a_valid_token_never_prompts(self, android):
        android.start_proxy()
        android.wait_until_online()
        android.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        android.ra.clear_journal()

        android.background_and_return()

        wait_until(lambda: android.ra.journal("patch"), 60, message="token check")
        time.sleep(3.0)
        assert android.ui.is_absent("android:id/button1", text=FORM_SAVE[1])
