# Maintainer Component & Build Reference

Wormholes is split into domain packages rather than one public class per feature. This reference maps every production package to the behavior it owns, identifies the runtime entry points, and records the verification and packaging tasks. Only `art.arcane.wormholes.api.traversal` excluding its `internal` package is a supported Java API.

## Runtime entry points

| Class or component | Responsibility |
|--------------------|----------------|
| `Wormholes` | Plugin entry point, load/enable/disable lifecycle, manager wiring, reset entry points |
| `WormholesBootstrap` | Paper bootstrap for the bundled pocket-dimension datapack |
| `WormholesReloadCoordinator` | Manual reload, TOML hotload application, complete data reset |
| `Settings` | Volatile runtime projection of the typed TOML settings and quality-profile overrides |
| `BlockManager` / `BlockOpsRuneConstruction` | Wand/rune item recognition, recipes, placed-rune index, rune construction dispatch |
| `ConstructionManager` / `WandSelectionManager` | Portal construction routing and box-selection workflow |
| `PortalManager` / `TraversableManager` | Portal registry, persistence loading, attendance, entry detection, traversal routing |
| `ProjectionManager` | Observer interest, projection scheduling, RTP view resolution, freeze/flush, projected events |
| `EffectManager` | Portal ambient effects and effect-entity ownership |

Enable and teardown order are documented in `13 - Runtime Architecture.md`.

## Package map

| Package | Behavior owners | User-facing reference |
|---------|-----------------|-----------------------|
| `art.arcane.wormholes` | Plugin lifecycle, listeners, construction, projection orchestration, config application | `03 - Building Portals.md`, `05 - Projection Modes & Settings.md`, `13 - Runtime Architecture.md` |
| `api.traversal` | Supported provider, context, quote, reservation, receipt, outcome, and event types | `20 - API - Getting Started.md`, `21 - API - Traversal Cost & Events.md` |
| `api.traversal.internal` | Provider discovery, ordering, fault policy, tickets, settlement, and event dispatch; not public API | `21 - API - Traversal Cost & Events.md` |
| `chunk` | Chunk leases, arrival warming, view-distance and send-rate helpers | `05 - Projection Modes & Settings.md` |
| `chunk.presend` | Optional traversal-time chunk pre-send planning, budgets, adapters, and platform delivery | `05 - Projection Modes & Settings.md` |
| `commands` | Director root, admin, network, and server command handlers and parameter completion | `09 - Commands & Permissions.md` |
| `config` / `config.toml` | Schema-2 TOML loading, canonical writes, and typed main/network/projection/render settings | `01 - Installation & Configuration.md` |
| `door` | Dimensional item identity, recipes, placement, visuals, access, OpenState, object/living transit, pockets, rescue, persistence, and protection | `07 - Dimensional Doors.md`, `08 - Pocket Dimensions.md` |
| `geometry` | Portal frames, structure planes, directions, transforms, and plane normalization | `02 - Concepts.md`, `03 - Building Portals.md` |
| `localization` | Typed message catalog, bundled/owner locale merging, validation, and audience rendering | `11 - Localization.md` |
| `network` | Identities, trust, routes, codes, peer lifecycle, protocol, compression, player admission/handoff, entity transfer, diagnostics | `10 - Cross-Server Networking.md` |
| `network.replication` | Remote-view chunk tracking, hashes, bulk/delta replication, resync, and statistics | `10 - Cross-Server Networking.md`, `05 - Projection Modes & Settings.md` |
| `network.replication.capture` | Optional shadow capture and comparison instrumentation for replicated chunks | `01 - Installation & Configuration.md`, `14 - Operator Runbooks & Smoke Tests.md` |
| `network.view` | Remote view subscriptions, block/entity capture, heartbeat, publishing, and peer session state | `05 - Projection Modes & Settings.md`, `10 - Cross-Server Networking.md` |
| `papi` | Snapshot publication, selection, formatting, resolvers, and PlaceholderAPI expansion lifecycle | `12 - PlaceholderAPI.md`, `22 - API - PlaceholderAPI.md` |
| `platform` | Paper/Spigot/Folia capability and packet/scheduler boundary helpers | `01 - Installation & Configuration.md`, `13 - Runtime Architecture.md` |
| `portal` | Local portal model, JSON persistence, menus, settings, tunnels, linking, costs, permissions, surfaces, and traversal transforms | `02 - Concepts.md`, `04 - Portal Types Menus & Settings.md` |
| `portal.rtp` | RTP editor, settings, sampler, safety validation, searches, leases, allocation, projection authorization, WorldGuard/Iris checks, and rim display | `06 - Random Teleport Portals.md` |
| `portal.vanilla` | Managed Nether and End detection, pairing, frame integrity, and vanilla event replacement | `03 - Building Portals.md` |
| `render` | Portal block scans, claims, blackout mesh, entities, recursive portals, light, occlusion, caching, and packet delivery | `05 - Projection Modes & Settings.md` |
| `render.view` | Live, snapshot, and region-safe world-view implementations used by the projector | `05 - Projection Modes & Settings.md`, `13 - Runtime Architecture.md` |
| `service` | Commands, audiences, diagnostics, bStats, stats snapshot, telemetry, and VolmLib integration service | `09 - Commands & Permissions.md`, `15 - Integrations.md`, `23 - API - Metrics & Integration Contract.md` |
| `survival.doors.dimension` | Bootstrap/datapack integration for the `wormholes:pockets` dimension | `08 - Pocket Dimensions.md` |
| `util` / `util.common` / `util.project.config` | Atomic I/O, caches, reflection helpers, bounded executors, and TOML file hotload support | `01 - Installation & Configuration.md`, `13 - Runtime Architecture.md` |

## Internal boundaries

- Portal CRUD, menus, projection/render classes, RTP search, dimensional doors, and network/wire classes are implementation details; plugins must not compile against them.
- `PortalTypeAccess` controls construction and type management only. Traversal authorization remains in the dynamic portal and door policies.
- World and entity access is scheduled through VolmLib's Folia-aware bridge. Network, disk, and heavy sampling work may run asynchronously, but game-state application returns to the owning region or entity.
- Portal JSON, door state, routes, trust, identities, dictionaries, and the stats snapshot have different lifecycle and reset rules; see `13 - Runtime Architecture.md` before changing persistence.

## Build and verification tasks

Run tasks from `WormholesPlugin/` with Java 25.

| Task | Result |
|------|--------|
| `./gradlew test` | JUnit suite with native access enabled for zstd |
| `./gradlew compileSpigotCompatibility` | Compiles supported source against Spigot API after excluding Paper-only bootstrap/listener/registrar classes |
| `./gradlew check` | Unit tests plus Spigot compatibility compilation |
| `./gradlew shadowJar` | Runtime plugin jar with configured relocations and SlimJar metadata |
| `./gradlew apiJar` | Compile-only public traversal API jar; excludes `api.traversal.internal` |
| `./gradlew build` | Full check plus runtime and API artifacts |
| `./gradlew bandwidthHarness` | Entity, transport, and replication comparison harness; scenario settings are supplied as JVM system properties |
| `./gradlew buildPsychoLT` | Full check plus managed test-server drop-in deployment; use only for the workspace Multiplexor test environment |

`build/libs/Wormholes-<version>.jar` is the server artifact; `build/libs/Wormholes-<version>-api.jar` is compile-only. A green build proves compilation and automated behavior, not client-visible projection, door interaction, cross-server transfer, or Folia ownership under a live workload; use the matching checks in `14 - Operator Runbooks & Smoke Tests.md`.
