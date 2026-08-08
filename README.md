# Wormholes

Through-portal projection and traversal for Paper and Folia. Frame portals show the other side and move travelers; the same plugin covers random teleport portals, survival Dimensional Doors with pocket dimensions, and cross-server gateways.

## Documentation

Authoritative reference: flat numbered docs under [`docs/`](docs/). Start at [`docs/00 - Overview.md`](docs/00%20-%20Overview.md).

| Track | Range |
|-------|--------|
| Operators / players | `00`–`15` |
| Maintainers | `16` |
| Plugin developers (API) | `20`–`23` |

## Install

1. Java 25 on Paper, a Paper-compatible derivative such as Purpur, or Folia.
2. Drop the shaded jar from `./gradlew shadowJar` into `plugins/`.
3. Optional soft depends: PlaceholderAPI, Vault, Iris.
4. First boot writes `plugins/Wormholes/config/wormholes.toml` (schema 2).
5. JVM tip: `--enable-native-access=ALL-UNNAMED` for zstd-jni native access without warnings.

Commands: `/wormholes` (aliases `/wh`, `/wormhole`). See `docs/09 - Commands & Permissions.md`.

## Build

```bash
./gradlew build
./gradlew test
./gradlew shadowJar
./gradlew apiJar
```

Third-party integrators compile against `build/libs/*-api.jar` (`compileOnly`). See `docs/20 - API - Getting Started.md`.

## License

See `LICENSE.md`.
