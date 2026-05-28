import unittest
from pathlib import Path
from unittest.mock import patch

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

    def test_status_reports_proxy_and_connectivity_when_credentials_exist(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.main_logged_in = True
        session.main_online = True
        session.refresh_main_menu_state = lambda force=False: None

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=True),
            "PROXY: RUNNING ONLINE",
        )

    def test_status_reports_login_required_when_credentials_missing(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.main_logged_in = False
        session.main_online = False
        session.refresh_main_menu_state = lambda force=False: None

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=False),
            "PROXY: STOPPED OFFLINE, LOGIN REQUIRED",
        )

    def test_cached_games_status_shows_count_out_of_max(self) -> None:
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
            "CACHED: 5 / 100",
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

    def test_file_browser_labels_show_add_folder_at_top(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "file_browser"
        session.browser_dir = type("PathLike", (), {"parent": "/root"})()
        session.browser_entries = [
            type("Entry", (), {"name": "game1.gba", "is_file": lambda self: True})(),
            type("Entry", (), {"name": "Subdir", "is_file": lambda self: False})(),
        ]
        session.browser_has_cacheable_files = lambda: True

        original_resolve_rom_root = menu_sdl.resolve_rom_root
        original_load_config = menu_sdl.load_config
        try:
            menu_sdl.resolve_rom_root = lambda _config: "/roms"
            menu_sdl.load_config = lambda: {}

            self.assertEqual(
                menu_sdl.MenuSdlSession.labels(session, running=False),
                ["Add folder", "..", "game1.gba", "Subdir", "Cancel"],
            )
        finally:
            menu_sdl.resolve_rom_root = original_resolve_rom_root
            menu_sdl.load_config = original_load_config

    def test_root_labels_show_cached_count_and_hide_empty_pending_awards(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.cached_games = [
            type("Game", (), {"title": "Tetris", "game_id": 10701})()
        ]
        session.pending_awards = []
        session.storage = object()

        original_load_config = menu_sdl.load_config
        original_autostart_supported = menu_sdl.autostart_supported
        original_online_check = menu_sdl.online_check
        original_is_logged_in = menu_sdl.MenuSdlSession.is_logged_in
        try:
            menu_sdl.load_config = lambda: {}
            menu_sdl.autostart_supported = lambda _config: False
            menu_sdl.online_check = lambda _config: True
            menu_sdl.MenuSdlSession.is_logged_in = lambda self, _config=None: True

            labels = menu_sdl.MenuSdlSession.labels(session, running=False)

            self.assertIn("Cached games (1)", labels)
            self.assertNotIn("Pending awards (0)", labels)
        finally:
            menu_sdl.load_config = original_load_config
            menu_sdl.autostart_supported = original_autostart_supported
            menu_sdl.online_check = original_online_check
            menu_sdl.MenuSdlSession.is_logged_in = original_is_logged_in

    def test_main_labels_place_autostart_below_cached_games(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.cached_games = [
            type("Game", (), {"title": "Tetris", "game_id": 10701})()
        ]
        session.pending_awards = []
        session.storage = object()

        original_load_config = menu_sdl.load_config
        original_autostart_supported = menu_sdl.autostart_supported
        original_online_check = menu_sdl.online_check
        original_is_logged_in = menu_sdl.MenuSdlSession.is_logged_in
        original_autostart_enabled = menu_sdl.autostart_enabled
        try:
            menu_sdl.load_config = lambda: {}
            menu_sdl.autostart_supported = lambda _config: True
            menu_sdl.autostart_enabled = lambda _config: False
            menu_sdl.online_check = lambda _config: True
            menu_sdl.MenuSdlSession.is_logged_in = lambda self, _config=None: True

            labels = menu_sdl.MenuSdlSession.labels(session, running=False)

            self.assertLess(labels.index("Cached games (1)"), labels.index("Enable autostart"))
        finally:
            menu_sdl.load_config = original_load_config
            menu_sdl.autostart_supported = original_autostart_supported
            menu_sdl.autostart_enabled = original_autostart_enabled
            menu_sdl.online_check = original_online_check
            menu_sdl.MenuSdlSession.is_logged_in = original_is_logged_in

    def test_activate_selected_opens_cached_games_with_counter_label(self) -> None:
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
                "Cached games (0)",
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

    def test_refresh_main_menu_state_checks_update_only_on_force(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.main_state_refreshed_at = 0.0
        session.main_update_available = False
        session.main_update_version = None
        session.main_update_asset_url = None
        session.main_update_dialog_seen = False

        update_calls = []

        with (
            patch.object(menu_sdl, "load_config", return_value={}),
            patch.object(
                menu_sdl.MenuSdlSession,
                "read_proxy_running",
                return_value=False,
            ),
            patch.object(menu_sdl, "online_check", return_value=True),
            patch.object(
                menu_sdl.MenuSdlSession,
                "is_logged_in",
                return_value=True,
            ),
            patch.object(menu_sdl, "autostart_supported", return_value=False),
            patch.object(menu_sdl, "autostart_enabled", return_value=False),
            patch.object(
                menu_sdl,
                "update_status",
                side_effect=lambda platform: update_calls.append(platform)
                or type(
                    "Update",
                    (),
                    {
                        "update_available": False,
                        "latest_version": None,
                        "asset_url": None,
                    },
                )(),
            ),
            patch.object(menu_sdl.time, "monotonic", side_effect=[100.0, 101.5]),
        ):
            menu_sdl.MenuSdlSession.refresh_main_menu_state(session, force=True)
            menu_sdl.MenuSdlSession.refresh_main_menu_state(session, force=False)

        self.assertEqual(update_calls, ["knulli"])

    def test_refresh_main_menu_state_rechecks_update_when_forced_again(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.main_state_refreshed_at = 0.0
        session.main_update_available = False
        session.main_update_version = None
        session.main_update_asset_url = None
        session.main_update_dialog_seen = False

        update_calls = []

        with (
            patch.object(menu_sdl, "load_config", return_value={}),
            patch.object(
                menu_sdl.MenuSdlSession,
                "read_proxy_running",
                return_value=False,
            ),
            patch.object(menu_sdl, "online_check", return_value=True),
            patch.object(
                menu_sdl.MenuSdlSession,
                "is_logged_in",
                return_value=True,
            ),
            patch.object(menu_sdl, "autostart_supported", return_value=False),
            patch.object(menu_sdl, "autostart_enabled", return_value=False),
            patch.object(
                menu_sdl,
                "update_status",
                side_effect=lambda platform: update_calls.append(platform)
                or type(
                    "Update",
                    (),
                    {
                        "update_available": False,
                        "latest_version": None,
                        "asset_url": None,
                    },
                )(),
            ),
            patch.object(menu_sdl.time, "monotonic", side_effect=[100.0, 101.5]),
        ):
            menu_sdl.MenuSdlSession.refresh_main_menu_state(session, force=True)
            menu_sdl.MenuSdlSession.refresh_main_menu_state(session, force=True)

        self.assertEqual(update_calls, ["knulli", "knulli"])

    def test_smart_cache_prompt_labels(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "smart_cache_prompt"

        self.assertEqual(
            menu_sdl.MenuSdlSession.labels(session, running=False),
            ["Start Smart Cache", "Skip"],
        )

    def test_maybe_offer_smart_cache_opens_prompt_view(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.storage = object()
        session.config_data = {}
        session.view = "main"
        session.selected_index = 0
        session.scroll_offset = 0
        session.save_view_position = lambda _view=None: None
        session.reset_selection = lambda: None
        session.refresh_main_menu_state = lambda force=False: None
        session.refresh_cached_games = lambda: setattr(session, "cached_games", [])
        session.main_online = True
        session.main_logged_in = True

        original_should_offer_smart_cache = menu_sdl.should_offer_smart_cache
        try:
            menu_sdl.should_offer_smart_cache = lambda _storage, _config, **kwargs: (
                type(
                    "Status",
                    (),
                    {"found_history": True, "total_candidates": 7},
                )()
            )

            menu_sdl.MenuSdlSession.maybe_offer_smart_cache(session)

            self.assertEqual(session.view, "smart_cache_prompt")
            self.assertTrue(session.smart_cache_prompt_available)
            self.assertEqual(session.smart_cache_prompt_count, 7)
        finally:
            menu_sdl.should_offer_smart_cache = original_should_offer_smart_cache

    def test_status_text_for_smart_cache_prompt(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "smart_cache_prompt"
        session.smart_cache_prompt_count = 9

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=True),
            "SMART CACHE: 9 recent games found",
        )

    def test_start_proxy_opens_smart_cache_prompt_when_eligible(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.message = None
        session.refresh_main_menu_state = lambda force=False: None
        session.refresh_cached_games = lambda: setattr(session, "cached_games", [])
        session.maybe_offer_smart_cache = lambda: setattr(
            session, "view", "smart_cache_prompt"
        )

        original_start_proxy_inline = menu_sdl.start_proxy_inline
        try:
            menu_sdl.start_proxy_inline = lambda: None

            menu_sdl.MenuSdlSession.start_proxy(session)

            self.assertEqual(session.view, "smart_cache_prompt")
            self.assertIsNone(session.message)
        finally:
            menu_sdl.start_proxy_inline = original_start_proxy_inline

    def test_maybe_offer_smart_cache_skips_auto_prompt_when_cached_games_exist(
        self,
    ) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "main"
        session.smart_cache_prompt_available = True
        session.smart_cache_prompt_count = 7
        session.storage = object()
        session.config_data = {}
        session.refresh_main_menu_state = lambda force=False: None
        session.refresh_cached_games = lambda: setattr(session, "cached_games", [object()])

        original_should_offer_smart_cache = menu_sdl.should_offer_smart_cache
        try:
            menu_sdl.should_offer_smart_cache = lambda *_args, **_kwargs: (_ for _ in ()).throw(
                AssertionError("should_offer_smart_cache should not be called when cache is non-empty")
            )

            menu_sdl.MenuSdlSession.maybe_offer_smart_cache(session)

            self.assertEqual(session.view, "main")
            self.assertFalse(session.smart_cache_prompt_available)
            self.assertEqual(session.smart_cache_prompt_count, 0)
        finally:
            menu_sdl.should_offer_smart_cache = original_should_offer_smart_cache

    def test_start_smart_cache_opens_cache_progress_view(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.smart_cache_in_progress = False
        session.config_data = {}
        session.storage = object()
        session.view = "smart_cache_prompt"
        session.reset_selection = lambda: setattr(session, "reset_called", True)

        original_load_content_history_paths = menu_sdl.load_content_history_paths
        original_run_smart_cache = menu_sdl.run_smart_cache
        original_thread = menu_sdl.threading.Thread
        try:
            menu_sdl.load_content_history_paths = lambda _config: [
                Path("/roms/tetris.gb"),
                Path("/roms/zelda.gbc"),
            ]
            menu_sdl.run_smart_cache = lambda *_args, **_kwargs: (_ for _ in ()).throw(
                AssertionError("worker should not run in this test")
            )

            class FakeThread:
                def __init__(self, target, daemon):
                    self.target = target
                    self.daemon = daemon

                def start(self):
                    setattr(session, "thread_started", True)

            menu_sdl.threading.Thread = FakeThread

            menu_sdl.MenuSdlSession.start_smart_cache(session)

            self.assertEqual(session.view, "cache_progress")
            self.assertEqual(session.cache_progress_title, "Smart Cache")
            self.assertEqual(session.cache_progress_text, "Caching 1/2: tetris.gb")
            self.assertEqual(session.cache_return_view, "main")
            self.assertTrue(session.thread_started)
        finally:
            menu_sdl.load_content_history_paths = original_load_content_history_paths
            menu_sdl.run_smart_cache = original_run_smart_cache
            menu_sdl.threading.Thread = original_thread

    def test_update_smart_cache_progress_uses_cache_progress_status_line(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)

        progress = type(
            "Progress",
            (),
            {"scanned": 2, "total": 5, "current_label": "Zelda.gbc"},
        )()

        menu_sdl.MenuSdlSession.update_smart_cache_progress(session, progress)

        self.assertEqual(session.cache_progress_text, "Caching 2/5: Zelda.gbc")

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
                    "detail_text": "First Line | 2026-01-01 12:00 | 5pts.",
                },
            )(),
            type(
                "Award",
                (),
                {
                    "game_title": "Mega Man",
                    "detail_text": "Boss Down | 2026-01-01 12:05 | 10pts.",
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

    def test_file_browser_item_positions_add_gap_after_add_folder(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "file_browser"
        session.cached_games = []
        session.browser_has_cacheable_files = lambda: True

        positions = menu_sdl.MenuSdlSession.item_positions(
            session,
            ["Add folder", "..", "game.gba", "Cancel"],
            100,
            30,
        )

        self.assertEqual(positions, [100, 144, 174, 204])

    def test_pending_awards_uses_item_font_for_game_rows(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "pending_awards"
        session.pending_awards = [object(), object()]
        session.title_font = object()
        session.item_font = object()

        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 0),
            session.item_font,
        )
        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 1),
            session.item_font,
        )
        self.assertIs(
            menu_sdl.MenuSdlSession.item_font_for_index(session, 2),
            session.item_font,
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

    def test_activate_file_browser_selected_starts_folder_cache_from_top_action(
        self,
    ) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.browser_dir = Path("/roms/current")
        session.browser_entries = []
        session.selected_index = 0
        session.browser_has_cacheable_files = lambda: True
        session.start_folder_cache_for_browser_dir = lambda: setattr(
            session, "started_folder_cache", True
        )

        original_resolve_rom_root = menu_sdl.resolve_rom_root
        original_load_config = menu_sdl.load_config
        try:
            menu_sdl.resolve_rom_root = lambda _config: Path("/roms")
            menu_sdl.load_config = lambda: {}

            menu_sdl.MenuSdlSession.activate_file_browser_selected(session)

            self.assertTrue(session.started_folder_cache)
        finally:
            menu_sdl.resolve_rom_root = original_resolve_rom_root
            menu_sdl.load_config = original_load_config

    def test_activate_file_browser_selected_starts_single_rom_cache(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        browser_dir = Path("/roms")
        rom_entry = Path("/roms/game.gba")
        session.browser_dir = browser_dir
        session.browser_entries = [rom_entry]
        session.selected_index = 0
        session.browser_has_cacheable_files = lambda: False
        session.start_single_rom_cache = lambda path: setattr(
            session, "cached_path", path
        )

        original_resolve_rom_root = menu_sdl.resolve_rom_root
        original_load_config = menu_sdl.load_config
        try:
            menu_sdl.resolve_rom_root = lambda _config: browser_dir
            menu_sdl.load_config = lambda: {}

            menu_sdl.MenuSdlSession.activate_file_browser_selected(session)

            self.assertEqual(session.cached_path, rom_entry)
        finally:
            menu_sdl.resolve_rom_root = original_resolve_rom_root
            menu_sdl.load_config = original_load_config

    def test_cached_games_labels_include_start_smart_cache_after_add_rom(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cached_games"
        session.cached_games = [type("Game", (), {"title": "Tetris", "game_id": 1})()]

        self.assertEqual(
            menu_sdl.MenuSdlSession.labels(session, running=False),
            ["Add ROM", "Start Smart Cache", "Tetris", "Clear cache", "Back"],
        )

    def test_activate_cached_games_selected_starts_smart_cache_from_second_item(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cached_games"
        session.cached_games = []
        session.selected_index = 1
        session.current_labels = lambda: [
            "Add ROM",
            "Start Smart Cache",
            "Clear cache",
            "Back",
        ]
        session.start_smart_cache = lambda: setattr(session, "smart_cache_started", True)

        menu_sdl.MenuSdlSession.activate_cached_games_selected(session)

        self.assertTrue(session.smart_cache_started)

    def test_finish_cache_progress_returns_to_file_browser_for_single_rom(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.cache_result = ("Cached game.gba", 0.0)
        session.cache_progress_text = None
        session.cache_progress_title = "Caching: game.gba"
        session.cache_return_view = "file_browser"
        session.cache_return_browser_dir = Path("/roms")
        session.cache_return_browser_restore = True
        session.refresh_cached_games = lambda: setattr(session, "refreshed", True)
        session.set_browser_dir = lambda path, restore=False: setattr(
            session, "browser_restore", (path, restore)
        )

        menu_sdl.MenuSdlSession.finish_cache_progress(session)

        self.assertEqual(session.view, "file_browser")
        self.assertEqual(session.browser_restore, (Path("/roms"), True))
        self.assertIsNone(session.cache_progress_title)

    def test_finish_cache_progress_returns_to_cached_games_for_folder_cache(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.cache_result = ("Folder cache complete", 0.0)
        session.cache_progress_text = None
        session.cache_progress_title = "Caching: current"
        session.cache_completed = True
        session.cache_completion_message = "Scanned 4, cached 3, skipped 1"
        session.cache_return_view = "cached_games"
        session.cache_return_browser_dir = Path("/roms")
        session.cache_return_browser_restore = False
        session.refresh_cached_games = lambda: setattr(session, "refreshed", True)
        session.restore_view_position = lambda view: setattr(session, "restored", view)

        menu_sdl.MenuSdlSession.finish_cache_progress(session)

        self.assertEqual(session.view, "cached_games")
        self.assertEqual(session.restored, "cached_games")
        self.assertIsNone(session.cache_progress_title)
        self.assertIsNone(session.cache_completion_message)

    def test_finish_cache_progress_returns_to_main_for_smart_cache(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.cache_result = None
        session.cache_progress_text = None
        session.cache_progress_title = "Smart Cache"
        session.cache_completed = True
        session.cache_completion_message = "Scanned 4, cached 3, skipped 1"
        session.cache_return_view = "main"
        session.cache_return_browser_dir = None
        session.cache_return_browser_restore = False
        session.refresh_cached_games = lambda: setattr(session, "refreshed", True)
        session.refresh_main_menu_state = lambda force=False: setattr(
            session, "main_refreshed", force
        )
        session.restore_view_position = lambda view: setattr(session, "restored", view)

        menu_sdl.MenuSdlSession.finish_cache_progress(session)

        self.assertEqual(session.view, "main")
        self.assertTrue(session.main_refreshed)
        self.assertEqual(session.restored, "main")

    def test_cache_progress_uses_static_title_and_preparing_status(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cache_progress"
        session.cache_progress_title = "Caching: Pokemon"
        session.cache_progress_text = "Preparing cache..."

        self.assertEqual(
            menu_sdl.MenuSdlSession.title_for_view(session),
            "Caching: Pokemon",
        )
        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=False),
            "Preparing cache...",
        )

    def test_update_cache_progress_uses_current_item_status_line(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)

        progress = type(
            "Progress",
            (),
            {"scanned": 1, "total": 3, "current_label": "Pokemon Red"},
        )()

        menu_sdl.MenuSdlSession.update_cache_progress(session, progress)

        self.assertEqual(
            session.cache_progress_text,
            "Caching 1/3: Pokemon Red",
        )

    def test_cache_progress_labels_show_back_when_complete(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cache_progress"
        session.cache_completed = True

        self.assertEqual(
            menu_sdl.MenuSdlSession.labels(session, running=False),
            ["Back"],
        )

    def test_cache_progress_labels_show_abort_while_running(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cache_progress"
        session.cache_completed = False

        self.assertEqual(
            menu_sdl.MenuSdlSession.labels(session, running=False),
            ["Abort"],
        )

    def test_cache_progress_status_uses_completion_message_when_done(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cache_progress"
        session.cache_completed = True
        session.cache_completion_message = "Scanned 4, cached 3, skipped 1"

        self.assertEqual(
            menu_sdl.MenuSdlSession.status_text(session, running=False),
            "Scanned 4, cached 3, skipped 1",
        )

    def test_activate_selected_finishes_cache_progress_when_back_selected(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cache_progress"
        session.cache_completed = True
        session.selected_index = 0
        session.finish_cache_progress = lambda: setattr(session, "finished", True)

        menu_sdl.MenuSdlSession.activate_selected(session)

        self.assertTrue(session.finished)

    def test_activate_selected_aborts_cache_progress_when_running(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "cache_progress"
        session.cache_completed = False
        session.selected_index = 0
        session.abort_cache_progress = lambda: setattr(session, "aborted", True)

        menu_sdl.MenuSdlSession.activate_selected(session)

        self.assertTrue(session.aborted)

    def test_abort_cache_progress_sets_abort_state(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.cache_completed = False
        session.cache_abort_requested = False
        session.cache_progress_text = "Caching 1/4: Tetris"

        menu_sdl.MenuSdlSession.abort_cache_progress(session)

        self.assertTrue(session.cache_abort_requested)
        self.assertEqual(session.cache_progress_text, "Aborting...")

    def test_start_single_rom_cache_shows_abort_menu_while_running(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.save_browser_position = lambda: None
        session.browser_dir = Path("/roms")

        original_load_config = menu_sdl.load_config
        original_add_rom_to_cache = menu_sdl.add_rom_to_cache
        original_thread = menu_sdl.threading.Thread
        try:
            menu_sdl.load_config = lambda: {}
            menu_sdl.add_rom_to_cache = lambda *_args, **_kwargs: (_ for _ in ()).throw(
                AssertionError("worker should not run in this test")
            )

            class FakeThread:
                def __init__(self, target, daemon):
                    self.target = target
                    self.daemon = daemon

                def start(self):
                    setattr(session, "thread_started", True)

            menu_sdl.threading.Thread = FakeThread

            menu_sdl.MenuSdlSession.start_single_rom_cache(
                session,
                Path("/roms/game.gba"),
            )

            self.assertEqual(session.view, "cache_progress")
            self.assertEqual(
                menu_sdl.MenuSdlSession.labels(session, running=False),
                ["Abort"],
            )
        finally:
            menu_sdl.load_config = original_load_config
            menu_sdl.add_rom_to_cache = original_add_rom_to_cache
            menu_sdl.threading.Thread = original_thread

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

    def test_current_achievement_preview_surface_uses_selected_unlock(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "game_actions"
        session.selected_index = 2
        session.active_game = type("Game", (), {"game_id": 10701})()
        session.game_actions_unlock_titles = lambda: ["First Steps", "Commander"]
        session.achievement_preview_surface = None
        session.achievement_preview_game_id = None
        session.achievement_preview_title = None
        session.load_achievement_preview_surface = lambda game_id, title: (
            game_id,
            title,
        )

        self.assertEqual(
            menu_sdl.MenuSdlSession.current_achievement_preview_surface(session),
            (10701, "Commander"),
        )

    def test_current_achievement_preview_surface_is_none_off_unlock_row(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.view = "game_actions"
        session.selected_index = 0
        session.active_game = type("Game", (), {"game_id": 10701})()
        session.game_actions_unlock_titles = lambda: ["First Steps"]
        session.achievement_preview_surface = "stale"
        session.achievement_preview_game_id = 10701
        session.achievement_preview_title = "First Steps"

        self.assertIsNone(
            menu_sdl.MenuSdlSession.current_achievement_preview_surface(session)
        )

    def test_install_update_uses_cached_asset_url_without_refresh(self) -> None:
        session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
        session.main_update_asset_url = "https://example.com/installer.sh"
        session.message = None
        session.dismiss_update_prompt = lambda: None
        session.storage = type("Storage", (), {"close": lambda self: None})()
        session.input_handles = []
        session.pygame = type("Pygame", (), {"quit": lambda self: None})()

        original_download = menu_sdl.download_knulli_update_installer
        original_update_status = menu_sdl.update_status
        original_close_input_devices = menu_sdl.close_input_devices
        original_execv = menu_sdl.os.execv
        captured = {}
        try:
            menu_sdl.download_knulli_update_installer = (
                lambda url: captured.setdefault("url", url) or "/tmp/installer.sh"
            )
            menu_sdl.update_status = lambda *args, **kwargs: (_ for _ in ()).throw(
                AssertionError("should not refresh update status")
            )
            menu_sdl.close_input_devices = lambda _handles: None
            menu_sdl.os.execv = lambda path, argv: captured.setdefault(
                "exec", (path, argv)
            )

            menu_sdl.MenuSdlSession.install_update(session)

            self.assertEqual(captured["url"], "https://example.com/installer.sh")
        finally:
            menu_sdl.download_knulli_update_installer = original_download
            menu_sdl.update_status = original_update_status
            menu_sdl.close_input_devices = original_close_input_devices
            menu_sdl.os.execv = original_execv


if __name__ == "__main__":
    unittest.main()
