# Wormholes

Portal projection, traversal, dimensional doors, and cross-server gateways for Bukkit, Fabric, Forge, and NeoForge.

The central [Wormholes manual](https://github.com/VolmitSoftware/docs/blob/master/wormholes/00-overview.md) covers [installation and builds](https://github.com/VolmitSoftware/docs/blob/master/wormholes/01-installation-configuration.md), [commands](https://github.com/VolmitSoftware/docs/blob/master/wormholes/09-commands-permissions.md), and [integration APIs](https://github.com/VolmitSoftware/docs/blob/master/wormholes/20-api-getting-started.md).

The projection library lives in the [Optics](https://github.com/VolmitSoftware/Optics) submodule at `optics/`; clone with `git clone --recurse-submodules` or run `git submodule update --init` in an existing checkout.

Build all platform distributions with Java 25 and `./gradlew buildAllToOut`. The jars are exported to the sibling `PluginOuts/` directory.

## Known issues

These apply to players with the client mod. [ClientView](https://github.com/VolmitSoftware/docs/blob/master/wormholes/05-projection-modes-settings.md#clientview) describes world views and streamed views.

- Streamed views, which Paper, Purpur, and Folia servers use for every linked portal and other servers use for random teleport portals and cross-server gateways, are shaded with the Iris shader programs of the dimension you are in instead of the destination's, and get no shadow pass of their own.
- With an Iris shader pack, a streamed view or a view into the dimension you are in shares one shader pipeline with your own view, so temporal effects such as TAA or motion blur can smear inside it.
- Portal views into another dimension show no particles from that dimension.
- `portal-edge-feather` draws its edge band only in streamed views.

See [LICENSE.md](LICENSE.md).
