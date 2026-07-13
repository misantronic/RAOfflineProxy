import logging
import os

import discord

HONEYPOT_CHANNEL_ID = int(os.environ["HONEYPOT_CHANNEL_ID"])
MODLOG_CHANNEL_ID = int(os.environ["MODLOG_CHANNEL_ID"])
BOT_TOKEN = os.environ["DISCORD_BOT_TOKEN"]

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("honeypot")

intents = discord.Intents.default()
intents.message_content = True
intents.members = True

client = discord.Client(intents=intents)


def is_exempt(member: discord.Member) -> bool:
    return member.guild_permissions.ban_members or member.guild_permissions.manage_guild


@client.event
async def on_ready():
    log.info("Logged in as %s", client.user)


@client.event
async def on_message(message: discord.Message):
    if message.author.bot or message.channel.id != HONEYPOT_CHANNEL_ID:
        return

    member = message.author
    if not isinstance(member, discord.Member) or is_exempt(member):
        return

    reason = "Posted in honeypot channel"
    content_preview = message.content[:500]

    try:
        await message.delete()
    except discord.HTTPException:
        log.exception("Failed to delete honeypot message from %s", member)

    try:
        await member.ban(reason=reason, delete_message_seconds=86400)
    except discord.HTTPException:
        log.exception("Failed to ban %s", member)
        return

    modlog = client.get_channel(MODLOG_CHANNEL_ID)
    if modlog is not None:
        embed = discord.Embed(
            title="Honeypot ban",
            description=f"Banned {member.mention} (`{member.id}`)",
            color=discord.Color.red(),
        )
        if content_preview:
            embed.add_field(name="Message", value=content_preview, inline=False)
        await modlog.send(embed=embed)

    log.info("Banned %s (%s) for posting in honeypot channel", member, member.id)


client.run(BOT_TOKEN)
