# Fake RetroAchievements server

A stdlib-only stand-in for `retroachievements.org` used by the Linux E2E harness.
It speaks enough of the `dorequest.php` connect API for RAOfflineProxy to run a
full session against it, and it asserts protocol invariants on the way in.

Point the proxy at it with `upstream_host` in `config.json`:

```json
{ "upstream_host": "http://127.0.0.1:8181" }
```

## Running

```bash
python3 -m linux.tests.e2e.fake_ra --port 8181 --ctl-port 8182 --state /tmp/ra-state.json
```

`--state` is optional; without it the server is purely in-memory. Run from the
repo root. Python 3.9+ so it can run inside the Miyoo/Onion containers too.

## Actions

`login` / `login2`, `gameid`, `patch`, `achievementsets`, `unlocks`,
`startsession`, `ping`, `postactivity`, `awardachievement`, `submitlbentry`.
`/Badge/*`, `/Images/*` and `/UserPic/*` return a 1x1 PNG so the image cache path
has something to fetch. `HEAD /` backs the proxy's reachability probe.

Two users ship in `fixtures/users.json`: `testuser` / `testpass` and
`seconduser` / `secondpass`.

## Invariants it enforces

These are recorded on `/_ctl/violations` and are the reason this is a server
rather than a mock:

- **`h=1` never arrives.** Any request carrying hardcore is logged as a
  violation, whatever the response.
- **Award validation hash.** When an award carries `v`, it is recomputed as
  `md5(a + u + h [+ a + o])` and rejected on mismatch, so a flushed award has to
  be one production would have accepted.
- **User-Agent gating.** The first UA token must be a known client with a
  parseable version, mirroring RA's `unsupported_client` 403.
- **Token validity.** Authenticated actions 401 on a stale token.

## Control plane

Served on a separate port so control traffic never pollutes the request journal.

| Route | Method | Purpose |
| --- | --- | --- |
| `/_ctl/health` | GET | liveness + current mode |
| `/_ctl/mode` | GET/POST | `online`, `offline`, `degraded`, `slow` |
| `/_ctl/journal` | GET | every request seen, in order (`?action=` filters) |
| `/_ctl/violations` | GET | invariant breaches |
| `/_ctl/unlocks` | GET | `?u=` and optional `?g=` |
| `/_ctl/score` | GET | `?u=` |
| `/_ctl/reset` | POST | wipe progress (`{}`, or `{"user":…, "game_id":…}`) |
| `/_ctl/rotate-token` | POST | invalidate a user's token |
| `/_ctl/map-hash` | POST | `{"hash":…, "game_id":…}` |
| `/_ctl/clear-journal` | POST | reset journal and violations |
| `/_ctl/user-agent-enforcement` | POST | `{"enabled": false}` to disable gating |
| `/_ctl/slow-delay` | POST | `{"seconds": 3.0}` for `slow` mode |

`/_ctl/reset` is what replaces resetting achievement progress on real RA — there
is no supported API for that, and this test never touches production.

### Modes

- `online` — normal.
- `offline` — drops the connection, so the proxy's probe fails the way a real
  outage does.
- `degraded` — 503 on everything, which the probe reads as unreachable.
- `slow` — sleeps before responding, for timeout paths.

## Hash fixtures

`fixtures/games.json` maps ROM hash to game id. The hashes are real RA hashes,
produced by running `libraproxy_rchash.so` over the fixtures in
`linux/tests/fixtures/` on aarch64:

| fixture | hash | game |
| --- | --- | --- |
| `mslug.7z` | `b43c8b4ec999588c04dad79bb8bcc745` | 1447 Metal Slug |
| `pokemon.7z` | `00bfc8c729f5d4d529a412b12c58ddd2` | 515 Pokemon Red |
| `bundle.7z` | `94377c156735b39dfa4ac607234cb87c` | 1448 Bundle Test Game |

`mystery.7z` (`dffccf88df7375c79e4ebbc246df0824`) is deliberately unmapped so the
unknown-game path (`GameID: 0`) stays covered.

Regenerate them inside a device container with:

```python
from raofflineproxy import rom_hashing
rom_hashing.hash_rom("/repo/linux/tests/fixtures/mslug.7z")
```

A hash can also be registered at runtime, which is how a scenario pins a ROM the
fixtures do not know about:

```
POST /_ctl/map-hash  {"hash": "<from libraproxy_rchash>", "game_id": 1447}
```
