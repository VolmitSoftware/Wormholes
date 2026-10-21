# Wormholes

Portal projection, traversal, dimensional doors, and cross-server gateways for Bukkit, Fabric, Forge, and NeoForge.

The central [Wormholes manual](https://github.com/VolmitSoftware/docs/blob/master/wormholes/00-overview.md) covers [installation and builds](https://github.com/VolmitSoftware/docs/blob/master/wormholes/01-installation-configuration.md), [commands](https://github.com/VolmitSoftware/docs/blob/master/wormholes/09-commands-permissions.md), and [integration APIs](https://github.com/VolmitSoftware/docs/blob/master/wormholes/20-api-getting-started.md).

The projection library lives in the [Optics](https://github.com/VolmitSoftware/Optics) submodule at `optics/`; clone with `git clone --recurse-submodules` or run `git submodule update --init` in an existing checkout.

Build the Bukkit distribution, API, proxy and every declared Fabric, Forge and NeoForge target with Java 25 and `./gradlew buildAll`. Artifacts stay in the producing module's `build/` directory. Use `./gradlew buildModdedVersions` for the mod matrix or `./gradlew checkAll` for every Bukkit, core, Optics, proxy and mod check. `buildFabric`, `buildForge` and `buildNeoforge` select the `minecraftVersion` Gradle property. Multiplexor reads `gradle/multiplexor.gradle` to build targets in separate source sandboxes and deliver selected artifacts to configured destinations.

## Known issues

These apply to players with the client mod. [ClientView](https://github.com/VolmitSoftware/docs/blob/master/wormholes/05-projection-modes-settings.md#clientview) describes world views and streamed views.

- Streamed views, which Paper, Purpur, and Folia servers use for every linked portal and other servers use for random teleport portals and cross-server gateways, are shaded with the Iris shader programs of the dimension you are in instead of the destination's, and get no shadow pass of their own.
- With an Iris shader pack, a streamed view shares temporal history with your own view, so TAA or motion blur can smear inside it. World views, including same-dimension portals and mirrors, keep separate histories.
- Portal views into another dimension show no particles from that dimension.
- `portal-edge-feather` draws its edge band only in streamed views.

See [LICENSE.md](LICENSE.md).
