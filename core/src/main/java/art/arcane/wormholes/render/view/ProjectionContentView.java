package art.arcane.wormholes.render.view;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.plate.PlateBox;
import java.util.UUID;

public interface ProjectionContentView<B, M> extends ProjectionMaterialView<B, M> {
    int LIGHT_UNAVAILABLE = -1;

    default boolean isEmpty(PlateBox box) {
        return false;
    }

    UUID worldId();
    String sampleBiome(int x, int y, int z);
    BlockEntitySample sampleBlockEntity(int x, int y, int z);
    default boolean blockEntitiesComplete(int x, int z) {
        return true;
    }
    int getLight(int x, int y, int z);
    int getSkyDarken();
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
