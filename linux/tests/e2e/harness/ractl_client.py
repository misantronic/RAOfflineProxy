from __future__ import annotations

import json

CLIENT_SOURCE = r'''
import json
import sys
import urllib.request

method, route = sys.argv[1], sys.argv[2]
payload = sys.argv[3] if len(sys.argv) > 3 else None
url = "http://127.0.0.1:8182" + route
if method == "POST":
    request = urllib.request.Request(
        url,
        data=(payload or "{}").encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
else:
    request = urllib.request.Request(url, method="GET")
with urllib.request.urlopen(request, timeout=10) as response:
    sys.stdout.write(response.read().decode("utf-8"))
'''

CLIENT_PATH = "/opt/ractl.py"


class FakeRaControl:
    """Drives the in-container fake RA server through docker exec.

    Control never crosses the container's external interface, so it keeps
    working while a test downs eth0 to simulate a real outage.
    """

    def __init__(self, container) -> None:
        self.container = container

    def install(self) -> None:
        self.container.write_file(CLIENT_PATH, CLIENT_SOURCE)

    def _call(self, method: str, route: str, payload: dict | None = None) -> dict:
        command = "python3 %s %s '%s'" % (CLIENT_PATH, method, route)
        if payload is not None:
            command += " '%s'" % json.dumps(payload)
        result = self.container.exec(command, check=True)
        return json.loads(result.stdout)

    def health(self) -> dict:
        return self._call("GET", "/_ctl/health")

    def mode(self) -> str:
        return self._call("GET", "/_ctl/mode")["mode"]

    def set_mode(self, mode: str) -> None:
        self._call("POST", "/_ctl/mode", {"mode": mode})

    def journal(self, action: str | None = None) -> list:
        route = "/_ctl/journal" + ("?action=" + action if action else "")
        return self._call("GET", route)["requests"]

    def actions(self) -> list:
        return [entry["action"] for entry in self.journal()]

    def violations(self) -> list:
        return self._call("GET", "/_ctl/violations")["violations"]

    def unlocks(self, user: str, game_id: int | None = None) -> list:
        route = "/_ctl/unlocks?u=" + user
        if game_id is not None:
            route += "&g=%d" % game_id
        return self._call("GET", route)["unlocks"]

    def score(self, user: str) -> int:
        return self._call("GET", "/_ctl/score?u=" + user)["score"]

    def reset(self, user: str | None = None, game_id: int | None = None) -> None:
        payload: dict = {}
        if user is not None:
            payload["user"] = user
        if game_id is not None:
            payload["game_id"] = game_id
        self._call("POST", "/_ctl/reset", payload)

    def rotate_token(self, user: str) -> None:
        self._call("POST", "/_ctl/rotate-token", {"user": user})

    def map_hash(self, hash_value: str, game_id: int) -> None:
        self._call("POST", "/_ctl/map-hash", {"hash": hash_value, "game_id": game_id})

    def clear_journal(self) -> None:
        self._call("POST", "/_ctl/clear-journal", {})
