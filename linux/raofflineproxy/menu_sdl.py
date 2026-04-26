import os
import subprocess
import time
from pathlib import Path

from .batocera_conf import patch_batocera_conf, revert_batocera_conf
from .config import CONFIG_DIR, detect_retroarch_cfg, load_config
from .retroarch_cfg import patch_retroarch_cfg, revert_retroarch_cfg
from .service import service_status, start_service_process, stop_service_process
from .state import load_patch_state, save_patch_state
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
TEXT_COLOR = (255, 255, 255)
SELECTED_COLOR = (255, 220, 120)
STATUS_COLOR = (180, 180, 180)
ERROR_SECONDS = 3
FPS = 30


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
    cfg_path = config_data.get("retroarch_cfg") or detect_retroarch_cfg()
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
        self.selected_index = 0
        self.last_navigation_at = 0.0
        self.message: tuple[str, float] | None = None
        self.input_handles = open_input_devices()
        self.title_font = pygame.font.Font(None, max(42, height // 14))
        self.status_font = pygame.font.Font(None, max(28, height // 24))
        self.item_font = pygame.font.Font(None, max(52, height // 11))
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

    def run(self) -> None:
        try:
            while self.running:
                self.handle_events()
                self.handle_raw_input()
                self.render()
                self.clock.tick(FPS)
        finally:
            close_input_devices(self.input_handles)

    def render(self) -> None:
        self.surface.fill(BACKGROUND_COLOR)

        title = self.title_font.render("RAOFFLINEPROXY MENU SDL", True, TEXT_COLOR)
        title_rect = title.get_rect(
            center=(self.width // 2, max(50, self.height // 10))
        )
        self.surface.blit(title, title_rect)

        running = self.proxy_running()
        status_text = "PROXY: RUNNING" if running else "PROXY: STOPPED"
        status = self.status_font.render(status_text, True, STATUS_COLOR)
        status_rect = status.get_rect(center=(self.width // 2, title_rect.bottom + 40))
        self.surface.blit(status, status_rect)

        items = self.labels(running)
        start_y = status_rect.bottom + 70
        gap = max(54, self.height // 10)
        for index, label in enumerate(items):
            color = SELECTED_COLOR if index == self.selected_index else TEXT_COLOR
            prefix = "> " if index == self.selected_index else "  "
            text = self.item_font.render(f"{prefix}{label}", True, color)
            rect = text.get_rect(center=(self.width // 2, start_y + (index * gap)))
            self.surface.blit(text, rect)

        if self.message is not None:
            text, expires_at = self.message
            if time.monotonic() >= expires_at:
                self.message = None
            else:
                overlay = self.status_font.render(text, True, SELECTED_COLOR)
                overlay_rect = overlay.get_rect(
                    center=(self.width // 2, self.height - 60)
                )
                self.surface.blit(overlay, overlay_rect)

        self.pygame.display.flip()

    def labels(self, running: bool) -> list[str]:
        toggle = "STOP PROXY" if running else "START PROXY"
        return [toggle, "UNINSTALL", "EXIT"]

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
            self.running = False

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
            self.running = False

    def handle_joy_button(self, button: int) -> None:
        if button in {0, 7}:
            self.activate_selected()
            return
        if button in {1, 6}:
            self.running = False

    def navigate(self, delta: int) -> None:
        now = time.monotonic()
        if (now - self.last_navigation_at) < 0.08:
            return
        self.last_navigation_at = now
        self.selected_index = (self.selected_index + delta) % 3
        log_menu_sdl(f"navigate delta={delta} selected={self.selected_index}")

    def activate_selected(self) -> None:
        running = self.proxy_running()
        if self.selected_index == 0:
            if running:
                self.stop_proxy()
            else:
                self.start_proxy()
            return
        if self.selected_index == 1:
            self.uninstall()
            return
        self.running = False

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

    def uninstall(self) -> None:
        self.running = False
        subprocess.run(
            ["/userdata/system/raofflineproxy/bin/raofflineproxy-uninstall"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
