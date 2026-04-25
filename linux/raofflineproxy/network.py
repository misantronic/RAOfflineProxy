import urllib.error
import urllib.parse
import urllib.request
from urllib.parse import urlsplit

from .config import FALLBACK_USER_AGENT, upstream_host
from .utils import proxy_user_agent


def read_response_bytes(response: object) -> bytes:
    return response.read()


def detect_charset(content_type: str | None, default: str = "utf-8") -> str:
    if not content_type:
        return default

    for part in content_type.split(";"):
        piece = part.strip()
        if piece.lower().startswith("charset="):
            return piece.split("=", 1)[1].strip() or default
    return default


def decode_response_body(body: bytes, content_type: str | None) -> str:
    return body.decode(detect_charset(content_type), errors="strict")


def response_content_type(response: object) -> str | None:
    headers = getattr(response, "headers", None)
    if headers is None:
        return None
    return headers.get("Content-Type")


def build_api_url(base: str, action: str, params: dict[str, str]) -> str:
    query = urllib.parse.urlencode({"r": action, **params})
    return f"{base.rstrip('/')}/dorequest.php?{query}"


def http_get(url: str, user_agent: str) -> str:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": user_agent,
            "Accept-Encoding": "identity",
        },
        method="GET",
    )

    with urllib.request.urlopen(request, timeout=10) as response:
        body = read_response_bytes(response)
        return decode_response_body(body, response_content_type(response))


def http_post(
    url: str, body: str, headers: dict[str, str] | None = None
) -> tuple[int, str, str]:
    request_headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept-Encoding": "identity",
    }
    if headers:
        request_headers.update(headers)

    request = urllib.request.Request(
        url,
        data=body.encode("utf-8"),
        headers=request_headers,
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            response_body = read_response_bytes(response)
            return (
                response.status,
                response.reason,
                decode_response_body(response_body, response_content_type(response)),
            )
    except urllib.error.HTTPError as error:
        response_body = error.read()
        return (
            error.code,
            error.reason,
            decode_response_body(response_body, error.headers.get("Content-Type")),
        )


def online_check(config_data: dict) -> bool:
    upstream = upstream_host(config_data)
    parsed = urlsplit(upstream)
    url = f"{parsed.scheme}://{parsed.netloc}/"
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": proxy_user_agent(FALLBACK_USER_AGENT),
            "Accept-Encoding": "identity",
        },
        method="HEAD",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return 200 <= response.status < 500
    except Exception:
        return False


def build_forward_headers(headers: dict[str, str]) -> dict[str, str]:
    skip_headers = {
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "accept-encoding",
    }
    forwarded: dict[str, str] = {}

    for key, value in headers.items():
        lower = key.lower()
        if lower in skip_headers:
            continue
        if lower == "user-agent":
            forwarded[key] = proxy_user_agent(value)
        else:
            forwarded[key] = value

    if "User-Agent" not in forwarded and "user-agent" not in forwarded:
        forwarded["User-Agent"] = proxy_user_agent(FALLBACK_USER_AGENT)

    return forwarded
