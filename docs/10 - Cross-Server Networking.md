# Cross-Server Networking

Wormholes links servers by exchanging pasteable codes, storing routes under `routes/` and public keys under `trust/`, then running a version-matched sideband peer protocol. Player handoff is admission-gated and rate-limited before the client is transferred. Eligible non-player entities use a separate snapshot-and-ack path.

## Enable and auto-enable

| Setting | Default | Role |
|---------|---------|------|
| `[network] enabled` | `false` | Master switch for cross-server networking |
| Import / export | — | Sets `enabled = true`, persists config, and starts `NetworkManager` if not running |

Manual enable: set `enabled = true` in `plugins/Wormholes/config/wormholes.toml` and reload or restart. Codes also enable networking without a separate edit (see `ImportExportService`).

Other network keys: `01 - Installation & Configuration.md` (`[network]` and nested tables).

## Codes

| Kind | Prefix | Source | Contents (encoded) |
|------|--------|--------|---------------------|
| Server | `WHS1.` | `/wormholes server export` | Server name, advertise host, fallback hosts, wormhole port, game port, public key |
| Portal | `WHP5.` | Gateway portal Export UI | Same host/key material plus portal UUID and portal name |

Encoding is URL-safe Base64 after the prefix. Invalid codes reject with a message naming both prefixes.

### Import commands (equivalent)

| Command | Effect |
|---------|--------|
| `/wormholes server import <code>` | Accepts `WHS1.` or `WHP5.` |
| `/wormholes network import <code>` | Same `importCode` path |

Import of a **server** code saves route + trust only. Import of a **portal** code from chat saves route + trust and reports the remote portal name; linking a local gateway to that remote portal is done through the portal Link UI (when import is invoked with a portal context from the UI, `linkRemote` is applied immediately).

Export of either kind also enables network and starts it. Server export to a player is click-to-copy; console prints the raw code.

### Identity collision

Importing a code whose server name equals this server’s local name is rejected (same-server portal or same identity).

## Storage

```
plugins/Wormholes/
  routes/peers.properties   PeerEntry fields learned from codes / links
  trust/peers.properties    Trusted peer public keys
  identity/                 Local key material for the wire handshake
```

| Action | Storage impact |
|--------|----------------|
| Import code | `trustPeer` + `savePeer` (route) |
| `/wormholes server remove <name>` | Deletes route and trusted key; drops remote portal registry entries for that peer |
| TOFU re-trust | Online peer that still has this server may re-register if `trust-on-first-use` remains true — remove on **both** servers to fully forget |

Peers are **not** configured as `[[peers]]` inside `wormholes.toml`.

## Trust on first use

| `trust-on-first-use` | Behavior |
|----------------------|----------|
| `true` (default) | Unknown peer with no stored key may be trusted on first connection/sideband if policy allows |
| `false` | Unknown peer with no route/trust entry is rejected until an import (or prior trust) stores a key |

If a stored key exists and the peer presents a different key, the connection is rejected (key change). Import overwrites trust via `trustOrReplace`.

The local identity is an Ed25519 key pair stored as `identity/server.identity`, with repaired compatibility mirrors at `identity/server.key` and `identity/server.pub`; private files are owner-only where POSIX permissions are available. `/wormholes network status` and import/export messages expose the public-key fingerprint for comparison. Back up the identity with the route/trust state: deleting or replacing it changes this server's fingerprint, so peers with the old key will reject it until trust is deliberately replaced. The signatures authenticate peer identity and message ownership; they do not encrypt the sideband connection.

## Transfer mode

`[network] transfer-mode` (`PlayerTransfer.resolveMethod`):

| Mode | Selection |
|------|-----------|
| `auto` (default) | **PROXY** if peer `useProxy` is true, else **DIRECT** |
| `proxy` | Always BungeeCord plugin message `Connect` on channel `BungeeCord` with peer name |
| `direct` | Paper `player.transfer(host, port)` to resolved game host/port |

Peer `useProxy` is stored on the route entry when set; imports set host/port from the code (default public game port 25565 if game port missing).

## Endpoint selection (direct handoff)

`PeerEndpointResolver` + handoff path:

| Player context | Host choice |
|----------------|-------------|
| Internet / non-local client | First of: `publicHost`, then `host`, then comma-separated `fallbackHosts` (`gameHosts` order) |
| Local client (loopback / site-local / link-local / ULA) **and** a verified private/local host from an active private connection | That verified private host |
| Localhost alone | Does **not** make a separately hosted peer’s private address valid without verification |

Game-port sideband tries fallbacks when a raw socket was never established and remembers the endpoint that answered for player handoff. Imported portal codes carry public and LAN candidates in the code payload (up to four fallback hosts).

`advertise-host-override` forces the host written into export codes when non-blank.

## Handoff admission

Before the source dispatches the client, the destination must grant a rate-limited admission lease for that transfer.

Destination checks include:

- Live destination portal can receive (open, not mirror-only, incoming traversals enabled)
- Selected transfer method is supported
- Profile passes ban and whitelist gates (ops exempt from whitelist)
- Online players + pending arrivals stay under the player limit
- Direct transfer support: native Paper transfers or compatibility path

| Outcome | Result |
|---------|--------|
| Deny / timeout / cooldown | Traveler returned to the source-facing side of the portal (not left in-plane / not orphaned disconnect) |
| Accept | Reservation held until destination portal teleport succeeds; transient placement failures retry then fall back to destination spawn |

### Rate limits

| Source | Value |
|--------|--------|
| Interval | `max(1000 ms, teleport-cooldown-millis)` (`TraversalAdmissionPolicy.handoffRateLimitMillis`) |
| Scope | Per-player outbound and destination admission rate limiters |
| Failure | Penalty re-applies the interval; denials can carry `retryAfterMillis` |

### Handoff timeout

`[network] handoff-timeout-ms` (default `5000`) bounds the admission/request window.

## Paper transfers and auto-accept

| Mechanism | Setting | Notes |
|-----------|---------|--------|
| Native Paper | `accepts-transfers=true` in **destination** `server.properties` + restart | Required for first-class transfer handshakes |
| Compatibility | `[network] auto-accept-transfers = true` (default) | `TransferGate` rewrites TRANSFER intention handshakes to LOGIN when network is enabled |

Destination support is treated as true when `autoAcceptTransfers` **or** the platform reports accepting transfers. Direct transfers fail admission with “destination does not accept direct transfers” when neither path is active.

## Wire protocol

| Constant | Value |
|----------|--------|
| `WireCodec.PROTOCOL_VERSION` | **19** |

Handshake Hello carries the protocol, Minecraft, and Wormholes versions. Raw peer links require an exact match for all three; mismatches reject the handshake with the peer and local values. Linked servers therefore need the same Minecraft version and exact Wormholes plugin version, which also ensures protocol **19** matches.

Optional compression and dictionary negotiation ride the same wire once Hello succeeds (`[network.transport]`).

## Non-player entity transfer

Eligible non-player entities entering a `UNIVERSAL` gateway are transferred as Bukkit `EntitySnapshot` data rather than as player handoffs. The source snapshot is capped at **256 KiB**; the destination recreates the entity at the exit, then applies the portal's relative position, look, and velocity transform.

Destination admission requires a live open exit with inbound traversal enabled and a type not listed in `[network] entity-transfer-deny-types` (comma-separated Bukkit entity type names, case-insensitive). A transfer-ID ledger suppresses duplicate creation, accepted ACKs are retried, and the source entity is removed only after an accepted ACK. Send failure, denial, timeout, or scheduler rejection restores the source entity's captured transit state; late accepted ACKs use tombstones to remove a restored duplicate.

Players never use this snapshot path. Their profile, capacity, transfer-method, and client-handoff rules remain the player path described above.

## Operator workflow

1. On server A: `/wormholes server export` → copy `WHS1.…`
2. On server B: `/wormholes server import <code>` (or `network import`)
3. Reverse export/import so both sides have routes and trust (one-way import creates a route on the importer only)
4. Ensure `accepts-transfers=true` (or rely on auto-accept) on destinations that receive direct transfers
5. Open the game port and the actual raw peer port reported by `/wormholes network status`. The listener tries configured `listen-port` through `listen-port + 50` when ports are busy; reserve the configured port or permit the reported bound port/range.
6. Link gateways: portal Export (`WHP5.…`) on one side, import + Link menu on the other
7. Verify: `/wormholes network status`, `/wormholes server list`, `/wormholes network doctor`

## Troubleshooting

| Command | Use |
|---------|-----|
| `/wormholes network status` | Listen address or outbound-only, fingerprint, peer state + RTT + last error |
| `/wormholes network doctor` | Free-form diagnostic lines when peers fail to connect |
| `/wormholes debug` | Toggle one-second projection/network/queue/peer/handoff telemetry to **console** on both servers while reproducing a failed handoff; toggle again to stop |
| `/wormholes stats` | Path to live snapshot file (network/view state) |

Direct transfer debug lines include client address, LAN classification, selected `host:port`, and configured endpoints; the destination logs transfer-gate handshake rewrite when auto-accept runs.

For an entity-transfer denial check, add a Bukkit entity type name to `entity-transfer-deny-types` and verify the source entity is restored. The TRANSFERS and failure sections in the stats snapshot include both player handoffs and entity transfers.

## Related docs

- `01 - Installation & Configuration.md` — full `[network]` key tables
- `09 - Commands & Permissions.md` — permission nodes for network/server
- `04 - Portal Types Menus & Settings.md` — gateway link UI
- `13 - Runtime Architecture.md` — managers and storage
