from __future__ import annotations

import threading

from linux.tests.e2e.fake_ra.server import build_servers
from linux.tests.e2e.fake_ra.state import RaState


class HostFakeRa:
    """The linux harness's fake RA server, run in-process on the host.

    The Android emulator reaches the host loopback as 10.0.2.2, which is what
    the e2e build type bakes into RA_HOST. Control is direct state access, so
    it keeps working while the device is in airplane mode.
    """

    def __init__(self, host: str, port: int) -> None:
        self._ra_server, self._ctl_server = build_servers(RaState(), host, port, 0)
        self._threads = [
            threading.Thread(target=server.serve_forever, daemon=True)
            for server in (self._ra_server, self._ctl_server)
        ]

    @property
    def state(self) -> RaState:
        return self._ra_server.RequestHandlerClass.state

    def start(self) -> None:
        for thread in self._threads:
            thread.start()

    def stop(self) -> None:
        for server in (self._ra_server, self._ctl_server):
            server.shutdown()
            server.server_close()

    def reset(self) -> None:
        state = RaState()
        for server in (self._ra_server, self._ctl_server):
            server.RequestHandlerClass.state = state

    def set_mode(self, mode: str) -> None:
        self.state.set_mode(mode)

    def journal(self, action: str | None = None) -> list:
        entries = list(self.state.journal)
        return [entry for entry in entries if action is None or entry.get("action") == action]

    def actions(self) -> list:
        return [entry.get("action") for entry in self.journal()]

    def violations(self) -> list:
        return list(self.state.violations)

    def unlocks(self, user: str, game_id: int) -> list:
        return sorted(self.state.user_unlocks(user, game_id))

    def clear_journal(self) -> None:
        self.state.clear_journal()
