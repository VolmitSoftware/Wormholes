package art.arcane.wormholes.render.view;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;

public interface ProjectionWorldView {
    int LIGHT_UNAVAILABLE = -1;

    World getWorld();

    int getMinHeight();

    int getMaxHeight();

    BlockData sampleBlockData(int x, int y, int z);

    default Material sampleMaterial(int x, int y, int z) {
        BlockData data = sampleBlockData(x, y, z);
        return data == null ? null : data.getMaterial();
    }

    String sampleBiome(int x, int y, int z);

    default BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        return null;
    }

    int getLight(int x, int y, int z);

    int getSkyDarken();

    default long getRevision() {
        return 0L;
    }

    default boolean isChunkReady(int x, int z) {
        return true;
    }

    default void requestChunk(int x, int z) {
    }

    static int computeSkyDarken(long dayTime) {
        return computeSkyDarken(dayTime, false, false);
    }

    static int computeSkyDarken(long dayTime, boolean storm, boolean thunder) {
        double d = (dayTime / 24000.0D) - 0.25D;
        d = d - Math.floor(d);
        double e = 0.5D - Math.cos(d * Math.PI) / 2.0D;
        double celestialAngle = (d * 2.0D + e) / 3.0D;
        double f = 1.0D - (Math.cos(celestialAngle * Math.PI * 2.0D) * 2.0D + 0.5D);
        f = Math.max(0.0D, Math.min(1.0D, f));
        double brightness = 1.0D - f;
        if (storm) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        if (thunder) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        return (int) ((1.0D - brightness) * 11.0D);
    }

    /** Applies the storm and thunder bands to a clear-sky darken value the same way {@link #computeSkyDarken(long, boolean, boolean)} does. */
    static int weatherDarken(int clearDarken, boolean storm, boolean thunder) {
        if (!storm && !thunder) {
            return clearDarken;
        }
        double brightness = 1.0D - (Math.max(0, Math.min(11, clearDarken)) / 11.0D);
        if (storm) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        if (thunder) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        return (int) ((1.0D - brightness) * 11.0D);
    }

    static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    static int packLight(int sky, int block) {
        return ((sky & 0x0F) << 4) | (block & 0x0F);
    }

    static int unpackSkyLight(int packed) {
        return (packed >> 4) & 0x0F;
    }

    static int unpackBlockLight(int packed) {
        return packed & 0x0F;
    }
}
