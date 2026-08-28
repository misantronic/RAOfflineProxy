from __future__ import annotations

import json

CLIENT_SOURCE = r'''
import sys
import urllib.error
import urllib.request

port, body, user_agent = sys.argv[1], sys.argv[2], sys.argv[3]
request = urllib.request.Request(
    "http://127.0.0.1:" + port + "/dorequest.php",
    data=body.encode("utf-8"),
    headers={
        "User-Agent": user_agent,
        "Content-Type": "application/x-www-form-urlencoded",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(request, timeout=15) as response:
        status, payload = response.status, response.read().decode("utf-8")
except urllib.error.HTTPError as error:
    status, payload = error.code, error.read().decode("utf-8")
print(status)
print(payload)
'''

CLIENT_PATH = "/opt/rcheevos.py"
DEFAULT_UA = "RetroArch/1.21.0 (Linux)"


class Emulator:
    """Replays the rcheevos request sequence against the proxy port.

    Stands in for a real emulator: the wire traffic is what the proxy sees, and
    an achievement condition cannot be driven deterministically anyway.
    """

    def __init__(self, container, port: int = 8080, user_agent: str = DEFAULT_UA) -> None:
        self.container = container
        self.port = port
        self.user_agent = user_agent

    def install(self) -> None:
        self.container.write_file(CLIENT_PATH, CLIENT_SOURCE)

    def request(self, body: str, user_agent: str | None = None) -> tuple:
        result = self.container.exec(
            "python3 %s %d '%s' '%s'"
            % (CLIENT_PATH, self.port, body, user_agent or self.user_agent),
            check=True,
        )
        head, _, payload = result.stdout.partition("\n")
        return int(head.strip()), json.loads(payload)

    def login(self, user: str, token: str) -> tuple:
        return self.request("r=login2&u=%s&t=%s" % (user, token))

    def game_id(self, rom_hash: str) -> tuple:
        return self.request("r=gameid&m=%s" % rom_hash)

    def patch(self, user: str, token: str, game_id: int) -> tuple:
        return self.request("r=patch&u=%s&t=%s&g=%d" % (user, token, game_id))

    def start_session(self, user: str, token: str, game_id: int) -> tuple:
        return self.request(
            "r=startsession&u=%s&t=%s&g=%d&h=0" % (user, token, game_id)
        )

    def unlocks(self, user: str, token: str, game_id: int) -> tuple:
        return self.request("r=unlocks&u=%s&t=%s&g=%d&h=0" % (user, token, game_id))

    def award(self, user: str, token: str, achievement_id: int, hardcore: int = 0) -> tuple:
        return self.request(
            "r=awardachievement&u=%s&t=%s&a=%d&h=%d"
            % (user, token, achievement_id, hardcore)
        )

    def boot_sequence(self, user: str, token: str, rom_hash: str) -> dict:
        """The requests rcheevos fires when a game is launched."""
        responses = {}
        _status, responses["login"] = self.login(user, token)
        _status, responses["gameid"] = self.game_id(rom_hash)
        game_id = responses["gameid"].get("GameID", 0)
        if game_id:
            _status, responses["patch"] = self.patch(user, token, game_id)
            _status, responses["startsession"] = self.start_session(user, token, game_id)
            _status, responses["unlocks"] = self.unlocks(user, token, game_id)
        return responses
