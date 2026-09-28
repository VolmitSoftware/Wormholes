package art.arcane.wormholes.portal.vanilla;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.DimensionalConfig;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;

/**
 * World-group pairing for vanilla portal replacement. With no groups configured every world pairs
 * with every other; with groups, a portal only pairs inside its own group, and a world listed alone
 * with itself has its vanilla portals disabled.
 */
public final class WorldGroups {
    private WorldGroups() {
    }

    public static Optional<String> groupOf(World world) {
        return world == null ? Optional.empty() : groupOf(world.getName(), configuredGroups());
    }

    public static Optional<String> groupOf(String worldName, List<String> groups) {
        return DimensionalRouting.groupOf(worldName, groups);
    }

    public static boolean canPair(World a, World b) {
        return a != null && b != null && canPair(a.getName(), b.getName(), configuredGroups());
    }

    public static boolean canPair(String a, String b, List<String> groups) {
        return DimensionalRouting.canPair(a, b, groups);
    }

    public static boolean isDisabled(World world) {
        return world != null && isDisabled(world.getName(), configuredGroups());
    }

    /** A group that names one world twice is the operator saying "no vanilla portals here". */
    public static boolean isDisabled(String worldName, List<String> groups) {
        return DimensionalRouting.isDisabled(worldName, groups);
    }

    static List<String> configuredGroups() {
        WormholesSettings settings = Wormholes.settings;
        DimensionalConfig dimensional = settings == null ? null : settings.getDimensional();
        return dimensional == null || dimensional.groups == null ? List.of() : dimensional.groups;
    }
}
