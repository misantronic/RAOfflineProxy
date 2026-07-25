---
name: announce-release
description: Post a GitHub release's changelog to the Discord #announcements channel. Use when the user asks to announce, post, or share a release (or its changelog) on Discord.
---

# Announce a release on Discord

Formats a GitHub release's notes and posts them to the project's Discord
`#announcements` channel via webhook.

Output format is always:

```
# {VERSION} :raofflineproxy:

{CHANGELOG FROM RELEASE, with trailing commit/PR references stripped}

{link to the release, e.g. https://github.com/misantronic/RAOfflineProxy/releases/tag/v1.8.0-alpha1}
```

Commit-hash and PR-number references at the end of changelog lines — e.g.
`(b983580)`, `(664e828, 826c21d)`, `(f7ab653, #52)` — are stripped, since
they're implementation detail that means nothing to the Discord audience.

## How to run it

1. **Preview first, always.** Run without `--send` and show the output to
   the user before posting anything:

   ```bash
   python3 .claude/skills/announce-release/announce_release.py [tag]
   ```

   `tag` is optional and defaults to the latest published release (e.g.
   `v1.9.0-alpha1`). If omitted, confirm with the user which release is
   about to be posted before proceeding.

2. **Get explicit confirmation** from the user that the preview looks right.
   Posting to Discord is visible to the whole community and cannot be
   unsent — never skip the preview step, even if the user's request sounds
   like "just post it."

3. **Post it** by re-running with `--send`:

   ```bash
   python3 .claude/skills/announce-release/announce_release.py [tag] --send
   ```

Messages over Discord's 2000-character limit are automatically split into
multiple sequential messages along section (blank-line) boundaries.

## Webhook credential

The webhook URL lives in AWS SSM Parameter Store at
`/raop/announcements/discord-webhook-url` (profile `kumo-admin`, region
`eu-central-1`), fetched at send time — never hardcode or print the webhook
URL. If the script reports the parameter is missing or empty, tell the user
rather than trying to work around it.
