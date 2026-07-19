import struct
import unittest

from linux.raofflineproxy import menu_input


class FakeHandle:
    def __init__(self, events: list[bytes]) -> None:
        self._events = list(events)

    def read(self, size: int) -> bytes:
        if not self._events:
            raise BlockingIOError
        return self._events.pop(0)


class FakeNoneHandle:
    def read(self, size: int):
        return None


def key_event(code: int, value: int = 1, seconds: int = 0, micros: int = 0) -> bytes:
    return struct.pack("llHHi", seconds, micros, menu_input.EV_KEY, code, value)


class LinuxMenuInputTests(unittest.TestCase):
    def test_read_keys_debounces_rapid_repeated_press(self) -> None:
        # Onion's gpio-keys-polled driver reports switch chatter as full
        # press/release cycles (not a stuck-down repeat) ~120-220ms apart for
        # a single physical tap. A state machine that only tracks
        # press/release can't distinguish that from genuine rapid presses,
        # so read_keys debounces by the event's own timestamp.
        handle = FakeHandle(
            [
                key_event(menu_input.KEY_DOWN, value=1, micros=0),
                key_event(menu_input.KEY_DOWN, value=0, micros=30_000),
                key_event(menu_input.KEY_DOWN, value=1, micros=60_000),
                key_event(menu_input.KEY_DOWN, value=0, micros=90_000),
            ]
        )

        original_select = menu_input.select.select
        try:
            menu_input.select.select = lambda handles, _w, _x, _t: (handles, [], [])

            keys = menu_input.read_keys([handle])

            self.assertEqual(keys, [menu_input.KEY_DOWN])
        finally:
            menu_input.select.select = original_select

    def test_read_keys_allows_press_after_debounce_window(self) -> None:
        handle = FakeHandle(
            [
                key_event(menu_input.KEY_DOWN, value=1, seconds=0),
                key_event(menu_input.KEY_DOWN, value=1, seconds=1),
            ]
        )

        original_select = menu_input.select.select
        try:
            menu_input.select.select = lambda handles, _w, _x, _t: (handles, [], [])

            keys = menu_input.read_keys([handle])

            self.assertEqual(keys, [menu_input.KEY_DOWN, menu_input.KEY_DOWN])
        finally:
            menu_input.select.select = original_select

    def test_read_keys_debounces_across_calls_with_shared_state(self) -> None:
        handle = FakeHandle([key_event(menu_input.KEY_DOWN, seconds=0)])
        last_press: dict[int, float] = {}

        original_select = menu_input.select.select
        try:
            menu_input.select.select = lambda handles, _w, _x, _t: (handles, [], [])

            first = menu_input.read_keys([handle], last_press)
            self.assertEqual(first, [menu_input.KEY_DOWN])

            handle._events.append(key_event(menu_input.KEY_DOWN, seconds=0, micros=50_000))
            second = menu_input.read_keys([handle], last_press)
            self.assertEqual(second, [])

            handle._events.append(key_event(menu_input.KEY_DOWN, seconds=1))
            third = menu_input.read_keys([handle], last_press)
            self.assertEqual(third, [menu_input.KEY_DOWN])
        finally:
            menu_input.select.select = original_select

    def test_read_keys_returns_empty_when_handle_would_block(self) -> None:
        handle = FakeHandle([])

        original_select = menu_input.select.select
        try:
            menu_input.select.select = lambda handles, _w, _x, _t: (handles, [], [])

            keys = menu_input.read_keys([handle])

            self.assertEqual(keys, [])
        finally:
            menu_input.select.select = original_select

    def test_read_keys_returns_empty_when_handle_returns_none(self) -> None:
        handle = FakeNoneHandle()

        original_select = menu_input.select.select
        try:
            menu_input.select.select = lambda handles, _w, _x, _t: (handles, [], [])

            keys = menu_input.read_keys([handle])

            self.assertEqual(keys, [])
        finally:
            menu_input.select.select = original_select


if __name__ == "__main__":
    unittest.main()
