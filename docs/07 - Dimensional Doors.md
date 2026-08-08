# Dimensional Doors

Dimensional Doors are survival vanilla doors and trapdoors that carry a durable identity in item PDC. Placed endpoints present a portal surface when their saved OpenState matches the physical block state; travelers that meet that surface transit on the same server. See `08 - Pocket Dimensions.md` for PERSONAL/PUBLIC destinations; PAIR links two endpoints and does not allocate a pocket.

## Kinds

| Kind | Source | Destination | Notes |
|------|--------|-------------|-------|
| `PAIR` | Crafted pair kit or admin item | The other linked endpoint of the same `pairId` | Kit unpacks A/B; either endpoint may move without losing the link |
| `PERSONAL` | Crafted or admin item | Traveler’s personal pocket (keyed by traveler UUID) | One pocket per player across all personal doors |
| `PUBLIC` | Crafted or admin item | Pocket keyed by this door’s immutable `itemId` | Breaking and re-placing the same item keeps the pocket; a new craft mints a new `itemId` and a different pocket |
| `RETURN` | Pocket structure only | Traveler’s saved return ticket | Never craftable; always `DOOR` form; Crimson door material; not breakable by players |

## Forms

| Form | Physical shape | Portal plane |
|------|----------------|--------------|
| `DOOR` | Two-block hinged door | Vertical one-block-wide, two-block-high aperture at the closed-door threshold |
| `TRAPDOOR` | One-block wooden trapdoor | Horizontal 1×1 plane at the middle of the plate slab (top or bottom half by placement) |

A door identity cannot become a trapdoor (or the reverse) through reskinning. Return doors are always `DOOR`.

## OpenState

Each placed endpoint stores an OpenState. New placements default to `OPEN`.

| OpenState | When the portal is live | Living traveler after transit | Contact pad |
|-----------|-------------------------|-------------------------------|-------------|
| `OPEN` (default) | Physical block is open | Source returns to closed dormant after a living traveler that claims the open cycle | No |
| `CLOSED` | Physical block is closed | Contact with the closed surface triggers transit; surface stays shut | Yes |

- Toggle OpenState from the access UI (applies to doors and trapdoors).
- Under `OPEN`, living travelers claim the door’s single armed open cycle and close the source behind them. Objects never claim that cycle, so a volley can pass while the door stays open.
- Under `CLOSED`, the shut surface is a contact pad: nothing crosses while the block stands open; contact pads never swing open for arrivals and never consume an open cycle.
- Destination behavior: a hinged door with OpenState `OPEN` may be auto-opened by the server for an arriving living traveler and later closed only if the server opened it; a player-opened door is left alone. Trapdoor destinations are not auto-swung for arrival. OpenState `CLOSED` destinations stay shut.
- Trapdoor through-mapping is straight (drop in the top of one → exit under the far plate still falling; climb up through the bottom → land on top of the far plate). Hinged doors mirror onto the matching face (front-to-front / back-to-back).

## Access UI and rules

Access is **per door** (one `DoorAccessRecord` per door `itemId`), with **per-player** entries inside that record. It is not a global per-player grant across doors.

**Open the menu:** sneak, empty main hand, right-click a placed Pair/Personal/Public door block. Owner, ops, and `wormholes.admin` can manage. Return doors have no access menu and are never gated.

**Window contents:** header placard, centered OpenState control, add-player control; then one stained-glass pane per listed player. Height grows one row per nine listed players (viewport capped at six rows).

| Control | Action |
|---------|--------|
| OpenState (lime/gray dye) | Left-click toggles `OPEN` ↔ `CLOSED` |
| Add player | Chat name prompt; new entries start `NEUTRAL` |
| Listed pane left-click | `WHITELIST` (green) |
| Listed pane right-click | `BLACKLIST` (red) |
| Listed pane middle-click or shift-left-click | Remove from list |
| Black pane (`NEUTRAL`) | No transit effect; entry is still listed |

**Authorization (`DoorAccessPolicy`):**

| Actor | Result |
|-------|--------|
| Blacklisted player | Always refused |
| Any whitelist present | Only whitelisted players (plus always-pass actors) may use |
| No whitelist | Anyone not blacklisted may use |
| Door owner | Always passes |
| `wormholes.doors.bypass` (included under `wormholes.admin`) | Always passes |
| Ops / `wormholes.admin` | May manage access; ops also treated as administrators for manage |
| Return door | Never gated |
| Unowned object (e.g. dispenser projectile) | Ungated for access lists |

**Ownership for non-player travelers:** projectiles use the player shooter; dropped items use the thrower UUID if that player is online; otherwise the traveler is ungated for access. Objects are never issued a return ticket.

Normal vanilla door interaction and any server protection plugin still apply outside portal transit.

## Portal surface and block protection

When a placed endpoint's OpenState matches the physical door or trapdoor state, Wormholes shows an animated client-visible surface made from crying obsidian and Nether-portal block displays. Nearby viewers receive the animated overlay, particles follow the global particle setting, and dimensional-door sounds follow the global portal sound volume. The display is removed when the endpoint is no longer live or the feature drains.

Registered dimensional doors, their hinged-door support blocks, and pocket core blocks are protected from fire, piston movement, entity block changes, and explosion block removal. Normal player breaking still follows the identity rules below; return doors remain unbreakable.

## Transit eligibility

Travelers are classified as `LIVING` (players, mobs, vehicles) or `OBJECT` (projectiles, dropped items, experience orbs). Objects are swept each tick around live doors when they do not fire move events. Aperture fit for non-players is at most width 1.0 and height 2.0. Travelers inside a vehicle, with passengers, or leashed cannot enter.

| Kind | Players | Mobs / empty vehicles (fit aperture) | Objects |
|------|---------|--------------------------------------|---------|
| `PAIR` | Yes | Yes (not bosses / complex living entities) | Yes |
| `PERSONAL` | Yes | No | No |
| `PUBLIC` | Yes | No | Yes |
| `RETURN` | Yes | No | No |

Pair and Public support object travel; Personal and Return stay player-only. Pair loads unloaded destination chunks before transit. Same-server cross-dimension travel is supported for eligible kinds.

## Recipes

All product recipes require the exact **Wormhole Rune** item (`R`). Shift-crafting identity products is blocked so one craft cannot mint bulk identities. Identity is minted on the actual craft click.

```text
Entangled pair       Personal door       Public dimension door
E D E                 _ R _               R D R
O R O                 C D E               _ E _
_ D _                                     _ L _
```

| Symbol | Meaning |
|--------|---------|
| `R` | Exact Wormhole Rune |
| `E` | Ender Eye (pair recipes); Ender Chest (personal/public recipes) |
| `D` | Any vanilla door for door recipes; a hand-openable wooden trapdoor for trapdoor recipes |
| `O` | Obsidian |
| `C` | Recovery Compass |
| `L` | Lodestone |

Trapdoor products use the same shapes with trapdoor `D`. Defaults:

| Product | Default material |
|---------|------------------|
| Pair door / trapdoor kit endpoints | Oak door / oak trapdoor |
| Personal door / trapdoor | Dark oak door / dark oak trapdoor |
| Public door / trapdoor | Pale oak door / pale oak trapdoor |
| Return (structure) | Crimson door |

**Ingredient vs product:** hinged-door recipes accept any vanilla door, including iron and copper; trapdoor recipes accept only hand-openable wooden trapdoors. The minted product always uses the default wooden material above, not the ingredient material, so every dimensional product remains hand-openable. Return doors have no recipe.

**Pair kit:** craft yields a bundle. Right-click air or block unpacks linked A/B items and registers the pair; creative and survival consume the kit on unpack.

## Reskin

Shapeless craft: one dimensional door/trapdoor item + one ordinary hand-openable door/trapdoor of the **same form**.

- Result keeps the same identity (pair link, personal mapping, or public `itemId`) and adopts the ordinary item’s material.
- Iron and copper doors/trapdoors cannot be skins; dimensional products must stay player-operable (`Tag.WOODEN_DOORS` / `Tag.WOODEN_TRAPDOORS`).
- Door cannot reskin into trapdoor or reverse.
- Same material as current skin is invalid.
- Shift-craft of skin recipes is blocked.

**Legacy:** placed Public doors that are still iron blocks are converted at runtime to the Pale Oak Public Door default without changing destination identity. Trapdoors never carried the iron skin era.

## Admin grant

Requires player sender and `wormholes.admin.items` (or full admin). Feature must be enabled and the door manager running.

```text
/wormholes door type=<pair|personal|public|pair_trapdoor|personal_trapdoor|public_trapdoor>
```

Default `type` is `pair`. Overflow drops at the player’s feet.

## Creative and break identity

| Action | Behavior |
|--------|----------|
| Place in Creative | Held dimensional door item is consumed (same as survival identity consumption for kits/doors) |
| Break Pair / Personal / Public | Always drops that exact identity item (including Creative); block vanilla drops are suppressed |
| Break material | Uses live block material if still player-operable for that form; otherwise the kind’s default material |
| Return door break | Cancelled; exit is anchored |

## Config and lifecycle

| Setting | Location | Effect |
|---------|----------|--------|
| `dimensional-doors-enabled` | `[main]` in `plugins/Wormholes/config/wormholes.toml` (field default `true`) | Live enable/disable of the full dimensional-doors feature |

When set `false` while running: new entries stop; active travelers and pocket occupants may finish through return routes; recipes, protection, and portal displays shut down after drain; existing blocks behave as ordinary doors; saved door and pocket identities remain for re-enable.

**Pocket datapack / world:** first install or update of the bundled `wormholes:pockets` dimension requires a **full server restart** so registries load (`/reload` is insufficient). Until restart, doors that need pockets stay dormant. See `08 - Pocket Dimensions.md` and `01 - Installation & Configuration.md`.

## Related

- Pocket layout, rescue, and bindings: `08 - Pocket Dimensions.md`
- Commands and permission nodes: `09 - Commands & Permissions.md`
- Concepts overview: `02 - Concepts.md`
