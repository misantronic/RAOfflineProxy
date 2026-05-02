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

        session.restore_view_position("cached_games")

        self.assertEqual(session.selected_index, 5)
        self.assertEqual(session.scroll_offset, 2)

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
        session.active_game_unlock_game_id = 10701
        session.active_game_unlock_count_cached = 12

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=False),
            "GAME ID: 10701, UNLOCKS: 12",
        )

    def test_game_actions_labels_include_unlock_titles(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "game_actions"
        session.active_game = type("Game", (), {"game_id": 10701, "title": "Tetris"})()
        session.active_game_unlock_game_id = 10701
        session.active_game_unlock_titles_cached = ["First Steps", "Commander"]

        self.assertEqual(
            menu_sdl.MenuSdlSession.labels(session, running=False),
            ["Remove cache", "First Steps", "Commander", "Back"],
        )

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

    def test_navigate_advances_immediately_on_each_call(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.selected_index = 0
        session.scroll_offset = 0
        session.current_labels = lambda running=None: ["One", "Two", "Three"]
        session.item_start_y = lambda: 100
        session.item_gap = lambda: 28
        session.ensure_selection_visible = lambda items, start_y, gap: None

        menu_sdl.MenuSdlSession.navigate(session, 1)
        menu_sdl.MenuSdlSession.navigate(session, 1)

        self.assertEqual(session.selected_index, 2)

    def test_pending_awards_labels_do_not_include_blank_spacer_rows(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "pending_awards"
        session.pending_awards = [
            type(
                "Award",
                (),
                {
                    "game_title": "Tetris",
                    "summary_text": "First Line | 2026-01-01 12:00 | 5pts.",
                },
            )(),
            type(
                "Award",
                (),
                {
                    "game_title": "Mega Man",
                    "summary_text": "Boss Down | 2026-01-01 12:05 | 10pts.",
                },
            )(),
        ]

        self.assertEqual(
            menu_sdl.MenuSdlSession.labels(session, running=False),
            [
                "Tetris",
                "First Line | 2026-01-01 12:00 | 5pts.",
                "Mega Man",
                "Boss Down | 2026-01-01 12:05 | 10pts.",
                "Back",
            ],
        )

    def test_pending_awards_uses_title_font_for_game_rows(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "pending_awards"
        session.pending_awards = [object(), object()]
        session.title_font = object()
        session.item_font = object()

        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 0),
            session.title_font,
        )
        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 1),
            session.item_font,
        )
        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 2),
            session.title_font,
        )
        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 4),
            session.item_font,
        )

    def test_activate_pending_awards_selected_uses_two_rows_per_award(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.pending_awards = [object(), object()]
        session.selected_index = 2
        session.active_pending_award = None
        session.save_view_position = lambda _view: None
        session.reset_selection = lambda: None

        menu_sdl.MenuSdlSession.activate_pending_awards_selected(session)

        self.assertIs(session.active_pending_award, session.pending_awards[1])
        self.assertEqual(session.view, "pending_award_actions")

    def test_is_logged_in_uses_cached_main_menu_state_without_config(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.main_logged_in = True
        session.refresh_main_menu_state = lambda force=False: None

        self.assertTrue(menu_sdl.MenuSdlSession.is_logged_in(session))

    def test_game_actions_unlock_titles_uses_cached_values_for_active_game(
        self,
    ) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.active_game = type("Game", (), {"game_id": 42})()
        session.active_game_unlock_game_id = 42
        session.active_game_unlock_titles_cached = ["First Steps"]
        session.refresh_active_game_unlocks = lambda: (_ for _ in ()).throw(
            AssertionError("should not refresh unlocks")
        )

        self.assertEqual(
            menu_sdl.MenuSdlSession.game_actions_unlock_titles(session),
            ["First Steps"],
        )

    def test_handle_events_ignores_joystick_events(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.running = True
        session.handle_key = lambda _key: (_ for _ in ()).throw(
            AssertionError("keyboard handler should not run")
        )
        session.navigate = lambda _delta: (_ for _ in ()).throw(
            AssertionError("joystick navigation should not run")
        )

        class FakePygame:
            QUIT = 1
            KEYDOWN = 2
            JOYHATMOTION = 3
            JOYBUTTONDOWN = 4

            class event:
                @staticmethod
                def get():
                    return [
                        type(
                            "Event",
                            (),
                            {"type": FakePygame.JOYHATMOTION, "value": (1, 0)},
                        )(),
                        type(
                            "Event", (), {"type": FakePygame.JOYBUTTONDOWN, "button": 0}
                        )(),
                    ]

        session.pygame = FakePygame

        menu_sdl.MenuSdlSession.handle_events(session)

        self.assertTrue(session.running)


if __name__ == "__main__":
    unittest.main()
