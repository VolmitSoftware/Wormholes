# Installation & Configuration

Install the shaded Wormholes jar into `plugins/`, start once so `plugins/Wormholes/` is created, then edit `config/wormholes.toml` (`schema = 2`). Soft depends are optional; missing plugins skip their bridges. Changes to the consolidated config hot-reload via file watch or `/wh reload` (see `09 - Commands & Permissions.md`).

## Requirements

| Item | Value |
|------|--------|
| Runtime | Paper, Paper-compatible derivatives such as Purpur, and Folia (`folia-supported: true`) |
| Java | 25 (build toolchain and server launch) |
| Native access | Prefer `--enable-native-access=ALL-UNNAMED` so zstd-jni loads without restricted-access warnings |
| Soft depends | PlaceholderAPI, Iris, Vault (optional; load BEFORE when present) |
| Artifact | Shaded plugin jar from `./gradlew shadowJar` (prefer over thin/api jars for runtime) |

## Install

1. Place the shaded jar in `plugins/`.
2. Start the server. Wormholes creates the data folder and writes `config/wormholes.toml` if missing.
3. Edit `plugins/Wormholes/config/wormholes.toml`. Unsupported or schema-less files are rejected; `schema = 2` is required.
4. Apply config changes with `/wormholes reload` or the config file watcher. Direct edits to `languages/*.toml` require `/wormholes reload` (or a config change); Dimensional Doors pack/registry changes require a full server restart (see `07 - Dimensional Doors.md`).

## Data folder layout

```
plugins/Wormholes/
  config/wormholes.toml     consolidated settings (schema 2)
  portals/                  saved local portal files
  doors/                    dimensional door / pocket state
  languages/                optional per-locale TOML overrides
  routes/peers.properties   learned peer routes (not in wormholes.toml)
  trust/peers.properties    trusted peer public keys
  identity/                 network key material (server.identity, keys)
  dict/                     persisted network compression dictionaries
  uds/                      default Unix-domain socket directory
  wormholes-stats.txt       default stats snapshot path (overridable)
```

Peers are not listed under `[network]` in TOML. Import/export writes routes and trust under `routes/` and `trust/` (see `10 - Cross-Server Networking.md`).

## Config path and schema

| Property | Value |
|----------|--------|
| Path | `plugins/Wormholes/config/wormholes.toml` |
| Schema | `schema = 2` (`WormholesConfigFile.CURRENT_SCHEMA`) |
| Quality key | top-level `quality` (not inside a table) |
| Sections | `[main]`, `[network]` (+ nested), `[projection]`, `[render]` |
| Key form | kebab-case from Java field names (`teleportCooldownMillis` → `teleport-cooldown-millis`) |

On successful load the file is rewritten in canonical form with every known key. Canonical rewriting removes custom comments and unknown or misspelled keys. Schema-less files, wrong schemas, and parse failures keep the previous live settings.

## Visual quality (`quality`)

| Value | Effect after clamps from `[projection]` / `[render]` are applied |
|-------|------------------------------------------------------------------|
| `auto` (default) | No profile clamps |
| `performance` | Forces `lighting-fidelity = false`, `entity-spoofing = false`; caps range ≤ 32, depth ≤ 48, max projectors/tick ≤ 12, max portals/observer/tick ≤ 2, max new observer scans/tick ≤ 32 |
| `balanced` | Lighting refresh interval ≥ 6, entity update interval ≥ 2, max spoofed entities ≤ 16, max projectors/tick ≤ 20, max new observer scans/tick ≤ 64 |
| `cinematic` | Range ≥ 64, depth ≥ 96, max projectors/tick ≥ 32, max new observer scans/tick ≥ 128, lighting refresh ≤ 2, lighting max sections/pass ≥ 4, entity spoof range ≥ 64, max spoofed entities ≥ 48 |

Unknown profile names fail load. `enable-particles` remains an independent global particle switch; `quality` controls the projection/render profile.

## Runtime clamps (`Settings.refresh`)

Config values are clamped when applied to runtime. Canonical rewriting happens before runtime clamps, so an out-of-range source value can remain on disk while the live value is bounded.

| Runtime field source | Clamp |
|----------------------|--------|
| `portal-collapse-speed` | 0.0–1.0 |
| `teleport-cooldown-millis` | 0–60000 |
| `portal-pushback-multiplier` | 0.0–4.0 (non-finite → 1.0) |
| `portal-sound-volume-multiplier` | 0.0–4.0 (non-finite → 1.0) |
| `chunk-pre-send-radius-chunks` | 0–16 |
| `chunk-pre-send-max-chunks` | 0–1024 |
| `chunk-pre-send-budget-micros` | 0–25000 |
| `arrival-warm-radius-chunks` | 0–12 |
| `arrival-warm-max-radius-chunks` | ≥ warm radius, ≤ 32 |
| `arrival-warm-hold-millis` / `arrival-warm-throttle-millis` | 0–60000 |
| `arrival-transition-mask-ticks` | 0–200 |
| `chunk-send-rate-target` / `chunk-load-rate-target` | 0.0–10000.0 (≤0 or >10000 treated as unlimited at Paper tuner) |
| `projection.range` | 1.0–256.0 |
| `near-plane-padding` | 0.0–16.0 |
| `aperture-padding-blocks` | 0.0–8.0 |
| `frustum-culling-ratio` | 0.0–1.0 |
| `refresh-interval-ticks` | 1–20 |
| `depth-blocks` | 1–256 |
| `recursive-portal-depth` | 3–64 |
| `stable-cell-resample-interval-ticks` | 1–200 |
| `observer-interest-dot` | −1.0–1.0 |
| `side-grace-dot` | 0.0–1.0 |
| `max-projectors-per-tick` | 1–512 |
| `max-portals-per-observer-tick` | 1–64 |
| `max-new-observer-scans-per-tick` | 1–4096 |
| `interest-grace-ticks` | 0–100 |
| `initial-resend-passes` | 0–20 |
| `max-projected-cells` | 0–50000000 (0 disables the ceiling) |
| `lighting-refresh-interval-ticks` | 1–40 |
| `lighting-max-sections-per-pass` | 1–64 |
| `entity-update-interval-ticks` | 1–20 |
| `entity-spoof-range` | 1.0–256.0 |
| `entity-candidate-cache-ticks` | 1–40 |
| `max-spoofed-entities` | 0–256 |
| `capture-zone-radius` | 1.0–64.0 |

## Top-level keys

| Key | Default | Notes |
|-----|---------|--------|
| `schema` | `2` | Must match exactly |
| `quality` | `auto` | `auto` \| `performance` \| `balanced` \| `cinematic` |

## `[main]`

| Key | Default | Notes |
|-----|---------|--------|
| `language` | `en_US` | Active locale name; see `11 - Localization.md` |
| `language-fallbacks` | `""` | Comma-separated fallback locales; code English always final |
| `enable-particles` | `true` | Independent global particle switch |
| `replace-nether-and-end-portals` | `true` | Auto-link vanilla Nether/End frames as Wormholes portals |
| `dimensional-doors-enabled` | `true` | Full Dimensional Doors feature set; live disable allowed |
| `portal-collapse-speed` | `0.91` | Collapse animation factor |
| `verbose-logging` | `false` | Verbose console logs (`Settings.DEBUG`) |
| `debug-rendering` | `false` | Debug rendering aids |
| `teleport-cooldown-millis` | `1000` | Local teleport cooldown; also floors cross-server handoff rate limit (min 1000 ms) |
| `portal-pushback-multiplier` | `1.0` | Rejected-traversal push scale; 0 mutes knockback |
| `portal-sound-volume-multiplier` | `1.0` | Portal/door/traversal sound scale; 0 mutes |
| `traversal-api-enabled` | `true` | When false, no cost provider runs and traversal events do not fire |
| `traversal-api-provider-failure-policy` | `allow` | `allow` (treat fault as free pass) or `deny` (close portal) on provider throw/misbehavior |
| `traversal-api-provider-fault-limit` | `5` | Faults before provider quarantine; `0` disables quarantine |
| `traversal-api-slow-provider-millis` | `5` | Warn when a provider call meets/exceeds this ms; `0` disables |
| `chunk-pre-send-enabled` | `false` | Pre-send destination chunks at traversal commit (off until verified) |
| `chunk-pre-send-radius-chunks` | `3` | Radius of pre-send |
| `chunk-pre-send-max-chunks` | `32` | Hard ceiling per traversal |
| `chunk-pre-send-budget-micros` | `2000` | Microseconds of the commit tick the pre-send may use |
| `arrival-prewarm-on-interest` | `true` | Pre-warm arrival chunks when observers show interest |
| `arrival-warm-radius-chunks` | `4` | Warm radius |
| `arrival-warm-max-radius-chunks` | `10` | Max warm radius |
| `arrival-warm-hold-millis` | `5000` | Hold warm state |
| `arrival-warm-throttle-millis` | `1000` | Throttle between warm actions |
| `arrival-transition-mask` | `true` | Transition mask at arrival |
| `arrival-transition-mask-ticks` | `25` | Mask duration |
| `chunk-send-rate-tuner` | `true` | Once at startup, raise Paper per-player chunk send/load rate caps (never lowers) |
| `chunk-send-rate-target` | `1000.0` | Target chunks/sec send; Paper default 75 |
| `chunk-load-rate-target` | `1000.0` | Target chunks/sec load; Paper default 100 |

### Traversal API-related main keys

| Key | Role |
|-----|------|
| `traversal-api-enabled` | Master switch for third-party cost providers and traversal events |
| `traversal-api-provider-failure-policy` | `allow` vs `deny` on provider failure |
| `traversal-api-provider-fault-limit` | Session quarantine threshold |
| `traversal-api-slow-provider-millis` | Slow-call warning threshold |

API surface details: `21 - API - Traversal Cost & Events.md`.

## `[network]`

Cross-server networking. Default `enabled = false`. Import/export auto-sets `enabled = true` and starts the network when needed (see `10 - Cross-Server Networking.md`).

| Key | Default | Notes |
|-----|---------|--------|
| `enabled` | `false` | Cross-server portals / peers |
| `listen-enabled` | `true` | Accept inbound peer connections |
| `listen-port` | `8901` | Preferred raw-stream port; bind scans this port through +50, otherwise game-port sideband is used |
| `trust-on-first-use` | `true` | Trust unknown peer keys on first approved contact when no stored key |
| `entity-transfer-deny-types` | `""` | Comma-separated entity type names denied for entity transfer |
| `advertise-host-override` | `""` | Force advertised host in export codes |
| `server-name` | `""` | Local network name override (empty uses identity default) |
| `transfer-mode` | `auto` | `auto` \| `proxy` \| `direct` (see networking doc) |
| `handoff-timeout-ms` | `5000` | Admission / handoff deadline |
| `auto-accept-transfers` | `true` | Compatibility rewrite of TRANSFER handshakes to LOGIN when native `accepts-transfers` is not set |

Static `[[peers]]` are not written into this file. Peers live in `routes/peers.properties`.

### `[network.transport]`

| Key | Default | Notes |
|-----|---------|--------|
| `compression-enabled` | `true` | Wire compression |
| `compression-level` | `3` | Runtime clamp 1–22 |
| `compression-dict-train-bytes` | `10485760` | Dictionary corpus budget; runtime minimum 65536 bytes |
| `compression-dict-target-size` | `65536` | Dictionary size target |
| `compression-retrain-interval-sec` | `600` | Retrain interval; runtime minimum 30 s |
| `uds-enabled` | `true` | Unix domain sockets when available |
| `uds-dir` | `""` | Empty uses `plugins/Wormholes/uds`; a relative override resolves from the JVM working directory |

### `[network.view]`

Entity delta rates for remote views:

Non-positive Hz disables that distance band. Positive rates above 20 Hz still schedule at most once per server tick.

| Key | Default |
|-----|---------|
| `entity-delta-enabled` | `true` |
| `entity-rate-near-range` | `16.0` |
| `entity-rate-mid-range` | `64.0` |
| `entity-rate-far-range` | `128.0` |
| `entity-rate-near-hz` | `20.0` |
| `entity-rate-mid-hz` | `10.0` |
| `entity-rate-far-hz` | `4.0` |
| `entity-rate-very-far-hz` | `1.0` |

### `[network.stats]`

| Key | Default | Notes |
|-----|---------|--------|
| `enabled` | `true` | Periodic stats snapshot file |
| `interval-sec` | `10` | Write interval; runtime minimum 1 s |
| `path-override` | `""` | Empty → `wormholes-stats.txt`; relative paths resolve under the data folder, absolute paths are used as written |

### `[network.replication]`

| Key | Default | Notes |
|-----|---------|--------|
| `hash-probe-interval-sec` | `30` | Hash probe cadence; runtime minimum 1 s |
| `hash-probe-chunks-per-tick` | `16` | Probe budget; runtime minimum 1 |
| `diff-window-size` | `32` | Diff window; runtime minimum 1 |
| `resync-timeout-sec` | `5` | Resync timeout; runtime minimum 0 |
| `max-queued-diffs-per-peer` | `4096` | Queue cap |
| `capture-snapshot-interval-ticks` | `100` | Snapshot interval; runtime minimum 20 ticks |
| `capture-max-queued-diffs-per-chunk` | `256` | Per-chunk queue; runtime minimum 16 |
| `capture-light-enabled` | `true` | Capture light in replication |
| `capture-block-entity-enabled` | `false` | Block-entity NBT capture; disabled by default (renderer does not consume it) |

## `[projection]`

| Key | Default | Notes |
|-----|---------|--------|
| `range` | `48.0` | Observer interest / projection range |
| `refresh-interval-ticks` | `1` | Projection refresh cadence |
| `near-plane-padding` | `2.0` | Near plane pad |
| `aperture-padding-blocks` | `0.75` | Extra outward pad past aperture edges; raise if rim bleed-through |
| `frustum-culling-ratio` | `0.2` | Frustum cull ratio |
| `depth-blocks` | `64` | Extra search distance for recursive portal candidates; primary view depth is per portal |
| `recursive-portal-depth` | `3` | Nested portal recursion (runtime min 3) |
| `stable-cell-resample-interval-ticks` | `4` | Stable cell resample |
| `client-view-distance-cap` | `true` | Cap to client view distance |
| `foveated-unrendering` | `false` | Foveated unrender when interest lost |
| `observer-interest-dot` | `-0.2` | Look-toward interest threshold |
| `side-grace-dot` | `0.12` | Portal-side grace |
| `max-projectors-per-tick` | `24` | Global projector budget |
| `max-portals-per-observer-tick` | `4` | Per-observer portal budget |
| `max-new-observer-scans-per-tick` | `64` | New observer scan budget |
| `interest-grace-ticks` | `5` | Interest grace |
| `initial-resend-passes` | `1` | Full sends after view create (raise only to diagnose packet loss) |
| `max-projected-cells` | `250000` | Hard scan ceiling; budget drops lateral pad first then depth; `0` disables (not recommended) |

Projection behavior detail: `05 - Projection Modes & Settings.md`.

## `[render]`

| Key | Default | Notes |
|-----|---------|--------|
| `lighting-fidelity` | `false` | Send destination lighting with projected blocks |
| `entity-spoofing` | `true` | Show destination-side entities in projections |
| `lighting-refresh-interval-ticks` | `4` | Lighting refresh |
| `lighting-max-sections-per-pass` | `2` | Lighting section budget |
| `adaptive-lighting` | `true` | Adaptive lighting |
| `entity-update-interval-ticks` | `1` | Entity spoof update cadence |
| `entity-spoof-range` | `48.0` | Spoof range |
| `entity-candidate-cache-ticks` | `3` | Candidate cache TTL |
| `max-spoofed-entities` | `24` | Cap per view |
| `capture-zone-radius` | `8.0` | Capture zone radius |

## Hot reload

| Path | Mechanism |
|------|-----------|
| `config/wormholes.toml` change | `HotloadManager` reloads config and then reloads the selected language when valid |
| `languages/*.toml` change | Not watched directly; use `/wormholes reload` or touch the config file |
| `/wormholes reload` | Explicit reload of configuration and language files (`wormholes.admin.reload`) |
| Failed language load on reload | Config may still apply; last valid language retained; console reports cause |
| Network enable/peer changes | Import/export may start the network without a full restart |

Destructive wipe of config, routes, trust, identity, portals, and doors: `/wormholes admin deleteeverything` (`wormholes.admin.reset`).

## Related docs

- `09 - Commands & Permissions.md` — reload, debug, stats, network commands
- `10 - Cross-Server Networking.md` — network keys in operation
- `11 - Localization.md` — `language` / overrides
- `21 - API - Traversal Cost & Events.md` — traversal API contract
