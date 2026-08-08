# API - Getting Started

Wormholes exposes three supported third-party surfaces: traversal pricing/veto (`TraversalCostProvider` and traversal events), PlaceholderAPI keys, and runtime metrics via VolmLib `IntegrationServiceContract`. Projection, portal CRUD, the cross-server wire protocol, RTP destination selection, and dimensional-door pocket worlds have no public API.

| Goal | Surface | Doc |
|------|---------|-----|
| Charge, refund, or refuse a traversal | `TraversalCostProvider` via Bukkit `ServicesManager` | `21 - API - Traversal Cost & Events.md` |
| Watch or cancel traversals without money | `WormholesPortalTraverseEvent` / `WormholesPortalTraversedEvent` | `21 - API - Traversal Cost & Events.md` |
| Nearby portal name/state/distance on a board | `%wormholes_…%` | `12 - PlaceholderAPI.md`, `22 - API - PlaceholderAPI.md` |
| Portal counts, peer health, transfer volume as numbers | `IntegrationServiceContract` | `23 - API - Metrics & Integration Contract.md` |

Plugin name: `Wormholes`. Command: `/wormholes` (aliases `/wh`, `/wormhole`). Permissions under `wormholes.*`. Nothing in this API set requires a permission.

## Depending on Wormholes

### Plugin descriptor

Bukkit (`plugin.yml`):

```yaml
softdepend: [Wormholes]
```

Paper (`paper-plugin.yml`):

```yaml
dependencies:
  server:
    Wormholes:
      load: BEFORE
      required: false
      join-classpath: true
```

`join-classpath: true` is required on Paper. Without it, `art.arcane.wormholes.api.*` throws `NoClassDefFoundError` at runtime even though classes ship unrelocated in the plugin jar.

Placeholders need no Wormholes descriptor entry; they go through PlaceholderAPI only.

### API-only artifact

```
Wormholes-<version>.jar          plugin runtime — do not compile against this for API work
Wormholes-<version>-api.jar      art.arcane.wormholes.api.** minus internal packages
```

Build with `./gradlew apiJar` → `build/libs`. No shaded libraries in the API jar. Use `compileOnly` only; shading API classes duplicates types and breaks `ServicesManager` matching.

Example Gradle dependency when the API jar is kept in the consumer's `libs/` directory:

```groovy
dependencies {
    compileOnly(fileTree(dir: 'libs', include: 'Wormholes-*-api.jar'))
}
```

`apiJar` includes `art/arcane/wormholes/api/**` and excludes `**/internal/**`.

### Version discipline

Contract package: `art.arcane.wormholes.api.traversal` (public types only). Anything under `…traversal.internal` or outside `api` is unsupported and changes without notice. Enums may gain constants; third-party `switch` expressions need a `default` arm.

## Public package inventory

Package-private helper `TraversalText` is not part of the integrator surface.

| Type | Role |
|------|------|
| `TraversalCostProvider` | Functional service interface: quote / reserve / commit / refund |
| `TraversalContext` | Immutable attempt description |
| `TraversalDestination` | Optional far-side identity/location |
| `TraversalQuote` / `TraversalQuoteStatus` | Provider quote |
| `TraversalReservation` / `TraversalReservationStatus` | Reserve result |
| `TraversalReceipt` | Opaque token you create; Wormholes never invokes it |
| `TraversalRefundReason` | Why a refund is called |
| `TraversalKind` | `LOCAL`, `CROSS_SERVER`, `RANDOM_TELEPORT`, `DIMENSIONAL_DOOR` |
| `TraversalOutcome` / `TraversalDecision` | Final verdict (decision is not passed to providers) |
| `WormholesPortalTraverseEvent` | Pre-quote cancel (free) |
| `WormholesPortalTraversedEvent` | Post-commit observe (allowed outcomes only) |

## Acquiring the traversal service

You register; Wormholes looks you up:

```java
plugin.getServer().getServicesManager().register(
    TraversalCostProvider.class,
    context -> context.traveler().hasPermission("example.travel")
        ? TraversalQuote.pass()
        : TraversalQuote.denied("You may not use portals here"),
    plugin, ServicePriority.Normal);
```

Keep API types out of classes that load when Wormholes is absent. Put the imports and registration in a separate hook class, and load that class only after the plugin check:

```java
Plugin wormholes = getServer().getPluginManager().getPlugin("Wormholes");
if (wormholes != null && wormholes.isEnabled()) {
    WormholesTraversalHook.register(this);
}
```

`WormholesTraversalHook` may then import and register `TraversalCostProvider`; the main plugin class should not declare API-typed fields or method signatures. Bukkit unregisters services on your disable. Wormholes listens for `ServiceRegisterEvent` and `ServiceUnregisterEvent`, so a late registration is picked up on the next traversal.

## Threading (all surfaces)

Folia has region and entity ownership; there is no global main thread.

| Surface | Thread |
|---------|--------|
| Provider `quote` / `reserve` / `commit` | Region thread owning the portal |
| Provider `refund` | Same, except `EXPIRED` (next evaluation's portal region) and `SERVER_SHUTDOWN` (unload thread) — see `21 - API - Traversal Cost & Events.md` |
| `WormholesPortalTraverseEvent` | Portal region thread, inline |
| `WormholesPortalTraversedEvent` | Traveler entity scheduler |
| Placeholder resolve | Caller thread; Wormholes resolvers are non-blocking snapshot reads |
| `IntegrationServiceContract` sample | Caller thread; volatile counters / concurrent structures only |

Do not block, sleep, do I/O, join futures, or take contended locks on any of these paths. Rate-derived metrics recompute at most once per second.

## What has no API

- Projection content, sampling, and draw path
- Programmatic portal create/edit/delete or registry events
- Cross-server wire protocol and sideband
- RTP destination supply/veto/read (state via placeholders; price via `TraversalKind.RANDOM_TELEPORT`)
- Dimensional door pocket-world control

All four trip kinds are distinguishable via `TraversalKind` on the traversal API.
