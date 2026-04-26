import mmap
import os
import select
import struct
import subprocess
import time
from pathlib import Path

from .batocera_conf import patch_batocera_conf, revert_batocera_conf
from .config import detect_retroarch_cfg, load_config
from .retroarch_cfg import patch_retroarch_cfg, revert_retroarch_cfg
from .service import service_status, start_service_process, stop_service_process
from .state import load_patch_state, save_patch_state
from .ui import (
    GLYPH_HEIGHT,
    GLYPH_SPACING,
    GLYPH_WIDTH,
    MARGIN_X,
    MARGIN_Y,
    normalize_glyph,
    normalize_text_lines,
    normalize_font_scale,
    render_text_pixels,
    scaled_line_height,
    write_bmp,
)

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
FB_PATH = Path("/dev/fb0")
FB_BITS_PER_PIXEL_PATH = Path("/sys/class/graphics/fb0/bits_per_pixel")
FB_STRIDE_PATH = Path("/sys/class/graphics/fb0/stride")
MENU_IMAGE_PATH = Path("/userdata/system/raofflineproxy/menu.bmp")
STATUS_SECONDS = 15
ACTION_SECONDS = 6
POLL_TIMEOUT_SECONDS = 0.10
STALE_HOOK_PATH = Path("/userdata/system/scripts/RAOfflineProxy_game_hook.sh")
MENU_BOOTSTRAP_IMAGE_PATH = Path("/userdata/system/raofflineproxy/menu-bootstrap.bmp")


def run_menu(command_runner: str) -> None:
    width, height = detect_resolution()
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
        self.framebuffer = FramebufferRenderer(image_width, image_height)
        self.menu_frame_cache: dict[tuple[bool, int], bytes] = {}
        self.last_frame: bytes | None = None
        self.bootstrap_shown = False
        self.bootstrap_active = False

    def run(self) -> None:
        try:
            try:
                self.render_menu()
                while self.running:
                    key = read_key(self.input_handles)
                    if key is None:
                        continue
                    self.handle_key(key)
            except Exception as exc:
                self.show_timed_text(
                    f"RAOFFLINEPROXY MENU ERROR\n\n{exc}", ACTION_SECONDS, 2
                )
        finally:
            close_input_devices(self.input_handles)
            self.framebuffer.close()

    def render_menu(self) -> None:
        proxy_running = self.proxy_running()
        cache_key = (proxy_running, self.selected_index)
        cached_frame = self.menu_frame_cache.get(cache_key)
        if cached_frame is not None:
            self.display_frame(cached_frame)
            return

        entries = self.entries()
        status_text = "RUNNING" if proxy_running else "STOPPED"
        lines = ["RAOFFLINEPROXY MENU", "", f"PROXY: {status_text}", ""]

        for index, entry in enumerate(entries):
            prefix = ">" if index == self.selected_index else " "
            lines.append(f"{prefix} {entry.label}")

        frame = self.framebuffer.build_text_frame("\n".join(lines), font_scale=2)
        self.menu_frame_cache[cache_key] = frame
        self.display_frame(frame)

    def display_frame(self, frame: bytes) -> None:
        self.last_frame = frame
        if not self.bootstrap_shown:
            self.show_bootstrap_frame(frame)
            self.bootstrap_shown = True
            self.bootstrap_active = True
            return

        if self.bootstrap_active:
            self.framebuffer.stop_fallback()
            time.sleep(0.05)
            self.framebuffer.ensure_open()
            self.bootstrap_active = False

        self.framebuffer.display_frame(frame)

    def show_bootstrap_frame(self, frame: bytes) -> None:
        pixels = self.framebuffer.frame_to_pixels(frame)
        MENU_BOOTSTRAP_IMAGE_PATH.parent.mkdir(parents=True, exist_ok=True)
        write_bmp(
            MENU_BOOTSTRAP_IMAGE_PATH,
            pixels,
            self.image_width,
            self.image_height,
        )
        self.framebuffer.display_with_fallback(MENU_BOOTSTRAP_IMAGE_PATH, duration=0.2)

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
            return False
        return bool(service.get("running"))

    def handle_key(self, key: int) -> None:
        if key in {KEY_UP, KEY_LEFT, BTN_DPAD_UP, BTN_DPAD_LEFT}:
            self.selected_index = (self.selected_index - 1) % len(self.entries())
            self.render_menu()
            return

        if key in {KEY_DOWN, KEY_RIGHT, BTN_DPAD_DOWN, BTN_DPAD_RIGHT}:
            self.selected_index = (self.selected_index + 1) % len(self.entries())
            self.render_menu()
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
            start_proxy_inline()
            self.menu_frame_cache.clear()
        except Exception as exc:
            self.show_timed_text(
                f"RAOFFLINEPROXY START FAILED\n\n{exc}", ACTION_SECONDS, 2
            )

    def stop_proxy(self) -> None:
        try:
            stop_proxy_inline()
            self.menu_frame_cache.clear()
        except Exception as exc:
            self.show_timed_text(
                f"RAOFFLINEPROXY STOP FAILED\n\n{exc}", ACTION_SECONDS, 2
            )

    def uninstall(self) -> None:
        self.framebuffer.close()
        self.running = False
        subprocess.run(
            ["/userdata/system/raofflineproxy/bin/raofflineproxy-uninstall"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )

    def exit_menu(self) -> None:
        self.running = False

    def show_timed_text(self, text: str, seconds: int, font_scale: int) -> None:
        self.display_text(text, font_scale)
        time.sleep(seconds)

    def display_text(self, text: str, font_scale: int) -> None:
        pixels, image_width, image_height = render_text_pixels(
            text,
            image_width=self.image_width,
            image_height=self.image_height,
            font_scale=font_scale,
        )
        self.framebuffer.display_pixels(pixels, image_width, image_height)


class MenuEntry:
    def __init__(self, label: str, action) -> None:
        self.label = label
        self.action = action


class FramebufferRenderer:
    def __init__(self, width: int, height: int) -> None:
        self.width = width
        self.height = height
        self.fb_fd: int | None = None
        self.fb_map: mmap.mmap | None = None
        self.bits_per_pixel = read_int(FB_BITS_PER_PIXEL_PATH, 32)
        self.bytes_per_pixel = max(1, self.bits_per_pixel // 8)
        default_stride = self.width * self.bytes_per_pixel
        self.stride = read_int(FB_STRIDE_PATH, default_stride)
        self.screen_size = self.stride * self.height
        self.previous_frame: bytes | None = None
        self.fallback_process: subprocess.Popen | None = None
        self.open()

    def open(self) -> None:
        try:
            self.fb_fd = os.open(FB_PATH, os.O_RDWR)
            self.fb_map = mmap.mmap(
                self.fb_fd,
                self.screen_size,
                mmap.MAP_SHARED,
                mmap.PROT_WRITE | mmap.PROT_READ,
            )
            self.previous_frame = self.fb_map[:]
        except OSError:
            self.close()

    def ensure_open(self) -> None:
        if self.fb_map is not None:
            return
        self.open()

    def display_bmp(self, image_path: Path) -> None:
        if self.fb_map is None:
            self.display_with_fallback(image_path)
            return

        pixels, image_width, image_height = read_bmp(image_path)
        self.display_pixels(pixels, image_width, image_height)

    def display_pixels(
        self, pixels: list[list[int]], image_width: int, image_height: int
    ) -> None:
        self.ensure_open()
        if self.fb_map is None:
            return

        if image_width != self.width or image_height != self.height:
            return

        frame = bytearray(self.screen_size)
        for row_index in range(self.height):
            row = pixels[row_index]
            row_offset = row_index * self.stride
            for column_index in range(self.width):
                value = row[column_index]
                pixel_offset = row_offset + (column_index * self.bytes_per_pixel)
                if self.bytes_per_pixel >= 4:
                    frame[pixel_offset : pixel_offset + 4] = bytes(
                        (value, value, value, 0)
                    )
                elif self.bytes_per_pixel == 3:
                    frame[pixel_offset : pixel_offset + 3] = bytes(
                        (value, value, value)
                    )
                elif self.bytes_per_pixel == 2:
                    rgb565 = ((value >> 3) << 11) | ((value >> 2) << 5) | (value >> 3)
                    frame[pixel_offset : pixel_offset + 2] = struct.pack("<H", rgb565)
                else:
                    frame[pixel_offset] = value

        self.fb_map.seek(0)
        self.fb_map.write(frame)
        self.safe_flush()

    def display_frame(self, frame: bytes) -> None:
        self.ensure_open()
        if self.fb_map is None:
            return

        self.fb_map.seek(0)
        self.fb_map.write(frame)
        self.safe_flush()

    def safe_flush(self) -> None:
        if self.fb_map is None:
            return

        try:
            self.fb_map.flush()
        except (BufferError, OSError, ValueError):
            pass

    def build_text_frame(self, text: str, font_scale: int) -> bytes:
        resolved_font_scale = normalize_font_scale(font_scale)
        lines = normalize_text_lines(text, width=72, height=32)
        line_height = scaled_line_height(resolved_font_scale)
        frame = bytearray(self.screen_size)

        y = MARGIN_Y
        for line in lines:
            self.draw_text_line_to_frame(
                frame, line.upper(), MARGIN_X, y, resolved_font_scale
            )
            y += line_height
            if y + (GLYPH_HEIGHT * resolved_font_scale) >= self.height - MARGIN_Y:
                break

        return bytes(frame)

    def draw_text_line_to_frame(
        self, frame: bytearray, text: str, x: int, y: int, font_scale: int
    ) -> None:
        cursor_x = x
        glyph_width = GLYPH_WIDTH * font_scale
        glyph_spacing = GLYPH_SPACING * font_scale

        for char in text:
            self.draw_glyph_to_frame(
                frame, normalize_glyph(char), cursor_x, y, font_scale
            )
            cursor_x += glyph_width + glyph_spacing
            if cursor_x + glyph_width >= self.width - MARGIN_X:
                return

    def draw_glyph_to_frame(
        self, frame: bytearray, glyph: tuple[str, ...], x: int, y: int, font_scale: int
    ) -> None:
        for row_index, row in enumerate(glyph):
            pixel_y = y + (row_index * font_scale)
            if pixel_y >= self.height:
                return

            for column_index, value in enumerate(row):
                if value != "1":
                    continue

                pixel_x = x + (column_index * font_scale)
                if pixel_x >= self.width:
                    return

                for y_offset in range(font_scale):
                    for x_offset in range(font_scale):
                        self.write_white_pixel(
                            frame, pixel_x + x_offset, pixel_y + y_offset
                        )

    def write_white_pixel(self, frame: bytearray, pixel_x: int, pixel_y: int) -> None:
        if pixel_x >= self.width or pixel_y >= self.height:
            return

        pixel_offset = (pixel_y * self.stride) + (pixel_x * self.bytes_per_pixel)
        if self.bytes_per_pixel >= 4:
            frame[pixel_offset : pixel_offset + 4] = b"\xff\xff\xff\x00"
            return

        if self.bytes_per_pixel == 3:
            frame[pixel_offset : pixel_offset + 3] = b"\xff\xff\xff"
            return

        if self.bytes_per_pixel == 2:
            frame[pixel_offset : pixel_offset + 2] = struct.pack("<H", 0xFFFF)
            return

        frame[pixel_offset] = 0xFF

    def display_with_fallback(
        self, image_path: Path, duration: float = 0.1, auto_stop: bool = False
    ) -> None:
        self.stop_fallback()
        self.fallback_process = subprocess.Popen(
            ["fbv", "-i", "-c", "-u", str(image_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(duration)
        if auto_stop:
            self.stop_fallback()

    def frame_to_pixels(self, frame: bytes) -> list[list[int]]:
        pixels = [[0 for _ in range(self.width)] for _ in range(self.height)]
        for row_index in range(self.height):
            row_offset = row_index * self.stride
            for column_index in range(self.width):
                pixel_offset = row_offset + (column_index * self.bytes_per_pixel)
                if self.bytes_per_pixel >= 3:
                    pixels[row_index][column_index] = frame[pixel_offset]
                elif self.bytes_per_pixel == 2:
                    packed = struct.unpack(
                        "<H", frame[pixel_offset : pixel_offset + 2]
                    )[0]
                    blue = (packed & 0x1F) << 3
                    pixels[row_index][column_index] = blue
                else:
                    pixels[row_index][column_index] = frame[pixel_offset]
        return pixels

    def stop_fallback(self) -> None:
        if self.fallback_process is None:
            return
        if self.fallback_process.poll() is None:
            self.fallback_process.terminate()
            try:
                self.fallback_process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self.fallback_process.kill()
                self.fallback_process.wait(timeout=1)
        self.fallback_process = None

    def close(self) -> None:
        self.stop_fallback()
        if self.fb_map is not None:
            self.fb_map.close()
            self.fb_map = None
        if self.fb_fd is not None:
            os.close(self.fb_fd)
            self.fb_fd = None


def read_int(path: Path, default: int) -> int:
    try:
        return int(path.read_text(encoding="ascii").strip())
    except Exception:
        return default


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

    return handles


def close_input_devices(handles: list[int]) -> None:
    for fd in handles:
        try:
            os.close(fd)
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
                    return code

                if event_type == EV_ABS:
                    if code == ABS_HAT0Y and value == -1:
                        return KEY_UP
                    if code == ABS_HAT0Y and value == 1:
                        return KEY_DOWN
                    if code == ABS_HAT0X and value == -1:
                        return KEY_LEFT
                    if code == ABS_HAT0X and value == 1:
                        return KEY_RIGHT
    except OSError:
        return None

    return None


def read_bmp(path: Path) -> tuple[list[list[int]], int, int]:
    data = path.read_bytes()
    if data[:2] != b"BM":
        raise ValueError("Unsupported image format")

    pixel_offset = struct.unpack_from("<I", data, 10)[0]
    dib_header_size = struct.unpack_from("<I", data, 14)[0]
    if dib_header_size < 40:
        raise ValueError("Unsupported BMP header")

    width = struct.unpack_from("<I", data, 18)[0]
    height = struct.unpack_from("<I", data, 22)[0]
    bits_per_pixel = struct.unpack_from("<H", data, 28)[0]
    if bits_per_pixel != 24:
        raise ValueError("Unsupported BMP depth")

    row_size = ((width * 3 + 3) // 4) * 4
    rows: list[list[int]] = []
    for row_index in range(height):
        start = pixel_offset + (row_index * row_size)
        row_data = data[start : start + row_size]
        row: list[int] = []
        for column in range(width):
            blue = row_data[column * 3]
            row.append(blue)
        rows.append(row)

    rows.reverse()
    return rows, width, height
