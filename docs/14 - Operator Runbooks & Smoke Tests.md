# Operator Runbooks & Smoke Tests

Manual verification checklists for install, portals, projection, RTP, dimensional doors, networking codes, and projection maintenance. Use an isolated test instance when possible. Commands use aliases `/wormholes`, `/wh`, or `/wormhole`. Architecture context: `13 - Runtime Architecture.md`. Permissions: `09 - Commands & Permissions.md`.

## Install and reload

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Place the shaded Wormholes jar in `plugins/` on Paper or Folia; install optional PlaceholderAPI / Vault / Iris if testing those paths. | Server starts without enable failure splash. |
| 2 | Confirm `plugins/Wormholes/config/wormholes.toml` exists with `schema = 2`. | File present; quality and sections `[main]`, `[network]`, `[projection]`, `[render]`. |
| 3 | `/wh info` | Building instructions print. |
| 4 | Under `[main]`, edit a harmless key such as `verbose-logging = true`, then run `/wh reload` as op (`wormholes.admin.reload`). | Reload success message; no retained-settings error. |
| 5 | Toggle `/wh debug` (`wormholes.admin`). | Verbose logs / one-second telemetry toggle; console reflects change. |
| 6 | Restart server once after first Spigot-only pocket datapack install if testing doors on non-Paper. | Log no longer requires restart for pocket datapack; doors can start when enabled. |

## Build two local portals and link

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Use `/wh wand` or `/wh wand rune=<portal|wormhole|gateway> count=<n>` with `wormholes.admin.items`. | Wand and required runes are in inventory. |
| 2 | Build frame A; complete construction so a portal is created. | Portal appears; saved under `plugins/Wormholes/portals/`. |
| 3 | Build frame B in the same or another loaded world. | Second portal exists. |
| 4 | Open portal A menu → link/connect to portal B (tunnel). | Bidirectional or configured one-way tunnel valid; both menus show destination. |
| 5 | Walk through A → B and return. | Teleport succeeds; cooldown respects `[main] teleport-cooldown-millis` (default 1000 ms). |
| 6 | Fully restart the server/plugin. | Portals reload from JSON and the link remains. `/wh reload` does not reconstruct portal persistence. |

See `03 - Building Portals.md` and `04 - Portal Types Menus & Settings.md`.

## Enable projection and walk to view

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | On a linked portal, ensure **Projection** is **ON** and type supports projection (for example wormhole/gateway as configured). | Menu shows ON. |
| 2 | Leave **Render mode** at default Venticular; optionally compare PanOptic later. | Destination geometry appears through the aperture when standing in the view volume. |
| 3 | Stand outside the activation/view range (global default range 48 when activation range is 0), then walk into range facing the portal. | Projection starts when inside view AABB; stops after leaving and grace expires (`[projection] interest-grace-ticks`, default 5). |
| 4 | If testing blackout: enable blackout background and cycle color. | A far-plane display seal uses the chosen concrete color without replacing the sampled view. |
| 5 | With entity spoofing on (and quality not `performance`), have a mob near the destination. | Spoofed entity visible through the portal within spoof range. |
| 6 | Set quality to `performance`, reload. | Entity spoofing off; range/depth/budgets clamped per `05 - Projection Modes & Settings.md`. |

## RTP smoke

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Portal menu → type **RTP** → **Random Destination**. | Editor opens (overview with Destination/Landing/Routing/Effects). |
| 2 | Change min radius (for example +128). | Change applies immediately (applied notification); no Apply Changes control required. |
| 3 | Confirm defaults if untouched: min 512, max 4096, center portal-relative, vertical surface, allocation shared, rotation on-traversal, rim/sound on. | Status/settings match `06 - Random Teleport Portals.md`. |
| 4 | Wait until destination is ready; look through portal. | Projection shows a remote landing when state is ready. |
| 5 | Traverse the RTP portal. | Safe teleport; shared ON_TRAVERSAL portal begins reroll (state may show `rerolling` / `warming`). |
| 6 | With PlaceholderAPI: `%wormholes_rtp.state%` / `%wormholes_rtp.cooldown%` near the portal. | States among `rerolling` / `warming` / `ready` / `cooldown` / `idle`. |
| 7 | Optional: set rotation STATIC, traverse again. | Destination does not auto-reroll after trip. |

## Dimensional door pair: craft, place, travel

Requires `[main] dimensional-doors-enabled = true` (default) and a loaded pocket datapack on Spigot-family after restart if applicable.

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Craft or `/wh door pair` (admin items path) for an **Entangled Door Pair** kit. | Pair kit item granted. |
| 2 | Right-click air or a block with the kit to unpack linked A/B Wormhole Doors. | Two linked door items/endpoints. |
| 3 | Place door A and door B in valid positions. | Doors place; OpenState defaults to Open (portal active when physical door is open). |
| 4 | Open physical door A (if OpenState is Open) and walk through the portal surface. | Teleport to B; orientation rules per `07 - Dimensional Doors.md`; open-state source may close after living traveler. |
| 5 | Return through B. | Arrives at A safely. |
| 6 | Optional: access menu (owner sneak-right-click empty hand) and OpenState toggle. | Access list and OpenState change behavior as documented. |

Personal/Public pocket doors and trapdoor variants are separate products; pair kit is the minimal cross-link smoke.

## Dimensional door coverage

| Path | Check | Pass criteria |
|------|-------|---------------|
| Personal | Place two Personal doors; enter as two different players. | Each player reaches their own stable pocket from either door and returns through the anchored return door. |
| Public | Break/re-place one Public door, then craft a second. | The moved identity keeps its pocket; the new item receives a different shared pocket. |
| Trapdoor | Repeat Pair and one pocket path with trapdoor products. | Top/bottom aperture mapping and OpenState match `07 - Dimensional Doors.md`. |
| Access | Add whitelist/blacklist entries, test owner/bypass, then toggle OpenState CLOSED. | Per-door policy is enforced; CLOSED acts as a contact surface while the block remains shut. |
| Objects | Send a projectile or dropped item through Pair/Public, then Personal. | Pair/Public transfer eligible objects with momentum; Personal refuses them. |
| Persistence | Fully restart after placed endpoints, access edits, and pocket allocation. | Door identities, access records, links, and pocket slots reload; return routes still work. |

## Server code export/import (network enabled)

Requires `wormholes.admin.network`. Export/import sets `[network] enabled = true` and starts networking when necessary.

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | On server A: `/wh server export`. | Clickable/copyable server code in chat. |
| 2 | On server B: `/wh server import <code>`. | Route/trust accepted; peer appears. |
| 3 | `/wh server list` and `/wh network status` on both. | Peer listed; status CONNECTED when reachable (or doctor explains failure). |
| 4 | Optional portal export: gateway portal menu **Export**, then `/wh network import <portal code>` on the other side; link via gateway Link menu. | Remote portal route stored; gateway can link. |
| 5 | `/wh network doctor` if not connected. | Diagnostics lines printed. |

## Cross-server gateway handoff and entity transfer

Use two isolated servers running the exact same Minecraft and Wormholes versions.

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Complete the bidirectional route/trust workflow above and link one Gateway on each server. | Both peers CONNECTED; each gateway names the remote destination. |
| 2 | Walk a non-op player through A. | Destination admits and places the player at B; source does not dispatch before ACK. |
| 3 | Lock B inbound or trigger another destination admission denial, then retry. | Player remains or returns on the source-facing side; doctor/stats records the denial. |
| 4 | Send a permitted non-player entity through A. | One matching entity appears at B with transformed momentum; source copy disappears after accepted ACK. |
| 5 | Add that Bukkit entity type to `[network] entity-transfer-deny-types`, reload, and retry. | Destination refuses it and the source entity is restored instead of duplicated or lost. |

Details: `10 - Cross-Server Networking.md`. Import/export services are constructed during network bootstrap even when diagnosing disabled network; status commands report disabled/not running when appropriate.

## Debug toggle

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | `/wh debug` with `wormholes.admin`. | Toggles verbose console logs and one-second telemetry. |
| 2 | Perform a projection or network action. | Additional diagnostic lines appear when enabled. |
| 3 | `/wh debug` again. | Telemetry quieted. |

`[main] verbose-logging` / debug telemetry also sync on reload (`WormholesDiagnosticsRuntime`).

## Stats snapshot

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Confirm `[network.stats] enabled = true`; optionally set `interval-sec` or `path-override`. | Default output is `plugins/Wormholes/wormholes-stats.txt`; an absolute override is used as written. |
| 2 | `/wh stats now=true` with `wormholes.admin`. | Command reports the output path and force-replaces the snapshot atomically. |
| 3 | Inspect the file while projection/network activity runs. | Sections include CONFIG, PEERS, COMPRESSION, VIEW STREAMING, TRANSFERS, FAILURES, and ERRORS from the last 60 seconds. |

## Freeze and flush projection

Permission: `wormholes.admin.projection`.

| Step | Action | Pass criteria |
|------|--------|---------------|
| 1 | Stand in an active projection. | Destination view visible. |
| 2 | `/wh admin freeze 30` (default 30 if omitted; allowed 5–300). | Message that projections are frozen; view stops updating. |
| 3 | `/wh admin freeze 0` | Projections resume. |
| 4 | `/wh admin flush` | Projected blocks revert to ground truth and rebuild; flushed count reported. |

## Quick failure matrix

| Symptom | Check |
|---------|--------|
| Plugin fails enable | Console stacktrace; schema-less or wrong `schema` in TOML; dependency load. |
| No projection | Projection OFF; outside view AABB; freeze active; portal not projectable (RTP not ready); budgets starved under PERFORMANCE. |
| RTP never ready | World not loaded; radius/border too tight; safety rejects all candidates; search cooldown. |
| Doors missing | `[main] dimensional-doors-enabled` false; pocket datapack restart still required; drain in progress. |
| Network import fails | Network not initialized; invalid/truncated code; same identity; peer offline (`/wh network doctor`). |

## Cross-references

- Config: `01 - Installation & Configuration.md`
- Building: `03 - Building Portals.md`
- Menus: `04 - Portal Types Menus & Settings.md`
- Projection: `05 - Projection Modes & Settings.md`
- RTP: `06 - Random Teleport Portals.md`
- Doors: `07 - Dimensional Doors.md`
- Commands: `09 - Commands & Permissions.md`
- Network: `10 - Cross-Server Networking.md`
- Architecture: `13 - Runtime Architecture.md`
