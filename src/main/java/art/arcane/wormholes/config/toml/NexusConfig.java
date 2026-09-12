package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Portal networks, addresses, and dialing. Changes hot-reload."
})
public class NexusConfig {
    @ConfigDescription("Characters used when a portal is given an automatic network address. Ambiguous glyphs are left out on purpose.")
    public String addressAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @ConfigDescription("Characters per automatic network address.")
    public int addressLength = 4;

    @ConfigDescription("Shortest interval between projection swaps while a player cycles through addresses.")
    public int dialDebounceMillis = 400;

    @ConfigDescription("A manual dial reverts to the portal's default address after this many idle seconds. 0 keeps the dialed address.")
    public int dialHoldSeconds = 120;

    @ConfigDescription("Tick cadence of the scheduled and timed destination task.")
    public int schedulerIntervalTicks = 20;

    @ConfigDescription("Networks a player may create. Holders of wormholes.admin.nexus are unlimited.")
    public int maxNetworksPerPlayer = 4;

    @ConfigDescription("Portals one network may contain.")
    public int maxMembersPerNetwork = 256;

    @ConfigDescription("Let redstone next to a portal frame open, close, lock, and dial it, and drive comparator output.")
    public boolean redstoneEnabled = true;
}
