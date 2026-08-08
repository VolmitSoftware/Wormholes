# Concepts

Wormholes models frame portals as typed apertures with optional destination tunnels, optional through-portal projection, and separate travel/access policy. Local portals live on this server; remote gateway portals are peer-replicated for cross-server pairing. Dimensional Doors and pocket worlds are a survival product path that does not use the same construction menus as frame portals.

## Portal types

| `PortalType` | Role |
|--------------|------|
| `PORTAL` | Linkable frame portal. Default type for wand box construction. |
| `WORMHOLE` | Linkable frame portal (same projection capability as `PORTAL`). |
| `GATEWAY` | Cross-server capable type. Pairs with other gateway locals and remote gateways; uses export/import codes. |
| `RTP` | Random teleport portal. No destination tunnel; destination is sampled by the RTP service. |

`LocalPortal.supportsProjections()` is `true` for every type. A normal portal projects only when projection mode is `ON`, the portal is open, its surface does not block projection, and it has either a tunnel or mirror mode; an RTP portal also needs an authorized READY destination view for that observer. Menu copy may describe `PORTAL` as “basic” and `WORMHOLE` as “viewport projection,” but both types can project under the same conditions. See `05 - Projection Modes & Settings.md`.

RTP cannot be linked as a tunnel destination. Switching a portal to `RTP` clears tunnel, mirror mode, and dimensional pair identity; switching away from `RTP` also clears the tunnel. RTP construction by rune is unsupported (wand left-click on an RTP rune is rejected).

## Projection mode vs render mode

| Control | Values | Meaning |
|---------|--------|---------|
| `ProjectionMode` | `ON`, `OFF` | Whether this portal produces a through-view for interested observers. Default `ON`. |
| `ProjectionRenderMode` | `PANOPTIC`, `VENTICULAR` | How the projector samples and culls cells. Default `VENTICULAR`. |

- **PanOptic** — full aperture sample; no buried-cell culling / observer occlusion path used by Venticular.
- **Venticular** — uses buried-cell culling and observer occlusion (`usesBuriedCellCulling` / `usesObserverOcclusion`).

Projection detail, budgets, and global ranges: `05 - Projection Modes & Settings.md`.

## Tunnels and destinations

A tunnel binds a portal to a destination. Tunnel kinds in storage:

| `TunnelType` | Use |
|--------------|-----|
| `LOCAL` | Same-world portal-to-portal link |
| `UNIVERSAL` | Cross-server gateway link (peer server name on the tunnel) |
| `DIMENSIONAL` | Cross-world same-server link; also used by managed vanilla nether/end pairs |

Linking rules (operator-relevant):

- Non-gateway locals only list other non-gateway locals that are generic destinations (not managed dimensional portals).
- Gateways only list other gateway locals, plus remote `GATEWAY` entries when the remote registry is live.
- Mirror mode rejects destination linking and clears any existing tunnel when enabled.
- Managed dimensional portals (`DimensionalPortalKind` ≠ `NONE`) refuse manual re-linking.

## Mirror mode

Mirror mode reflects the local world through the portal and locks travel (menu shows travel locked). Enabling mirror:

- Clears any tunnel.
- Disables managed-portal mirror for dimensional kinds.
- If the portal was `RTP`, converts type to `PORTAL`.

Mirror rotation is `0` / `90` / `180` / `270` degrees. Quarter turns (90/270) only apply when the portal frame normal is vertical; non-vertical frames coerce 90→0 and 270→180.

## Travel modes

Travel is stored as two booleans (`outgoingTraversalsEnabled`, `incomingTraversalsEnabled`) and presented as:

| Mode | Outgoing | Incoming |
|------|----------|----------|
| `BOTH` | yes | yes |
| `OUTBOUND` | yes | no |
| `INBOUND` | no | yes |
| `LOCKED` | no | no |

Default for a new portal is both directions enabled (`BOTH`). Mirror mode and managed dimensional kinds override or freeze this control in the menu. Vanilla-managed nether/end portals keep fixed travel rules (see `03 - Building Portals.md` and vanilla replace).

## Local vs remote portals

| Kind | Storage / identity | Destination use |
|------|--------------------|-----------------|
| Local | This server’s portal files and runtime registry | Link target for same type (gateway vs non-gateway) |
| Remote | Replicated gateway metadata from a peer | Appears in gateway destination menus; traversal is `CROSS_SERVER` |

A portal is a gateway when `type == GATEWAY`. Remote entries are only gateway-typed.

## Dimensional doors vs frame portals

| | Frame portals | Dimensional doors |
|--|---------------|-------------------|
| Construction | Wand box or coplanar runes; menus | Crafted door/trapdoor items |
| Surface | Block aperture with optional surface skin | Vanilla door/trapdoor threshold while OpenState matches |
| Menus | Full portal home/settings/type menus | Compact access + OpenState UI on sneak empty-hand |
| Config gate | Always available | `dimensionalDoorsEnabled` (default true) |

Doors do not become frame `PortalType` entries. Details: `07 - Dimensional Doors.md`.

## Pocket dimensions (summary)

Personal and public dimensional doors resolve into a shared pocket void dimension with a return door. Layout, rescue, and retention are covered in `08 - Pocket Dimensions.md`. Pair doors link two overworld endpoints without a pocket.

## Traversal kinds (API)

Public `TraversalKind` values for cost providers and events (`21 - API - Traversal Cost & Events.md`):

| Kind | Meaning |
|------|---------|
| `LOCAL` | Same-server frame portal traversal |
| `CROSS_SERVER` | Gateway handoff to another server |
| `RANDOM_TELEPORT` | RTP portal trip |
| `DIMENSIONAL_DOOR` | Dimensional door / pocket transit |

When `traversalApiEnabled` is false in main config, third-party cost providers and traversal events are not invoked.

## Access policy (frame portals)

Per-portal permission node: `wormholes.portal.<sanitizedName>` (see `04 - Portal Types Menus & Settings.md`).

| `PortalPermissionMode` | Rule (non-op players) |
|------------------------|------------------------|
| `BLACKLIST` (default) | Players **with** the node are blocked |
| `WHITELIST` | Players need the node to use the portal |

Operators (`isOp`) always pass the portal permission check. Menu management (open settings, destroy, skin) requires portal owner UUID match or op / `wormholes.admin`.
