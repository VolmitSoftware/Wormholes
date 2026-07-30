package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.LongSet;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.util.Direction;

final class ProjectorBlackoutSeal {
    private BlockData blackoutData;
    private BlackoutColor blackoutColorCache;
    private boolean enabled;
    private int thicknessBlocks;

    void beginPass(BlackoutColor blackoutColor) {
        enabled = true;
        thicknessBlocks = Settings.PROJECTION_BLACKOUT_SHELL_THICKNESS_BLOCKS;
        if (blackoutData == null || blackoutColor != blackoutColorCache) {
            blackoutData = parseBlackout(blackoutColor);
            blackoutColorCache = blackoutColor;
        }
    }

    void disable() {
        enabled = false;
    }

    boolean isEnabled() {
        return enabled;
    }

    BlockData data() {
        return blackoutData;
    }

    int thicknessBlocks() {
        return thicknessBlocks;
    }

    boolean sealsCell(long key,
                      LongSet geometry,
                      Direction normal,
                      Direction right,
                      Direction up,
                      int normalMin,
                      int normalMax) {
        return sealsGeometryCell(key, geometry, normal, right, up, normalMin, normalMax, thicknessBlocks);
    }

    boolean isSyntheticClaim(ProjectedBlockClaim claim) {
        return claim != null
            && claim.getLightView() == null
            && claim.getLightRemoteKey() == ProjectedBlockClaim.NO_REMOTE_KEY
            && !claim.isMaskAir();
    }

    private static BlockData parseBlackout(BlackoutColor color) {
        try {
            return Bukkit.createBlockData(color.blockState());
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
    }

    static boolean sealsGeometryCell(long key,
                                     LongSet geometry,
                                     Direction normal,
                                     Direction right,
                                     Direction up,
                                     int normalMin,
                                     int normalMax,
                                     int requestedThickness) {
        int x = ProjectionCellKey.unpackX(key);
        int y = ProjectionCellKey.unpackY(key);
        int z = ProjectionCellKey.unpackZ(key);
        int normalCoordinate = coordinate(x, y, z, normal);
        int normalSign = normal.x() + normal.y() + normal.z();
        int availableLayers = Math.max(0, normalMax - normalMin);
        int requested = clampThickness(requestedThickness);
        int thickness = Math.min(requested, availableLayers);
        int farCoordinate = normalSign > 0 ? normalMin : normalMax;
        int farOffset = (normalCoordinate - farCoordinate) * normalSign;
        if (farOffset >= 0 && farOffset < thickness) {
            return true;
        }

        int frontCoordinate = normalSign > 0 ? normalMax : normalMin;
        int depthFromFront = (frontCoordinate - normalCoordinate) * normalSign;
        int lateralThickness = Math.min(requested, Math.max(0, depthFromFront));
        for (int distance = 1; distance <= lateralThickness; distance++) {
            if (missing(geometry, x, y, z, right, distance)
                || missing(geometry, x, y, z, right, -distance)
                || missing(geometry, x, y, z, up, distance)
                || missing(geometry, x, y, z, up, -distance)) {
                return true;
            }
        }
        return false;
    }

    static boolean isFarLayer(int normalCoordinate,
                              int normalMin,
                              int normalMax,
                              int normalSign,
                              int requestedThickness) {
        int availableLayers = Math.max(0, normalMax - normalMin);
        int thickness = Math.min(clampThickness(requestedThickness), availableLayers);
        int farCoordinate = normalSign > 0 ? normalMin : normalMax;
        int farOffset = (normalCoordinate - farCoordinate) * normalSign;
        return farOffset >= 0 && farOffset < thickness;
    }

    private static boolean missing(LongSet geometry,
                                   int x,
                                   int y,
                                   int z,
                                   Direction direction,
                                   int distance) {
        return !geometry.contains(ProjectionCellKey.pack(
            x + (direction.x() * distance),
            y + (direction.y() * distance),
            z + (direction.z() * distance)));
    }

    private static int coordinate(int x, int y, int z, Direction direction) {
        return direction.x() != 0 ? x : direction.y() != 0 ? y : z;
    }

    private static int clampThickness(int thickness) {
        return Math.max(1, Math.min(2, thickness));
    }
}
