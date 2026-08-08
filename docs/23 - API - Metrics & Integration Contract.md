# API - Metrics & Integration Contract

Wormholes registers VolmLib `art.arcane.volmlib.integration.IntegrationServiceContract` with Bukkit `ServicesManager` at `ServicePriority.Normal`. React and other monitors sample Wormholes without either plugin hard-depending on the other. Handshake, heartbeat, and sample shapes are defined by VolmLib; this page records what Wormholes contributes.

Descriptor / soft-depend notes for Wormholes itself: `20 - API - Getting Started.md`. Soft depends and operator integrations: `15 - Integrations.md`.

## Contract identity

| Field | Value |
|-------|-------|
| `pluginId()` | `wormholes` |
| Supported protocols | `1.0`, `1.1` |
| Capabilities | `handshake`, `heartbeat`, `metrics`, `wormholes-projection-metrics` |
| Priority | `ServicePriority.Normal` |

The contract type ships in **VolmLib**, not in `Wormholes-*-api.jar`. Consumers need VolmLib as `compileOnly` and must share the same runtime class identity as the registered service, normally through Paper dependency classpath joining. Bundling another unrelocated copy does not make typed `ServicesManager` lookup cross classloaders. React additionally scans registrations through its reflective adapter, but ordinary consumers do not receive that behavior automatically.

## Acquire, handshake, and sample

```java
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

Collection<RegisteredServiceProvider<IntegrationServiceContract>> registrations =
    Bukkit.getServicesManager().getRegistrations(IntegrationServiceContract.class);

for (RegisteredServiceProvider<IntegrationServiceContract> registration : registrations) {
    IntegrationServiceContract provider = registration.getProvider();
    if (!"wormholes".equals(provider.pluginId())) {
        continue;
    }

    IntegrationHandshakeRequest request = new IntegrationHandshakeRequest(
        "example-monitor",
        "1.0.0",
        Set.of(new IntegrationProtocolVersion(1, 1)),
        Set.of("metrics"),
        System.currentTimeMillis()
    );
    IntegrationHandshakeResponse response = provider.handshake(request);
    if (!response.accepted()) {
        continue;
    }

    IntegrationHeartbeat heartbeat = provider.heartbeat();
    Map<String, IntegrationMetricSample> samples = provider.sampleMetrics(Set.of(
        "wormholes.portals",
        "wormholes.transfers-in-flight"
    ));
}
```

Acquire after both plugins enable, repeat discovery when service registrations change, and stop using a provider after its registration disappears. A successful handshake negotiates the highest shared protocol; the heartbeat reports that negotiated protocol and current health. A sample with `available() == false` has no numeric value and carries the reason in `message()`.

## Sampling

- `sampleMetrics(keys)` with null/empty keys returns every Wormholes key from `IntegrationMetricSchema.wormholesKeys()`.
- Unknown keys return `IntegrationMetricSample.unavailable` with reason `unsupported-key`.
- Answers come from volatile counters, immutable snapshots, and concurrent structures. Sampling does not touch entities, blocks, or chunks.
- Rate-derived samples (bytes/s, drops/s, replicated blocks/s) recompute at most once per second behind a short lock; faster sampling returns the previous rate.

## Unavailable vs zero

When a backing subsystem is not started, Wormholes returns **unavailable** with a reason string — not a numeric zero.

| Reason (examples) | Subsystem |
|-------------------|-----------|
| `portal-manager-not-ready` | Local portal count |
| `network-manager-not-ready` | Peers, wire, compression, sideband, replication |
| `remote-portal-registry-not-ready` | Remote portal count |
| `view-server-not-ready` | View subscriptions / tracked entities |
| `traversal-service-not-ready` | Transfers in flight / failed |
| `unsupported-key` | Key not owned by Wormholes schema |

Treat unavailable as "no answer". Zero is a real measurement after the subsystem is up (e.g. zero connected peers while networking runs).

## Metric keys (`wormholes.*`)

All keys begin with `wormholes.` and are declared in VolmLib `IntegrationMetricSchema`. Wormholes filters descriptors to that prefix.

### Portals

| Key | Type / unit | Meaning |
|-----|-------------|---------|
| `wormholes.portals` | long / portals | Local registered portals |
| `wormholes.remote-portals` | long / portals | Known remote portals |

### Projection

| Key | Type / unit | Meaning |
|-----|-------------|---------|
| `wormholes.projections-active` | long / portals | Active projections |
| `wormholes.projection-observers` | long / players | Distinct observers with a frame in flight |
| `wormholes.projection-render-ms` | double / ms-per-second | Render work rate |
| `wormholes.spoofed-entities` | long / entities | Spoofed entity count |
| `wormholes.view-subscriptions` | int / subscriptions | View subscriptions |
| `wormholes.view-tracked-entities` | int / entities | Tracked view entities |

### Travel

| Key | Type / unit | Meaning |
|-----|-------------|---------|
| `wormholes.traversals-per-minute` | double / traversals-per-minute | Traversal rate |
| `wormholes.transfers-in-flight` | int / transfers | Player handoffs plus non-player entity transfers in progress |
| `wormholes.transfers-failed-total` | long / transfers | Cumulative failed transfers |

### Network

| Key | Type / unit | Meaning |
|-----|-------------|---------|
| `wormholes.peers-connected` | int / servers | Handshake-complete, non-disconnected peers |
| `wormholes.peer-rtt-max-ms` | long / ms | Max RTT among connected peers |
| `wormholes.wire-bytes-out-per-second` | double / bytes-per-second | Wire egress rate |
| `wormholes.wire-bytes-in-per-second` | double / bytes-per-second | Wire ingress rate |
| `wormholes.compression-ratio-out` | double / ratio | Outbound compression ratio |
| `wormholes.sideband-queued-bytes` | long / bytes | Sideband queue depth |
| `wormholes.sideband-drops-per-second` | double / drops-per-second | Sideband drop rate |
| `wormholes.block-changes-per-second` | double / blocks-per-second | Block-change send rate |
| `wormholes.packets-per-second` | double / packets-per-second | Packet send rate |
| `wormholes.replicated-blocks-per-second` | double / blocks-per-second | Replication block rate |
| `wormholes.resync-requests-total` | long / requests | Cumulative resync requests |

## Scoreboard alternative

A subset of the same runtime facts is available as PlaceholderAPI strings without VolmLib compile deps — see `12 - PlaceholderAPI.md` (`portals`, projections, peers, transfers, failures). Use the integration contract when you need typed numeric samples and domain tags for monitoring.
