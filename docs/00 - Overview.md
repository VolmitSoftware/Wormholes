# Overview

Wormholes is a through-portal projection and traversal plugin for Paper and Folia. Frame portals show a live destination view and move travelers; related systems cover random teleport, survival Dimensional Doors with pocket dimensions, cross-server gateways, PlaceholderAPI keys, and a public traversal pricing API.

## Feature map

| Area | Summary | Doc |
|------|---------|-----|
| Frame portals | Wand/rune construction, types, menus, skins | `03 - Building Portals.md`, `04 - Portal Types Menus & Settings.md` |
| Projection | ON/OFF, PanOptic vs Venticular, budgets | `05 - Projection Modes & Settings.md` |
| Concepts | Types, tunnels, travel, local vs remote | `02 - Concepts.md` |
| Random teleport | RTP portal type and editor | `06 - Random Teleport Portals.md` |
| Dimensional doors | Pair / Personal / Public doors and trapdoors | `07 - Dimensional Doors.md` |
| Pocket dimensions | Shared void pocket world and return doors | `08 - Pocket Dimensions.md` |
| Commands & permissions | `/wormholes` tree and nodes | `09 - Commands & Permissions.md` |
| Cross-server | Gateway codes, trust, handoff | `10 - Cross-Server Networking.md` |
| Localization | Bundled locales and overrides | `11 - Localization.md` |
| PlaceholderAPI | Operator `%wormholes_…%` keys | `12 - PlaceholderAPI.md` |
| Runtime architecture | Managers, Folia, storage | `13 - Runtime Architecture.md` |
| Operator runbooks | Manual smoke checks | `14 - Operator Runbooks & Smoke Tests.md` |
| Integrations | Vault, Iris, soft depends | `15 - Integrations.md` |
| Maintainer reference | Production packages, boundaries, build tasks | `16 - Maintainer Component & Build Reference.md` |
| Public API | apiJar, traversal cost, metrics | `20`–`23` API docs |

Install and `config/wormholes.toml` are covered in `01 - Installation & Configuration.md`.

## Documentation index

| File | Covers |
|------|--------|
| `00 - Overview.md` | What Wormholes is, feature map, runtime, build |
| `01 - Installation & Configuration.md` | Install, data folder, `config/wormholes.toml`, quality profiles |
| `02 - Concepts.md` | Portals, projection, tunnels, RTP, doors, pockets, cross-server |
| `03 - Building Portals.md` | Wand, runes, construction, skins, vanilla portal replace |
| `04 - Portal Types Menus & Settings.md` | Types, menus, travel, access, costs, cosmetics |
| `05 - Projection Modes & Settings.md` | Projection ON/OFF, PanOptic vs Venticular, budgets, render |
| `06 - Random Teleport Portals.md` | RTP type, editor options, safety, rotation |
| `07 - Dimensional Doors.md` | Pair/Personal/Public, OpenState, access, recipes, transit |
| `08 - Pocket Dimensions.md` | Pocket world, layout, return door, rescue |
| `09 - Commands & Permissions.md` | Every `/wormholes` command and permission node |
| `10 - Cross-Server Networking.md` | Codes, trust, handoff, transfer modes, doctor |
| `11 - Localization.md` | Locales, overrides, fallbacks |
| `12 - PlaceholderAPI.md` | `%wormholes_…%` keys for operators |
| `13 - Runtime Architecture.md` | Boot, managers, Folia, storage layout |
| `14 - Operator Runbooks & Smoke Tests.md` | Manual verification checklists |
| `15 - Integrations.md` | Vault, Iris, React metrics, soft depends |
| `16 - Maintainer Component & Build Reference.md` | Production package map, internal boundaries, build tasks |
| `20 - API - Getting Started.md` | apiJar, service registration, public surface map |
| `21 - API - Traversal Cost & Events.md` | TraversalCostProvider and traversal events |
| `22 - API - PlaceholderAPI.md` | Integrator notes for placeholders |
| `23 - API - Metrics & Integration Contract.md` | IntegrationServiceContract metrics |

Docs `00`–`15` are for operators and players, doc `16` is for maintainers, and docs `20`–`23` are for plugin developers. Numbers `17`–`19` are reserved.

## Runtime requirements

| Item | Value |
|------|--------|
| Java | 25 (compiled with `-parameters`, release 25) |
| Folia | Supported (`folia-supported: true` in paper-plugin metadata) |
| Soft depends | PlaceholderAPI, Iris, Vault (optional; load before Wormholes when present) |
| Runtime library | `zstd-jni` is supplied by the legacy plugin library declaration (`com.github.luben:zstd-jni:1.5.7-11`), not shaded into the jar |

JVM tip: include `--enable-native-access=ALL-UNNAMED` on the server (and test) JVM so zstd-jni can load its native library without restricted-access warnings.

## Build

From `WormholesPlugin/`:

| Task | Purpose |
|------|---------|
| `./gradlew build` | Full gate; also builds shadow jar and api jar |
| `./gradlew test` | Unit tests (uses `--enable-native-access=ALL-UNNAMED`) |
| `./gradlew shadowJar` | Shaded runtime plugin jar |
| `./gradlew apiJar` | Compile-only public API (`art.arcane.wormholes.api/**`, excluding `internal`) |
| `./gradlew compileSpigotCompatibility` | Compile supported source against Spigot API |
| `./gradlew bandwidthHarness` | Run the transport/entity/replication comparison harness |

Prefer the shaded jar for servers. Prefer `*-api.jar` for third-party compile-only consumers.

## Commands

Base command aliases (see `09 - Commands & Permissions.md` for the full tree):

| Alias | Notes |
|-------|--------|
| `/wormholes` | Primary |
| `/wh` | Short alias |
| `/wormhole` | Singular alias |
