# Building Portals

Frame portals are coplanar apertures built with the Portal Wand (box select) or by placing and activating matching runes. Construction always produces a flat structure; a non-flat rune set is rejected before its blocks are consumed. Surface skins, menu access, and optional vanilla nether/end replacement attach after the portal exists.

## Tools and recipes

| Item | Material | Craft recipe registered? |
|------|----------|--------------------------|
| Portal Wand | Enchanted blaze rod | Yes — `portal_wand` |
| Portal rune | Enchanted prismarine | Yes — `portal_rune` (yields 4) |
| Wormhole rune | Enchanted dark prismarine | Yes — `wormhole_rune` (yields 4) |
| Gateway rune | Enchanted black stained glass | No — admin supply only |

### Craft shapes

**Portal wand** (`portal_wand`):

```
d d
 r
 d
```

`d` = glowstone dust, `r` = blaze rod.

**Portal rune** (`portal_rune`, 4):

```
p b p
b d b
p b p
```

`p` = ender pearl, `b` = prismarine crystals, `d` = blaze powder.

**Wormhole rune** (`wormhole_rune`, 4):

```
p b p
b d b
p b p
```

`p` = eye of ender, `b` = prismarine shard, `d` = nether star.

Admin give remains available for all runes.

### Admin supply

Permission: `wormholes.admin.items`.

| Command | Result |
|---------|--------|
| `/wormholes wand` | One Portal Wand + one wormhole rune |
| `/wormholes wand rune=portal count=N` | Portal runes (N clamped 1–64) |
| `/wormholes wand rune=wormhole count=N` | Wormhole runes |
| `/wormholes wand rune=gateway count=N` | Gateway runes |

Aliases: `/wh`, `/wormhole`. Full command list: `09 - Commands & Permissions.md`.

## Wand box construction

1. Hold the Portal Wand in the main hand.
2. **Left-click** a block for corner A; **right-click** a block for corner B (or the reverse order).
3. Selection must be one cell thick on one axis (flat wall/floor/ceiling plane). Max **4096** cells.
4. With a valid complete selection, **left-click** the selection (block inside the box or aim at the selection pane within 64 blocks) to open the portal.
5. Construction always creates `PortalType.PORTAL` owned by the player UUID. Change type later in the type menu (`04 - Portal Types Menus & Settings.md`).

Selection UI: light-blue pane while valid; red when invalid (not flat or too large). Changing world, dropping/swapping off the wand, or changing hotbar away from the wand clears the selection.

Wand interactions aimed at an existing portal open that portal’s menu instead of editing selection (see Menu access).

## Rune construction

1. Place portal, wormhole, or gateway runes. Any 6-face-connected shape of the **same** rune type works (rectangles, lines, L-shapes, single blocks, etc.); diagonal contact alone does not connect sets.
2. All runes of the connected set must be **coplanar** (one flat axis-aligned surface).
3. Hold the Portal Wand and **left-click** any rune block in the set.
4. Connected same-type runes are reserved, consumed to air, and opened as a portal of that rune’s type, owned by the clicking player.

| Rune type | Resulting `PortalType` |
|-----------|------------------------|
| Portal | `PORTAL` |
| Wormhole | `WORMHOLE` |
| Gateway | `GATEWAY` |

RTP runes are not a construction product: left-clicking a tracked RTP rune with the wand shows that RTP rune construction is unsupported.

Non-coplanar connected sets are rejected before consumption and the placed, tracked runes remain in the world. If construction fails after a valid set has been reserved and consumed, rollback restores or refunds the matching runes and releases their reservations. Breaking a placed rune in survival returns the matching rune item (except RTP); breaking with the wand is cancelled.

## Surface skin

While looking at a portal, operators and owners may:

- Main hand holds a **non-tool** item (not the wand or a portal/wormhole/gateway rune).
- **Right-click** air or block with that hand.

| Held item | Skin applied |
|-----------|--------------|
| Water bucket | `minecraft:water` |
| Lava bucket | `minecraft:lava` |
| Any block material | That block’s `BlockData` string |

Empty hand does not apply a skin. Opaque skins **block projection** through the surface; transparent/non-occluding skins (glass, ice, water, slime, honey, barrier, etc.) do not. Clear skins via the settings cosmetics control. See `04 - Portal Types Menus & Settings.md`.

## Menu access

Only the portal owner, ops, or players with `wormholes.admin` may open management menus.

| Gesture | Action |
|---------|--------|
| Portal Wand, looking at portal, left or right click | Open portal home menu |
| Sneak + empty main hand + right-click a block that is part of or adjoins the portal structure | Open portal home menu |

Destroy: home menu **Destroy** control, **shift-left-click** (not a normal left click).

## Vanilla nether and end portals

Config: `main.replaceNetherAndEndPortals` in `config/wormholes.toml` (default `true`). Hot-reloads with other main gameplay settings.

When enabled, lighting a vanilla nether portal (or related create reasons the replacer handles) converts the nether portal cells into managed Wormholes portals with dimensional pairing. End portal windows are similarly intercepted so vanilla portal events are cancelled when covered by the Wormholes index. Managed portals use `DimensionalPortalKind` (`NETHER`, `END_SOURCE`, `END_ARRIVAL`) and lock manual destination, type, and travel edits. Nether pairs travel both ways; an End source is outbound-only, while its fixed arrival receiver is inbound-only with projection off.

Set `replaceNetherAndEndPortals = false` to leave vanilla portals alone.

## Permissions used during build

| Node | Default | Role |
|------|---------|------|
| `wormholes.portals` | true | Parent that grants both non-gateway type nodes |
| `wormholes.portals.portal` | true | Construct and type-switch to Portal/RTP |
| `wormholes.portals.wormhole` | true | Construct and type-switch to Wormhole |
| `wormholes.gateway` | op | Create/modify gateway portals |
| `wormholes.admin.items` | op | `/wormholes wand` supplies |

See `09 - Commands & Permissions.md` for the full tree.

## Quick path

1. Admin: `/wormholes wand` (or craft wand + runes).
2. Place coplanar runes **or** box-select with the wand and left-click to open.
3. Open the menu (wand look-click or sneak empty-hand right-click on the frame).
4. Set type if needed, pick a destination (or RTP editor / gateway codes), then walk through.

Related: types and settings in `04 - Portal Types Menus & Settings.md`; projection in `05 - Projection Modes & Settings.md`.
