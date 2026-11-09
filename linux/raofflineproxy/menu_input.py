from __future__ import annotations

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



# Onion's gpio-keys-polled hardware has been observed producing a second
# full press/release cycle ~120-220ms after the first for a single physical
# tap (measured via kernel event timestamps on an isolated single-tap
# capture) -- a real switch-quality issue the kernel's own debounce config
# isn't filtering, not something a userspace timing window can fully
# separate from a fast deliberate repeat. This value is a tuned compromise
# between the two; raise it if double-navigates are still seen, lower it if
# rapid navigation feels throttled.
DEBOUNCE_SECONDS = 0.08


def read_keys(handles: list[object], last_press: dict[int, float] | None = None) -> list[int]:
    """Reads pending key events, emitting each code at most once per
    DEBOUNCE_SECONDS.

    Onion's gpio-keys-polled driver reports switch chatter as full,
    legitimate-looking press/release cycles (not just a stuck-down repeat) —
    a single physical tap has been observed producing several such cycles
    tens to hundreds of milliseconds apart. A press/release state machine
    can't tell that apart from genuine rapid presses, so this instead
    suppresses same-code presses that land within DEBOUNCE_SECONDS of the
    last accepted one, keyed off each event's own kernel timestamp (so it's
    unaffected by how long read_keys() itself takes to run). Pass the same
    dict across calls to debounce across calls; omit it to only debounce
    within a single call.
    """
    return [code for code, pressed in read_key_events(handles, last_press) if pressed]


def read_key_events(
    handles: list[object], last_press: dict[int, float] | None = None
) -> list[tuple[int, bool]]:
    """Like read_keys, but in arrival order and with releases as (code, False), so a
    caller can tell which button is still held."""
    if not handles:
        return []

    last = last_press if last_press is not None else {}
    events: list[tuple[int, bool]] = []
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

            seconds, micros, event_type, code, value = struct.unpack("llHHi", event)
            timestamp = seconds + micros / 1_000_000
            if event_type == EV_KEY:
                if value == 1:
                    _emit_debounced(events, last, code, timestamp)
                elif value == 0:
                    events.append((code, False))
                continue
            if event_type == EV_ABS:
                if code == ABS_HAT0Y:
                    _emit_hat(events, last, value, BTN_DPAD_UP, BTN_DPAD_DOWN, timestamp)
                if code == ABS_HAT0X:
                    _emit_hat(events, last, value, BTN_DPAD_LEFT, BTN_DPAD_RIGHT, timestamp)
    return events


def _emit_hat(
    events: list[tuple[int, bool]],
    last: dict[int, float],
    value: int,
    negative_code: int,
    positive_code: int,
    timestamp: float,
) -> None:
    if value < 0:
        _emit_debounced(events, last, negative_code, timestamp)
    elif value > 0:
        _emit_debounced(events, last, positive_code, timestamp)
    else:
        events.append((negative_code, False))
        events.append((positive_code, False))


def _emit_debounced(
    events: list[tuple[int, bool]], last: dict[int, float], code: int, timestamp: float
) -> None:
    previous = last.get(code)
    if previous is not None and timestamp - previous < DEBOUNCE_SECONDS:
        return
    last[code] = timestamp
    events.append((code, True))


KEY_REPEAT_DELAY_SECONDS = 0.4
KEY_REPEAT_INTERVAL_SECONDS = 0.06


class KeyRepeat:
    """Steps a held up/down button through a long list. It does not rely on the
    kernel's repeat events, which gpio-keys devices do not send."""

    def __init__(
        self,
        delay: float = KEY_REPEAT_DELAY_SECONDS,
        interval: float = KEY_REPEAT_INTERVAL_SECONDS,
    ) -> None:
        self.delay = delay
        self.interval = interval
        self.direction = 0
        self.next_at = 0.0

    def press(self, direction: int, now: float) -> None:
        self.direction = direction
        self.next_at = now + self.delay

    def release(self, direction: int) -> None:
        if self.direction == direction:
            self.direction = 0

    def cancel(self) -> None:
        self.direction = 0

    def due(self, now: float) -> int:
        """The direction to step once now, or 0. At most one step per call, so a slow
        frame never builds up a backlog of steps."""
        if self.direction == 0 or now < self.next_at:
            return 0
        self.next_at = now + self.interval
        return self.direction
