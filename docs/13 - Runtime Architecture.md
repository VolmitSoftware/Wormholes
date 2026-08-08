# Runtime Architecture

Wormholes boots as a Paper/Folia Java plugin (`art.arcane.wormholes.Wormholes`), loads schema-2 TOML settings, then constructs managers for portals, projection, RTP, dimensional doors, and optional cross-server networking. This document describes enable order, storage layout, Folia scheduling, hot reload, and soft dependency load order. Operator procedures live in `14 - Operator Runbooks & Smoke Tests.md`; config keys in `01 - Installation & Configuration.md`.

## Boot and enable order

### Constructor / onLoad

1. SlimJar dependency bootstrap in the plugin constructor.
2. `onLoad`: set `INSTANCE`/`instance`; PacketEvents `load()`.

### onEnable (success path)

Order from `Wormholes.onEnable` (then network bootstrap):

1. `resetForEnable` — clear stale listeners/integration/placeholders; reset drain flags.
2. Spigot pocket datapack prepare (`doors.prepareSpigotPocketDatapack`) when not Paper.
3. Preload JSON persistence classes.
4. `WormholesSettings.loadAll(dataFolder)` → `Settings.refresh`.
5. `VaultEconomy`, `WormholesLocalization`, localization reload from settings.
6. Install scheduler bridge (`SchedulerRuntime` / FoliaScheduler), chunk lease registry, `ChunkSendRateTuner`.
7. PacketEvents `init()`; audience and HUD services start.
8. Core managers constructed:
   - `BlockManager`
   - `EffectManager`
   - `ConstructionManager`
   - `WandSelectionManager`
   - `VanillaTravelCostCapture`
   - `PortalManager`
   - `TraversableManager`
   - `ProjectionManager` (with PacketEvents projection chunk tracker)
   - `ProjectionWorldChangeTracker`
   - `ArrivalWarmer`
   - `BukkitRtpRuntime` via `BukkitRtpEnvironment`; wired into projection as RTP projection provider
9. `doors.applySetting(settings)` — starts dimensional doors / pocket world when `dimensionalDoorsEnabled` and datapack ready.
10. Register listeners: block, effect, construction, wand, vanilla travel cost, portal skins, portal manager, traversable, projection, projection change, vanilla portal replacer, chat input.
11. Repeating tasks: RTP attendance sweep (20t), arrival warmer sweep (40t), vanilla dimensional-frame validation (40t).
12. `network.bootstrap(settings)` — remote registry, `NetworkManager`, import/export, portal sync, traversal service, remote view cache, view subscriptions, view server, message/peer sinks, transfer channel, network start.
13. `WormholesCommandService.register()`.
14. `WormholesIntegrationService.register()`.
15. PlaceholderAPI expansion registration when present.
16. `TraversalCostGateway` from traversal API settings.
17. Hotload manager start (`HotloadManager` watching `config/wormholes.toml`).
18. Diagnostics start; network capture runtime start.
19. Splash screen print; on failure, full tear-down and self-disable.

### Disable / pre-unload

`tearDownBeforeDrain` order: unregister integration and placeholders; shut traversal cost gateway; door manager then pocket world; RTP close; projection shutdown; view server; arrival warmer; chunk leases; effects; cancel plugin/Folia tasks; drain remaining static state. BileTools `onPreUnload` uses the same tear-down path.

## Storage layout (`plugins/Wormholes/`)

| Path | Contents |
|------|----------|
| `config/wormholes.toml` | Consolidated settings schema **2**: `schema`, `quality`, `[main]`, `[network]`, `[projection]`, `[render]`. Written canonically on load/save. |
| `portals/` | Local portal JSON files in a nested UUID layout (`portals/<segment>/<segment>/<uuid>.json`). |
| `doors/` | Dimensional door store (`doors/state.json` and related door repository data). |
| `languages/` | Optional per-locale TOML overrides (`<locale>.toml`). Bundled locales ship in the jar; English is code-catalog owned. |
| `routes/` | Cross-server route data for imported peers/portals. |
| `trust/` | Peer trust keys (`PeerTrustStore`). |
| `identity/` | Local network identity material (reset deletes this tree). |
| `dict/` | Persisted Zstd dictionaries and training state for the sideband transport. |
| `uds/` | Default Unix-domain socket location when UDS transport is enabled and no custom directory is set. |
| `wormholes-stats.txt` | Periodically overwritten operator snapshot unless `[network.stats] output-file` overrides it. |

Full plugin reset (`/wh admin deleteeverything`) deletes `config`, `identity`, `routes`, `trust`, `portals`, and `doors`, then rewrites a fresh door snapshot (preserving retired pocket slot counter) and reloads defaults. It refuses while players are inside or mid-transit in a pocket dimension.

## Folia and scheduling

- Plugin declares `folia-supported: true` (Paper plugin metadata).
- World/entity work uses VolmLib `FoliaScheduler` region/entity/global runners, not raw Bukkit async world mutation.
- **Projection:** global tick schedules observer frames with `FoliaScheduler.runEntity` on each observer so packet and claim work run on the observer’s entity thread. On Folia, world sampling uses `RegionSnapshotWorldViewProvider`; non-Folia uses live views.
- Portal RTP settings apply on the portal source region when not already on that region.
- Network traversal maintenance is global-scheduled; view subscription sweep is async-repeating.
- Chunk leases and arrival warmer hold destination chunks without projecting them.

## Hot reload

| Trigger | Behavior |
|---------|----------|
| `/wh reload` | Permission `wormholes.admin.reload`. Loads `wormholes.toml`, prepares localization, applies on global scheduler next tick. |
| File hotload | `HotloadManager` watches `config/wormholes.toml` and applies the same path after the file is stable. |

`applyReloadedState` refreshes `Settings`, syncs debug telemetry, notifies `BlockManager` language, re-applies dimensional-doors enable/disable, notifies `ProjectionManager` / `ViewServer` of projection setting changes, invalidates command cache, applies network config and replication/capture settings, restarts stats snapshot writer. Invalid language reloads keep the previous localization snapshot. Invalid config parse keeps previous live settings (load throws; hotload completion fails without applying).

## Soft depends and load order

`plugin.yml` softdepend: **PlaceholderAPI**, **Iris**, **Vault**.

Paper plugin dependencies (optional, `load: BEFORE`, join-classpath):

| Plugin | Role |
|--------|------|
| PlaceholderAPI | `%wormholes_…%` expansion (`WormholesPlaceholders`). |
| Iris | Terrain/probe integration for RTP and worldgen-aware features where present. |
| Vault | Economy for travel costs via `VaultEconomy`. |

Wormholes load: paper metadata `load: STARTUP` (legacy `plugin.yml` uses `POSTWORLD`). Soft depends are not required; missing plugins skip their integrations. See `15 - Integrations.md`.

## Major runtime components

| Component | Role |
|-----------|------|
| `PortalManager` / registry storage | Load, save, tick attendance for local portals. |
| `ProjectionManager` | Observer interest, budgets, freeze/flush, projectors. |
| `BukkitRtpRuntime` | RTP search, leases, projection views, attendance. |
| `DimensionalDoorManager` / `PocketWorldService` | Survival doors and pocket dimension. |
| `NetworkManager` + view/replication stack | Cross-server codes, peers, remote views, handoff. |
| `ArrivalWarmer` | Destination chunk prewarm on projection interest. |
| `TraversalCostGateway` | Public traversal pricing API. |

## Cross-references

- Installation and TOML: `01 - Installation & Configuration.md`
- Projection settings: `05 - Projection Modes & Settings.md`
- RTP: `06 - Random Teleport Portals.md`
- Networking: `10 - Cross-Server Networking.md`
- Operator checklists: `14 - Operator Runbooks & Smoke Tests.md`
