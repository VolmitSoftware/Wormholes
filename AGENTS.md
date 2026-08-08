# Wormholes Agent Guide

Wormholes is a through-portal projection and traversal plugin for Paper and Folia: frame portals with live destination views, random teleport portals, survival Dimensional Doors with pocket dimensions, cross-server gateway handoff, and a public traversal pricing API. Read this file before making any change; the workspace-level `../AGENTS.md` also applies when working inside the VolmitSoftware workspace.

## Documentation Policy (mandatory)

- `docs/` is the authoritative reference for every feature of this plugin. Files are flat (no subfolders) and numbered `NN - Title.md`, ordered for someone new to the plugin; API docs always keep the highest numbers.
- ANY change that alters a feature, behavior, workflow, command, permission, setting, config TOML shape, portal type or menu, projection mode, dimensional door or pocket behavior, network handoff, PlaceholderAPI key, or API surface MUST update the matching numbered doc in the same workstream. A behavior change with stale docs is an incomplete change — do not finish work without the doc update.
- Docs state actual runtime behavior, not intended behavior. If a change fixes a documented quirk, update or remove that quirk entry. If a change introduces surprising behavior, document it plainly.
- Docs are purely factual reference material: no marketing language, no emojis, no filler. Each file opens with a 1–4 sentence summary.
- Cross-references use exact filenames (for example `see "05 - Projection Modes & Settings.md"`). When adding or renumbering files, fix every cross-reference.
- Hosted external docs and the root README are not authority; this `docs/` tree is.
- When adding a public API type or PlaceholderAPI key, update the matching `API - …` doc and, for player-facing keys, `12 - PlaceholderAPI.md`.

## Doc Index

| File | Covers |
|------|--------|
| `00 - Overview.md` | What Wormholes is, feature map, doc index, building |
| `01 - Installation & Configuration.md` | Install, data folder, `config/wormholes.toml`, quality profiles |
| `02 - Concepts.md` | Portals, projection, tunnels, RTP, doors, pockets, cross-server |
| `03 - Building Portals.md` | Wand, runes, construction, skins, vanilla portal replace |
| `04 - Portal Types Menus & Settings.md` | Types, menus, travel, access, costs, cosmetics, blackout |
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

Docs `00`–`15` serve operators and players in reading order; doc `16` serves maintainers; docs `20`–`23` serve plugin developers. Numbers `17`–`19` are reserved for future operator or maintainer topics.

## Content Model

- **Frame portals** (`portal/`) — constructed apertures with types PORTAL, WORMHOLE, GATEWAY, RTP; menus and per-portal settings.
- **Projection** (`ProjectionManager`, `render/`) — through-portal block and entity views for observers.
- **Dimensional doors** (`door/`) — survival door and trapdoor products, access, transit, pockets.
- **Network** (`network/`) — peer links, portal/server codes, player handoff, replication for remote views.
- **Config** (`config/toml`) — `plugins/Wormholes/config/wormholes.toml` schema 2.
- **Public third-party API** — `art.arcane.wormholes.api.traversal` (except `internal`), PlaceholderAPI expansion, VolmLib `IntegrationServiceContract`. Portal CRUD, projection internals, and the wire protocol are not public API.

## Build and Test

- Java 25, compiled with `-parameters`. Independent Gradle build from `WormholesPlugin/`: `./gradlew build`, `./gradlew test`, `./gradlew shadowJar`, `./gradlew apiJar`.
- Prefer the shaded plugin jar for runtime; use `*-api.jar` for compile-only third-party API consumers.
- Deploy only to the workspace Multiplexor test-server path when live-verifying; do not place jars into arbitrary server folders.
