# PlaceholderAPI

Wormholes registers a PlaceholderAPI expansion under the identifier `wormholes`. Server keys describe plugin-wide state; player keys describe the one portal selected for that player from a published snapshot. Values are plain strings; there is no Wormholes compile dependency for consumers.

For integrator lifecycle, threading, and compile notes see `22 - API - PlaceholderAPI.md`.

## Dependency

Wormholes soft-depends on PlaceholderAPI (`plugin.yml` softdepend; Paper `dependencies.server.PlaceholderAPI` with `load: BEFORE`, `required: false`, `join-classpath: true`). Your scoreboard or plugin only needs PlaceholderAPI; you do not soft-depend on Wormholes for placeholders.

When Wormholes is absent or disabled, keys do not resolve and PlaceholderAPI leaves the text unreplaced.

## Lifecycle

1. On Wormholes enable, if PlaceholderAPI is already enabled, the expansion registers.
2. Wormholes also listens for PlaceholderAPI `PluginEnableEvent` and registers when PlaceholderAPI enables later.
3. Until the first attendance publish, server keys answer `---` (except `available`, which is `false`); player keys answer `---` for every player.
4. On plugin disable/unload, the expansion unregisters, player snapshots drop, and the server snapshot clears. `/wormholes reload` only reloads config/language and leaves the expansion registered.

The expansion sets `persist()`, so `/papi reload` does not remove it and Wormholes does not re-register for that case alone.

## Where values come from

Resolvers do not query the portal registry live. The portal attendance pass records player positions (join, move, world change; forgotten on quit). Attendance runs every fifth portal-update-driver pass; placeholders publish every fourth attendance pass (~1 Hz under normal load). Values can lag by about one second, longer when the server is behind.

Resolving a key is a map/field read of an immutable snapshot: non-blocking, no chunk/entity access. There is no force-publish key and no age key. A standing player with no recorded position after a plugin reload has no snapshot until they move.

## Selection rule (player keys)

For each player Wormholes picks at most one portal:

1. **Same world.** Other worlds are never candidates.
2. **In range.** Distance from the player location to the portal centre must be within `64 blocks + half the portal bounding-box diagonal`. Constants are compiled in (`BASE_RANGE = 64`, not configurable).
3. **Facing wins.** Among candidates, any portal the player is looking at beats any they are not, regardless of distance. Facing means cosine of angle from look direction to portal centre ≥ `0.94` (~20° cone). Direction is from player **location** (not eyes) to portal **centre**.
4. **Then nearest** within the same facing class.
5. **Then current snapshot order** for exact ties. Registry changes can reorder that final tie-break.

If none pass steps 1–2, `%wormholes_portal.available%` is `false` and every other `portal.*` / `rtp.*` key is `---`.

### Logout grace

On quit the player snapshot is not deleted immediately; it expires after 60 seconds (`DEFAULT_GRACE_MS`). Resolving against that `OfflinePlayer` still returns the last portal for that minute, then `---`.

## Keys

Prefix `%wormholes_`. Matching is case-insensitive; canonical form is lowercase with `-` inside a segment and `.` between segments.

### Server keys

Ignore the player; same answer for everyone and for a no-player parse.

| Key | Format | Meaning |
|-----|--------|---------|
| `%wormholes_available%` | boolean | `true` after first server snapshot publish; `false` before. Never `---` |
| `%wormholes_portals%` | integer | Local registered portals. Remote portals not counted |
| `%wormholes_projections.active%` | integer | Portals currently rendering a projection |
| `%wormholes_projections.observers%` | integer | Distinct players with a projection frame in flight (one player on two portals counts once) |
| `%wormholes_peers.connected%` | integer | Peers with completed handshake and live link |
| `%wormholes_peers.link%` | enum | Cross-server link health (see vocabularies) |
| `%wormholes_transfers.in-flight%` | integer | Player handoffs plus non-player entity transfers in progress |
| `%wormholes_failures%` | integer | Cumulative internal failures since startup |
| `%wormholes_failures.per-minute%` | decimal | Failures per minute extrapolated from the latest elapsed sample interval (at least one second) |

`failures` is a health signal (refused scheduler tasks, dropped events, provider faults, sideband drops), not an error log.

### Player keys

Describe the selected portal. Except `portal.available` (`false`), all answer `---` when no portal is in range, the player is unknown, or there is no player context.

| Key | Format | Meaning |
|-----|--------|---------|
| `%wormholes_portal.available%` | boolean | `true` when this player has a portal snapshot. Never `---`; branch on this first |
| `%wormholes_portal.name%` | text | Selected portal name; `---` if none |
| `%wormholes_portal.state%` | enum | `open`, `closed`, or `syncing` |
| `%wormholes_portal.destination%` | text | Linked portal name; peer server name for cross-server with unknown remote name; `---` if no tunnel |
| `%wormholes_portal.distance%` | decimal | Blocks to portal centre, two decimal places |
| `%wormholes_portal.cross-server%` | boolean | `true` when the tunnel goes to another server |
| `%wormholes_rtp.state%` | enum | RTP state, or `---` when the selected portal is not RTP |
| `%wormholes_rtp.cooldown%` | decimal | Seconds until this RTP portal may search again (`0.00` when allowed now); `---` when not RTP |

`rtp.*` keys describe the **selected portal**, not the player.

## Value vocabularies

Treat unknown words like `---` and always keep a `default` arm.

`%wormholes_portal.state%`:

| Value | Meaning |
|-------|---------|
| `open` | Open and passable |
| `closed` | Closed |
| `syncing` | Mid open/close transition (priority over open/closed) |

`%wormholes_rtp.state%`:

| Value | Meaning |
|-------|---------|
| `ready` | Destination chosen; portal accepts a traveler |
| `warming` | Destination search running |
| `rerolling` | Discarding destination and choosing another |
| `cooldown` | No destination; may not search yet |
| `idle` | No destination and no cooldown |
| `---` | Not an RTP portal, or RTP runtime not registered |

`%wormholes_peers.link%`:

| Value | Meaning |
|-------|---------|
| `offline` | Cross-server networking not configured or not running |
| `solo` | Networking running but no peers configured (not a fault) |
| `down` | Peers configured and none connected |
| `degraded` | Some configured peers connected, not all |
| `linked` | Every configured peer connected |

## Formats

| Format | Produced as |
|--------|-------------|
| boolean | `true` or `false` (never localised; never `---` unless the table says so) |
| integer | Base-ten digits, no grouping, no sign; negatives clamped to zero |
| decimal | Exactly two places, `.` separator regardless of locale; non-finite → `---` |
| text | `%` removed; legacy `§` colour pairs stripped; empty after strip → `---` |

`---` is the unavailable marker: a value, not an error. Raw portal names with colour or `%` cannot be recovered through this surface.

## Failure policy

| Situation | Result |
|-----------|--------|
| Wormholes missing/disabled | Key does not resolve |
| PlaceholderAPI not present | No expansion |
| Unknown key | Does not resolve (not `---`) |
| Empty/blank parameter | Does not resolve |
| Player key, no player | `---`; `portal.available` = `false` |
| Player never seen | `---`; `portal.available` = `false` |
| Quit &lt; 60s ago | Last known portal, then `---` |
| Resolver throws | `---` + one warning per distinct key (cap 64 keys logged) |
| Wormholes disables mid-parse | In-flight parse may return last values; then keys stop resolving |

**Does not resolve** means PlaceholderAPI leaves `%wormholes_…%` literal. **`---`** means the expansion answered "no value".

## Configuration

None for the expansion: no toggle, range, interval, or unavailable-marker setting. Selection range and facing threshold are shared with portal attendance and are not TOML-configurable. Server config still affects values indirectly (e.g. networking off → `peers.link` = `offline`).

## Discover on a live server

```
/papi info wormholes
/papi parse me %wormholes_portal.state%
```

## Operator examples

Scoreboard / config line (no code required):

```
Portal: %wormholes_portal.name% (%wormholes_portal.state%)
```

Branch on availability before other player keys so "no portal" is not confused with an unnamed portal. Compare destination to `---` when a portal may be unlinked.
