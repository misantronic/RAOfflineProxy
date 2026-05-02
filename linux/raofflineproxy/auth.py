import json
import logging

from . import cache_keys
from .config import FALLBACK_USER_AGENT, detect_retroarch_cfg, upstream_host
from .network import build_api_url, http_get
from .retroarch_cfg import load_retroarch_password_credentials
from .storage import Storage
from .utils import proxy_user_agent

LOGGER = logging.getLogger("raofflineproxy")


def resolve_credentials(
    storage: Storage,
    config_data: dict | None = None,
    user_agent: str = FALLBACK_USER_AGENT,
) -> dict | None:
    cached = storage.load_login_credentials()
    if cached is not None:
        return cached

    config_data = config_data or {}
    cfg_path = str(config_data.get("retroarch_cfg") or detect_retroarch_cfg())
    password_credentials = load_retroarch_password_credentials(cfg_path)
    if password_credentials is None:
        return None

    return login_and_cache_token(storage, config_data, password_credentials, user_agent)


def login_and_cache_token(
    storage: Storage,
    config_data: dict,
    credentials: dict,
    user_agent: str,
) -> dict | None:
    url = build_api_url(
        upstream_host(config_data),
        "login2",
        {
            "u": credentials["user"],
            "p": credentials["password"],
        },
    )
    try:
        response_body = http_get(
            url, proxy_user_agent(user_agent or FALLBACK_USER_AGENT)
        )
        payload = json.loads(response_body)
    except Exception as exc:
        LOGGER.warning("login2 request failed: %s", exc)
        return None

    user = payload.get("User") or credentials["user"]
    token = payload.get("Token")
    if not payload.get("Success") or not user or not token:
        LOGGER.warning("login2 rejected credentials for user=%s", credentials["user"])
        return None

    storage.upsert_cache(cache_keys.login(user), response_body)
    return {"user": user, "token": token}
