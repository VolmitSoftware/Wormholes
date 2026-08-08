# Portal Types Menus & Settings

Each local frame portal has a home menu and nested type, orientation, destination, settings, cost, and cosmetics screens. Defaults below match a newly constructed portal (`LocalPortalSettings` constructor). Concepts: `02 - Concepts.md`. Construction: `03 - Building Portals.md`.

## Default settings

| Setting | Default | Notes |
|---------|---------|-------|
| `projectionMode` | `ON` | Toggle on home menu |
| `renderMode` | `VENTICULAR` | PanOptic / Venticular cycle |
| `mirrorMode` | `false` | Set from type menu |
| `mirrorRotation` | `0°` | 90° steps; vertical frames only for 90/270 |
| `permissionMode` | `BLACKLIST` | See Access |
| `outgoingTraversalsEnabled` | `true` | Travel mode `BOTH` |
| `incomingTraversalsEnabled` | `true` | Travel mode `BOTH` |
| Network view quality | `STANDARD` | Depth 64, heartbeat 60, entity interval 10, grace 30s |
| `networkViewLateralPad` | `48` | Clamped 0–64 (not in preset table; persists) |
| `networkViewFallbackBlock` | `minecraft:air` | Invalid chat input resets to air |
| `blackoutBackground` | `false` | |
| `blackoutColor` | `BLACK` | Concrete color enum |
| `activationRange` | `0` | `0` = use global `Settings.PROJECTION_RANGE` (default 48) |
| `ambientStyle` | `SPARKS` | `SPARKS` / `OUTLINE` / `CORNERS` / `OFF` |
| `ambientColor` | `0xB969FF` | RGB 0–0xFFFFFF |
| `surfaceSkin` | empty | No skin |
| `travelCost` | free (`null`) | Free / vanilla item / Vault |
| `settingsSyncEnabled` | `true` | Broadcast settings to linked/remote when applicable |

### Network view quality presets

Cycle order: Standard → Performance → Balanced → Cinematic → Custom → Standard.

| Preset | Depth | Heartbeat (ticks) | Entity interval (ticks) | Unsubscribe grace (s) |
|--------|-------|-------------------|-------------------------|------------------------|
| `STANDARD` | 64 | 60 | 10 | 30 |
| `PERFORMANCE` | 32 | 100 | 20 | 10 |
| `BALANCED` | 64 | 40 | 5 | 30 |
| `CINEMATIC` | 96 | 20 | 2 | 45 |
| `CUSTOM` | operator-set | operator-set | operator-set | operator-set |

Custom clamps when editing numbers: depth 1–128, heartbeat 2–600, entity interval 2–600, grace 5–600. Menu step controls use smaller UI steps (e.g. depth ±4/±16).

Selecting a non-custom preset overwrites depth/heartbeat/entity/grace to that preset. Values that match no preset resolve as `CUSTOM` on load.

### Activation range

- `0` — global projection range from config (`projection.range`, default 48 blocks).
- Positive values are clamped to **8–256** blocks.

### Settings sync

When enabled, setting changes broadcast through the portal sync service (linked locals / gateway peers as applicable). When disabled, local edits stay local. Gateway portals also emit a settings-toggle broadcast on enable/disable.

## Per-portal permission node

Node: `wormholes.portal.<sanitizedName>`.

Sanitization: lower-case portal name; keep `a-z`, `0-9`, `.`, `-`, `_`; other characters collapse to `_`; empty result becomes `unnamed`.

| Mode | Effect for non-op players |
|------|---------------------------|
| `BLACKLIST` | Holding the node **blocks** use |
| `WHITELIST` | Holding the node **allows** use |

Ops always pass. Cycle mode in Settings.

## Home menu

Opens via wand look-click or sneak empty-hand right-click on the frame (`03 - Building Portals.md`). Requires owner or admin.

| Control | Action |
|---------|--------|
| Placard | Name, type/mode, facing, destination or RTP summary |
| Destination | Local destination list; gateway pair submenu; or RTP editor when type is RTP |
| Rename | Chat name prompt |
| Projection | Cycle `ON` / `OFF` |
| Settings | Opens settings menu |
| Orientation | Facing / flip / rotate submenu |
| Type | Type and mirror submenu |
| Destroy | **Shift-left-click** to destroy the portal |

Managed dimensional portals refuse destination and type changes with managed notices.

## Destination menu

Blocked for RTP, mirror mode, and managed dimensional portals.

**Non-gateway:** lists other non-gateway local portals that are generic destinations (same world structure present). Left-click links or unlinks.

**Gateway:** lists other local gateways, then remote gateway entries (server name, coords, open/closed). Gateway home destination control opens a pair menu:

| Pair control | Action |
|--------------|--------|
| Export | Print invite/export code to chat |
| Choose destination | Open destination list (local gateways + remotes) |
| Import | Chat prompt for peer invite code |

Cross-server handoff detail: `10 - Cross-Server Networking.md`.

## Type menu

Options: `PORTAL`, `WORMHOLE`, `GATEWAY`, `RTP`, and **Mirror**.

| Choice | Effect |
|--------|--------|
| Portal / Wormhole / Gateway / RTP | Sets type and disables mirror mode if it was on |
| Mirror | Enables mirror mode (travel locked; tunnel cleared) |

RTP editor entry lives on the home destination control when type is RTP (`06 - Random Teleport Portals.md`).

Menu descriptions (localized): Portal = basic linkable; Wormhole = linkable with viewport projection; Gateway = cross-network; RTP = local random teleport; Mirror = reflect local world with travel locked. Runtime projection capability is still ON/OFF for all types (`02 - Concepts.md`).

## Settings menu

| Control | Behavior |
|---------|----------|
| Permission mode | Cycle blacklist/whitelist; shows node |
| Travel direction | Cycle BOTH / OUTBOUND / INBOUND / LOCKED (disabled under mirror / managed) |
| Stream quality | Cycle network view presets; shift-left opens advanced layout with custom numbers |
| Settings sync | Toggle on/off |
| Blackout | Left toggles background; right opens color picker (16 concrete colors) |
| Ambient particles | Left cycles style; right opens RGB/dye color menu |
| Surface skin | Menu control for skin display/clear (in-world apply in `03`) |
| Activation range | ±8 / ±32 steps; below 8 snaps to global (`0`) |
| Render mode | Cycle PanOptic / Venticular |
| Travel cost | Opens cost menu |
| Fallback block | Chat block-state string (custom quality layout / advanced) |

Custom quality expands the window to show depth, full-refresh ticks, entity interval, and view grace editors.

## Travel cost menu

| Mode | How to set | Requirement |
|------|------------|-------------|
| Free | Select free | Default |
| Vanilla item | Capture held item template; set quantity | Quantity adjustable ±1 / ±8 |
| Vault | Chat amount | Soft-depend Vault economy available |

Invalid stored travel cost loads as free and logs a warning. Third-party `TraversalCostProvider` is separate (`21 - API - Traversal Cost & Events.md`) and gated by `traversalApiEnabled`.

## Orientation menu

| Control | Effect |
|---------|--------|
| Direction | Cycle facing |
| Flip face | Invert portal face |
| Rotate CW / CCW | Rotate frame up-vector / orientation |

Affects which way travelers face and how projection maps space. Right-clicking the rotation control turns clockwise; shift-right-click turns counterclockwise.

## Cosmetics and blackout

| Ambient style | Icon material (menu) |
|---------------|----------------------|
| `SPARKS` | Firework star |
| `OUTLINE` | Blaze rod |
| `CORNERS` | End rod |
| `OFF` | Glass |

Ambient RGB controls change a channel by 8 per click or 32 while shifting; the color picker also provides 16 dye presets. Left-clicking the surface-skin control clears the skin, while right-clicking opens the Glass/Clear choices.

Blackout builds a concrete display seal from the farthest valid slice of the projected volume, preventing the client from seeing beyond the sampled view. If that display cannot be sent safely, projection continues without the seal. Opaque surface skins block projection entirely (`blocksProjection`).

## Behavior notes

- Destroy requires **shift-left-click** on the destroy element; normal left-click does not delete.
- Wand box construction always starts as type `PORTAL` regardless of intent; change type in the type menu or build with the matching rune.
- Gateway runes have **no craft recipe**; use `/wormholes wand rune=gateway`.
- All frame types can project; use projection mode OFF to disable the view. Construction and type changes require `wormholes.portals.portal` (PORTAL/RTP), `wormholes.portals.wormhole` (WORMHOLE), or `wormholes.gateway` (GATEWAY); traversal uses the portal's dynamic access policy instead.
- Enabling mirror while type is RTP converts the portal to `PORTAL`.
- RTP and gateway destination menus are mutually exclusive paths; RTP cannot tunnel-link.
- Per-portal permission blacklist is the default: the node denies, it does not grant.
