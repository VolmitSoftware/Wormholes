# Wormholes

Through-portal projection and traversal for Paper and Folia. Frame portals show the other side and move travelers; the same plugin covers random teleport portals, survival Dimensional Doors with pocket dimensions, and cross-server gateways.

## Documentation

Authoritative reference: the central [VolmitSoftware/docs](https://github.com/VolmitSoftware/docs/tree/master/wormholes) repository. Start at [00 - Overview](https://github.com/VolmitSoftware/docs/blob/master/wormholes/00-overview.md).

| Track | Range |
|-------|--------|
| Operators / players | `00`–`15` |
| Maintainers | `16` |
| Plugin developers (API) | `20`–`23` |

## Install

1. Java 25 on Paper, Purpur, or Folia **26.1.2–26.2** (`folia-supported: true`). Spigot 26.2 is a compile and runtime fallback.
2. Drop runtime `Wormholes-<version>.jar` from `./gradlew shadowJar` into
   `plugins/`.
3. First start needs access to the configured dependency repositories unless the
   SlimJar cache is already warm. SlimJar resolves and caches PacketEvents,
   bStats, TOML4J, Kyori, and zstd-jni.
4. Optional soft depends: PlaceholderAPI, Vault, Iris.
5. First boot writes `plugins/Wormholes/wormholes.toml` (schema 3).
6. `zstd-jni` 1.5.7-11 is also declared in `plugin.yml` `libraries`. JVM tip:
   `--enable-native-access=ALL-UNNAMED` enables native access without warnings.

Commands: `/wormholes` (aliases `/wh`, `/wormhole`). See [09 - Commands & Permissions](https://github.com/VolmitSoftware/docs/blob/master/wormholes/09-commands-permissions.md).

## Build

```bash
./gradlew build
./gradlew test
./gradlew shadowJar
./gradlew apiJar
```

Third-party integrators compile against `build/libs/*-api.jar` (`compileOnly`). See [20 - API - Getting Started](https://github.com/VolmitSoftware/docs/blob/master/wormholes/20-api-getting-started.md).

## License

See `LICENSE.md`.
