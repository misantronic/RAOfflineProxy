import os
import subprocess
import time
from pathlib import Path

from .auth import resolve_credentials
from .batocera_conf import patch_batocera_conf, revert_batocera_conf
from .config import CONFIG_DIR, load_config
from .platform import (
    autostart_enabled,
    autostart_supported,
    disable_autostart,
    enable_autostart,
    resolve_retroarch_cfg,
    resolve_rom_root,
)
from .pending_awards import delete_pending_award, list_pending_awards
from .retroarch_cfg import patch_retroarch_cfg, revert_retroarch_cfg
from .rom_browser import (
    add_rom_to_cache,
    ensure_game_preview,
    list_browser_entries,
    list_cached_games,
    remove_cached_game,
)
from .service import service_status, start_service_process, stop_service_process
from .state import load_patch_state, save_patch_state
from .storage import Storage
from .menu import (
    BTN_DPAD_DOWN,
    BTN_DPAD_LEFT,
    BTN_DPAD_RIGHT,
    BTN_DPAD_UP,
    BTN_EAST,
    BTN_SELECT,
    BTN_SOUTH,
    BTN_START,
    KEY_BACKSPACE,
    KEY_DOWN,
    KEY_ENTER,
    KEY_ESC,
    KEY_LEFT,
    KEY_Q,
    KEY_RIGHT,
    KEY_S,
    KEY_SPACE,
    KEY_UP,
    close_input_devices,
    open_input_devices,
    read_key,
)

SDL_LOG_PATH = CONFIG_DIR / "menu-sdl.log"
STALE_HOOK_PATH = Path("/userdata/system/scripts/RAOfflineProxy_game_hook.sh")
BACKGROUND_COLOR = (0, 0, 0)
PRIMARY_COLOR = (28, 81, 130)
SECONDARY_COLOR = (210, 164, 72)
TEXT_COLOR = (255, 255, 255)
SELECTED_COLOR = SECONDARY_COLOR
STATUS_COLOR = (180, 180, 180)
SECONDARY_TEXT_COLOR = (110, 110, 110)
ERROR_SECONDS = 3
FPS = 30
LEFT_MARGIN = 32
GROUP_GAP = 14
INITIAL_NAV_INTERVAL_SECONDS = 0.08
FAST_NAV_INTERVAL_SECONDS = 0.03
FAST_NAV_TRIGGER_SECONDS = 0.35
NAV_RESET_SECONDS = 0.18
FONT_CANDIDATES = [
    "DejaVu Sans Mono",
    "Monospace",
    "DejaVu Sans",
    "DejaVu Sans Condensed",
    "Noto Sans JP",
    "Noto Sans SC",
    "Noto Sans KR",
    "Noto Sans TC",
    "Noto Sans HK",
]
LOGO_PATH = Path(__file__).resolve().parent / "logo-320.png"


def run_menu_sdl(command_runner: str) -> None:
    import pygame

    pygame.init()
    pygame.font.init()
    pygame.joystick.init()

    try:
        surface = pygame.display.set_mode((0, 0), pygame.FULLSCREEN)
        width, height = surface.get_size()
        log_menu_sdl(f"run_menu_sdl start width={width} height={height}")
        session = MenuSdlSession(command_runner, surface, width, height, pygame)
        session.run()
    finally:
        pygame.quit()


def log_menu_sdl(message: str) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with SDL_LOG_PATH.open("a", encoding="utf-8") as handle:
        handle.write(f"{timestamp} {message}\n")


def remove_stale_hook() -> None:
    if STALE_HOOK_PATH.exists():
        STALE_HOOK_PATH.unlink()


def runtime_config() -> tuple[dict, str]:
    config_data = load_config()
    cfg_path = resolve_retroarch_cfg(config_data)
    return config_data, cfg_path


def start_proxy_inline() -> None:
    config_data, cfg_path = runtime_config()
    remove_stale_hook()
    patch_retroarch_cfg(cfg_path, config_data)
    batocera = patch_batocera_conf(config_data)
    patch_state = load_patch_state() or {}
    patch_state["batocera_previous"] = batocera.get("previous", {})
    patch_state["batocera_conf_path"] = batocera.get("path")
    save_patch_state(patch_state)
    start_service_process(config_data)


def stop_proxy_inline() -> None:
    config_data, cfg_path = runtime_config()
    remove_stale_hook()
    service = stop_service_process()
    patch_state = load_patch_state() or {}
    previous_batocera = patch_state.get("batocera_previous", {})
    revert_batocera_conf(config_data, previous_batocera)

    if patch_state:
        revert_retroarch_cfg(cfg_path)
        return

    if not service.get("already_stopped"):
        try:
            revert_retroarch_cfg(cfg_path)
        except Exception:
            pass
        return

    try:
        revert_retroarch_cfg(cfg_path)
    except Exception:
        pass


class MenuSdlSession:
    def __init__(
        self, command_runner: str, surface, width: int, height: int, pygame
    ) -> None:
        self.command_runner = command_runner
        self.surface = surface
        self.width = width
        self.height = height
        self.pygame = pygame
        self.running = True
        self.view = "main"
        self.selected_index = 0
        self.scroll_offset = 0
        self.last_navigation_at = 0.0
        self.last_navigation_delta = 0
        self.navigation_hold_started_at = 0.0
        self.message: tuple[str, float] | None = None
        self.storage = Storage()
        self.cached_games = []
        self.pending_awards = []
        self.active_game = None
        self.active_pending_award = None
        self.browser_dir: Path | None = None
        self.browser_entries: list[Path] = []
        self.view_positions: dict[str, tuple[int, int]] = {}
        self.browser_positions: dict[str, tuple[int, int]] = {}
        self.preview_surface = None
        self.preview_game_id = None
        self.logo_surface = None
        self.input_handles = open_input_devices()
        self.title_font = self.load_font(max(30, height // 19), bold=True)
        self.status_font = self.load_font(max(20, height // 30))
        self.item_font = self.load_font(max(22, height // 30), bold=False)
        self.clock = pygame.time.Clock()
        for joystick_index in range(pygame.joystick.get_count()):
            joystick = pygame.joystick.Joystick(joystick_index)
            joystick.init()
            log_menu_sdl(
                f"joystick init index={joystick_index} name={joystick.get_name()}"
            )

        log_menu_sdl(
            f"MenuSdlSession init width={width} height={height} input_handles={len(self.input_handles)}"
        )
        self.refresh_cached_games()

    def load_font(self, size: int, bold: bool = False):
        for font_name in FONT_CANDIDATES:
            font_path = self.pygame.font.match_font(font_name)
            if font_path is None:
                continue

            font = self.pygame.font.Font(font_path, size)
            font.set_bold(bold)
            log_menu_sdl(
                f"font selected name={font_name} path={font_path} size={size} bold={bold}"
            )
            return font

        font = self.pygame.font.Font(None, size)
        font.set_bold(bold)
        log_menu_sdl(f"font fallback default size={size} bold={bold}")
        return font

    def run(self) -> None:
        try:
            while self.running:
                self.handle_events()
                self.handle_raw_input()
                self.render()
                self.clock.tick(FPS)
        finally:
            close_input_devices(self.input_handles)
            self.storage.close()

    def render(self) -> None:
        self.surface.fill(BACKGROUND_COLOR)

        title_text = self.title_for_view()
        title = self.title_font.render(title_text, False, PRIMARY_COLOR)
        title_rect = title.get_rect(topleft=(LEFT_MARGIN, max(36, self.height // 12)))
        self.surface.blit(title, title_rect)

        running = self.proxy_running()
        status_text = self.status_text(running)
        status = self.status_font.render(status_text, False, STATUS_COLOR)
        status_rect = status.get_rect(topleft=(LEFT_MARGIN, title_rect.bottom + 20))
        self.surface.blit(status, status_rect)

        items = self.current_labels(running)
        start_y = status_rect.bottom + 34
        gap = max(self.item_font.get_height() + 6, self.height // 18)
        self.normalize_selection(items, start_y, gap)
        self.render_game_preview()
        self.render_home_logo()
        positions = self.item_positions(items, start_y, gap)
        visible_offset, visible_items = self.visible_items(items, positions, start_y)
        scroll_base_y = positions[visible_offset] - start_y if visible_items else 0
        for visible_index, (actual_index, label) in enumerate(visible_items):
            color = (
                SELECTED_COLOR
                if actual_index == self.selected_index
                else self.item_text_color(label)
            )
            prefix = "> " if actual_index == self.selected_index else "  "
            text = self.item_font.render(f"{prefix}{label}", False, color)
            rect = text.get_rect(
                topleft=(LEFT_MARGIN, positions[actual_index] - scroll_base_y)
            )
            self.surface.blit(text, rect)

        if self.message is not None:
            text, expires_at = self.message
            if time.monotonic() >= expires_at:
                self.message = None
            else:
                overlay = self.status_font.render(text, False, SELECTED_COLOR)
                overlay_rect = overlay.get_rect(topleft=(LEFT_MARGIN, self.height - 56))
                self.surface.blit(overlay, overlay_rect)
        else:
            hint = self.bottom_hint_text()
            if hint is not None:
                overlay = self.status_font.render(hint, False, SELECTED_COLOR)
                overlay_rect = overlay.get_rect(topleft=(LEFT_MARGIN, self.height - 56))
                self.surface.blit(overlay, overlay_rect)

        self.pygame.display.flip()

    def labels(self, running: bool) -> list[str]:
        if self.view == "cached_games":
            cached = [game.title for game in self.cached_games]
            return ["Add ROM", *cached, "Clear cache", "Back"]

        if self.view == "pending_awards":
            labels = []
            for award in self.pending_awards:
                labels.extend([award.game_title, award.summary_text, ""])
            labels.append("Back")
            return labels

        if self.view == "pending_award_actions":
            return ["Delete pending award", "Back"]

        if self.view == "game_actions":
            return ["Remove cache", "Back"]

        if self.view == "file_browser":
            if self.browser_dir is None:
                return ["Cancel"]

            labels = []
            root = resolve_rom_root(load_config())
            if self.browser_dir.parent != self.browser_dir and self.browser_dir != root:
                labels.append("..")
            labels.extend(entry.name for entry in self.browser_entries)
            labels.append("Cancel")
            return labels

        toggle = "Stop proxy" if running else "Start proxy"
        labels = [toggle]
        config_data = load_config()
        if autostart_supported(config_data):
            labels.append(
                "Disable autostart"
                if autostart_enabled(config_data)
                else "Enable autostart"
            )
        if self.is_logged_in(config_data):
            labels.append("Cached games")
        if self.pending_awards:
            labels.append(f"Pending awards ({len(self.pending_awards)})")
        labels.extend(["Uninstall", "Exit Menu"])
        return labels

    def is_logged_in(self, config_data: dict | None = None) -> bool:
        return (
            resolve_credentials(self.storage, config_data or load_config()) is not None
        )

    def current_labels(self, running: bool | None = None) -> list[str]:
        return self.labels(self.proxy_running() if running is None else running)

    def title_for_view(self) -> str:
        if self.view == "cached_games":
            return "Cached Games"
        if self.view == "pending_awards":
            return "Pending Awards"
        if self.view == "pending_award_actions":
            return (
                self.active_pending_award.game_title
                if self.active_pending_award is not None
                else "Pending Award"
            )
        if self.view == "game_actions":
            return (
                self.active_game.title
                if self.active_game is not None
                else "Cached Game"
            )
        if self.view == "file_browser":
            return "Add ROM"
        return "RAOfflineProxy"

    def item_text_color(self, label: str) -> tuple[int, int, int]:
        if label in {"Back", "Cancel", ""}:
            return SECONDARY_TEXT_COLOR
        return TEXT_COLOR

    def status_text(self, running: bool) -> str:
        if self.view == "cached_games":
            return f"CACHED: {len(self.cached_games)}"
        if self.view == "pending_awards":
            return f"PENDING: {len(self.pending_awards)}"
        if self.view == "pending_award_actions":
            return (
                self.active_pending_award.summary_text
                if self.active_pending_award is not None
                else "No pending award selected"
            )
        if self.view == "game_actions":
            return (
                f"GAME ID: {self.active_game.game_id}"
                if self.active_game is not None
                else "No game selected"
            )
        if self.view == "file_browser":
            return str(self.browser_dir or "No ROM directory")
        logged_in = self.is_logged_in()
        proxy_status = "RUNNING" if running else "STOPPED"
        login_status = "LOGGED IN" if logged_in else "NOT LOGGED IN"
        return f"PROXY: {proxy_status}, STATUS: {login_status}"

    def bottom_hint_text(self) -> str | None:
        if self.view != "main":
            return None

        if self.is_logged_in():
            return None

        return "Login to RetroAchievements in system settings."

    def handle_events(self) -> None:
        for event in self.pygame.event.get():
            if event.type == self.pygame.QUIT:
                self.running = False
                return

            if event.type == self.pygame.KEYDOWN:
                log_menu_sdl(f"pygame keydown key={event.key}")
                self.handle_key(event.key)
                continue

            if event.type == self.pygame.JOYHATMOTION:
                log_menu_sdl(f"pygame hat value={event.value}")
                x, y = event.value
                if y == 1:
                    self.navigate(-1)
                elif y == -1:
                    self.navigate(1)
                elif x == -1:
                    self.navigate(-1)
                elif x == 1:
                    self.navigate(1)
                continue

            if event.type == self.pygame.JOYBUTTONDOWN:
                log_menu_sdl(f"pygame joybutton button={event.button}")
                self.handle_joy_button(event.button)

    def handle_raw_input(self) -> None:
        key = read_key(self.input_handles)
        if key is None:
            return

        log_menu_sdl(f"raw key={key}")
        if key in {KEY_UP, KEY_LEFT, BTN_DPAD_UP, BTN_DPAD_LEFT}:
            self.navigate(-1)
            return

        if key in {KEY_DOWN, KEY_RIGHT, BTN_DPAD_DOWN, BTN_DPAD_RIGHT}:
            self.navigate(1)
            return

        if key in {KEY_ENTER, KEY_SPACE, KEY_S, BTN_SOUTH, BTN_START}:
            self.activate_selected()
            return

        if key in {KEY_ESC, KEY_BACKSPACE, KEY_Q, BTN_EAST, BTN_SELECT}:
            self.go_back()

    def handle_key(self, key: int) -> None:
        if key in {self.pygame.K_UP, self.pygame.K_LEFT}:
            self.navigate(-1)
            return
        if key in {self.pygame.K_DOWN, self.pygame.K_RIGHT}:
            self.navigate(1)
            return
        if key in {self.pygame.K_RETURN, self.pygame.K_SPACE, self.pygame.K_s}:
            self.activate_selected()
            return
        if key in {self.pygame.K_ESCAPE, self.pygame.K_BACKSPACE, self.pygame.K_q}:
            self.go_back()

    def handle_joy_button(self, button: int) -> None:
        if button in {0, 7}:
            self.activate_selected()
            return
        if button in {1, 6}:
            self.go_back()

    def navigate(self, delta: int) -> None:
        now = time.monotonic()
        if (
            delta != self.last_navigation_delta
            or (now - self.last_navigation_at) > NAV_RESET_SECONDS
        ):
            self.navigation_hold_started_at = now
            self.last_navigation_delta = delta

        interval = INITIAL_NAV_INTERVAL_SECONDS
        if (now - self.navigation_hold_started_at) >= FAST_NAV_TRIGGER_SECONDS:
            interval = FAST_NAV_INTERVAL_SECONDS

        if (now - self.last_navigation_at) < interval:
            return

        self.last_navigation_at = now
        items = self.current_labels()
        item_count = max(1, len(items))
        self.selected_index = (self.selected_index + delta) % item_count
        start_y = self.item_start_y()
        gap = self.item_gap()
        self.ensure_selection_visible(items, start_y, gap)
        log_menu_sdl(f"navigate delta={delta} selected={self.selected_index}")

    def activate_selected(self) -> None:
        if self.view == "cached_games":
            self.activate_cached_games_selected()
            return

        if self.view == "pending_awards":
            self.activate_pending_awards_selected()
            return

        if self.view == "pending_award_actions":
            self.activate_pending_award_actions_selected()
            return

        if self.view == "game_actions":
            self.activate_game_actions_selected()
            return

        if self.view == "file_browser":
            self.activate_file_browser_selected()
            return

        labels = self.current_labels()
        selected_label = labels[self.selected_index] if labels else ""
        running = self.proxy_running()
        if self.selected_index == 0:
            if running:
                self.stop_proxy()
            else:
                self.start_proxy()
            return

        config_data = load_config()
        if selected_label in {"Enable autostart", "Disable autostart"}:
            self.toggle_autostart(config_data)
            return

        if selected_label == "Cached games":
            self.save_view_position("main")
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            self.refresh_cached_games()
            return

        if selected_label.startswith("Pending awards ("):
            self.save_view_position("main")
            self.view = "pending_awards"
            self.restore_view_position("pending_awards")
            self.refresh_pending_awards()
            return

        if selected_label == "Uninstall":
            self.uninstall()
            return

        self.running = False

    def activate_cached_games_selected(self) -> None:
        clear_cache_index = len(self.cached_games) + 1
        back_index = len(self.cached_games) + 2
        if self.selected_index == 0:
            self.open_file_browser()
            return

        if self.selected_index == clear_cache_index:
            self.storage.clear_cache()
            self.active_game = None
            self.refresh_cached_games()
            self.restore_view_position("cached_games")
            self.message = ("Cache cleared", time.monotonic() + 1.5)
            return

        if self.selected_index == back_index:
            self.save_view_position("cached_games")
            self.view = "main"
            self.restore_view_position("main")
            return

        game_index = self.selected_index - 1
        if 0 <= game_index < len(self.cached_games):
            self.save_view_position("cached_games")
            self.active_game = self.cached_games[game_index]
            self.view = "game_actions"
            self.reset_selection()
            return

    def activate_pending_awards_selected(self) -> None:
        back_index = len(self.pending_awards) * 3
        if self.selected_index == back_index:
            self.save_view_position("pending_awards")
            self.view = "main"
            self.restore_view_position("main")
            return

        award_index = self.selected_index // 3
        if 0 <= award_index < len(self.pending_awards):
            self.save_view_position("pending_awards")
            self.active_pending_award = self.pending_awards[award_index]
            self.view = "pending_award_actions"
            self.reset_selection()

    def activate_pending_award_actions_selected(self) -> None:
        if self.active_pending_award is None:
            self.view = "pending_awards"
            self.restore_view_position("pending_awards")
            self.refresh_pending_awards()
            return

        if self.selected_index == 0:
            delete_pending_award(self.storage, self.active_pending_award.achievement_id)
            removed_title = self.active_pending_award.achievement_title
            self.active_pending_award = None
            self.refresh_pending_awards()
            self.view = "pending_awards"
            self.restore_view_position("pending_awards")
            self.message = (f"Removed {removed_title}", time.monotonic() + 1.5)
            return

        self.active_pending_award = None
        self.view = "pending_awards"
        self.restore_view_position("pending_awards")

    def activate_game_actions_selected(self) -> None:
        if self.active_game is None:
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            self.refresh_cached_games()
            return

        if self.selected_index == 0:
            remove_cached_game(self.storage, self.active_game.game_id)
            removed_title = self.active_game.title
            self.active_game = None
            self.refresh_cached_games()
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            self.message = (f"Removed {removed_title}", time.monotonic() + 1.5)
            return

        self.active_game = None
        self.view = "cached_games"
        self.restore_view_position("cached_games")

    def activate_file_browser_selected(self) -> None:
        if self.browser_dir is None:
            self.view = "cached_games"
            self.reset_selection()
            return

        root = resolve_rom_root(load_config())
        has_parent = (
            self.browser_dir.parent != self.browser_dir and self.browser_dir != root
        )
        first_entry_index = 1 if has_parent else 0
        cancel_index = first_entry_index + len(self.browser_entries)
        if has_parent and self.selected_index == 0:
            self.save_browser_position()
            self.set_browser_dir(self.browser_dir.parent, restore=True)
            return
        if self.selected_index == cancel_index:
            self.save_browser_position()
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            self.refresh_cached_games()
            return

        entry = self.browser_entries[self.selected_index - first_entry_index]
        if entry.is_dir():
            self.save_browser_position()
            self.set_browser_dir(entry, restore=True)
            return

        result = add_rom_to_cache(entry, self.storage, load_config())
        self.message = (result.message, time.monotonic() + ERROR_SECONDS)
        self.save_browser_position()
        self.view = "cached_games"
        self.restore_view_position("cached_games")
        self.refresh_cached_games()

    def open_file_browser(self) -> None:
        try:
            self.save_view_position("cached_games")
            root = resolve_rom_root(load_config())
            self.set_browser_dir(root, restore=True)
            self.view = "file_browser"
        except Exception as exc:
            self.message = (f"Browse failed: {exc}", time.monotonic() + ERROR_SECONDS)

    def set_browser_dir(self, path: Path, restore: bool = False) -> None:
        self.browser_dir = path
        self.browser_entries = list_browser_entries(path)
        if restore:
            self.restore_browser_position(path)
            return
        self.reset_selection()

    def refresh_cached_games(self) -> None:
        self.cached_games = list_cached_games(self.storage)
        self.pending_awards = list_pending_awards(self.storage)
        self.preview_surface = None
        self.preview_game_id = None

    def refresh_pending_awards(self) -> None:
        self.pending_awards = list_pending_awards(self.storage)

    def go_back(self) -> None:
        if self.view == "file_browser":
            if self.browser_dir is None:
                self.view = "cached_games"
                self.restore_view_position("cached_games")
                self.refresh_cached_games()
                return

            root = resolve_rom_root(load_config())
            if self.browser_dir == root:
                self.save_browser_position()
                self.view = "cached_games"
                self.restore_view_position("cached_games")
                self.refresh_cached_games()
                return

            if self.browser_dir.parent != self.browser_dir:
                self.save_browser_position()
                self.set_browser_dir(self.browser_dir.parent, restore=True)
                return

            self.view = "cached_games"
            self.restore_view_position("cached_games")
            self.refresh_cached_games()
            return

        if self.view == "cached_games":
            self.save_view_position("cached_games")
            self.view = "main"
            self.restore_view_position("main")
            return

        if self.view == "pending_awards":
            self.save_view_position("pending_awards")
            self.view = "main"
            self.restore_view_position("main")
            return

        if self.view == "pending_award_actions":
            self.active_pending_award = None
            self.view = "pending_awards"
            self.restore_view_position("pending_awards")
            return

        if self.view == "game_actions":
            self.active_game = None
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            return

        self.running = False

    def render_game_preview(self) -> None:
        game = self.preview_target_game()
        if game is None:
            self.preview_surface = None
            self.preview_game_id = None
            return

        if self.preview_game_id != game.game_id or self.preview_surface is None:
            self.preview_surface = self.load_game_preview_surface(game)
            self.preview_game_id = (
                game.game_id if self.preview_surface is not None else None
            )

        if self.preview_surface is None:
            return

        preview_rect = self.preview_surface.get_rect(topright=(self.width - 24, 24))
        self.surface.blit(self.preview_surface, preview_rect)

    def render_home_logo(self) -> None:
        if self.view != "main":
            return

        if self.logo_surface is None:
            self.logo_surface = self.load_logo_surface()

        if self.logo_surface is None:
            return

        logo_rect = self.logo_surface.get_rect(topright=(self.width - 24, 24))
        self.surface.blit(self.logo_surface, logo_rect)

    def preview_target_game(self):
        if self.view == "cached_games":
            game_index = self.selected_index - 1
            if 0 <= game_index < len(self.cached_games):
                return self.cached_games[game_index]
            return None

        if (
            self.view == "pending_award_actions"
            and self.active_pending_award is not None
        ):
            game_id = self.active_pending_award.game_id
            if game_id is None:
                return None
            for game in self.cached_games:
                if game.game_id == game_id:
                    return game
            return None

        if self.view == "game_actions":
            return self.active_game

        return None

    def load_game_preview_surface(self, game):
        try:
            preview_path = ensure_game_preview(game, self.storage, load_config())
            if preview_path is None:
                return None

            image = self.pygame.image.load(str(preview_path))
            image = (
                image.convert_alpha()
                if image.get_alpha() is not None
                else image.convert()
            )
            scaled_size = self.fit_preview_size(image.get_width(), image.get_height())
            return self.pygame.transform.smoothscale(image, scaled_size)
        except Exception as exc:
            log_menu_sdl(f"preview load failed gameId={game.game_id} error={exc}")
            return None

    def load_logo_surface(self):
        try:
            if not LOGO_PATH.exists():
                return None

            image = self.pygame.image.load(str(LOGO_PATH))
            image = (
                image.convert_alpha()
                if image.get_alpha() is not None
                else image.convert()
            )
            scaled_size = self.fit_preview_size(image.get_width(), image.get_height())
            return self.pygame.transform.smoothscale(image, scaled_size)
        except Exception as exc:
            log_menu_sdl(f"logo load failed path={LOGO_PATH} error={exc}")
            return None

    def fit_preview_size(self, width: int, height: int) -> tuple[int, int]:
        max_width = max(96, self.width // 4)
        max_height = max(96, self.height // 3)
        scale = min(max_width / max(1, width), max_height / max(1, height), 1.0)
        return max(1, int(width * scale)), max(1, int(height * scale))

    def item_start_y(self) -> int:
        title_top = max(36, self.height // 12)
        title_height = self.title_font.get_height()
        status_top = title_top + title_height + 20
        status_height = self.status_font.get_height()
        return status_top + status_height + 34

    def item_gap(self) -> int:
        return max(self.item_font.get_height() + 6, self.height // 18)

    def reset_selection(self) -> None:
        self.selected_index = 0
        self.scroll_offset = 0
        self.last_navigation_delta = 0
        self.navigation_hold_started_at = 0.0

    def save_view_position(self, view: str | None = None) -> None:
        key = view or self.view
        self.view_positions[key] = (self.selected_index, self.scroll_offset)

    def restore_view_position(self, view: str) -> None:
        saved = self.view_positions.get(view)
        if saved is None:
            self.reset_selection()
            return

        self.selected_index, self.scroll_offset = saved
        self.last_navigation_delta = 0
        self.navigation_hold_started_at = 0.0

    def save_browser_position(self) -> None:
        if self.browser_dir is None:
            return
        self.browser_positions[str(self.browser_dir)] = (
            self.selected_index,
            self.scroll_offset,
        )

    def restore_browser_position(self, path: Path) -> None:
        saved = self.browser_positions.get(str(path))
        if saved is None:
            self.reset_selection()
            return

        self.selected_index, self.scroll_offset = saved
        self.last_navigation_delta = 0
        self.navigation_hold_started_at = 0.0

    def normalize_selection(self, items: list[str], start_y: int, gap: int) -> None:
        if not items:
            self.selected_index = 0
            self.scroll_offset = 0
            return

        self.selected_index = max(0, min(self.selected_index, len(items) - 1))
        max_offset = max(0, len(items) - 1)
        self.scroll_offset = max(0, min(self.scroll_offset, max_offset))
        self.ensure_selection_visible(items, start_y, gap)

    def visible_items(
        self,
        items: list[str],
        positions: list[int],
        start_y: int,
    ) -> tuple[int, list[tuple[int, str]]]:
        end_offset = self.visible_end_offset(positions, self.scroll_offset, start_y)
        visible = [
            (index, items[index]) for index in range(self.scroll_offset, end_offset)
        ]
        return self.scroll_offset, visible

    def item_positions(self, items: list[str], start_y: int, gap: int) -> list[int]:
        positions: list[int] = []
        current_y = start_y
        clear_cache_index = len(self.cached_games) + 1
        last_game_index = len(self.cached_games)
        for index, _label in enumerate(items):
            positions.append(current_y)
            current_y += gap
            if self.view == "cached_games" and index == 0:
                current_y += GROUP_GAP
            if self.view == "cached_games" and index == last_game_index:
                current_y += GROUP_GAP
            if self.view == "cached_games" and clear_cache_index == 1 and index == 0:
                current_y -= GROUP_GAP
            if self.view == "pending_awards" and (index + 1) % 3 == 0:
                current_y += GROUP_GAP
        return positions

    def bottom_limit(self) -> int:
        message_padding = 44 if self.message is not None else 4
        return self.height - message_padding

    def visible_end_offset(
        self, positions: list[int], offset: int, start_y: int
    ) -> int:
        if not positions:
            return 0

        bottom_limit = self.bottom_limit()
        item_height = max(1, self.item_font.get_height())
        scroll_base_y = positions[offset] - start_y
        end_offset = offset
        while end_offset < len(positions):
            relative_y = positions[end_offset] - scroll_base_y
            if relative_y + item_height > bottom_limit and end_offset > offset:
                break
            if relative_y + item_height > bottom_limit and end_offset == offset:
                end_offset += 1
                break
            end_offset += 1
        return end_offset

    def ensure_selection_visible(
        self,
        items: list[str],
        start_y: int,
        gap: int,
    ) -> None:
        if self.selected_index < self.scroll_offset:
            self.scroll_offset = self.selected_index
        positions = self.item_positions(items, start_y, gap)
        end_offset = self.visible_end_offset(positions, self.scroll_offset, start_y)
        while self.selected_index >= end_offset and self.scroll_offset < len(items) - 1:
            self.scroll_offset += 1
            end_offset = self.visible_end_offset(positions, self.scroll_offset, start_y)
        self.scroll_offset = max(0, min(self.scroll_offset, max(0, len(items) - 1)))

    def proxy_running(self) -> bool:
        try:
            service = service_status() or {}
        except Exception:
            return False
        return bool(service.get("running"))

    def start_proxy(self) -> None:
        try:
            start_proxy_inline()
            self.message = ("Proxy started", time.monotonic() + 1.2)
        except Exception as exc:
            self.message = (f"Start failed: {exc}", time.monotonic() + ERROR_SECONDS)

    def stop_proxy(self) -> None:
        try:
            stop_proxy_inline()
            self.message = ("Proxy stopped", time.monotonic() + 1.2)
        except Exception as exc:
            self.message = (f"Stop failed: {exc}", time.monotonic() + ERROR_SECONDS)

    def toggle_autostart(self, config_data: dict) -> None:
        try:
            if autostart_enabled(config_data):
                disable_autostart(config_data)
                self.message = ("Autostart disabled", time.monotonic() + 1.2)
            else:
                enable_autostart(config_data)
                self.message = ("Autostart enabled", time.monotonic() + 1.2)
        except Exception as exc:
            self.message = (
                f"Autostart failed: {exc}",
                time.monotonic() + ERROR_SECONDS,
            )

    def uninstall(self) -> None:
        launcher = "/userdata/system/raofflineproxy/bin/raofflineproxy-uninstall"
        try:
            self.storage.close()
        except Exception:
            pass
        try:
            close_input_devices(self.input_handles)
        except Exception:
            pass
        os.execv(launcher, [launcher])
