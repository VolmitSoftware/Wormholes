package art.arcane.wormholes.render.view;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;

public interface ProjectionWorldView extends ProjectionContentView<BlockData, Material> {

    World getWorld();

    default UUID worldId() {
        World world = getWorld();
        return world == null ? null : world.getUID();
    }

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

    static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

}
