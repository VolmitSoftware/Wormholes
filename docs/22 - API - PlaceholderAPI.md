# API - PlaceholderAPI

Wormholes publishes `%wormholes_…%` through PlaceholderAPI without requiring a Wormholes compile dependency. Full operator key tables, selection rules, vocabularies, formats, and failure matrix are in `12 - PlaceholderAPI.md`. This page covers integrator lifecycle, threading, and compile notes only.

## Compile and dependency

- Depend on PlaceholderAPI the usual way (`softdepend: [PlaceholderAPI]` or Paper optional dependency).
- Do **not** put Wormholes or `Wormholes-*-api.jar` on the classpath for placeholders alone.
- When Wormholes is absent, keys do not resolve; PlaceholderAPI leaves the original text.

## Lifecycle

1. Wormholes enable attempts registration if PlaceholderAPI is already enabled.
2. A `PluginEnableEvent` listener registers when PlaceholderAPI enables later.
3. Values stay `---` / `available=false` until the portal attendance pass publishes snapshots (~1 Hz).
4. Disable/unload unregisters the expansion and clears snapshots.
5. Expansion `persist()` is set; `/papi reload` does not drop it.

Discover keys at runtime: `/papi info wormholes`.

## Threading

`PlaceholderAPI.setPlaceholders` for Wormholes keys may run on any thread. Resolvers only read a `volatile` immutable server snapshot or a concurrent player map of immutable records; they never touch `Player`, entities, worlds, blocks, or chunks.

Caveats:

- Other expansions in the same string are not necessarily thread-safe; resolve Wormholes keys alone if off-region.
- Writing a scoreboard/boss bar/title after resolve still follows Folia entity/region ownership for that write.

Publish runs on Wormholes' attendance task; consumers never schedule it.

## Consumption pattern

1. Check `%wormholes_portal.available%` first (`true`/`false` only).
2. Treat `---` as a first-class unavailable value (e.g. unlinked destination vs no portal).
3. The batch `setPlaceholders(player, List.of(...))` overload keeps reads adjacent, but it is not an atomic multi-key snapshot; server and player records may publish between individual resolutions.
4. Switch on enum keys (`portal.state`, `rtp.state`, `peers.link`) with a `default` arm — vocabularies can grow; `---` is always possible for player/RTP keys.

Minimal resolve:

```java
String state = PlaceholderAPI.setPlaceholders(player, "%wormholes_portal.state%");
```

Adjacent multi-key HUD read:

```java
List<String> values = PlaceholderAPI.setPlaceholders(player, List.of(
    "%wormholes_portal.available%",
    "%wormholes_portal.name%",
    "%wormholes_portal.state%",
    "%wormholes_portal.distance%"
));

if (!Boolean.parseBoolean(values.get(0))) {
    return;
}
String name = values.get(1);
String state = values.get(2);
String distance = values.get(3);
```

The list call reduces consumer-side repetition but is still not an atomic snapshot; tolerate a nearby portal changing between fields and refresh on the next HUD interval.

## Not an API jar surface

Placeholder types live under `art.arcane.wormholes.papi` and VolmLib helpers; they are **not** in `Wormholes-*-api.jar`. Integrators only need PlaceholderAPI at runtime. For numeric metrics without string parsing, use `23 - API - Metrics & Integration Contract.md`.
