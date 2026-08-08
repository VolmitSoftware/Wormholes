# Pocket Dimensions

Personal and Public dimensional doors allocate persistent rooms in a single shared void world. Pair doors never use pockets; they link two placed endpoints. Return doors are structure-built exits for pocket rooms. See `07 - Dimensional Doors.md` for door kinds, access, and transit eligibility.

## World

| Property | Value |
|----------|--------|
| World key | `wormholes:pockets` |
| Dimension type | `wormholes:fullbright_pockets` |
| Generator | Flat void (`minecraft:the_void` biome, air layer) |
| Ambient light | Fullbright (`ambient_light` 1.0); no potion effects required |
| Time | Fixed; skybox none |
| Height | `min_y` −64, height 384 |
| Beds / respawn anchors | Beds never; respawn anchors disabled |
| Tick speed | Vanilla |

The dimension is installed from the plugin’s bundled datapack (`wormholes-pockets.zip` under the level `datapacks` on Spigot-class installs; Paper bootstrap stages the pack before registries load). Installing or updating the pack requires a **full server restart**. If `wormholes:pockets` is missing after start, PERSONAL/PUBLIC entry cannot provision or enter pockets.

## Allocation

`PocketAllocator` places rooms on a deterministic square spiral.

| Constant | Value |
|----------|-------|
| Stride between pocket centers | 8,192 blocks |
| Center Y | 128 |
| Chunk center offset | +8 on X/Z so the seed sits inside the first room chunk |
| Slot reuse | Never; slots are monotonic |

Each allocation stores a `PocketSpace`: stable `spaceId` (name-UUID from binding), binding, slot index, and center coordinates. Restoring from disk reloads existing spaces; `nextSlot` must stay greater than every allocated slot.

## Layout (`PocketLayout`)

| Constant | Value | Meaning |
|----------|-------|---------|
| `CHUNK_SIZE` | 16 | |
| `ROOM_CHUNKS` | 2 | Room spans 2×2 chunks |
| `ROOM_SIZE` | 32 | Outer shell edge length |
| Interior | 30³ usable | Shell faces are protected; interior is not shell |
| Shell material | Smooth stone | Outer faces of the 32³ volume |
| `RETURN_DOOR_CENTER_OFFSET` | 15 | `(ROOM_SIZE / 2) - 1` |

Bounds are chunk-aligned from the pocket center. Shell blocks are protected (`isProtected` = shell only). Interior blocks are player-editable. Provisioning fills the shell once; later provision calls leave player interior space and always repair the return door and its support.

## Return door

| Property | Value |
|----------|--------|
| Material | Crimson door (`RETURN_DOOR_MATERIAL`) |
| Kind / form | `RETURN` / `DOOR` only |
| Wall | +Z face of the shell (`maxZ`) |
| Position | Centered on the wall at floor level: lower block at `minY + 1`, facing south, left hinge, starts closed |
| Identity | Deterministic from pocket `spaceId` (`wormholes:pocket-return-door:v1:…`) |
| Entry landing | Just inside the door (`x + 0.5`, door Y, `z - 0.5`, yaw/pitch 0) |
| Craft / place / break | Not craftable; player place cancelled; break cancelled (anchored) |
| Access | Never gated |

The return route uses the traveler’s saved `ReturnTicket` (source endpoint, world, position, look). If the ticket world is missing, is itself a pocket world, or the point is obstructed, rescue falls back to a safe location near a loaded non-pocket world spawn.

## Escape and lethal damage

**Shell leave (escape):** if a player moves outside the pocket layout with lateral margin 1 block (vertical margin 0), they are treated as escaped: glitch effect, then ejection via the same return-ticket path as rescue.

**Lethal damage in the pocket world:** `EntityDamageEvent` on players in `wormholes:pockets` is evaluated by `PocketRescuePolicy`. Damage that would kill cancels, health is set to one heart (2.0, or max health if lower), fall/fire cleared, i-frames extended, and ejection starts through the return ticket (with the same fallbacks). Concurrent rescue holds further lethal hits at one heart until ejection finishes.

Spectators are not escape-ejected by the move path. Objects never receive return tickets.

## Bindings

| Door kind | Binding | Key | Sharing |
|-----------|---------|-----|---------|
| `PERSONAL` | `PocketBindingKind.PERSONAL` | Traveler UUID | Every personal door sends that player to the same pocket |
| `PUBLIC` | Internal `PocketBindingKind.IRON` | Door `itemId` | Every traveler using that item shares one pocket |
| `PAIR` | None | — | No pocket; destination is the mate endpoint |
| `RETURN` | Pocket of `spaceId` on identity | Traveler UUID on ticket | Exit only |

Pocket `spaceId` is derived as `nameUUID("wormholes:pocket:" + kind + ":" + bindingId)`. The stored `IRON` binding name is an internal persistence identifier for Public doors; the player-facing door kind remains `PUBLIC`.

## Related

- Door kinds, OpenState, access, recipes, config: `07 - Dimensional Doors.md`
- Install and TOML: `01 - Installation & Configuration.md`
- Runtime boot and managers: `13 - Runtime Architecture.md`
