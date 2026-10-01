package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import net.minecraft.world.level.block.state.BlockState;

public interface ClientViewSurface {
    boolean chunkLoaded(int chunkX, int chunkZ);

    BlockState state(int x, int y, int z);

    void write(int x, int y, int z, BlockState state);

    void blockEntity(int x, int y, int z, BlockEntitySample sample);

    void attachLight(ClientLightPatches patches);

    void detachLight(ClientLightPatches patches);

    void lightChanged(int sectionX, int sectionY, int sectionZ);

    int skyDarken();

    void flush();
}
