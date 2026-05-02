import unittest

from linux.raofflineproxy import menu_sdl


class DummyFont:
    def __init__(self, height: int) -> None:
        self._height = height

    def get_height(self) -> int:
        return self._height


class MenuLayoutTests(unittest.TestCase):
    def test_ensure_selection_visible_uses_item_list_signature(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cached_games"
        session.cached_games = [type("Game", (), {"title": "Game One", "game_id": 1})()]
        session.selected_index = 2
        session.scroll_offset = 0
        session.height = 480
        session.message = None
        session.item_font = DummyFont(22)

        items = ["Add ROM", "Game One", "Clear cache", "Back"]
        start_y = 100
        gap = 28

        session.ensure_selection_visible(items, start_y, gap)

        self.assertGreaterEqual(session.scroll_offset, 0)

    def test_restore_view_position_restores_selection_and_scroll(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view_positions = {"cached_games": (5, 2)}
        session.selected_index = 0
        session.scroll_offset = 0
        session.last_navigation_delta = 1
        session.navigation_hold_started_at = 10.0

        session.restore_view_position("cached_games")

        self.assertEqual(session.selected_index, 5)
        self.assertEqual(session.scroll_offset, 2)
        self.assertEqual(session.last_navigation_delta, 0)
        self.assertEqual(session.navigation_hold_started_at, 0.0)

    def test_bottom_hint_points_to_system_login(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.storage = type(
            "Storage",
            (),
            {"load_login_credentials": lambda self, _config=None: None},
        )()

        self.assertEqual(
            menu_sdl.MenuSdlSession.bottom_hint_text(session),
            "Login to RetroAchievements in system settings.",
        )

    def test_status_reports_logged_in_when_retroarch_credentials_exist(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.storage = type(
            "Storage",
            (),
            {
                "load_login_credentials": lambda self, _config=None: {
                    "user": "misantronic",
                    "token": "token",
                }
            },
        )()

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=True),
            "PROXY: RUNNING, STATUS: LOGGED IN",
        )


if __name__ == "__main__":
    unittest.main()
