package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Portal access lists, ownership, claims, and limits. Changes hot-reload."
})
public class AccessConfig {
    @ConfigDescription("Keep honoring wormholes.portal.<sanitized portal name> as an alias for the stable permission key. Turn this off once every node has been renamed to the key shown in the Access menu.")
    public boolean legacyNameNodeEnabled = true;

    @ConfigDescription("Run the land-claim placement policy when a portal is built with the wand, a rune, or a replaced vanilla portal.")
    public boolean claimCheckOnConstruct = true;

    @ConfigDescription("Run the land-claim placement policy on the destination cells when a portal is linked.")
    public boolean claimCheckOnLink = true;

    @ConfigDescription("Run the land-claim placement policy when a traveler uses a portal. Off by default; results are cached per portal and player for five seconds.")
    public boolean claimCheckOnUse = false;

    @ConfigDescription("A portal that already stands keeps working when its claim plugin is installed but cannot be asked. Building and linking still fail closed in that case.")
    public boolean grandfatherExistingPortals = true;

    @ConfigDescription("Portals a player may own without a wormholes.limit.<n> permission. 0 is unlimited.")
    public int portalLimitDefault = 0;

    @ConfigDescription("Consult the wormholes-create, wormholes-use, wormholes-link, and wormholes-arrive WorldGuard region flags. They are registered while WorldGuard is still loading; turn this off, or run a setup where Wormholes loaded after WorldGuard closed its flag registry, and the adapter reads the built-in build and interact flags instead.")
    public boolean worldguardFlagsEnabled = true;

    @ConfigDescription("Claim plugins consulted, in order, as a comma-separated list. Known adapters: worldguard, griefprevention, towny, lands, plotsquared. Remove one to stop consulting it.")
    public String claimAdapters = "worldguard,griefprevention,towny,lands,plotsquared";
}
