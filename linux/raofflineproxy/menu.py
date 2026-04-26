import os
import select
import struct
import subprocess
import time
from pathlib import Path

from .batocera_conf import patch_batocera_conf, revert_batocera_conf
from .config import CONFIG_DIR, detect_retroarch_cfg, load_config
from .retroarch_cfg import patch_retroarch_cfg, revert_retroarch_cfg
from .service import service_status, start_service_process, stop_service_process
from .state import load_patch_state, save_patch_state
from .ui import render_text_pixels, write_bmp

EVENT_SIZE = struct.calcsize("llHHi")
EV_KEY = 0x01
EV_ABS = 0x03
ABS_HAT0X = 16
ABS_HAT0Y = 17
KEY_UP = 103
KEY_DOWN = 108
KEY_LEFT = 105
KEY_RIGHT = 106
KEY_ENTER = 28
KEY_SPACE = 57
KEY_ESC = 1
KEY_BACKSPACE = 14
KEY_Q = 16
KEY_S = 31
BTN_SOUTH = 304
BTN_EAST = 305
BTN_SELECT = 314
BTN_START = 315
BTN_DPAD_UP = 544
BTN_DPAD_DOWN = 545
BTN_DPAD_LEFT = 546
BTN_DPAD_RIGHT = 547
INPUT_DIR = Path("/dev/input")
FB_SIZE_PATH = Path("/sys/class/graphics/fb0/virtual_size")
MENU_LOG_PATH = CONFIG_DIR / "menu.log"
ACTION_SECONDS = 6
POLL_TIMEOUT_SECONDS = 0.10
STALE_HOOK_PATH = Path("/userdata/system/scripts/RAOfflineProxy_game_hook.sh")
STARTUP_OPEN_DELAY_SECONDS = 0.8


def run_menu(command_runner: str) -> None:
    width, height = detect_resolution()
    log_menu(f"run_menu start width={width} height={height}")
    log_menu(f"startup open delay={STARTUP_OPEN_DELAY_SECONDS}")
    time.sleep(STARTUP_OPEN_DELAY_SECONDS)
    menu = MenuSession(command_runner, width, height)
    menu.run()


def detect_resolution() -> tuple[int, int]:
    if FB_SIZE_PATH.exists():
        try:
            raw_value = FB_SIZE_PATH.read_text(encoding="ascii").strip()
            width_text, height_text = raw_value.split(",", maxsplit=1)
            width = int(width_text)
            height = int(height_text)
            if width > 0 and height > 0:
                return width, height
        except Exception:
            pass
    return 640, 480


def remove_stale_hook() -> None:
    if STALE_HOOK_PATH.exists():
        STALE_HOOK_PATH.unlink()


def log_menu(message: str) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with MENU_LOG_PATH.open("a", encoding="utf-8") as handle:
        handle.write(f"{timestamp} {message}\n")


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


class MenuSession:
    def __init__(
        self, command_runner: str, image_width: int, image_height: int
    ) -> None:
        self.command_runner = command_runner
        self.image_width = image_width
        self.image_height = image_height
        self.selected_index = 0
        self.running = True
        self.input_handles = open_input_devices()
        self.presenter = FbvMenuPresenter(image_width, image_height)
        self.menu_frame_cache: dict[tuple[bool, int], Path] = {}
        log_menu(
            f"MenuSession init width={image_width} height={image_height} input_handles={len(self.input_handles)}"
        )

    def run(self) -> None:
        try:
            try:
                log_menu("menu loop entering")
                self.prewarm_menu_frames()
                self.render_menu()
                while self.running:
                    key = read_key(self.input_handles)
                    if key is None:
                        continue
                    log_menu(f"input key={key}")
                    self.handle_key(key)
            except Exception as exc:
                log_menu(f"menu exception={exc}")
                self.show_timed_text(
                    f"RAOFFLINEPROXY MENU ERROR\n\n{exc}", ACTION_SECONDS, 2
                )
        finally:
            log_menu("menu loop exiting")
            close_input_devices(self.input_handles)
            self.presenter.close()

    def render_menu(self) -> None:
        proxy_running = self.proxy_running()
        self.render_menu_for_state(proxy_running)

    def render_menu_for_state(self, proxy_running: bool) -> None:
        cache_key = (proxy_running, self.selected_index)
        cached_image = self.menu_frame_cache.get(cache_key)
        if cached_image is not None:
            log_menu(f"render_menu cache_hit state={cache_key}")
            self.presenter.display_image(cached_image)
            return

        image_path = self.presenter.render_text_image(
            self.menu_text(proxy_running, self.selected_index),
            font_scale=2,
            image_key=f"menu-{int(proxy_running)}-{self.selected_index}",
        )
        self.menu_frame_cache[cache_key] = image_path
        log_menu(f"render_menu cache_miss state={cache_key}")
        self.presenter.display_image(image_path)

    def prewarm_menu_frames(self) -> None:
        for proxy_running in (False, True):
            for selected_index in range(3):
                cache_key = (proxy_running, selected_index)
                if cache_key in self.menu_frame_cache:
                    continue

                image_path = self.presenter.render_text_image(
                    self.menu_text(proxy_running, selected_index),
                    font_scale=2,
                    image_key=f"menu-{int(proxy_running)}-{selected_index}",
                )
                self.menu_frame_cache[cache_key] = image_path
                log_menu(f"prewarm_menu_frames cached state={cache_key}")

    def menu_text(self, proxy_running: bool, selected_index: int) -> str:
        toggle_label = "STOP PROXY" if proxy_running else "START PROXY"
        labels = [toggle_label, "UNINSTALL", "EXIT"]
        lines = [
            "RAOFFLINEPROXY MENU",
            "",
            f"PROXY: {'RUNNING' if proxy_running else 'STOPPED'}",
            "",
        ]

        for index, label in enumerate(labels):
            prefix = ">" if index == selected_index else " "
            lines.append(f"{prefix} {label}")

        return "\n".join(lines)

    def entries(self) -> list["MenuEntry"]:
        toggle_entry = (
            MenuEntry("STOP PROXY", self.stop_proxy)
            if self.proxy_running()
            else MenuEntry("START PROXY", self.start_proxy)
        )

        entries = [
            toggle_entry,
            MenuEntry("UNINSTALL", self.uninstall),
            MenuEntry("EXIT", self.exit_menu),
        ]

        if self.selected_index >= len(entries):
            self.selected_index = len(entries) - 1

        return entries

    def proxy_running(self) -> bool:
        try:
            service = service_status() or {}
        except Exception:
            log_menu("proxy_running status_error")
            return False
        log_menu(
            f"proxy_running running={bool(service.get('running'))} pid={service.get('pid')}"
        )
        return bool(service.get("running"))

    def handle_key(self, key: int) -> None:
        proxy_running = self.proxy_running()

        if key in {KEY_UP, KEY_LEFT, BTN_DPAD_UP, BTN_DPAD_LEFT}:
            self.selected_index = (self.selected_index - 1) % len(self.entries())
            self.render_menu_for_state(proxy_running)
            return

        if key in {KEY_DOWN, KEY_RIGHT, BTN_DPAD_DOWN, BTN_DPAD_RIGHT}:
            self.selected_index = (self.selected_index + 1) % len(self.entries())
            self.render_menu_for_state(proxy_running)
            return

        if key in {KEY_ENTER, KEY_SPACE, KEY_S, BTN_SOUTH, BTN_START}:
            self.entries()[self.selected_index].action()
            if self.running:
                self.render_menu()
            return

        if key in {KEY_ESC, KEY_BACKSPACE, KEY_Q, BTN_EAST, BTN_SELECT}:
            self.exit_menu()

    def start_proxy(self) -> None:
        try:
            log_menu("start_proxy begin")
            start_proxy_inline()
            self.menu_frame_cache.clear()
            log_menu("start_proxy success")
        except Exception as exc:
            log_menu(f"start_proxy error={exc}")
            self.show_timed_text(
                f"RAOFFLINEPROXY START FAILED\n\n{exc}", ACTION_SECONDS, 2
            )

    def stop_proxy(self) -> None:
        try:
            log_menu("stop_proxy begin")
            stop_proxy_inline()
            self.menu_frame_cache.clear()
            log_menu("stop_proxy success")
        except Exception as exc:
            log_menu(f"stop_proxy error={exc}")
            self.show_timed_text(
                f"RAOFFLINEPROXY STOP FAILED\n\n{exc}", ACTION_SECONDS, 2
            )

    def uninstall(self) -> None:
        log_menu("uninstall begin")
        self.presenter.close()
        self.running = False
        subprocess.run(
            ["/userdata/system/raofflineproxy/bin/raofflineproxy-uninstall"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )

    def exit_menu(self) -> None:
        log_menu("exit_menu")
        self.running = False

    def show_timed_text(self, text: str, seconds: int, font_scale: int) -> None:
        self.presenter.display_text(text, font_scale=font_scale, image_key="message")
        time.sleep(seconds)


class MenuEntry:
    def __init__(self, label: str, action) -> None:
        self.label = label
        self.action = action


class FbvMenuPresenter:
    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        self.current_image_path: Path | None = None
        self.viewer_process: subprocess.Popen | None = None
        log_menu(f"fbv presenter init width={width} height={height}")

    def render_text_image(self, text: str, font_scale: int, image_key: str) -> Path:
        image_path = Path(f"/userdata/system/raofflineproxy/{image_key}.bmp")
        pixels, image_width, image_height = render_text_pixels(
            text,
            image_width=self.width,
            image_height=self.height,
            font_scale=font_scale,
        )
        image_path.parent.mkdir(parents=True, exist_ok=True)
        write_bmp(image_path, pixels, image_width, image_height)
        return image_path

    def display_text(self, text: str, font_scale: int, image_key: str) -> None:
        image_path = self.render_text_image(text, font_scale, image_key)
        self.display_image(image_path)

    def display_image(self, image_path: Path) -> None:
        if (
            self.current_image_path == image_path
            and self.viewer_process is not None
            and self.viewer_process.poll() is None
        ):
            log_menu(f"fbv display_image reuse path={image_path}")
            return

        self.stop_viewer()
        log_menu(f"fbv display_image start path={image_path}")
        self.viewer_process = subprocess.Popen(
            ["fbv", "-i", "-c", "-u", str(image_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.current_image_path = image_path
        time.sleep(0.01)

    def stop_viewer(self) -> None:
        if self.viewer_process is None:
            return
        log_menu("fbv stop_viewer")
        if self.viewer_process.poll() is None:
            self.viewer_process.kill()
            try:
                self.viewer_process.wait(timeout=0.05)
            except subprocess.TimeoutExpired:
                pass
        self.viewer_process = None

    def close(self) -> None:
        log_menu("fbv presenter close")
        self.stop_viewer()


def open_input_devices() -> list[int]:
    handles: list[int] = []
    if not INPUT_DIR.exists():
        return handles

    for path in sorted(INPUT_DIR.glob("event*")):
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
        except OSError:
            continue
        handles.append(fd)
        log_menu(f"input open path={path} fd={fd}")

    return handles


def close_input_devices(handles: list[int]) -> None:
    for fd in handles:
        try:
            os.close(fd)
            log_menu(f"input close fd={fd}")
        except OSError:
            pass


def read_key(handles: list[int]) -> int | None:
    if not handles:
        return None

    try:
        ready, _, _ = select.select(handles, [], [], POLL_TIMEOUT_SECONDS)
        for fd in ready:
            while True:
                try:
                    packet = os.read(fd, EVENT_SIZE)
                except BlockingIOError:
                    break
                except OSError:
                    break

                if len(packet) != EVENT_SIZE:
                    break

                _, _, event_type, code, value = struct.unpack("llHHi", packet)
                if event_type == EV_KEY and value == 1:
                    log_menu(f"read_key ev_key code={code} value={value}")
                    return code

                if event_type == EV_ABS:
                    if code == ABS_HAT0Y and value == -1:
                        log_menu("read_key abs up")
                        return KEY_UP
                    if code == ABS_HAT0Y and value == 1:
                        log_menu("read_key abs down")
                        return KEY_DOWN
                    if code == ABS_HAT0X and value == -1:
                        log_menu("read_key abs left")
                        return KEY_LEFT
                    if code == ABS_HAT0X and value == 1:
                        log_menu("read_key abs right")
                        return KEY_RIGHT
    except OSError:
        return None

    return None
