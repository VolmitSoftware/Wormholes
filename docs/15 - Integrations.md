# Integrations

Wormholes soft-depends on PlaceholderAPI, Iris, and Vault, and detects WorldGuard reflectively for RTP destination admission. PacketEvents and bStats are internal runtime dependencies, not separate server plugins. React and other monitors read Wormholes through VolmLib `IntegrationServiceContract` without a hard dependency either way.

Public third-party surfaces are summarized in `20 - API - Getting Started.md`. Operator PlaceholderAPI keys are in `12 - PlaceholderAPI.md`.

## Soft dependencies

Declared in `plugin.yml` as `softdepend: [ PlaceholderAPI, Iris, Vault ]` and in `paper-plugin.yml` as optional server dependencies with `load: BEFORE`, `required: false`, `join-classpath: true`.

| Plugin | Role when present | When absent |
|--------|-------------------|-------------|
| PlaceholderAPI | Registers `%wormholes_…%` expansion | No placeholders |
| Vault (+ economy provider) | Portal menu travel cost type **Vault Economy** | Vault cost mode unavailable; free/item costs still work |
| Iris | RTP candidate safety probe for fluid surface | Probe returns null; RTP uses non-Iris checks only |

None of these are required to enable Wormholes.

## WorldGuard

WorldGuard is not declared as a soft dependency; the RTP environment looks it up by plugin name and invokes its API reflectively. For each player's prepared RTP destination, WorldGuard bypass allows access and `Flags.ENTRY` decides normal access. Missing WorldGuard allows the destination; installed-but-disabled or incompatible reflective paths surface as an RTP integration failure. See `06 - Random Teleport Portals.md`.

## Vault travel costs

Portals can require a travel cost of type **vanilla item** or **Vault economy** (per-portal menu; free is the default with no cost set). Vault costs use VolmLib `VaultEconomy` constructed at Wormholes enable (`Wormholes.vaultEconomy`).

- Amount is a positive `BigDecimal`, max `1000000000000`, scale capped at 8.
- Status: `AVAILABLE` if the economy is up and the player can afford; `INSUFFICIENT` if not; `UNAVAILABLE` if Vault/economy is missing; `FAILED` on transaction failure.
- Reserve withdraws with reason `Wormholes portal travel for <uuid>`; commit finalizes the charge; refund reverses it if traversal aborts after reserve.
- Messages cover insufficient funds, Vault unavailable, and failed transactions. Selecting Vault mode in the menu without Vault + economy is rejected with a notice.

Vault costs are the built-in per-portal price path. Third-party plugins that price or veto travel should use `TraversalCostProvider` (`21 - API - Traversal Cost & Events.md`); that path is independent of the portal menu cost types.

## Iris

Soft-depend only. RTP candidate loading calls `IrisTerrainProbe` reflectively (`art.arcane.iris.core.tools.IrisToolbelt`) when Iris is enabled. If the world is under Iris fluid at the candidate column, the candidate is rejected. If Iris is missing, disabled, or the reflective path fails, the probe returns null and does not block non-Iris worlds.

No Iris world-gen or pack APIs are exposed to third parties through Wormholes.

## PlaceholderAPI

See `12 - PlaceholderAPI.md` for keys, selection, and formats; `22 - API - PlaceholderAPI.md` for integrator notes. Expansion identifier: `wormholes`.

## React / IntegrationServiceContract

Wormholes registers VolmLib `art.arcane.volmlib.integration.IntegrationServiceContract` at `ServicePriority.Normal` with `pluginId()` `wormholes`. Typed consumers must share the registered VolmLib class identity; React can also adapt equivalent registrations reflectively across plugin classloaders. Full metric keys, acquisition rules, unavailable reasons, and protocol details: `23 - API - Metrics & Integration Contract.md`.

No direct React API dependency exists inside Wormholes.

## PacketEvents (internal, shaded)

PacketEvents is relocated into the shaded plugin jar (`com.github.retrooper.packetevents` / `io.github.retrooper.packetevents` → plugin-internal packages). Operators do not install PacketEvents separately.

Used for:

- Projection rendering and client chunk tracking
- Entity spoof / identity packets for through-portal views
- **TransferGate**: on handshake receive, when network is enabled and `autoAcceptTransfers` is true, rewrites client `TRANSFER` intention to `LOGIN` so Paper/Folia accepts cross-server transfer handoffs
- Network status-bridge packet listeners when networking is active

There is no public PacketEvents integration surface for third-party plugins.

## bStats

Wormholes starts its relocated bStats client with plugin ID **33193**. The charts are `total_portals`, `portals_by_type`, `cross_server`, `wire_compression`, and `connected_peers`; callbacks read volatile values or immutable snapshots and skip a chart cycle when its manager is unavailable. Standard bStats collection can be disabled through the server-wide bStats configuration.

## What has no soft-depend integration

Projection internals, portal CRUD, wire protocol, RTP destination selection, and dimensional-door pocket APIs are not exposed. See "What has no API" in `20 - API - Getting Started.md`.
