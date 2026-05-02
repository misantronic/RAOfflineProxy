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

    def test_cached_games_status_shows_count_out_of_fifty(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cached_games"
        session.cached_games = [
            type("Game", (), {"title": "One", "game_id": 1})(),
            type("Game", (), {"title": "Two", "game_id": 2})(),
            type("Game", (), {"title": "Three", "game_id": 3})(),
            type("Game", (), {"title": "Four", "game_id": 4})(),
            type("Game", (), {"title": "Five", "game_id": 5})(),
        ]

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=False),
            "CACHED: 5 / 50",
        )

    def test_game_actions_status_includes_cached_unlock_count(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "game_actions"
        session.active_game = type("Game", (), {"game_id": 10701, "title": "Tetris"})()
        session.storage = object()

        original_cached_unlock_count = menu_sdl.cached_unlock_count
        try:
            menu_sdl.cached_unlock_count = lambda _storage, _game_id: 12

            self.assertEqual(
                menu_sdl.MenuSdlSession.status_text(session, running=False),
                "GAME ID: 10701, UNLOCKS: 12",
            )
        finally:
            menu_sdl.cached_unlock_count = original_cached_unlock_count

    def test_game_actions_labels_include_unlock_titles(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "game_actions"
        session.active_game = type("Game", (), {"game_id": 10701, "title": "Tetris"})()
        session.storage = object()

        original_cached_unlock_titles = menu_sdl.cached_unlock_titles
        try:
            menu_sdl.cached_unlock_titles = lambda _storage, _game_id: [
                "First Steps",
                "Commander",
            ]

            self.assertEqual(
                menu_sdl.MenuSdlSession.labels(session, running=False),
                ["Remove cache", "First Steps", "Commander", "Back"],
            )
        finally:
            menu_sdl.cached_unlock_titles = original_cached_unlock_titles

    def test_root_labels_hide_cached_count_and_empty_pending_awards(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.cached_games = [
            type("Game", (), {"title": "Tetris", "game_id": 10701})()
        ]
        session.pending_awards = []
        session.storage = object()

        original_load_config = menu_sdl.load_config
        original_autostart_supported = menu_sdl.autostart_supported
        original_is_logged_in = menu_sdl.MenuSdlSession.is_logged_in
        try:
            menu_sdl.load_config = lambda: {}
            menu_sdl.autostart_supported = lambda _config: False
            menu_sdl.MenuSdlSession.is_logged_in = lambda self, _config=None: True

            labels = menu_sdl.MenuSdlSession.labels(session, running=False)

            self.assertIn("Cached games", labels)
            self.assertNotIn("Cached games (1)", labels)
            self.assertNotIn("Pending awards (0)", labels)
        finally:
            menu_sdl.load_config = original_load_config
            menu_sdl.autostart_supported = original_autostart_supported
            menu_sdl.MenuSdlSession.is_logged_in = original_is_logged_in

    def test_activate_selected_opens_cached_games_without_counter_label(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.selected_index = 1
        session.cached_games = []
        session.pending_awards = []
        session.running = True
        session.storage = object()

        original_current_labels = menu_sdl.MenuSdlSession.current_labels
        original_proxy_running = menu_sdl.MenuSdlSession.proxy_running
        original_save_view_position = menu_sdl.MenuSdlSession.save_view_position
        original_restore_view_position = menu_sdl.MenuSdlSession.restore_view_position
        original_refresh_cached_games = menu_sdl.MenuSdlSession.refresh_cached_games
        original_load_config = menu_sdl.load_config
        try:
            menu_sdl.MenuSdlSession.current_labels = lambda self, running=None: [
                "Start proxy",
                "Cached games",
                "Uninstall",
                "Exit Menu",
            ]
            menu_sdl.MenuSdlSession.proxy_running = lambda self: False
            menu_sdl.MenuSdlSession.save_view_position = lambda self, key: None
            menu_sdl.MenuSdlSession.restore_view_position = lambda self, key: None
            menu_sdl.MenuSdlSession.refresh_cached_games = lambda self: None
            menu_sdl.load_config = lambda: {}

            menu_sdl.MenuSdlSession.activate_selected(session)

            self.assertEqual(session.view, "cached_games")
        finally:
            menu_sdl.MenuSdlSession.current_labels = original_current_labels
            menu_sdl.MenuSdlSession.proxy_running = original_proxy_running
            menu_sdl.MenuSdlSession.save_view_position = original_save_view_position
            menu_sdl.MenuSdlSession.restore_view_position = (
                original_restore_view_position
            )
            menu_sdl.MenuSdlSession.refresh_cached_games = original_refresh_cached_games
            menu_sdl.load_config = original_load_config

    def test_activate_game_actions_selected_uses_back_index_after_unlock_titles(
        self,
    ) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.active_game = type("Game", (), {"game_id": 10701, "title": "Tetris"})()
        session.selected_index = 3
        session.refresh_cached_games = lambda: None
        session.restore_view_position = lambda _view: None

        original_game_actions_unlock_titles = (
            menu_sdl.MenuSdlSession.game_actions_unlock_titles
        )
        try:
            menu_sdl.MenuSdlSession.game_actions_unlock_titles = lambda self: [
                "First Steps",
                "Commander",
            ]

            menu_sdl.MenuSdlSession.activate_game_actions_selected(session)

            self.assertIsNone(session.active_game)
            self.assertEqual(session.view, "cached_games")
        finally:
            menu_sdl.MenuSdlSession.game_actions_unlock_titles = (
                original_game_actions_unlock_titles
            )

    def test_render_home_logo_only_draws_on_main_view(self) -> None:
        fake_logo = type(
            "Logo",
            (),
            {"get_rect": lambda self, **kwargs: kwargs},
        )()

        main_session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        main_session.view = "main"
        main_session.width = 640
        main_session.logo_surface = fake_logo
        main_surface_calls = []
        main_session.surface = type(
            "Surface",
            (),
            {
                "blit": lambda self, surface, rect: main_surface_calls.append(
                    (surface, rect)
                )
            },
        )()
        main_session.load_logo_surface = lambda: None
        main_session.pygame = object()

        menu_sdl.MenuSdlSession.render_home_logo(main_session)

        self.assertEqual(len(main_surface_calls), 1)

        cached_session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        cached_session.view = "cached_games"
        cached_session.width = 640
        cached_session.logo_surface = fake_logo
        cached_session.surface = type(
            "Surface",
            (),
            {
                "blit": lambda self, surface, rect: (_ for _ in ()).throw(
                    AssertionError("should not blit")
                )
            },
        )()

        menu_sdl.MenuSdlSession.render_home_logo(cached_session)

    def test_navigate_uses_fast_interval_after_shorter_hold(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.selected_index = 0
        session.scroll_offset = 0
        session.last_navigation_at = 100.0
        session.last_navigation_delta = 1
        session.navigation_hold_started_at = 100.0
        session.current_labels = lambda running=None: ["One", "Two", "Three"]
        session.item_start_y = lambda: 100
        session.item_gap = lambda: 28
        session.ensure_selection_visible = lambda items, start_y, gap: None

        original_monotonic = menu_sdl.time.monotonic
        try:
            menu_sdl.time.monotonic = lambda: 100.23

            menu_sdl.MenuSdlSession.navigate(session, 1)

            self.assertEqual(session.selected_index, 1)
        finally:
            menu_sdl.time.monotonic = original_monotonic


if __name__ == "__main__":
    unittest.main()
