# Honeypot spam-bot ban bot

Watches a single "honeypot" channel. Anyone who posts there gets their message
deleted and gets banned, except members with `Ban Members` or `Manage Server`
permission. Posts a log embed to a mod-log channel.

## Setup

1. Create a private channel (e.g. `#do-not-post`) that regular members can
   see but that has no legitimate reason to be posted in. Don't link it from
   anywhere, or link it deceptively (e.g. named to look tempting to spam
   bots, like `#verify-here` with a misleading pinned message).
2. Make sure the channel is visible to `@everyone` (spam bots need `View
   Channel` + `Send Messages` there) but not pinned/advertised to real users.
3. Create a bot application at https://discord.com/developers/applications,
   add a bot user, enable the **Server Members Intent** and **Message
   Content Intent** under Bot settings, and copy the token.
4. Invite the bot with `Ban Members`, `Manage Messages`, and `View Channel`
   permissions scoped at least to the honeypot and mod-log channels.
5. Set environment variables:
   - `DISCORD_BOT_TOKEN`
   - `HONEYPOT_CHANNEL_ID`
   - `MODLOG_CHANNEL_ID`
6. `pip install -r requirements.txt && python bot.py`

## Notes

- The bot's own role must sit above any role you expect it to ban, and
  above nothing it shouldn't touch.
- This only catches bots/spammers that post in the honeypot channel. Pair it
  with Discord's built-in raid/verification settings for broader coverage.
