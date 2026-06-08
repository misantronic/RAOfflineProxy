import os
import subprocess
import threading
import traceback
import time
from pathlib import Path

from .batocera_conf import patch_batocera_conf, revert_batocera_conf
from .config import APP_VERSION, CONFIG_DIR, load_config, save_config
from .platform import (
    autostart_enabled,
    autostart_supported,
    disable_autostart,
    enable_autostart,
    resolve_retroarch_cfg,
    resolve_rom_root,
)
from .pending_awards import delete_pending_award, list_pending_awards
from .network import online_check
from .retroarch_cfg import (
    enforce_patched_cfg,
    load_retroarch_credentials,
    patch_retroarch_cfg,
    revert_retroarch_cfg,
)
from .rom_browser import (
    MAX_CACHED_GAMES,
    add_rom_to_cache,
    cached_unlock_badge_path,
    cached_unlock_count,
    cached_unlock_titles,
    clear_cached_games,
    ensure_game_preview,
    list_browser_files_fast,
    list_browser_entries,
    list_cached_games,
    remove_cached_game,
)
from .service import service_status, start_service_process, stop_service_process
from .smart_cache import (
    SMART_CACHE_LIMIT,
    load_content_history_paths,
    run_folder_cache,
    run_smart_cache,
    should_offer_smart_cache,
)
from .state import load_patch_state, save_patch_state
from .storage import Storage
from .update import download_knulli_update_installer, update_status
from .menu_input import (
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
    read_keys,
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
FPS = 60
LEFT_MARGIN = 32
GROUP_GAP = 14
MAIN_MENU_STATE_REFRESH_SECONDS = 1.0
KNULLI_FONT_CANDIDATES = [
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
MUOS_FONT_REGULAR = Path("/usr/share/fonts/liberation/LiberationMono-Regular.ttf")
MUOS_FONT_BOLD = Path("/usr/share/fonts/liberation/LiberationMono-Bold.ttf")
LOGO_PATH = Path(__file__).resolve().parent / "logo-320.png"
CALIBRATION_FACE_BUTTONS = {BTN_SOUTH, BTN_EAST}


def run_menu_sdl(command_runner: str) -> None:
    import pygame

    pygame.init()
    pygame.font.init()

    try:
        try:
            surface = pygame.display.set_mode((0, 0), pygame.FULLSCREEN)
        except pygame.error as exc:
            configured_driver = os.environ.get("SDL_VIDEODRIVER", "")
            if configured_driver != "" and "not available" in str(exc):
                log_menu_sdl(
                    f"display fallback from driver={configured_driver} error={exc}"
                )
                os.environ.pop("SDL_VIDEODRIVER", None)
                pygame.display.quit()
                pygame.display.init()
                surface = pygame.display.set_mode((0, 0), pygame.FULLSCREEN)
            else:
                raise
        width, height = surface.get_size()
        session = MenuSdlSession(command_runner, surface, width, height, pygame)
        session.run()
    except Exception:
        log_menu_sdl(traceback.format_exc().rstrip())
        raise
    finally:
        restart_muos_frontend()
        pygame.quit()


def log_menu_sdl(message: str) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with SDL_LOG_PATH.open("a", encoding="utf-8") as handle:
        handle.write(f"{timestamp} {message}\n")


def remove_stale_hook() -> None:
    if STALE_HOOK_PATH.exists():
        STALE_HOOK_PATH.unlink()


def running_on_muos() -> bool:
    return Path("/opt/muos/script/archive").exists()


def restart_muos_frontend() -> None:
    if not running_on_muos():
        return

    subprocess.Popen(
        ["setsid", "-f", "/opt/muos/script/mux/frontend.sh"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def runtime_config() -> tuple[dict, str]:
    config_data = load_config()
    cfg_path = resolve_retroarch_cfg(config_data)
    return config_data, cfg_path


def start_proxy_inline() -> None:
    config_data, cfg_path = runtime_config()
    remove_stale_hook()
    patch_retroarch_cfg(cfg_path, config_data)
    enforce_patched_cfg(cfg_path, config_data)
    batocera = patch_batocera_conf(config_data)
    patch_state = load_patch_state() or {}
    patch_state["batocera_previous"] = batocera.get("previous", {})
    patch_state["batocera_conf_path"] = batocera.get("path")
    save_patch_state(patch_state)
    start_service_process(config_data)


def stop_proxy_inline() -> None:
    config_data, cfg_path = runtime_config()
    remove_stale_hook()
    patch_state = load_patch_state() or {}
    revert_cfg_path = patch_state.get("cfg_path") or cfg_path
    service = stop_service_process()
    previous_batocera = patch_state.get("batocera_previous", {})
    revert_batocera_conf(config_data, previous_batocera)

    if patch_state:
        revert_retroarch_cfg(revert_cfg_path, patch_state)
        return

    if not service.get("already_stopped"):
        try:
            revert_retroarch_cfg(revert_cfg_path)
        except Exception:
            pass
        return

    try:
        revert_retroarch_cfg(revert_cfg_path)
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
        self.config_data = load_config()
        self.running = True
        self.view = "controller_calibration" if self.needs_controller_calibration() else "main"
        self.selected_index = 0
        self.scroll_offset = 0
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
        self.smart_cache_prompt_available = False
        self.smart_cache_prompt_count = 0
        self.smart_cache_in_progress = False
        self.smart_cache_progress_text: str | None = None
        self.smart_cache_result: tuple[str, float] | None = None
        self.smart_cache_thread: threading.Thread | None = None
        self.cache_progress_text: str | None = None
        self.cache_progress_title: str | None = None
        self.cache_result: tuple[str, float] | None = None
        self.cache_worker_thread: threading.Thread | None = None
        self.cache_abort_requested = False
        self.cache_completed = False
        self.cache_completion_message: str | None = None
        self.cache_return_view = "cached_games"
        self.cache_return_browser_dir: Path | None = None
        self.cache_return_browser_restore = False
        self.preview_surface = None
        self.preview_game_id = None
        self.achievement_preview_surface = None
        self.achievement_preview_game_id = None
        self.achievement_preview_title = None
        self.logo_surface = None
        self.main_state_refreshed_at = 0.0
        self.main_running = False
        self.main_online = False
        self.main_logged_in = False
        self.main_autostart_supported = False
        self.main_autostart_enabled = False
        self.main_update_available = False
        self.main_update_version = None
        self.main_update_asset_url = None
        self.main_update_dialog_seen = False
        self.calibration_confirm_button = self.configured_confirm_button()
        self.calibration_cancel_button = self.configured_cancel_button()
        self.calibration_step = (
            "done"
            if self.calibration_complete()
            else ("confirm" if self.calibration_confirm_button is None else "cancel")
        )
        self.clear_cache_return_view = "cached_games"
        self.active_game_unlock_game_id = None
        self.active_game_unlock_count_cached = None
        self.active_game_unlock_titles_cached: list[str] = []
        self.input_handles = open_input_devices()
        self.title_font = self.load_font(max(30, height // 19), bold=True)
        self.status_font = self.load_font(max(20, height // 30))
        self.item_font = self.load_font(max(22, height // 30), bold=False)
        self.meta_font = self.load_font(max(16, height // 44), bold=False)
        self.clock = pygame.time.Clock()

        self.refresh_main_menu_state(force=True)
        self.refresh_cached_games()

    def load_font(self, size: int, bold: bool = False):
        if Path("/opt/muos/script/archive").exists():
            muos_font_path = MUOS_FONT_BOLD if bold else MUOS_FONT_REGULAR
            if not muos_font_path.exists():
                muos_font_path = MUOS_FONT_REGULAR
            if muos_font_path.exists():
                return self.pygame.font.Font(str(muos_font_path), size)
            font = self.pygame.font.Font(None, size)
            font.set_bold(bold)
            return font

        for font_name in KNULLI_FONT_CANDIDATES:
            font_path = self.pygame.font.match_font(font_name)
            if font_path is None:
                continue

            font = self.pygame.font.Font(font_path, size)
            font.set_bold(bold)
            return font

        font = self.pygame.font.Font(None, size)
        font.set_bold(bold)
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
            font = self.item_font_for_index(actual_index)
            text = font.render(f"{prefix}{label}", False, color)
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

        if self.view == "main":
            version = self.meta_font.render(APP_VERSION, False, SECONDARY_TEXT_COLOR)
            version_rect = version.get_rect(
                bottomright=(self.width - LEFT_MARGIN, self.height - 18)
            )
            self.surface.blit(version, version_rect)

        self.pygame.display.flip()

    def labels(self, running: bool) -> list[str]:
        if self.view == "controller_calibration":
            return []

        if self.view == "cached_games":
            cached = [game.title for game in self.cached_games]
            return ["Add ROM", "Start Smart Cache", *cached, "Clear cache", "Back"]

        if self.view == "pending_awards":
            labels = []
            for award in self.pending_awards:
                labels.extend([award.game_title, award.detail_text])
            labels.append("Back")
            return labels

        if self.view == "pending_award_actions":
            return ["Delete pending award", "Back"]

        if self.view == "smart_cache_prompt":
            return ["Start Smart Cache", "Skip"]

        if self.view == "clear_cache_confirm":
            return ["YES", "NO"]

        if self.view == "cache_progress":
            return ["Back"] if getattr(self, "cache_completed", False) else ["Abort"]

        if self.view == "update_prompt":
            return ["Download and install", "Later"]

        if self.view == "game_actions":
            unlock_titles = self.game_actions_unlock_titles()
            return ["Remove cache", *unlock_titles, "Back"]

        if self.view == "file_browser":
            if self.browser_dir is None:
                return ["Cancel"]

            labels = []
            if self.browser_has_cacheable_files():
                labels.append("Add folder")
            root = resolve_rom_root(load_config())
            if self.browser_dir.parent != self.browser_dir and self.browser_dir != root:
                labels.append("..")
            labels.extend(entry.name for entry in self.browser_entries)
            labels.append("Cancel")
            return labels

        toggle = "Stop proxy" if running else "Start proxy"
        labels = [toggle]
        self.refresh_main_menu_state()
        if self.main_logged_in:
            labels.append(f"Cached games ({len(self.cached_games)})")
        if self.pending_awards:
            labels.append(f"Pending awards ({len(self.pending_awards)})")
        if self.main_autostart_supported:
            labels.append(
                "Disable autostart"
                if self.main_autostart_enabled
                else "Enable autostart"
            )
        if self.is_knulli_platform() or running_on_muos():
            labels.append("Uninstall")
        labels.append("Exit Menu")
        return labels

    def is_logged_in(self, config_data: dict | None = None) -> bool:
        if config_data is None:
            self.refresh_main_menu_state()
            return bool(getattr(self, "main_logged_in", False))

        if self.storage.load_login_credentials() is not None:
            return True

        return (
            load_retroarch_credentials(resolve_retroarch_cfg(config_data)) is not None
        )

    def current_labels(self, running: bool | None = None) -> list[str]:
        return self.labels(self.proxy_running() if running is None else running)

    def title_for_view(self) -> str:
        if self.view == "controller_calibration":
            return "Controller Setup"
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
        if self.view == "update_prompt":
            return "Update Available"
        if self.view == "clear_cache_confirm":
            return "Clear Cache?"
        if self.view == "cache_progress":
            return self.cache_progress_title or "Caching"
        return "RAOfflineProxy"

    def item_text_color(self, label: str) -> tuple[int, int, int]:
        if label in {"Back", "Cancel", ""}:
            return SECONDARY_TEXT_COLOR
        return TEXT_COLOR

    def item_font_for_index(self, index: int):
        return self.item_font

    def game_actions_unlock_titles(self) -> list[str]:
        if self.active_game is None:
            return []
        if (
            getattr(self, "active_game_unlock_game_id", None)
            != self.active_game.game_id
        ):
            self.refresh_active_game_unlocks()
        return list(getattr(self, "active_game_unlock_titles_cached", []))

    def status_text(self, running: bool) -> str:
        if self.view == "controller_calibration":
            if self.calibration_step == "confirm":
                return "Press the button labeled A"
            if self.calibration_step == "cancel":
                return "Press the button labeled B"
            return "Controller setup complete"
        if self.view == "cached_games":
            return f"CACHED: {len(self.cached_games)} / {MAX_CACHED_GAMES}"
        if self.view == "pending_awards":
            return f"PENDING: {len(self.pending_awards)}"
        if self.view == "pending_award_actions":
            return (
                self.active_pending_award.summary_text
                if self.active_pending_award is not None
                else "No pending award selected"
            )
        if self.view == "smart_cache_prompt":
            return f"SMART CACHE: {self.smart_cache_prompt_count} recent games found"
        if self.view == "cache_progress":
            if getattr(self, "cache_completed", False):
                return self.cache_completion_message or "Cache complete"
            return self.cache_progress_text or "Preparing cache..."
        if self.view == "game_actions":
            if self.active_game is None:
                return "No game selected"
            if (
                getattr(self, "active_game_unlock_game_id", None)
                != self.active_game.game_id
            ):
                self.refresh_active_game_unlocks()
            unlock_count = getattr(self, "active_game_unlock_count_cached", None)
            if unlock_count is None:
                return f"GAME ID: {self.active_game.game_id}, UNLOCKS: unknown"
            return f"GAME ID: {self.active_game.game_id}, UNLOCKS: {unlock_count}"
        if self.view == "file_browser":
            return str(self.browser_dir or "No ROM directory")
        if self.view == "update_prompt":
            return (
                f"Version {self.main_update_version} is available."
                if self.main_update_version is not None
                else "A new version is available."
            )
        self.refresh_main_menu_state()
        logged_in = bool(getattr(self, "main_logged_in", False))
        proxy_status = "RUNNING" if running else "STOPPED"
        connectivity_status = (
            "ONLINE" if bool(getattr(self, "main_online", False)) else "OFFLINE"
        )
        status = f"PROXY: {proxy_status} {connectivity_status}"
        if not logged_in:
            status += ", LOGIN REQUIRED"
        return status

    def bottom_hint_text(self) -> str | None:
        if self.view == "controller_calibration":
            if self.calibration_step == "confirm":
                return "Face buttons only. Press the labeled A button to continue."
            if self.calibration_step == "cancel":
                return "Now press the labeled B button."
            return None

        if self.view != "main":
            if self.view == "smart_cache_prompt":
                return None
            if self.view == "clear_cache_confirm":
                return self.confirm_cancel_hint("confirm", "cancel")
            if self.view == "cache_progress":
                return None
            if self.view == "update_prompt":
                return self.confirm_cancel_hint("install", None)
            cache_result = getattr(self, "cache_result", None)
            if cache_result is not None:
                text, expires_at = cache_result
                if time.monotonic() >= expires_at:
                    self.finish_cache_progress()
                    return None
                return text
            return getattr(self, "smart_cache_progress_text", None)

        if (
            getattr(self, "smart_cache_in_progress", False)
            and getattr(self, "smart_cache_progress_text", None) is not None
        ):
            return self.smart_cache_progress_text

        smart_cache_result = getattr(self, "smart_cache_result", None)
        if smart_cache_result is not None:
            text, expires_at = smart_cache_result
            if time.monotonic() >= expires_at:
                self.smart_cache_result = None
            else:
                return text

        if self.is_logged_in():
            return None

        return "Login to RetroAchievements in system settings."

    def handle_events(self) -> None:
        for event in self.pygame.event.get():
            if event.type == self.pygame.QUIT:
                self.running = False
                return

            if event.type == self.pygame.KEYDOWN:
                self.handle_key(event.key)
                continue

    def handle_raw_input(self) -> None:
        for key in read_keys(self.input_handles):
            if self.handle_calibration_key(key):
                continue

            if key in {KEY_UP, KEY_LEFT, BTN_DPAD_UP, BTN_DPAD_LEFT}:
                self.navigate(-1)
                continue

            if key in {KEY_DOWN, KEY_RIGHT, BTN_DPAD_DOWN, BTN_DPAD_RIGHT}:
                self.navigate(1)
                continue

            if self.is_confirm_key(key):
                self.activate_selected()
                continue

            if self.is_cancel_key(key):
                self.go_back()

    def handle_key(self, key: int) -> None:
        if self.view == "controller_calibration":
            return

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

    def navigate(self, delta: int) -> None:
        items = self.current_labels()
        item_count = max(1, len(items))
        self.selected_index = (self.selected_index + delta) % item_count
        start_y = self.item_start_y()
        gap = self.item_gap()
        self.ensure_selection_visible(items, start_y, gap)

    def activate_selected(self) -> None:
        if self.view == "controller_calibration":
            return

        if self.view == "cached_games":
            self.activate_cached_games_selected()
            return

        if self.view == "pending_awards":
            self.activate_pending_awards_selected()
            return

        if self.view == "pending_award_actions":
            self.activate_pending_award_actions_selected()
            return

        if self.view == "smart_cache_prompt":
            self.activate_smart_cache_prompt_selected()
            return

        if self.view == "clear_cache_confirm":
            self.activate_clear_cache_confirm_selected()
            return

        if self.view == "update_prompt":
            self.activate_update_prompt_selected()
            return

        if self.view == "game_actions":
            self.activate_game_actions_selected()
            return

        if self.view == "file_browser":
            self.activate_file_browser_selected()
            return

        if self.view == "cache_progress":
            if self.selected_index == 0:
                if getattr(self, "cache_completed", False):
                    self.finish_cache_progress()
                else:
                    self.abort_cache_progress()
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

        config_data = getattr(self, "config_data", {})
        if selected_label in {"Enable autostart", "Disable autostart"}:
            self.toggle_autostart(config_data)
            return

        if selected_label.startswith("Cached games"):
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
        labels = self.current_labels()
        selected_label = labels[self.selected_index] if labels else ""

        if selected_label == "Add ROM":
            self.open_file_browser()
            return

        if selected_label == "Start Smart Cache":
            self.start_smart_cache()
            return

        if selected_label == "Clear cache":
            if self.is_knulli_platform():
                self.save_view_position("cached_games")
                self.clear_cache_return_view = "cached_games"
                self.view = "clear_cache_confirm"
                self.reset_selection()
            else:
                self.clear_cache_and_return()
            return

        if selected_label == "Back":
            self.save_view_position("cached_games")
            self.view = "main"
            self.restore_view_position("main")
            return

        game_index = self.selected_index - 2
        if 0 <= game_index < len(self.cached_games):
            self.save_view_position("cached_games")
            self.active_game = self.cached_games[game_index]
            self.refresh_active_game_unlocks()
            self.view = "game_actions"
            self.reset_selection()
            return

    def activate_pending_awards_selected(self) -> None:
        back_index = len(self.pending_awards) * 2
        if self.selected_index == back_index:
            self.save_view_position("pending_awards")
            self.view = "main"
            self.restore_view_position("main")
            return

        award_index = self.selected_index // 2
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

    def activate_smart_cache_prompt_selected(self) -> None:
        if self.selected_index == 0:
            self.start_smart_cache()
            return

        self.dismiss_smart_cache_prompt()

    def activate_clear_cache_confirm_selected(self) -> None:
        if self.selected_index == 0:
            self.clear_cache_and_return()
            return

        self.view = self.clear_cache_return_view
        self.restore_view_position(self.clear_cache_return_view)
        if self.view == "cached_games":
            self.refresh_cached_games()

    def clear_cache_and_return(self) -> None:
        clear_cached_games(self.storage)
        self.active_game = None
        self.refresh_cached_games()
        self.view = self.clear_cache_return_view
        self.restore_view_position(self.clear_cache_return_view)
        self.message = ("Cache cleared", time.monotonic() + 1.5)

    def is_knulli_platform(self) -> bool:
        return Path("/userdata/system").exists()

    def calibration_complete(self) -> bool:
        return (
            self.configured_confirm_button() is not None
            and self.configured_cancel_button() is not None
        )

    def needs_controller_calibration(self) -> bool:
        return not self.calibration_complete()

    def configured_confirm_button(self) -> int | None:
        value = self.config_data.get("controller_confirm_button")
        return self.parse_button_code(value)

    def configured_cancel_button(self) -> int | None:
        value = self.config_data.get("controller_cancel_button")
        return self.parse_button_code(value)

    def parse_button_code(self, value: object) -> int | None:
        if value is None:
            return None

        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def handle_calibration_key(self, key: int) -> bool:
        if self.view != "controller_calibration":
            return False

        if key not in CALIBRATION_FACE_BUTTONS:
            return True

        if self.calibration_step == "confirm":
            self.calibration_confirm_button = key
            self.calibration_step = "cancel"
            self.persist_controller_mapping()
            return True

        if self.calibration_step != "cancel":
            return True

        if key == self.calibration_confirm_button:
            self.message = ("B must be a different button", time.monotonic() + 1.5)
            return True

        self.calibration_cancel_button = key
        self.calibration_step = "done"
        self.persist_controller_mapping()
        self.view = "main"
        self.message = ("Controller mapping saved", time.monotonic() + 1.5)
        self.refresh_main_menu_state(force=True)
        return True

    def persist_controller_mapping(self) -> None:
        self.config_data["controller_confirm_button"] = self.calibration_confirm_button
        if self.calibration_cancel_button is None:
            self.config_data.pop("controller_cancel_button", None)
        else:
            self.config_data["controller_cancel_button"] = self.calibration_cancel_button
        save_config(self.config_data)

    def confirm_button_name(self) -> str:
        return "A" if self.calibration_confirm_button == BTN_SOUTH else "B"

    def cancel_button_name(self) -> str:
        return "A" if self.calibration_cancel_button == BTN_SOUTH else "B"

    def confirm_cancel_hint(self, confirm_action: str, cancel_action: str | None) -> str:
        confirm_label = self.confirm_button_name()
        if cancel_action is None:
            return f"Press START or {confirm_label} to {confirm_action}."

        cancel_label = self.cancel_button_name()
        return (
            f"Press {confirm_label} or START to {confirm_action}. "
            f"{cancel_label} to {cancel_action}."
        )

    def is_confirm_key(self, key: int) -> bool:
        confirm_key = self.calibration_confirm_button or BTN_SOUTH
        return key in {KEY_ENTER, KEY_SPACE, KEY_S, confirm_key, BTN_START}

    def is_cancel_key(self, key: int) -> bool:
        cancel_key = self.calibration_cancel_button or BTN_EAST
        return key in {KEY_ESC, KEY_BACKSPACE, KEY_Q, cancel_key, BTN_SELECT}

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
            self.clear_active_game_unlocks()
            self.refresh_cached_games()
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            self.message = (f"Removed {removed_title}", time.monotonic() + 1.5)
            return

        if self.selected_index == len(self.game_actions_unlock_titles()) + 1:
            self.active_game = None
            self.clear_active_game_unlocks()
            self.view = "cached_games"
            self.restore_view_position("cached_games")

    def activate_file_browser_selected(self) -> None:
        if self.browser_dir is None:
            self.view = "cached_games"
            self.reset_selection()
            return

        root = resolve_rom_root(load_config())
        has_add_folder = self.browser_has_cacheable_files()
        has_parent = (
            self.browser_dir.parent != self.browser_dir and self.browser_dir != root
        )
        first_entry_index = 1 if has_add_folder else 0
        parent_index = first_entry_index if has_parent else None
        if has_parent:
            first_entry_index += 1
        cancel_index = first_entry_index + len(self.browser_entries)
        if has_add_folder and self.selected_index == 0:
            self.start_folder_cache_for_browser_dir()
            return
        if parent_index is not None and self.selected_index == parent_index:
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

        self.start_single_rom_cache(entry)

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
        self.achievement_preview_surface = None
        self.achievement_preview_game_id = None
        self.achievement_preview_title = None

    def refresh_pending_awards(self) -> None:
        self.pending_awards = list_pending_awards(self.storage)

    def browser_has_cacheable_files(self) -> bool:
        return any(entry.is_file() for entry in self.browser_entries)

    def start_single_rom_cache(self, path: Path) -> None:
        self.save_browser_position()
        self.cache_progress_title = f"Caching: {path.name}"
        self.cache_progress_text = "Preparing cache..."
        self.cache_result = None
        self.cache_abort_requested = False
        self.cache_completed = False
        self.cache_completion_message = None
        self.cache_return_view = "file_browser"
        self.cache_return_browser_dir = self.browser_dir
        self.cache_return_browser_restore = True
        self.view = "cache_progress"

        def worker() -> None:
            try:
                result = add_rom_to_cache(path, self.storage, load_config())
                aborted = self.cache_abort_requested
                self.cache_result = (result.message, time.monotonic() + 1.5)
                self.cache_completion_message = (
                    "Aborted: scanned 1, cached 1, skipped 0"
                    if aborted and result.success
                    else result.message
                    if not result.success
                    else "Aborted: scanned 1, cached 0, skipped 1"
                    if aborted
                    else "Scanned 1, cached 1, skipped 0"
                    if result.success
                    else "Scanned 1, cached 0, skipped 1"
                )
                self.cache_completed = True
            except Exception as exc:
                self.cache_result = (
                    f"Cache failed: {exc}",
                    time.monotonic() + ERROR_SECONDS,
                )
                self.cache_completion_message = f"Cache failed: {exc}"
                self.cache_completed = True
            finally:
                if self.view == "cache_progress":
                    self.cache_progress_text = None

        self.cache_worker_thread = threading.Thread(target=worker, daemon=True)
        self.cache_worker_thread.start()

    def start_folder_cache_for_browser_dir(self) -> None:
        if self.browser_dir is None:
            return

        current_dir = self.browser_dir
        cache_paths = list_browser_files_fast(current_dir)
        self.save_browser_position()
        self.cache_progress_title = f"Caching: {current_dir.name}"
        if cache_paths:
            self.cache_progress_text = (
                f"Caching 1/{len(cache_paths)}: {cache_paths[0].name}"
            )
        else:
            self.cache_progress_text = "Preparing cache..."
        self.cache_result = None
        self.cache_abort_requested = False
        self.cache_completed = False
        self.cache_completion_message = None
        self.cache_return_view = "cached_games"
        self.cache_return_browser_dir = current_dir
        self.cache_return_browser_restore = False
        self.view = "cache_progress"

        def worker() -> None:
            try:
                result = run_folder_cache(
                    self.storage,
                    load_config(),
                    current_dir,
                    paths=cache_paths,
                    should_abort=lambda: self.cache_abort_requested,
                    on_progress=self.update_cache_progress,
                )
                if result.total <= 0:
                    self.cache_result = (
                        "No ROM files in this folder",
                        time.monotonic() + ERROR_SECONDS,
                    )
                    self.cache_completion_message = (
                        "Aborted: scanned 0, cached 0, skipped 0"
                        if self.cache_abort_requested
                        else "Scanned 0, cached 0, skipped 0"
                    )
                    self.cache_completed = True
                else:
                    self.cache_result = (
                        f"Folder cache complete: {result.cached} / {result.total}",
                        time.monotonic() + 1.5,
                    )
                    self.cache_completion_message = (
                        f"Aborted: scanned {result.scanned}, cached {result.cached}, skipped {result.skipped}"
                        if self.cache_abort_requested
                        else f"Scanned {result.scanned}, cached {result.cached}, skipped {result.skipped}"
                    )
                    self.cache_completed = True
            except Exception as exc:
                self.cache_result = (
                    f"Folder cache failed: {exc}",
                    time.monotonic() + ERROR_SECONDS,
                )
                self.cache_completion_message = f"Folder cache failed: {exc}"
                self.cache_completed = True
            finally:
                if self.view == "cache_progress":
                    self.cache_progress_text = None

        self.cache_worker_thread = threading.Thread(target=worker, daemon=True)
        self.cache_worker_thread.start()

    def update_cache_progress(self, progress) -> None:
        self.cache_progress_text = (
            f"Caching {progress.scanned}/{progress.total}: {progress.current_label}"
        )

    def finish_cache_progress(self) -> None:
        return_view = self.cache_return_view
        return_browser_dir = self.cache_return_browser_dir
        return_browser_restore = self.cache_return_browser_restore
        self.cache_result = None
        self.cache_progress_text = None
        self.cache_progress_title = None
        self.cache_abort_requested = False
        self.cache_completed = False
        self.cache_completion_message = None
        self.cache_return_view = "cached_games"
        self.cache_return_browser_dir = None
        self.cache_return_browser_restore = False
        self.refresh_cached_games()
        if return_view == "file_browser" and return_browser_dir is not None:
            self.view = "file_browser"
            self.set_browser_dir(return_browser_dir, restore=return_browser_restore)
            return
        if return_view == "main":
            self.view = "main"
            self.refresh_main_menu_state(force=True)
            self.restore_view_position("main")
            return
        self.view = "cached_games"
        self.restore_view_position("cached_games")

    def abort_cache_progress(self) -> None:
        if self.cache_completed:
            self.finish_cache_progress()
            return

        self.cache_abort_requested = True
        self.cache_progress_text = "Aborting..."

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
            self.refresh_main_menu_state(force=True)
            self.restore_view_position("main")
            return

        if self.view == "pending_awards":
            self.save_view_position("pending_awards")
            self.view = "main"
            self.refresh_main_menu_state(force=True)
            self.restore_view_position("main")
            return

        if self.view == "smart_cache_prompt":
            self.dismiss_smart_cache_prompt()
            return

        if self.view == "clear_cache_confirm":
            self.view = self.clear_cache_return_view
            self.restore_view_position(self.clear_cache_return_view)
            if self.view == "cached_games":
                self.refresh_cached_games()
            return

        if self.view == "update_prompt":
            self.dismiss_update_prompt()
            return

        if self.view == "cache_progress":
            return

        if self.view == "pending_award_actions":
            self.active_pending_award = None
            self.view = "pending_awards"
            self.restore_view_position("pending_awards")
            return

        if self.view == "game_actions":
            self.active_game = None
            self.clear_active_game_unlocks()
            self.view = "cached_games"
            self.restore_view_position("cached_games")
            return

        self.running = False

    def render_game_preview(self) -> None:
        game = self.preview_target_game()
        if game is None:
            self.preview_surface = None
            self.preview_game_id = None
            self.achievement_preview_surface = None
            self.achievement_preview_game_id = None
            self.achievement_preview_title = None
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

        achievement_surface = self.current_achievement_preview_surface()
        if achievement_surface is None:
            return

        award_rect = achievement_surface.get_rect(
            right=preview_rect.left - 16,
            centery=preview_rect.centery,
        )
        self.surface.blit(achievement_surface, award_rect)

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
            game_index = self.selected_index - 2
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

    def current_achievement_preview_surface(self):
        if self.view != "game_actions" or self.active_game is None:
            self.achievement_preview_surface = None
            self.achievement_preview_game_id = None
            self.achievement_preview_title = None
            return None

        unlock_index = self.selected_index - 1
        unlock_titles = self.game_actions_unlock_titles()
        if unlock_index < 0 or unlock_index >= len(unlock_titles):
            self.achievement_preview_surface = None
            self.achievement_preview_game_id = None
            self.achievement_preview_title = None
            return None

        title = unlock_titles[unlock_index]
        if (
            self.achievement_preview_surface is not None
            and self.achievement_preview_game_id == self.active_game.game_id
            and self.achievement_preview_title == title
        ):
            return self.achievement_preview_surface

        self.achievement_preview_surface = self.load_achievement_preview_surface(
            self.active_game.game_id, title
        )
        self.achievement_preview_game_id = self.active_game.game_id
        self.achievement_preview_title = title
        return self.achievement_preview_surface

    def load_achievement_preview_surface(self, game_id: int, title: str):
        try:
            badge_path = cached_unlock_badge_path(self.storage, game_id, title)
            if badge_path is None:
                return None

            image = self.pygame.image.load(str(badge_path))
            image = (
                image.convert_alpha()
                if image.get_alpha() is not None
                else image.convert()
            )
            scaled_size = self.fit_achievement_preview_size(
                image.get_width(), image.get_height()
            )
            return self.pygame.transform.smoothscale(image, scaled_size)
        except Exception as exc:
            log_menu_sdl(
                f"achievement preview load failed gameId={game_id} title={title} error={exc}"
            )
            return None

    def fit_achievement_preview_size(self, width: int, height: int) -> tuple[int, int]:
        max_width = max(64, self.width // 8)
        max_height = max(64, self.height // 6)
        scale = min(max_width / max(1, width), max_height / max(1, height), 1.0)
        return max(1, int(width * scale)), max(1, int(height * scale))

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

    def maybe_offer_smart_cache(self) -> None:
        self.refresh_main_menu_state(force=True)
        self.refresh_cached_games()
        if self.cached_games:
            self.smart_cache_prompt_available = False
            self.smart_cache_prompt_count = 0
            return

        status = should_offer_smart_cache(
            self.storage,
            self.config_data,
            is_online=bool(getattr(self, "main_online", False)),
            has_credentials=bool(getattr(self, "main_logged_in", False)),
        )
        self.smart_cache_prompt_available = status.found_history
        self.smart_cache_prompt_count = status.total_candidates
        if not status.found_history:
            return

        self.save_view_position("main")
        self.view = "smart_cache_prompt"
        self.reset_selection()

    def dismiss_smart_cache_prompt(self) -> None:
        self.smart_cache_prompt_available = False
        self.smart_cache_prompt_count = 0
        self.view = "main"
        self.restore_view_position("main")

    def start_smart_cache(self) -> None:
        if self.smart_cache_in_progress:
            return

        history_paths = load_content_history_paths(self.config_data)
        total_candidates = min(len(history_paths), SMART_CACHE_LIMIT)
        self.smart_cache_in_progress = True
        self.cache_progress_title = "Smart Cache"
        if total_candidates > 0:
            self.cache_progress_text = (
                f"Caching 1/{total_candidates}: {history_paths[0].name}"
            )
        else:
            self.cache_progress_text = "Preparing cache..."
        self.cache_completed = False
        self.cache_abort_requested = False
        self.cache_completion_message = None
        self.cache_return_view = "main"
        self.cache_return_browser_dir = None
        self.cache_return_browser_restore = False
        self.smart_cache_result = None
        self.smart_cache_progress_text = None
        self.view = "cache_progress"
        self.reset_selection()

        def worker() -> None:
            try:
                result = run_smart_cache(
                    self.storage,
                    self.config_data,
                    SMART_CACHE_LIMIT,
                    should_abort=lambda: self.cache_abort_requested,
                    on_progress=self.update_smart_cache_progress,
                )
                self.cache_completion_message = (
                    f"Aborted: scanned {result.scanned}, cached {result.cached}, skipped {result.skipped}"
                    if self.cache_abort_requested
                    else f"Scanned {result.scanned}, cached {result.cached}, skipped {result.skipped}"
                )
                self.cache_completed = True
            except Exception as exc:
                self.cache_completion_message = f"Smart Cache failed: {exc}"
                self.cache_completed = True
            finally:
                self.smart_cache_in_progress = False
                if self.view == "cache_progress" and not self.cache_completed:
                    self.cache_progress_text = None

        self.smart_cache_thread = threading.Thread(target=worker, daemon=True)
        self.smart_cache_thread.start()

    def update_smart_cache_progress(self, progress) -> None:
        self.cache_progress_text = (
            f"Caching {progress.scanned}/{progress.total}: {progress.current_label}"
        )

    def refresh_main_menu_state(self, force: bool = False) -> None:
        if not hasattr(self, "main_state_refreshed_at"):
            self.main_state_refreshed_at = 0.0
        if not hasattr(self, "config_data"):
            self.config_data = {}
        if not hasattr(self, "main_running"):
            self.main_running = False
        if not hasattr(self, "main_online"):
            self.main_online = False
        if not hasattr(self, "main_logged_in"):
            self.main_logged_in = False
        if not hasattr(self, "main_autostart_supported"):
            self.main_autostart_supported = False
        if not hasattr(self, "main_autostart_enabled"):
            self.main_autostart_enabled = False
        if not hasattr(self, "main_update_available"):
            self.main_update_available = False
        if not hasattr(self, "main_update_version"):
            self.main_update_version = None
        if not hasattr(self, "main_update_asset_url"):
            self.main_update_asset_url = None
        if not hasattr(self, "main_update_dialog_seen"):
            self.main_update_dialog_seen = False

        if not force and self.view != "main":
            return

        now = time.monotonic()
        if (
            not force
            and (now - self.main_state_refreshed_at) < MAIN_MENU_STATE_REFRESH_SECONDS
        ):
            return

        self.config_data = load_config()
        self.main_running = self.read_proxy_running()
        self.main_online = online_check(self.config_data)
        self.main_logged_in = self.is_logged_in(self.config_data)
        self.main_autostart_supported = autostart_supported(self.config_data)
        self.main_autostart_enabled = (
            self.main_autostart_supported and autostart_enabled(self.config_data)
        )
        if force:
            try:
                update = update_status("knulli")
                self.main_update_available = update.update_available
                self.main_update_version = update.latest_version
                self.main_update_asset_url = update.asset_url
            except Exception as exc:
                log_menu_sdl(f"update check failed error={exc}")
                self.main_update_available = False
                self.main_update_version = None
                self.main_update_asset_url = None
        self.main_state_refreshed_at = now
        if (
            self.view == "main"
            and self.main_update_available
            and not self.main_update_dialog_seen
        ):
            self.main_update_dialog_seen = True
            self.save_view_position("main")
            self.view = "update_prompt"
            self.reset_selection()

    def dismiss_update_prompt(self) -> None:
        self.view = "main"
        self.restore_view_position("main")

    def activate_update_prompt_selected(self) -> None:
        if self.selected_index == 0:
            self.install_update()
            return
        self.dismiss_update_prompt()

    def clear_active_game_unlocks(self) -> None:
        self.active_game_unlock_game_id = None
        self.active_game_unlock_count_cached = None
        self.active_game_unlock_titles_cached = []
        self.achievement_preview_surface = None
        self.achievement_preview_game_id = None
        self.achievement_preview_title = None

    def refresh_active_game_unlocks(self) -> None:
        if self.active_game is None:
            self.clear_active_game_unlocks()
            return

        self.active_game_unlock_game_id = self.active_game.game_id
        self.active_game_unlock_count_cached = cached_unlock_count(
            self.storage, self.active_game.game_id
        )
        self.active_game_unlock_titles_cached = cached_unlock_titles(
            self.storage, self.active_game.game_id
        )

    def save_view_position(self, view: str | None = None) -> None:
        key = view or self.view
        self.view_positions[key] = (self.selected_index, self.scroll_offset)

    def restore_view_position(self, view: str) -> None:
        saved = self.view_positions.get(view)
        if saved is None:
            self.reset_selection()
            return

        self.selected_index, self.scroll_offset = saved

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
        clear_cache_index = len(self.cached_games) + 2
        first_game_index = 2 if self.view == "cached_games" else 1
        last_game_index = len(self.cached_games) + 1 if self.view == "cached_games" else len(self.cached_games)
        for index, _label in enumerate(items):
            positions.append(current_y)
            current_y += gap
            if self.view == "cached_games" and index == 1:
                current_y += GROUP_GAP
            if self.view == "cached_games" and index == last_game_index and index >= first_game_index:
                current_y += GROUP_GAP
            if self.view == "cached_games" and clear_cache_index == first_game_index and index == 1:
                current_y -= GROUP_GAP
            if self.view == "game_actions" and index == 0:
                current_y += GROUP_GAP
            if self.view == "game_actions" and index == len(items) - 2:
                current_y += GROUP_GAP
            if self.view == "file_browser" and index == 0 and self.browser_has_cacheable_files():
                current_y += GROUP_GAP
            if self.view == "pending_awards" and index % 2 == 1:
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

    def read_proxy_running(self) -> bool:
        try:
            service = service_status() or {}
        except Exception:
            return False
        return bool(service.get("running"))

    def proxy_running(self) -> bool:
        if self.view == "main":
            self.refresh_main_menu_state()
            return self.main_running

        return self.read_proxy_running()

    def start_proxy(self) -> None:
        try:
            start_proxy_inline()
            self.refresh_main_menu_state(force=True)
            self.maybe_offer_smart_cache()
            if self.view != "smart_cache_prompt":
                self.message = ("Proxy started", time.monotonic() + 1.2)
        except Exception as exc:
            self.message = (f"Start failed: {exc}", time.monotonic() + ERROR_SECONDS)

    def stop_proxy(self) -> None:
        try:
            stop_proxy_inline()
            self.refresh_main_menu_state(force=True)
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
            self.refresh_main_menu_state(force=True)
        except Exception as exc:
            self.message = (
                f"Autostart failed: {exc}",
                time.monotonic() + ERROR_SECONDS,
            )

    def install_update(self) -> None:
        asset_url = getattr(self, "main_update_asset_url", None)
        if not asset_url:
            update = update_status("knulli", force=True)
            asset_url = update.asset_url
            self.main_update_asset_url = asset_url
            self.main_update_version = update.latest_version
            self.main_update_available = update.update_available
        if not asset_url:
            self.message = ("Update install failed: no installer URL", time.monotonic() + ERROR_SECONDS)
            self.dismiss_update_prompt()
            return

        try:
            stop_proxy_inline()
            installer_path = download_knulli_update_installer(asset_url)
            self.storage.close()
            close_input_devices(self.input_handles)
            self.pygame.quit()
            os.execv(str(installer_path), [str(installer_path)])
        except Exception as exc:
            self.message = (f"Update install failed: {exc}", time.monotonic() + ERROR_SECONDS)
            self.dismiss_update_prompt()

    def uninstall(self) -> None:
        if running_on_muos():
            launcher = "/run/muos/storage/application/RAOfflineProxy/uninstall.sh"
        elif self.is_knulli_platform():
            launcher = "/userdata/system/raofflineproxy/bin/raofflineproxy-uninstall"
        else:
            self.message = ("Uninstall is not available on this platform", time.monotonic() + ERROR_SECONDS)
            return

        try:
            self.storage.close()
        except Exception:
            pass
        try:
            close_input_devices(self.input_handles)
        except Exception:
            pass
        os.execv(launcher, [launcher])
