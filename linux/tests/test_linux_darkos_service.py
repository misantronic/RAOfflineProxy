import unittest
from unittest.mock import patch

from linux.raofflineproxy import darkos_service


class DarkosSystemdUnitTests(unittest.TestCase):
    def test_unit_runs_as_the_device_user(self) -> None:
        # Running as root would leave the config dir's files unwritable by the
        # menu, which runs unprivileged as the same account.
        unit = darkos_service.darkos_systemd_unit()

        self.assertIn(f"User={darkos_service.DARKOS_USER}", unit)
        self.assertIn(f"Group={darkos_service.DARKOS_USER}", unit)

    def test_unit_patches_emulator_configs_before_starting(self) -> None:
        # Without this the proxy comes back after a reboot but nothing is
        # pointed at it, so achievements bypass the proxy entirely.
        unit = darkos_service.darkos_systemd_unit()

        self.assertIn("apply-emulator-config", unit)
        self.assertIn("run-service", unit)

    def test_a_failed_patch_does_not_block_the_proxy(self) -> None:
        self.assertIn("ExecStartPre=-", darkos_service.darkos_systemd_unit())


class DarkosServiceControlTests(unittest.TestCase):
    def test_stop_reports_the_pid_it_stopped(self) -> None:
        with patch.object(
            darkos_service, "systemd_service_status", lambda: True
        ), patch.object(
            darkos_service, "systemd_service_pid", lambda: 123
        ), patch.object(darkos_service, "systemd_stop_service", lambda: True):
            result = darkos_service.darkos_service_stop()

        self.assertTrue(result["stopped"])
        self.assertFalse(result["already_stopped"])
        self.assertEqual(result["pid"], 123)

    def test_stop_reports_an_already_stopped_unit(self) -> None:
        with patch.object(
            darkos_service, "systemd_service_status", lambda: False
        ), patch.object(
            darkos_service, "systemd_service_pid", lambda: None
        ), patch.object(darkos_service, "systemd_stop_service", lambda: True):
            result = darkos_service.darkos_service_stop()

        self.assertTrue(result["already_stopped"])

    def test_start_reports_an_already_running_unit(self) -> None:
        # _ensure_unit_installed is stubbed so the test never shells out to
        # sudo, which would really write a unit file on a passwordless-sudo CI box.
        with patch.object(
            darkos_service, "_ensure_unit_installed", lambda: None
        ), patch.object(
            darkos_service, "systemd_service_status", lambda: True
        ), patch.object(
            darkos_service, "systemd_service_pid", lambda: 7
        ), patch.object(darkos_service, "systemd_start_service", lambda: True):
            result = darkos_service.darkos_service_start()

        self.assertTrue(result["already_running"])
        self.assertEqual(result["pid"], 7)

    def test_status_reports_the_configured_proxy_port(self) -> None:
        with patch.object(
            darkos_service, "systemd_service_status", lambda: True
        ), patch.object(
            darkos_service, "systemd_service_pid", lambda: 1
        ), patch.object(
            darkos_service, "systemd_service_start_time", lambda: 0
        ), patch.object(
            darkos_service, "load_config", lambda: {"proxy_port": 9999}
        ):
            status = darkos_service.darkos_service_status()

        self.assertEqual(status["proxyPort"], 9999)


class DarkosTimestampTests(unittest.TestCase):
    def test_parses_a_systemd_timestamp(self) -> None:
        parsed = darkos_service._systemd_timestamp_to_unix(
            "Mon 2026-08-31 20:02:52 EEST"
        )

        self.assertIsInstance(parsed, int)

    def test_returns_none_rather_than_raising_on_unusable_output(self) -> None:
        # Empty for a unit that has never started, and the property is missing
        # entirely on some systemd builds.
        for value in ("", "n/a", "garbage here", "Mon not-a-date 20:02:52"):
            with self.subTest(value=value):
                self.assertIsNone(darkos_service._systemd_timestamp_to_unix(value))


if __name__ == "__main__":
    unittest.main()
