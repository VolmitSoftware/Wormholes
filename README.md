# Wormholes
Now, you can see the other side.

## Language and localization

Canonical English is defined in the typed Java catalogs under `src/main/java/art/arcane/wormholes/localization`; Wormholes does not ship a separate English translation bundle. Complete bundles are included for German, Spanish, Finnish, French, Hebrew, Italian, Japanese, Korean, Lithuanian, Dutch, Polish, Portuguese, Russian, Turkish, Vietnamese, Simplified Chinese, and Traditional Chinese. The main configuration selects the active locale and optional fallback locales. A file at `plugins/Wormholes/languages/<locale>.toml` can override only the messages a server wants to change; omitted entries resolve through bundled fallbacks and finally code-owned English.

## Random Teleport portals

Switch a portal to **RTP** from its Type menu, then open **Random Destination**
on the portal home page to configure it. The editor controls the target world,
portal-relative or custom search center, minimum and maximum radius, vertical
search bounds, shared or per-player destinations, destination rotation, timing,
reservation behavior, rim feedback, and portal-specific sound. Settings are
grouped into direct-choice submenus with dedicated decrease/increase buttons;
normal left clicks stage a draft and **Apply Changes** commits the batch once.
Manual rerolls and private-pool rebuilds use an explicit confirmation page.

New RTP portals reroll their shared destination after every successful trip.
Operators can instead choose a timed cycle or an intentionally static route;
an explicitly saved Static choice remains static. Per-player portals expose a
separate private rotation time and swap each reservation from an already warm
pool. During refills, rerolls, and authorization checks, the last READY
projection remains online until its replacement can be published; idle lease
grace avoids cold churn when observers briefly step away.

Surface mode checks only the actual motion-blocking terrain surface. Water,
waterlogged or aquatic blocks, tree structure, hazards, collisions, and cave
fallbacks are rejected. Clear ground beneath a sufficiently high canopy remains
valid. Each RTP portal can mute its own open, close, ambient, rejection, and
travel sounds without disabling particles, and the layered opening impact uses
a quieter mix. Travelers keep the look and movement orientation associated with
the side they entered, and the final teleport uses the same complete entity
envelope that passed the destination safety check.

## Survival Dimensional Doors

Dimensional Doors are real vanilla doors: they activate only while physically
open and only when an eligible traveler crosses the visible portal surface.
After a successful crossing, the source door closes with its normal material
sound and the traveler hears the player-teleport cue. The open doorway uses an
opaque, fullbright Crying Obsidian backing with a client-animated Nether Portal
veil. It is recessed one pixel inside the closed-door edge, keeps one pixel of
clearance beside the hinge, and extends flush to the opposite latch edge.
Every crossing preserves the entered closed-door face at the destination:
front-to-front and back-to-back, facing outward while preserving relative look
direction and safely adjusting upward or downward for uneven terrain.
Access is per player, not per door. The owner sneak-right-clicks a placed door
with an empty hand to open a compact access window that grows one row for every
nine listed players: a header with the door placard and the add-player button,
then one stained-glass pane per listed player. Black is neutral and changes
nothing, green whitelists, red blacklists. Left-click a pane to whitelist,
right-click to blacklist, and middle-click or shift-left-click to drop the
player from the list. A blacklisted player is always refused; once anybody is
whitelisted, everyone who is not whitelisted is refused as well. The owner,
operators, `wormholes.admin`, and `wormholes.doors.bypass` holders always pass,
and exit doors inside pockets are never gated. Normal door interaction and any
server protection plugin remain the authority everywhere else.

- **Entangled Door Pair:** right-click either the air or a block with the crafted
  bundle to unpack automatically linked A/B Wormhole Doors. Either endpoint can
  be moved without losing the pairing. Ordinary mobs and empty vehicles that
  physically fit through a one-block-wide, two-block-high door can use it from
  either side.
- **Personal Dimension Door:** every traveler reaches their own persistent
  pocket in the shared void dimension.
- **Public Dimension Door:** the item itself owns one shared persistent pocket. Breaking
  and moving that specific door preserves its destination; crafting another
  creates a different pocket. Every traveler using that door enters the same space.

All three support cross-dimensional player travel on the same server, and Pair
doors load a far-away or unloaded destination chunk before transit. Pocket
spaces are separated by 8,192 blocks and use protected 32x32x32 shells with
buildable 30x30x30 interiors. A manually operable return door is centered at
floor level on one wall, with arrivals placed safely just inside it. Lethal
damage anywhere in the shared pocket world leaves the player at one heart and
ejects them through their saved return route, falling back to a safe loaded
non-pocket world spawn when that route is unavailable. The dimension is
fullbright without potion effects, and tick speed remains vanilla.

### Recipes

Each recipe uses the existing exact **Wormhole Rune** item (`R`).

```text
Entangled pair       Personal door       Public dimension door
E D E                 _ R _               R D R
O R O                 C D E               _ E _
_ D _                                     _ L _
```

`E` is Ender Eye (pair), Ender Chest (other recipes); `D` is any vanilla door
for every recipe; `O` is Obsidian, `C` is Recovery Compass, and `L` is
Lodestone. Pair endpoints default to Oak, Personal Doors default to Dark Oak,
and Public Doors default to Pale Oak.

To change the appearance, combine one Dimensional Door with one ordinary
wooden, bamboo, crimson, or warped door in any crafting-table arrangement. The
result keeps the exact same pair, personal traveler mapping, or public pocket
identity while adopting the ordinary door's material. Iron and copper doors
cannot be selected as skins because every Dimensional Door must remain
hand-openable. Existing placed Iron Dimension Doors are converted to the new
Pale Oak Public Door default without changing their saved destination.

Identity-bearing kits and placed doors are consumed normally in Creative mode.
Breaking a Pair, Personal, or Public door always drops that exact door item,
including in Creative, so its link or pocket destination survives being moved.

Administrators can issue test items with
`/wormholes door type=<pair|personal|public>`. Installing the feature or changing
its bundled dimension data requires a full server restart so Paper can rebuild
the world registries.

Set `[main] dimensional-doors-enabled = false` in `wormholes.toml` to disable
the complete feature live. New entries stop immediately; active travelers and
pocket occupants may finish through their return route before recipes,
protection, and portal displays shut down. Existing blocks then behave as
ordinary doors, while saved door and pocket identities remain available if the
setting is re-enabled.

## Cross-server handoff

Servers pair by exchanging pasteable codes. `/wormhole server export` prints a
click-to-copy server code (console output prints the raw code instead); paste it
on the other server with `/wormhole server import <code>` to store the route and
trusted key under `routes/` and `trust/` in the plugin data folder. Portal codes
from a gateway's Export button are accepted by the same import and save the same
route. `/wormhole server list` shows every linked server with its live
reachability, `/wormhole server remove <name>` deletes a link again (while
`trust-on-first-use` is enabled, an online peer that still has this server saved
re-registers itself through the sideband, so remove the link on both servers),
and
`/wormhole server <name>` (long form `/wormhole server connect <name>`) transfers
you to that server over the same direct or proxy path portal traversals use.

Imported portal codes retain both public and LAN route candidates. Direct player
handoffs use a private fallback only when the player connected from loopback or
the LAN and Wormholes has verified that endpoint through an active
private/loopback raw connection or a successful game-port sideband request. A
localhost connection alone never makes a separately hosted peer's private
address reachable. Internet players continue to receive the destination's
public endpoint. Game-port sideband traffic tries the same fallbacks only when a
socket connection was never established, and remembers the exact endpoint that
answered for the player handoff.

The source does not dispatch the client until the destination grants a
rate-limited admission lease for that exact transfer. The destination checks
that the live portal can receive, the selected direct or proxy method is
supported, the profile passes the built-in ban and whitelist gates, and online
players plus pending arrivals remain below the player limit. A denial, timeout,
or active cooldown returns the traveler to the source-facing side of the portal
instead of letting them remain inside the plane or disconnecting them. An
accepted arrival remains reserved until its destination-portal teleport succeeds,
with transient placement failures retried before falling back to the destination
spawn. Both servers must run the same wire protocol version.

Native Paper transfer admission should use `accepts-transfers=true` in every
destination server's `server.properties` followed by a restart. Wormholes keeps
its `network.autoAcceptTransfers` compatibility gate enabled by default for
hosts where that property cannot be changed.

Run `/wormhole debug` on both servers before reproducing a failed handoff. The
command is a silent toggle in game and writes one-second projection, network,
queue, peer, and handoff telemetry to each server console. Direct transfers log
the client's address, LAN classification, selected `host:port`, and every
configured endpoint; the destination logs when its transfer gate receives and
rewrites the incoming handshake. Run the same command again to stop the stream.

## PlaceholderAPI

Wormholes registers a `%wormholes_*%` expansion when PlaceholderAPI is enabled,
and unregisters it on disable, reload, and hot load. Every key below is a
complete list; anything else returns the literal text so a typo stays visible.
A key that is spelled correctly but has no value right now returns `---`.

Server-wide keys:

| Key | Value |
|---|---|
| `%wormholes_available%` | `true` once the first snapshot is published, `false` before that |
| `%wormholes_portals%` | portals loaded on this server |
| `%wormholes_projections.active%` | portals currently projecting |
| `%wormholes_projections.observers%` | players currently receiving a projection |
| `%wormholes_peers.connected%` | cross-server peers with a live link |
| `%wormholes_peers.link%` | `offline`, `solo`, `down`, `degraded`, or `linked` |
| `%wormholes_transfers.in-flight%` | cross-server handoffs and entity transfers in progress |
| `%wormholes_failures%` | terminal failures counted since start |
| `%wormholes_failures.per-minute%` | terminal failures per minute over the last window |

Per-player keys, all describing one portal: the portal the player is looking at
if there is one within its activation range, otherwise the nearest portal in
range. Range is the same threshold the ambient attendance pass already uses,
so the portal answered here is always a portal the player is close enough to
affect. A player with no portal in range gets `false` and `---`.

| Key | Value |
|---|---|
| `%wormholes_portal.available%` | `true` when a portal is in range, `false` otherwise |
| `%wormholes_portal.name%` | the portal's name, stripped of colour codes and `%` |
| `%wormholes_portal.state%` | `open`, `closed`, or `syncing` |
| `%wormholes_portal.destination%` | linked portal's name, or the peer server name for an unresolved cross-server link |
| `%wormholes_portal.distance%` | blocks from the player to the portal centre, two decimals |
| `%wormholes_portal.cross-server%` | `true` when the destination is on another server |
| `%wormholes_rtp.state%` | `rerolling`, `warming`, `ready`, `cooldown`, or `idle`; `---` when that portal is not a Random Teleport portal |
| `%wormholes_rtp.cooldown%` | seconds until the next destination search is allowed, two decimals |

Values carry no units, no grouping separators, no `%`, and no colour codes, so
they can be parsed and safely re-substituted by a scoreboard or tab plugin.

Resolution cost is one volatile read plus one map lookup and allocates nothing.
Nothing is computed while a placeholder is being resolved: the per-player answer
is built once a second on the thread that already owns the portal registry, by
the same pass that decides portal ambient attendance, and the server-wide answer
is built in that same pass. PlaceholderAPI does not need to be installed for any
of this - when it is absent the expansion is never registered and the publishing
pass never runs.

## Runtime

Java 25 server launch commands should include
`--enable-native-access=ALL-UNNAMED` so zstd-jni can continue loading its native
compression library without restricted-access warnings.
