package art.arcane.wormholes.access;

import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Published access guard, read by the construction, wand, vanilla-replacer, and linking paths. One
 * volatile read while the lane is not installed.
 */
public final class AccessGuards {
    private static volatile AccessGuard guard = AccessGuard.ALLOW;

    private AccessGuards() {
    }

    public static void install(AccessGuard installed) {
        guard = installed == null ? AccessGuard.ALLOW : installed;
    }

    public static void clear() {
        guard = AccessGuard.ALLOW;
    }

    public static boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
        return guard.allowConstruct(ownerId, cells, type);
    }

    public static boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind) {
        return guard.allowPlacement(actorId, world, cells, kind, "");
    }

    public static boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind, String subject) {
        return guard.allowPlacement(actorId, world, cells, kind, subject);
    }
}
