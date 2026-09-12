package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.access.PortalPlacementPolicy;

/** One claim plugin bridged reflectively, so the build never needs that plugin on the classpath. */
public interface ClaimAdapter extends PortalPlacementPolicy {
    /** Token used in the {@code claim-adapters} config list. */
    String id();

    /** Bukkit plugin name looked up to decide whether this adapter applies at all. */
    String pluginName();

    /**
     * True when the plugin protects individual blocks (regions, plots) and every cell has to be
     * asked; false when one representative cell per chunk is enough (chunk-granular claims).
     */
    boolean perCell();

    /** Drops cached class and method resolution, for example after the plugin disables. */
    void invalidate();
}
