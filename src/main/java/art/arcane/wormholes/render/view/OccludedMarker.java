package art.arcane.wormholes.render.view;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

public final class OccludedMarker {
    public static final String STATE_STRING = "wormholes:occluded";
    private static final String STAND_IN_STATE = "minecraft:stone";

    private static volatile BlockData standIn;

    private OccludedMarker() {
    }

    public static BlockData standIn() {
        BlockData local = standIn;
        if (local != null) {
            return local;
        }
        synchronized (OccludedMarker.class) {
            if (standIn == null) {
                standIn = createStandIn();
            }
            return standIn;
        }
    }

    public static boolean isStandIn(BlockData data) {
        return data != null && data == standIn;
    }

    public static boolean isSentinelState(String stateString) {
        return STATE_STRING.equals(stateString);
    }

    public static boolean isOccluding(BlockData data) {
        if (data == null) {
            return false;
        }
        Material material = data.getMaterial();
        return material != null && !ProjectionWorldView.isAir(material) && material.isOccluding();
    }

    private static BlockData createStandIn() {
        try {
            return Bukkit.createBlockData(STAND_IN_STATE);
        } catch (RuntimeException e) {
            return Material.STONE.createBlockData();
        }
    }
}
