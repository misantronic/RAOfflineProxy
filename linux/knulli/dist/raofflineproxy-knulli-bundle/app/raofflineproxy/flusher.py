import json
import time
from dataclasses import dataclass

from . import cache_keys
from .award_signing import public_key_base64, verify_award
from .config import FALLBACK_USER_AGENT, MAX_PROXY_PORT, upstream_host
from .network import build_api_url, http_post
from .rom_cache import cache_session, cache_unlocks, refresh_game_patch
from .storage import Storage
from .utils import (
    canonical_reason_phrase,
    extract_form_param,
    parse_form_params,
    replace_or_append_form_param,
    sha256_hex,
)

MAX_RETRIES = 5
GENESIS_HASH = "genesis"
MAX_AWARD_OFFSET_SECONDS = 14 * 24 * 60 * 60


@dataclass
class FlushOutcome:
    flushed: int
    total: int
    skipped_stale: int
    last_error: str | None = None


def is_hardcore_award(award: dict) -> bool:
    query_params = parse_form_params(
        award["queryString"].split("?", 1)[1] if "?" in award["queryString"] else ""
    )
    if "h" in query_params:
        return query_params["h"] == "1"
    return parse_form_params(award["requestBody"]).get("h") == "1"


def canonical_payload(award: dict) -> str:
    return f"{award['achievementId']}|{award['queryString']}|{award['requestBody']}|{award['queuedAt']}"


def compute_validation_hash(
    achievement_id: int, username: str, hardcore: int, seconds_since_unlock: int
) -> str:
    md5 = __import__("hashlib").md5()
    aid = str(achievement_id)
    md5.update(aid.encode("utf-8"))
    md5.update(username.encode("utf-8"))
    md5.update(str(hardcore).encode("utf-8"))
    if seconds_since_unlock:
        md5.update(aid.encode("utf-8"))
        md5.update(str(seconds_since_unlock).encode("utf-8"))
    return md5.hexdigest()


def clamp_award_offset_seconds(raw_offset_seconds: int) -> int:
    return max(0, min(MAX_AWARD_OFFSET_SECONDS, raw_offset_seconds))


def build_award_request_body(award: dict, now_millis: int | None = None) -> str:
    body = award["requestBody"]
    current_time = now_millis or int(time.time() * 1000)
    raw_offset = int((current_time - award["queuedAt"]) / 1000)
    offset_seconds = clamp_award_offset_seconds(raw_offset)
    if offset_seconds > 0:
        achievement_id = int(extract_form_param(body, "a") or award["achievementId"])
        username = extract_form_param(body, "u") or ""
        hardcore = int(extract_form_param(body, "h") or "0")
        body = replace_or_append_form_param(
            body,
            "v",
            compute_validation_hash(achievement_id, username, hardcore, offset_seconds),
        )
        body = replace_or_append_form_param(body, "o", str(offset_seconds))

    if not award.get("payloadHash"):
        return body

    body = replace_or_append_form_param(
        body, "ra_chain_payload_hash", award["payloadHash"]
    )
    body = replace_or_append_form_param(body, "ra_chain_prev_hash", award["prevHash"])
    body = replace_or_append_form_param(body, "ra_chain_sig", award["signature"])
    body = replace_or_append_form_param(body, "ra_chain_pubkey", public_key_base64())
    return body


def verify_chain(awards: list[dict]) -> tuple[bool, str | None, int | None]:
    for index, award in enumerate(awards):
        if not award.get("payloadHash"):
            continue

        expected_payload_hash = sha256_hex(canonical_payload(award))
        if award["payloadHash"] != expected_payload_hash:
            return False, "stored payloadHash does not match recomputed hash", index

        expected_prev_hash = (
            GENESIS_HASH
            if index == 0
            else (awards[index - 1].get("payloadHash") or GENESIS_HASH)
        )
        if award["prevHash"] != expected_prev_hash:
            return False, "chain link broken", index

        sign_input = f"{award['payloadHash']}:{award['prevHash']}".encode("utf-8")
        if not verify_award(sign_input, award["signature"]):
            return False, "invalid signature", index

    return True, None, None


def refresh_and_load_achievement_ids(
    storage: Storage, credentials: dict, user_agent: str, config_data: dict
) -> tuple[set[int], list[int]] | None:
    patch_entries = storage.get_all_cache_by_prefix(cache_keys.PREFIX_PATCH)
    game_ids = []
    for entry in patch_entries:
        game_id = cache_keys.parse_game_id_from_patch_key(entry["cacheKey"])
        if game_id is not None and game_id not in game_ids:
            game_ids.append(game_id)

    if not game_ids:
        return set(), []

    achievement_ids: set[int] = set()
    for game_id in game_ids:
        response_body = refresh_game_patch(
            game_id, credentials, user_agent, storage, config_data
        )
        if response_body is None:
            return None
        try:
            payload = json.loads(response_body)
            achievements = payload.get("PatchData", {}).get("Achievements", [])
            for achievement in achievements:
                achievement_id = achievement.get("ID")
                if isinstance(achievement_id, int):
                    achievement_ids.add(achievement_id)
        except Exception:
            return None

    return achievement_ids, game_ids


def send_award(award: dict, config_data: dict) -> tuple[str, str]:
    url = f"{upstream_host(config_data)}{award['queryString']}"
    body = build_award_request_body(award)
    status, _reason, response_body = http_post(
        url,
        body,
        headers={"User-Agent": award["userAgent"] or FALLBACK_USER_AGENT},
    )

    if status in (401, 403):
        return "auth_error", f"Token rejected by server (HTTP {status})"
    if status < 200 or status >= 300:
        return "network_error", f"HTTP {status}"

    try:
        payload = json.loads(response_body)
    except Exception:
        return "network_error", "Server returned invalid JSON"

    if payload.get("Success"):
        return "success", ""

    error = payload.get("Error") or "Server returned Success:false"
    lowered = error.lower()
    if any(
        keyword in lowered for keyword in ("invalid", "token", "credentials", "user")
    ):
        return "auth_error", error
    return "network_error", error


def flush_pending_awards(storage: Storage, config_data: dict) -> FlushOutcome:
    pending = storage.get_pending_awards()
    if not pending:
        return FlushOutcome(flushed=0, total=0, skipped_stale=0)

    valid, reason, index = verify_chain(pending)
    if not valid:
        return FlushOutcome(
            flushed=0,
            total=len(pending),
            skipped_stale=0,
            last_error=f"chain broken at {index}: {reason}",
        )

    credentials = storage.load_login_credentials()
    if credentials is None:
        return FlushOutcome(
            flushed=0,
            total=len(pending),
            skipped_stale=0,
            last_error="No login credentials available",
        )

    user_agent = storage.load_user_agent(FALLBACK_USER_AGENT)
    refreshed = refresh_and_load_achievement_ids(
        storage, credentials, user_agent, config_data
    )
    if refreshed is None:
        return FlushOutcome(
            flushed=0,
            total=len(pending),
            skipped_stale=0,
            last_error="Could not refresh achievement data from server",
        )

    known_achievement_ids, game_ids = refreshed
    flushed = 0
    skipped_stale = 0

    for award in pending:
        if is_hardcore_award(award):
            storage.delete_pending_award(award["achievementId"])
            flushed += 1
            continue

        if (
            known_achievement_ids
            and award["achievementId"] not in known_achievement_ids
        ):
            award["lastError"] = (
                f"Achievement {award['achievementId']} not found in live server data"
            )
            storage.update_pending_award(award)
            skipped_stale += 1
            continue

        outcome, message = send_award(award, config_data)
        if outcome == "success":
            storage.delete_pending_award(award["achievementId"])
            flushed += 1
        elif outcome == "auth_error":
            award["lastError"] = message
            storage.update_pending_award(award)
        else:
            award["retryCount"] = int(award.get("retryCount", 0)) + 1
            award["lastError"] = message
            storage.update_pending_award(award)

    if flushed:
        time.sleep(3)
        for game_id in game_ids:
            cache_unlocks(game_id, credentials, user_agent, config_data)
            cache_session(game_id, credentials, storage)

    return FlushOutcome(
        flushed=flushed, total=len(pending), skipped_stale=skipped_stale
    )
