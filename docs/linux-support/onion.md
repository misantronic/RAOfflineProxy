# Onion

Onion is a planned Linux target for `RAOfflineProxy`, but it does not have a usable public install flow yet.

This page documents the current state of the investigation and the main obstacles that still need to be solved before Onion can be treated like the existing KNULLI target.

## Current State

What is already known:

- Onion supports custom ports and launcher scripts from the SD card
- Onion ships a bundled Python 2.7 runtime through its Parasyte environment
- Onion also ships `pygame` for that Python 2.7 runtime
- Onion does not appear to ship a documented runtime that matches the current Linux implementation directly

That means Onion looks technically possible as a target, but not with the current Linux codebase unchanged.

## Current Obstacles

### Python Runtime Mismatch

The current Linux implementation in this repository targets a newer Python runtime than Onion appears to provide by default.

Onion, however, appears to provide Python 2.7 in its standard runtime environment.

That is currently the biggest blocker because the Linux codebase relies on newer language and standard-library behavior that is not expected to run unchanged in Onion's default environment.

So even though Onion has `pygame`, the current Linux app is not expected to run there unchanged.

### No Confirmed Matching Runtime Path Yet

The Onion repository shows Python 2.7 binaries in the bundled Parasyte environment, but there is currently no confirmed built-in runtime path that matches the current Linux implementation directly.

That means Onion support likely requires one of these approaches:

- bundle a matching private runtime with `RAOfflineProxy`
- maintain an Onion-specific Python 2.7-compatible build target
- reduce the Onion target to a smaller launcher flow that avoids the current menu implementation

No approach has been selected yet.

### Device-Specific RetroArch Integration Is Still Unverified

Even if the runtime issue is solved, Onion support still needs real-device validation for:

- the active `retroarch.cfg` location used during normal play
- where Onion expects custom tools or ports to store configuration data
- how reliable long-running proxy start and stop behavior is on-device
- what the cleanest user-facing install flow should be

Onion documentation is enough to estimate packaging direction, but not enough to claim end-to-end support without hardware testing.

### Packaging Still Needs a Target-Specific Flow

KNULLI currently has its own install bundle, launcher, and startup assumptions.

Onion will need a separate target-specific package based on Onion's port layout instead of reusing the KNULLI install flow directly.

That likely means:

- a dedicated `linux/onion/` target in this repository
- Onion-specific launcher scripts
- Onion-specific install documentation
- no assumption that KNULLI autostart behavior maps directly to Onion

## What Would Need To Happen Next

Before Onion can move from planned to usable, the remaining work is roughly:

1. choose a runtime strategy for Onion
2. add a dedicated Onion packaging target
3. confirm RetroArch and config paths on real hardware
4. validate start, stop, and offline flow on an actual Onion device

Until then, Onion should be treated as a planned target rather than an experimental install target.
