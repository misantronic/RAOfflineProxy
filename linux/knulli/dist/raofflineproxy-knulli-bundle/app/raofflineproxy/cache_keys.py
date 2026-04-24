USER_AGENT = "ua::last"

PREFIX_LOGIN = "login2::"
PREFIX_PATCH = "patch:"
PREFIX_UNLOCKS = "unlocks:"
PREFIX_STARTSESSION = "startsession:"
PREFIX_GAMEID = "gameid:"


def login(user: str) -> str:
    return f"login2::{user}"


def game_id(hash_value: str) -> str:
    return f"gameid:{hash_value}"


def patch(game_id_value: int | str, user: str) -> str:
    return f"patch:{game_id_value}:{user}"


def patch_prefix(game_id_value: int | str) -> str:
    return f"patch:{game_id_value}:"


def unlocks(game_id_value: int | str, user: str) -> str:
    return f"unlocks:{game_id_value}:{user}:0"


def start_session(game_id_value: int | str, user: str) -> str:
    return f"startsession:{game_id_value}:{user}:0"


def parse_game_id_from_patch_key(cache_key: str) -> int | None:
    if not cache_key.startswith(PREFIX_PATCH):
        return None

    value = cache_key.removeprefix(PREFIX_PATCH).split(":", 1)[0]
    return int(value) if value.isdigit() else None
