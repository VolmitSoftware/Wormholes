package art.arcane.wormholes.portal.vanilla;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.DimensionalConfig;
import org.bukkit.World;

import java.util.List;

/**
 * Per-world coordinate scaling for vanilla portal pairing. A trip maps
 * {@code dest = src * (source scale / destination scale)}, so the shipped nether scale of 8 keeps
 * vanilla's 8:1 ratio while another world can use its own.
 */
public final class DimensionalScaling {
    public static final double DEFAULT_SCALE = 1.0D;
    public static final double MAX_COORDINATE = 30_000_000.0D;
    public static final int BORDER_MARGIN = 16;

    private DimensionalScaling() {
    }

    public static double scaleOf(World world) {
        return world == null ? DEFAULT_SCALE : scaleOf(WorldIdentity.serialize(world), configuredScales());
    }

    /** Entries are {@code <world key>:<scale>}; anything unparseable or non-positive is ignored. */
    public static double scaleOf(String worldKey, List<String> scales) {
        return DimensionalRouting.scaleOf(worldKey, scales);
    }

    /** Floored horizontal mapping between two scaled worlds. */
    public static int[] map(int sourceX, int sourceZ, double sourceScale, double destinationScale) {
        return DimensionalRouting.map(sourceX, sourceZ, sourceScale, destinationScale);
    }

    /** Keeps a mapped site inside the world border, with a margin so the frame itself still fits. */
    public static int[] clampToBorder(int[] coordinates, double borderRadius) {
        return DimensionalRouting.clampToBorder(coordinates, borderRadius);
    }

    /** The mapped and clamped counterpart site for a trip between two worlds. */
    public static int[] mapBetween(World source, World destination, int sourceX, int sourceZ) {
        int[] mapped = map(sourceX, sourceZ, scaleOf(source), scaleOf(destination));
        double radius = destination == null ? MAX_COORDINATE : destination.getWorldBorder().getSize() / 2.0D;
        return clampToBorder(mapped, radius);
    }

    static List<String> configuredScales() {
        WormholesSettings settings = Wormholes.settings;
        DimensionalConfig dimensional = settings == null ? null : settings.getDimensional();
        return dimensional == null || dimensional.scales == null ? List.of() : dimensional.scales;
    }
}
