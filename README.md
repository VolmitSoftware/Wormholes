# Wormholes

Portal projection, traversal, dimensional doors, and cross-server gateways for Bukkit, Fabric, Forge, and NeoForge.

The central [Wormholes manual](https://github.com/VolmitSoftware/docs/blob/master/wormholes/00-overview.md) covers [installation and builds](https://github.com/VolmitSoftware/docs/blob/master/wormholes/01-installation-configuration.md), [commands](https://github.com/VolmitSoftware/docs/blob/master/wormholes/09-commands-permissions.md), and [integration APIs](https://github.com/VolmitSoftware/docs/blob/master/wormholes/20-api-getting-started.md).

The projection library lives in the [Optics](https://github.com/VolmitSoftware/Optics) submodule at `optics/`; clone with `git clone --recurse-submodules` or run `git submodule update --init` in an existing checkout.

Build all platform distributions with Java 25 and `./gradlew buildAllToOut`. The jars are exported to the sibling `PluginOuts/` directory.

See [LICENSE.md](LICENSE.md).
