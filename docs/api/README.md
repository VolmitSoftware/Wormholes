# Wormholes integration surfaces

Wormholes runs portals with live through-portal projection, cross-server traversal, dimensional doors and
random teleport. Three of those things are visible to other plugins: you can **price or veto a traversal**,
you can **read what a player is standing in front of** through PlaceholderAPI, and you can **sample the
plugin's runtime metrics** through the shared Volmit integration contract. Everything else — projection,
replication, the wire protocol, portal storage — is internal and has no supported entry point.

| You want to…                                                    | Surface                                                    | Document                                 |
|-----------------------------------------------------------------|------------------------------------------------------------|------------------------------------------|
| charge for a traversal, refund it, or refuse it                 | `TraversalCostProvider` via Bukkit `ServicesManager`        | [traversal-cost.md](traversal-cost.md)   |
| watch traversals, or cancel them for free                       | `WormholesPortalTraverseEvent` / `WormholesPortalTraversedEvent` | [traversal-cost.md](traversal-cost.md) |
| put the nearby portal's name, state, destination or distance on a scoreboard | `%wormholes_…%` placeholders                   | [placeholders.md](placeholders.md)       |
| read portal counts, peer link health or transfer volume as numbers | `IntegrationServiceContract` via Bukkit `ServicesManager` | below                                    |

The plugin is `Wormholes`. Its command is `/wormholes` (aliases `/wh`, `/wormhole`) and its permissions are all
under `wormholes.*`. The `wormholes.admin` and `wormholes.gateway` nodes default to operators; the
`wormholes.portals` nodes — building and using ordinary and projecting portals — default to everyone. Nothing
in this documentation set requires a permission.

---

## Depending on Wormholes

### The plugin descriptor

Bukkit plugin (`plugin.yml`):

```yaml
softdepend: [Wormholes]
```

Paper plugin (`paper-plugin.yml`):

```yaml
dependencies:
  server:
    Wormholes:
      load: BEFORE
      required: false
      join-classpath: true
```

`join-classpath: true` is mandatory on Paper. Plugin classloaders are isolated there, and without it every
reference to `art.arcane.wormholes.api.*` throws `NoClassDefFoundError` at runtime even though the classes
ship unrelocated inside the plugin jar.

You do **not** need a descriptor entry to read placeholders. Those go through PlaceholderAPI, and your plugin
never names a Wormholes type.

### The API-only artifact

Wormholes publishes a second jar next to the plugin jar containing nothing but the public API:

```
Wormholes-<version>.jar          the plugin. Do not compile against this
Wormholes-<version>-api.jar      art.arcane.wormholes.api.**, minus internal packages
```

The API jar has no dependencies of its own beyond a Bukkit-flavoured compile classpath, contains no
shaded libraries, and carries none of the plugin's runtime. It is what you compile against. Build it from
source with `./gradlew apiJar`; it lands in `build/libs`.

Gradle:

```groovy
dependencies {
    compileOnly files('libs/Wormholes-1.0.0-26.2-api.jar')
}
```

`compileOnly` is the correct scope, and the only correct scope. Shading these classes into your own jar
creates a second, incompatible copy of `TraversalCostProvider`, and the `ServicesManager` will never match
your registration to the one Wormholes is looking up.

If you would rather not vendor a jar, the same classes are present in the plugin jar and you can compile
against that instead. The API jar exists so that a compile-time mistake — reaching into
`art.arcane.wormholes.api.traversal.internal`, or into the plugin's own packages — fails at compile time
rather than at runtime on somebody else's server.

### Version discipline

`art.arcane.wormholes.api.traversal` is the contract. Anything else in the jar, and anything under a package
named `internal`, is not: it changes without notice, and it is excluded from the API artifact on purpose. The
enums in the API may gain constants, so third-party `switch` expressions over them need a `default` arm — see
the enum section of [traversal-cost.md](traversal-cost.md).

---

## Acquiring the traversal service

Wormholes does not hand out an object you fetch. You **register** with Bukkit's `ServicesManager` and
Wormholes looks you up:

```java
package com.example.bridge;

import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class TraversalBridge {
    private TraversalBridge() {
    }

    public static void register(JavaPlugin plugin) {
        plugin.getServer().getServicesManager().register(TraversalCostProvider.class,
            context -> context.traveler().hasPermission("example.travel")
                ? TraversalQuote.pass()
                : TraversalQuote.denied("You may not use portals here"),
            plugin, ServicePriority.Normal);
    }
}
```

Because that class names `TraversalCostProvider` in its own signature, loading it on a server without
Wormholes throws `NoClassDefFoundError`. Keep the API references inside a separate class and only touch it
once you know the plugin is there:

```java
package com.example.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class BridgePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        Plugin wormholes = Bukkit.getPluginManager().getPlugin("Wormholes");

        if (wormholes != null && wormholes.isEnabled()) {
            TraversalBridge.register(this);
        }
    }
}
```

`BridgePlugin` references no Wormholes type, so it verifies and loads anywhere. `TraversalBridge` is only
resolved when `register` is first called, which is guarded. This is the whole pattern; there is no soft-depend
helper to learn.

Bukkit unregisters your services automatically when your plugin disables. You do not need to unregister in
`onDisable`, and Wormholes notices registrations and unregistrations as they happen — it listens for
`ServiceRegisterEvent` and `ServiceUnregisterEvent` and rebuilds its provider list, so a provider registered
after Wormholes enabled is picked up on the next traversal.

---

## Threading, across all surfaces

This plugin suite runs on Folia, where region threads own the chunks they contain and entity schedulers own
the entities. There is no single "main thread" you can assume.

| Surface                              | Which thread you are on                                                   |
|--------------------------------------|---------------------------------------------------------------------------|
| `TraversalCostProvider` — `quote`, `reserve`, `commit` | The region thread that owns the portal. Blocking there stalls every portal in that region |
| `TraversalCostProvider` — `refund`   | The same region thread, except for `EXPIRED` (the region thread of whatever portal was used next) and `SERVER_SHUTDOWN` (the thread unloading the plugin). See [traversal-cost.md](traversal-cost.md#threading) |
| `WormholesPortalTraverseEvent`       | Same region thread, delivered inline during evaluation                     |
| `WormholesPortalTraversedEvent`      | The traveler's entity scheduler                                            |
| Placeholder resolution               | Whichever thread called PlaceholderAPI. The resolvers are non-blocking reads of a published snapshot |
| `IntegrationServiceContract` sampling | Whichever thread you call from. Wormholes answers from volatile counters, immutable snapshots and concurrent collections, and touches no entity, block or chunk. The contract's own rules still apply |

Nothing on any of these surfaces may block, sleep, do I/O, join a future, or take a lock it does not already
hold uncontended. Prime remote data on `PlayerJoinEvent` and cache it.

The rate-derived metric samples — bytes per second, drops per second, replicated blocks per second — are
recomputed at most once a second behind a short internal lock. Sampling faster returns the previous rate.

---

## Metrics: the integration service

Wormholes registers `art.arcane.volmlib.integration.IntegrationServiceContract` with the `ServicesManager` at
`ServicePriority.Normal`, reporting `pluginId()` as `wormholes`. This is how the React monitoring plugin reads
Wormholes without either plugin knowing about the other.

The contract type ships in **VolmLib**, not in the Wormholes API jar. Consuming it means putting VolmLib on
your compile classpath, and it is a shared contract — read its own documentation for the handshake, the
heartbeat and the sample shapes. What Wormholes contributes to it:

- Protocol versions `1.0` and `1.1`.
- Capabilities `handshake`, `heartbeat`, `metrics`, `wormholes-projection-metrics`.
- Every metric key beginning `wormholes.` from the shared metric schema — portal and remote-portal counts,
  active projections and observers, render milliseconds, block changes and packets per second, spoofed
  entities, traversals per minute, connected peers and worst peer round-trip, wire bytes in and out,
  compression ratio, sideband queue depth and drops, view subscriptions and tracked entities, replicated
  blocks per second, resync requests, and transfers in flight and failed.

A sample whose backing subsystem has not started yet comes back unavailable with a reason string such as
`network-manager-not-ready` rather than a zero. Treat unavailable as "no answer", never as "none".

If you only want a handful of these numbers on a scoreboard, use the placeholders instead — same values,
no compile dependency.

---

## What has no API

Stated plainly, so nobody spends an afternoon looking:

- **Projection.** What a portal shows, how it is sampled, replicated and drawn, is entirely internal. There is
  no way to add a surface, tint one, or read what a viewer currently sees.
- **Portal creation and editing.** Portals are created with the wand and edited through the in-game menus.
  There is no programmatic portal registry, no create/delete call, and no event for either.
- **The cross-server wire protocol.** Peer links, fragmentation, compression and the sideband are internal and
  version-locked to the pair of servers speaking them.
- **Random teleport destination selection.** You can see the state through `%wormholes_rtp.state%` and you can
  price the trip through `TraversalKind.RANDOM_TELEPORT`, but you cannot supply, veto or read a destination.
- **Dimensional door pocket worlds.** No entry point.

All four kinds of trip — local, cross-server, random teleport and dimensional door — are distinguishable at
the traversal API through `TraversalKind`, so a provider can price or refuse each of them separately even
where it cannot configure them.
