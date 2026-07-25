#!/usr/bin/env python3
"""Format a GitHub release's notes and post them to the Discord #announcements webhook."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request

DISCORD_CONTENT_LIMIT = 2000
DEFAULT_SSM_PARAM = "/raop/announcements/discord-webhook-url"
DEFAULT_AWS_PROFILE = "kumo-admin"
DEFAULT_AWS_REGION = "eu-central-1"
RAOFFLINEPROXY_EMOJI = "<:raofflineproxy:1508916418236780584>"

# Trailing "(abc1234)", "(abc1234, def5678)", "(#52)", "(abc1234, #52)" references.
COMMIT_PR_SUFFIX_RE = re.compile(
    r"[ \t]*\((?:[0-9a-f]{6,10}|#\d+)(?:,\s*(?:[0-9a-f]{6,10}|#\d+))*\)[ \t]*$",
    re.MULTILINE,
)


def latest_release_tag() -> str:
    out = run(["gh", "release", "list", "--limit", "1", "--json", "tagName", "-q", ".[0].tagName"])
    tag = out.strip()
    if not tag:
        raise SystemExit("No releases found.")
    return tag


def fetch_release_body(tag: str) -> str:
    return run(["gh", "release", "view", tag, "--json", "body", "-q", ".body"])


def strip_commit_pr_refs(body: str) -> str:
    stripped_lines = [COMMIT_PR_SUFFIX_RE.sub("", line).rstrip() for line in body.splitlines()]
    return "\n".join(stripped_lines).strip() + "\n"


def build_message(tag: str, body: str) -> str:
    version = tag.removeprefix("v")
    release_url = f"https://github.com/misantronic/RAOfflineProxy/releases/tag/{tag}"
    return f"# {version} {RAOFFLINEPROXY_EMOJI}\n\n{body}".rstrip() + f"\n\n{release_url}\n"


def chunk_message(message: str, limit: int = DISCORD_CONTENT_LIMIT) -> list[str]:
    """Split on blank-line boundaries so chunks never break mid-section."""
    if len(message) <= limit:
        return [message]

    blocks = message.split("\n\n")
    chunks: list[str] = []
    current = ""
    for block in blocks:
        candidate = f"{current}\n\n{block}" if current else block
        if len(candidate) > limit and current:
            chunks.append(current)
            current = block
        else:
            current = candidate
    if current:
        chunks.append(current)

    # A single block longer than the limit still needs a hard split.
    final: list[str] = []
    for chunk in chunks:
        while len(chunk) > limit:
            final.append(chunk[:limit])
            chunk = chunk[limit:]
        final.append(chunk)
    return final


def fetch_webhook_url(param_name: str, profile: str, region: str) -> str:
    out = run(
        [
            "aws",
            "ssm",
            "get-parameter",
            "--profile",
            profile,
            "--region",
            region,
            "--name",
            param_name,
            "--with-decryption",
            "--query",
            "Parameter.Value",
            "--output",
            "text",
        ]
    )
    url = out.strip()
    if not url:
        raise SystemExit(f"SSM parameter {param_name} is empty.")
    return url


SUPPRESS_EMBEDS_FLAG = 1 << 2  # Discord message flag that hides link preview cards.


def post_to_discord(webhook_url: str, content: str) -> None:
    payload = json.dumps({"content": content, "flags": SUPPRESS_EMBEDS_FLAG}).encode("utf-8")
    request = urllib.request.Request(
        webhook_url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "User-Agent": "RAOfflineProxy-AnnounceRelease/1.0",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.status not in (200, 204):
                raise SystemExit(f"Discord webhook returned HTTP {response.status}")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Discord webhook returned HTTP {error.code}: {detail}")


def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"Command failed: {' '.join(cmd)}\n{result.stderr}")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", nargs="?", help="Release tag, e.g. v1.9.0-alpha1. Defaults to the latest release.")
    parser.add_argument("--send", action="store_true", help="Actually post to Discord. Without this, only prints a preview.")
    parser.add_argument("--ssm-param", default=DEFAULT_SSM_PARAM, help="SSM parameter name holding the webhook URL.")
    parser.add_argument("--aws-profile", default=DEFAULT_AWS_PROFILE)
    parser.add_argument("--aws-region", default=DEFAULT_AWS_REGION)
    args = parser.parse_args()

    tag = args.tag or latest_release_tag()
    body = fetch_release_body(tag)
    stripped = strip_commit_pr_refs(body)
    message = build_message(tag, stripped)
    chunks = chunk_message(message)

    if not args.send:
        print("--- PREVIEW (not sent; pass --send to post) ---\n")
        for i, chunk in enumerate(chunks, start=1):
            if len(chunks) > 1:
                print(f"[message {i}/{len(chunks)}]")
            print(chunk)
            print()
        return

    webhook_url = fetch_webhook_url(args.ssm_param, args.aws_profile, args.aws_region)
    for chunk in chunks:
        post_to_discord(webhook_url, chunk)
    print(f"Posted {len(chunks)} message(s) for {tag} to Discord.")


if __name__ == "__main__":
    main()
