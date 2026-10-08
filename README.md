# Wormholes

Portal projection, traversal, dimensional doors, and cross-server gateways for Bukkit, Fabric, Forge, and NeoForge.

The central [Wormholes manual](https://github.com/VolmitSoftware/docs/blob/master/wormholes/00-overview.md) covers [installation and builds](https://github.com/VolmitSoftware/docs/blob/master/wormholes/01-installation-configuration.md), [commands](https://github.com/VolmitSoftware/docs/blob/master/wormholes/09-commands-permissions.md), and [integration APIs](https://github.com/VolmitSoftware/docs/blob/master/wormholes/20-api-getting-started.md).

The projection library lives in the [Optics](https://github.com/VolmitSoftware/Optics) submodule at `optics/`; clone with `git clone --recurse-submodules` or run `git submodule update --init` in an existing checkout.

Build all platform distributions with Java 25 and `./gradlew buildAllToOut`. The jars are exported to the sibling `PluginOuts/` directory.

## Known issues

- On Fabric, Forge, and NeoForge clients, copies of players shown in a portal view that uses standard projection instead of the mod's own renderer still collide. They can push the real player, and between portals that are rotated relative to each other they reuse the real player's entity ID.
- Right after a crossing, the view through the arrival portal can take up to about 10 seconds to appear when the CPU is heavily loaded.
- On macOS with shaders enabled, the first approach to a newly activated portal stutters for a few seconds while the shaders compile, because the graphics driver compiles them on the render thread.

See [LICENSE.md](LICENSE.md).
