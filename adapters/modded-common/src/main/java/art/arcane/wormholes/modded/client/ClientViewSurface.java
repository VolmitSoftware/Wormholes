package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import net.minecraft.world.level.block.state.BlockState;

public interface ClientViewSurface {
    int BORDER_WEST = 1;
    int BORDER_EAST = 1 << 1;
    int BORDER_DOWN = 1 << 2;
    int BORDER_UP = 1 << 3;
    int BORDER_NORTH = 1 << 4;
    int BORDER_SOUTH = 1 << 5;

    boolean chunkLoaded(int chunkX, int chunkZ);

    BlockState state(int x, int y, int z);

    void write(int x, int y, int z, BlockState state);

    void blockEntity(int x, int y, int z, BlockEntitySample sample);

    void attachLight(ClientLightPatches patches);

    void detachLight(ClientLightPatches patches);

    void lightChanged(int sectionX, int sectionY, int sectionZ, int boundaryMask);

    int skyDarken();

    void flush();
}
