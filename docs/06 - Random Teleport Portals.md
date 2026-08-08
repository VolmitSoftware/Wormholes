# Random Teleport Portals

An RTP portal is a frame portal whose type is `RTP`. Instead of a fixed linked destination, it samples a safe landing in a configured world and radius band, then projects and teleports travelers to that destination under rotation, lease, and allocation rules. Configuration lives on the portal JSON under `rtp` (`RtpSettings`); the in-game path is Type → RTP, then **Random Destination** on the portal home menu.

## Switch type and open the editor

1. Open the portal menu on a constructed portal.
2. Set type to **RTP** (requires management access for that portal and `wormholes.portals.portal`).
3. Open **Random Destination** to run `RtpPortalEditor`.

Editor pages: Overview, Destination, Landing, Routing, Effects, numeric entry, and manual-action confirmation. Overview links to Destination / Landing / Routing / Effects, **Reset defaults**, and back to the portal menu.

## Settings apply immediately

Editor changes apply immediately on the portal's source region; there is no staged draft or Apply Changes batch. Successful mutations refresh the menu with an applied notification, while a revision mismatch reloads the live settings. Manual rerolls, private-pool rebuilds, and Reset defaults require their confirmation actions.

## Default RtpSettings

Built by `RtpSettings.builder(world)` / `defaults(world)`:

| Field | Default |
|-------|---------|
| Target world | Source portal world |
| Center mode | `PORTAL_RELATIVE` |
| Custom center | none |
| Minimum radius | **512** |
| Maximum radius | **4096** (must be greater than minimum) |
| Vertical mode | `SURFACE` |
| Lower Y | `world.minHeight + 1` |
| Upper Y | `world.maxHeight - 2` |
| Preferred Y | `clamp(seaLevel + 1, lowerY, upperY)` |
| Allocation | `SHARED` |
| Rotation | `ON_TRAVERSAL` |
| Cycle duration | **300000** ms (300 s); clamp 15 s–86400 s |
| Lease idle | **30000** ms (30 s); clamp 5 s–600 s |
| Private release | **15000** ms (15 s); clamp 5 s–300 s |
| Rim enabled | **true** |
| Sound enabled | **true** |

## Enums

### Center — `RtpCenterMode`

| Value | Meaning |
|-------|---------|
| `PORTAL_RELATIVE` | Search ring centered on the portal’s source center. |
| `CUSTOM` | Search ring centered on stored `customCenterX` / `customCenterZ` (both required when CUSTOM). |

### Vertical — `RtpVerticalMode`

| Value | Meaning |
|-------|---------|
| `SURFACE` | Land on validated terrain surface (see safety). |
| `PREFERRED_AVERAGE` | Use preferred Y within lower/upper bounds during sampling. |

### Allocation — `RtpAllocationMode`

| Value | Meaning |
|-------|---------|
| `SHARED` | One shared destination for the portal’s viewers/travelers. |
| `PER_PLAYER` | Per-player destinations and private reservation pool behavior. |

### Shared rotation — `RtpRotationMode`

These choices apply while allocation is `SHARED`. `PER_PLAYER` always rotates each private reservation on `cycleDurationMillis`; the editor labels that field **Private rotation** and hides the shared rotation choices.

| Value | Meaning |
|-------|---------|
| `STATIC` | Destination stays until manually changed or settings force a rebuild. Explicit Static remains static. |
| `TIMED` | Reroll on cycle timer (`cycleDurationMillis`). |
| `ON_TRAVERSAL` | Default. Reroll after successful trip (shared path). |

## Editor surfaces

| Page | Controls |
|------|----------|
| Destination | Target world list (paged), center mode PORTAL_RELATIVE/CUSTOM, custom X/Z when custom, min/max radius, reset center/target to portal-relative source world |
| Landing | SURFACE / PREFERRED_AVERAGE, surface policy info, lower/upper/preferred Y |
| Routing | SHARED / PER_PLAYER; shared STATIC / TIMED / ON_TRAVERSAL choices; shared timed or private rotation interval; lease idle; private release; manual reroll/pool rebuild |
| Effects | Rim on/off, portal-specific sound on/off |

Numeric fields use decrease/increase steps and optional typed entry. Radii, Y, timings, and coordinates are clamped by editor and `RtpSettings` limits (radius max 30_000_000; cycle/lease/reservation bounds as above).

## Surface safety rules

`RtpSafetyValidator` rejects candidates that fail any of the following:

- Destination world key mismatch with the snapshot world.
- Zero-size entity envelopes, attached entities, vehicles or passengers, and envelopes larger than 8 blocks on any axis.
- Feet/body outside world height bounds or world border.
- Nether roof band: envelope top within **5** blocks of the nether logical ceiling.
- Missing, invalid, or too many region/chunk snapshots (max **4** chunks, **4** regions for the envelope).
- Support block missing, liquid (water/lava/bubble column), a built-in hazard, or tree-part support/body when `surfaceMode` is true.
- Body collision with solid collision boxes in the feet-to-top envelope.
- Incomplete support coverage under the footprint (End → `END_VOID`; otherwise `UNSUPPORTED`).

Built-in hazard materials include fire, soul fire, powder snow, cactus, magma, sweet berry bush, wither rose, pointed dripstone, cobweb, nether portal, end portal, end gateway, and lit campfire/soul campfire. Surface mode rejects tree-structure landings; clear ground under a high canopy can still pass when support and body checks succeed. Liquids and waterlogged aquatic landings fail, and the Iris integration rejects terrain probes that Iris identifies as fluid. Travel uses the same entity envelope that passed validation.

## Sampling and retry limits

Horizontal coordinates are sampled uniformly by area within the configured annulus, rather than uniformly by radius. Pocket worlds are excluded from the target-world list. `PREFERRED_AVERAGE` probes the preferred Y first, then alternates upward and downward inside the bounds; surface mode uses a separate Nether scan that avoids the roof band.

A search campaign starts at most 32 candidates and runs for at most five seconds. A campaign that cannot publish a safe destination enters exponential retry backoff, from one second up to 30 seconds. The existing READY view remains published during refill or authorization work until a replacement can be shown safely.

During refills, rerolls, and authorization checks, the last READY projection can remain online until a replacement is published. Idle lease grace (`leaseIdleMillis`, default 30 s) reduces cold churn when observers briefly leave the view AABB.

## Runtime behavior notes

- New RTP portals with default rotation **ON_TRAVERSAL** reroll the shared destination after a successful trip.
- Per-player allocation rotates reservations on the cycle duration and uses private release timing for reservation teardown. The saved shared rotation choice remains available if allocation switches back to SHARED.
- Per-player routing keeps at most 16 prepared or assigned destinations and reserves two free spares when capacity permits.
- Rim feedback and portal-specific sounds are independently togglable; muting sounds does not disable particles.
- Rim feedback is yellow while preparing, red for closing or a two-second failure indication, green for ready static/on-traversal routes, and a green-to-yellow-to-red timer for timed routes. Per-player routes use their actual timed runtime mode for this display.
- Travelers keep look/movement orientation from the side they entered.

## WorldGuard destination access

When WorldGuard is installed and enabled, the RTP service reflectively checks the `ENTRY` flag at each prepared destination for that player before publishing or using it. WorldGuard bypass passes; an entry denial rejects the destination. Missing WorldGuard allows the destination, while an installed-but-disabled plugin or reflective integration failure is reported as an RTP integration failure instead of silently allowing it.

## PlaceholderAPI RTP states

Keys (portal-scoped; see `12 - PlaceholderAPI.md`):

| Key | Values |
|-----|--------|
| `%wormholes_rtp.state%` | `rerolling`, `warming`, `ready`, `cooldown`, `idle`; `---` when the portal is not RTP or not registered with RTP runtime |
| `%wormholes_rtp.cooldown%` | Seconds until the next destination search is allowed (two-decimal style numeric); unavailable when not RTP |

Priority in `WormholesPortalSnapshot.rtpState`:

1. not RTP / not registered → unavailable  
2. `rerolling`  
3. `warming` (searching)  
4. `ready`  
5. `cooldown` if cooldown millis remain, else `idle`

## Cross-references

- Portal types and menus: `04 - Portal Types Menus & Settings.md`
- Projection of RTP destinations: `05 - Projection Modes & Settings.md`
- Commands: `09 - Commands & Permissions.md`
- Smoke checklist: `14 - Operator Runbooks & Smoke Tests.md`
