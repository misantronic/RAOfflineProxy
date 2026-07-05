# Pending Awards

When you earn a casual achievement while offline, `RAOfflineProxy`:

1. Stores it in the local queue
2. Returns success so the unlock registers immediately in RetroArch

When connectivity returns, queued awards are flushed automatically.

From the user side, this means offline unlocks still register locally right away and are sent to RetroAchievements later.

Queued awards are sent oldest first. Duplicate pending awards are discarded. Authentication failures require refreshed login state. Network failures keep awards queued for later retry.

On KNULLI and muOS, pending awards can be reviewed directly from the on-device menu. Onion does not currently include a dedicated pending-awards browser yet.

## Anti-Tamper

The queue uses the shared Linux anti-tamper hash chain.

See [Anti-Tamper Hash Chain](/linux-hash-chain).
