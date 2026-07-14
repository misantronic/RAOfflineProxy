import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import knulli_service, menu_sdl


class KnulliServiceModeDetectionTests(unittest.TestCase):
    def test_inactive_without_services_binary(self) -> None:
        with mock.patch.object(knulli_service.shutil, "which", return_value=None):
            self.assertFalse(knulli_service.service_mode_active())

    def test_inactive_with_binary_but_no_service_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "raofflineproxy"
            with (
                mock.patch.object(
                    knulli_service.shutil, "which", return_value="/usr/bin/knulli-services"
                ),
                mock.patch.object(knulli_service, "USER_SERVICE_FILE", missing),
                mock.patch.object(knulli_service, "SYSTEM_SERVICE_FILES", (missing,)),
            ):
                self.assertFalse(knulli_service.service_mode_active())

    def test_active_with_binary_and_service_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            service_file = Path(temp_dir) / "raofflineproxy"
            service_file.write_text("#!/bin/bash\n")
            with (
                mock.patch.object(
                    knulli_service.shutil, "which", return_value="/usr/bin/knulli-services"
                ),
                mock.patch.object(knulli_service, "USER_SERVICE_FILE", service_file),
                mock.patch.object(knulli_service, "SYSTEM_SERVICE_FILES", ()),
            ):
                self.assertTrue(knulli_service.service_mode_active())


class KnulliServiceModeMenuTests(unittest.TestCase):
    def _make_main_session(self, service_mode: bool) -> menu_sdl.MenuSdlSession:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.cached_games = []
        session.pending_awards = []
        session.main_logged_in = True
        session.main_running = False
        session.main_online = True
        session.main_service_mode = service_mode
        session.main_autostart_supported = not service_mode or True
        session.main_autostart_enabled = False
        return session

    def _labels(self, session: menu_sdl.MenuSdlSession) -> list[str]:
        with (
            mock.patch.object(
                menu_sdl.MenuSdlSession, "refresh_main_menu_state", lambda self, force=False: None
            ),
            mock.patch.object(
                menu_sdl.MenuSdlSession, "is_knulli_platform", lambda self: True
            ),
            mock.patch.object(menu_sdl, "running_on_muos", lambda: False),
        ):
            return menu_sdl.MenuSdlSession.labels(session, False)

    def test_legacy_mode_keeps_start_autostart_and_uninstall(self) -> None:
        session = self._make_main_session(service_mode=False)
        session.main_autostart_supported = True
        labels = self._labels(session)
        self.assertIn("Start proxy", labels)
        self.assertIn("Enable autostart", labels)
        self.assertIn("Uninstall", labels)

    def test_service_mode_hides_lifecycle_and_uninstall_entries(self) -> None:
        session = self._make_main_session(service_mode=True)
        labels = self._labels(session)
        self.assertNotIn("Start proxy", labels)
        self.assertNotIn("Stop proxy", labels)
        self.assertNotIn("Enable autostart", labels)
        self.assertNotIn("Disable autostart", labels)
        self.assertNotIn("Uninstall", labels)
        self.assertIn("Exit Menu", labels)

    def test_legacy_status_text_unchanged(self) -> None:
        session = self._make_main_session(service_mode=False)
        with mock.patch.object(
            menu_sdl.MenuSdlSession, "refresh_main_menu_state", lambda self, force=False: None
        ):
            status = menu_sdl.MenuSdlSession.status_text(session, running=True)
        self.assertEqual(status, "PROXY: RUNNING ONLINE")

    def test_service_status_text_mentions_service(self) -> None:
        session = self._make_main_session(service_mode=True)
        with mock.patch.object(
            menu_sdl.MenuSdlSession, "refresh_main_menu_state", lambda self, force=False: None
        ):
            status = menu_sdl.MenuSdlSession.status_text(session, running=True)
        self.assertEqual(status, "KNULLI SERVICE: RUNNING ONLINE")

    def test_legacy_start_proxy_uses_inline_path(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        calls = []
        with (
            mock.patch.object(menu_sdl, "service_mode_active", return_value=False),
            mock.patch.object(
                menu_sdl, "start_proxy_inline", lambda: calls.append("inline")
            ),
            mock.patch.object(
                menu_sdl, "start_service", lambda: calls.append("service")
            ),
            mock.patch.object(
                menu_sdl.MenuSdlSession,
                "refresh_main_menu_state",
                lambda self, force=False: None,
            ),
            mock.patch.object(
                menu_sdl.MenuSdlSession,
                "maybe_offer_smart_cache",
                lambda self: None,
            ),
        ):
            menu_sdl.MenuSdlSession.start_proxy(session)
        self.assertEqual(calls, ["inline"])

    def test_service_start_proxy_uses_service(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        calls = []
        with (
            mock.patch.object(menu_sdl, "service_mode_active", return_value=True),
            mock.patch.object(
                menu_sdl, "start_proxy_inline", lambda: calls.append("inline")
            ),
            mock.patch.object(
                menu_sdl, "start_service", lambda: calls.append("service")
            ),
            mock.patch.object(
                menu_sdl.MenuSdlSession,
                "refresh_main_menu_state",
                lambda self, force=False: None,
            ),
            mock.patch.object(
                menu_sdl.MenuSdlSession,
                "maybe_offer_smart_cache",
                lambda self: None,
            ),
        ):
            menu_sdl.MenuSdlSession.start_proxy(session)
        self.assertEqual(calls, ["service"])


if __name__ == "__main__":
    unittest.main()
