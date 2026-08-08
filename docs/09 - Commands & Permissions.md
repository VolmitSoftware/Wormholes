# Commands & Permissions

Wormholes exposes a single root command through the Director framework (`CommandWormholes` and nested `CommandAdmin`, `CommandNetwork`, `CommandServer`). `WormholesCommandService` opens the admin command path when the sender has **any** admin command leaf (or `wormholes.admin`); each handler still checks its own permission. Public `help` / `info` remain available without admin rights.

## Root

| Property | Value |
|----------|--------|
| Command | `/wormholes` |
| Aliases | `/wh`, `/wormhole` |
| Declared in | `plugin.yml` (`commands:`); Paper also registers via lifecycle (`paper-plugin.yml` has permissions only) |
| Root gate | Any of: `wormholes.admin`, `wormholes.admin.reload`, `wormholes.admin.items`, `wormholes.admin.network`, `wormholes.admin.projection`, `wormholes.admin.reset` |
| Without root gate | Only bare `help`/`?` (or empty) and `info` run; other args get no-permission |
| Public tab complete | `help`, `info` only when lacking all admin command leaves |
| Admin help | Full Director mini-menu when the root gate passes |

Handlers still require their own node (for example reload needs `wormholes.admin.reload`). Holding only a leaf is enough to enter Director routing; lacking that leaf still fails inside the handler.

## Command tree

### Public (no `wormholes.admin`)

| Syntax | Permission | Effect |
|--------|------------|--------|
| `/wormholes` or `/wormholes help` or `/wormholes ?` | none (public path) | Public help: points to `/wormholes info` and wand usage |
| `/wormholes info` | none (public path) | Multi-line portal building instructions |

### Root handlers (`CommandWormholes`)

| Syntax | Origin | Permission (after root gate) | Effect |
|--------|--------|------------------------------|--------|
| `/wormholes wand` | player | `wormholes.admin.items` | Give Portal Wand + 1 Wormhole Rune |
| `/wormholes wand rune=<portal\|wormhole\|gateway> [count=1]` | player | `wormholes.admin.items` | Give 1–64 runes of that type |
| `/wormholes door [type=pair]` | player | `wormholes.admin.items` | Give Dimensional Door item; requires doors enabled |
| `/wormholes reload` | both | `wormholes.admin.reload` | Reload config + language |
| `/wormholes debug` | both | `wormholes.admin` | Toggle verbose logs + one-second console telemetry (silent in-game) |
| `/wormholes stats [now=false]` | both | `wormholes.admin` | Print stats snapshot path; `now=true` force-writes |
| `/wormholes info` | both | public or admin | Building instructions |

Door `type` completions: `pair`, `personal`, `public`, `pair_trapdoor`, `personal_trapdoor`, `public_trapdoor`.

### `/wormholes admin` (`CommandAdmin`)

| Syntax | Permission | Effect |
|--------|------------|--------|
| `admin deleteallportals` | `wormholes.admin.reset` | Immediately delete every local portal and saved portal link; no confirmation prompt |
| `admin deleteeverything` | `wormholes.admin.reset` | Immediately reset portals, door state, config, trust, identity, and network routes; no confirmation prompt |
| `admin freeze [seconds=30]` | `wormholes.admin.projection` | Freeze all projections; `0` resumes; non-zero clamped 5–300 |
| `admin flush` | `wormholes.admin.projection` | Revert projected blocks for all observers and rebuild |

### `/wormholes network` (`CommandNetwork`)

| Syntax | Permission | Effect |
|--------|------------|--------|
| `network import <code>` | `wormholes.admin.network` | Import portal or server code; saves route/trust; does not auto-link a portal from chat (link via gateway menu) |
| `network status` | `wormholes.admin.network` | Local listen mode, public key fingerprint, peer CONNECTED/CONNECTING/WAITING/error |
| `network doctor` | `wormholes.admin.network` | Diagnostic lines for connection failures |

### `/wormholes server` (`CommandServer`)

| Syntax | Permission | Effect |
|--------|------------|--------|
| `server connect <name>` | `wormholes.admin.network` | Transfer self to linked server (player only) |
| `server export` | `wormholes.admin.network` | Export this server as click-to-copy code (console: raw code) |
| `server import <code>` | `wormholes.admin.network` | Same import path as `network import` (server or portal code) |
| `server list` | `wormholes.admin.network` | Linked servers with ready/offline + game address |
| `server remove <name>` | `wormholes.admin.network` | Delete route + trusted key for peer |
| `/wormholes server <name>` | `wormholes.admin.network` | Shorthand for `server connect` when second arg has no `=` |

`network import` and `server import` both call `ImportExportService.importCode` and are equivalent for code exchange.

`deleteeverything` refuses while a player is inside or transiting a pocket dimension. On success it deletes `config/`, `identity/`, `routes/`, `trust/`, `portals/`, and `doors/`, preserves the retired pocket-slot counter, and regenerates default config and an empty door snapshot. Language overrides, dictionaries, UDS paths, and the stats snapshot are outside that deletion set.

## Static permissions (`plugin.yml` / `paper-plugin.yml`)

| Node | Default | Description / children |
|------|---------|------------------------|
| `wormholes.*` | op | All Wormholes nodes → `admin`, `portals`, `gateway` |
| `wormholes.admin` | op | All admin → `admin.reload`, `admin.items`, `admin.network`, `admin.projection`, `admin.reset`, `doors.bypass` |
| `wormholes.admin.reload` | op | Reload configuration |
| `wormholes.admin.items` | op | Spawn wand, runes, door items |
| `wormholes.admin.network` | op | Network/server import export list remove status doctor connect |
| `wormholes.admin.projection` | op | Freeze / flush projections |
| `wormholes.admin.reset` | op | deleteallportals / deleteeverything |
| `wormholes.doors.bypass` | op | Bypass dimensional door access lists (also op / `wormholes.admin` pass access checks) |
| `wormholes.gateway` | op | Create and type-switch to gateway portals |
| `wormholes.portals` | true | Parent for non-gateway frame portal types → `portals.wormhole`, `portals.portal` |
| `wormholes.portals.wormhole` | true | Create and type-switch to wormhole-type frame portals |
| `wormholes.portals.portal` | true | Create and type-switch to portal-type and RTP frame portals |

### Frame portal type nodes (enforced)

`PortalTypeAccess` checks the resolved leaf on wand/rune construction and type-menu switches. Bukkit permission children make `wormholes.portals` grant both non-gateway leaves; querying the leaf preserves an explicit child denial.

| Type | Permission (any of) |
|------|---------------------|
| `PORTAL`, `RTP` | `wormholes.portals.portal` |
| `WORMHOLE` | `wormholes.portals.wormhole` |
| `GATEWAY` | `wormholes.gateway` |

`wormholes.admin` and ops always pass. These nodes do not gate departure or arrival; traversal uses the dynamic per-portal node below. Defaults let everyone construct Portal/Wormhole/RTP types and reserve Gateway management for ops.

### Dynamic per-portal node

| Node | When | Semantics |
|------|------|-----------|
| `wormholes.portal.<sanitized-name>` | Portal access policy enabled for that portal | `PortalPermissionMode`: **WHITELIST** requires the node; **BLACKLIST** allows anyone without the node (op always allowed). Name is sanitized from the portal display name. |

Remote portals use the same node form from the remote portal name.

Sanitization lowercases the name, keeps ASCII `a-z`, `0-9`, `.`, `-`, and `_`, collapses other character runs to `_`, trims leading and trailing underscores, and uses `unnamed` when nothing remains. Renaming changes the permission node, and distinct names can sanitize to the same node; permission assignments should therefore be reviewed after renames.

## Related docs

- `01 - Installation & Configuration.md` — reload targets
- `10 - Cross-Server Networking.md` — network/server command workflows
- `07 - Dimensional Doors.md` — door items and access bypass
- `03 - Building Portals.md` — wand / rune construction
