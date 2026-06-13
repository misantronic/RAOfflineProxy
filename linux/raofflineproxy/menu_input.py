import os
import select
import struct
from pathlib import Path

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
BTN_TL = 308
BTN_TR = 309
BTN_SELECT = 314
BTN_START = 315
BTN_DPAD_UP = 544
BTN_DPAD_DOWN = 545
BTN_DPAD_LEFT = 546
BTN_DPAD_RIGHT = 547
INPUT_DIR = Path("/dev/input")


def open_input_devices() -> list[object]:
    handles: list[object] = []
    if not INPUT_DIR.exists():
        return handles

    for path in sorted(INPUT_DIR.glob("event*")):
        try:
            handle = open(path, "rb", buffering=0)
            os.set_blocking(handle.fileno(), False)
        except OSError:
            continue
        handles.append(handle)
    return handles


def close_input_devices(handles: list[object]) -> None:
    for handle in handles:
        try:
            handle.close()
        except OSError:
            pass


def read_keys(handles: list[object]) -> list[int]:
    if not handles:
        return []

    keys: list[int] = []
    readable, _, _ = select.select(handles, [], [], 0)
    for handle in readable:
        while True:
            try:
                event = handle.read(EVENT_SIZE)
            except BlockingIOError:
                break
            except OSError:
                break
            if event is None:
                break
            if len(event) != EVENT_SIZE:
                break

            _seconds, _micros, event_type, code, value = struct.unpack("llHHi", event)
            if event_type == EV_KEY and value == 1:
                keys.append(code)
                continue
            if event_type == EV_ABS:
                if code == ABS_HAT0Y:
                    if value < 0:
                        keys.append(BTN_DPAD_UP)
                    elif value > 0:
                        keys.append(BTN_DPAD_DOWN)
                if code == ABS_HAT0X:
                    if value < 0:
                        keys.append(BTN_DPAD_LEFT)
                    elif value > 0:
                        keys.append(BTN_DPAD_RIGHT)
    return keys
