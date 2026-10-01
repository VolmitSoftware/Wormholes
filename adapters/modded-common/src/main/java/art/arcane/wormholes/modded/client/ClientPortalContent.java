package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.plate.PlateBox;

public interface ClientPortalContent {
    PlateBox cells();

    int backingState();

    int paletteIdAt(int x, int y, int z);

    BlockEntitySample blockEntityAt(int x, int y, int z);
}
