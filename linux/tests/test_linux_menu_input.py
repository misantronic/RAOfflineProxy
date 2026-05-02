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


def key_event(code: int, value: int = 1) -> bytes:
    return struct.pack("llHHi", 0, 0, menu_input.EV_KEY, code, value)


class LinuxMenuInputTests(unittest.TestCase):
    def test_read_keys_drains_all_available_events(self) -> None:
        handle = FakeHandle(
            [
                key_event(menu_input.KEY_DOWN),
                key_event(menu_input.KEY_DOWN),
            ]
        )

        original_select = menu_input.select.select
        try:
            menu_input.select.select = lambda handles, _w, _x, _t: (handles, [], [])

            keys = menu_input.read_keys([handle])

            self.assertEqual(keys, [menu_input.KEY_DOWN, menu_input.KEY_DOWN])
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
