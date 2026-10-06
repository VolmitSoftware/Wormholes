package art.arcane.wormholes.modded.client;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.plate.PlateBox;

public interface ClientPortalContent {
    PlateBox cells();

    int backingState();

    int paletteIdAt(int x, int y, int z);

    BlockEntitySample blockEntityAt(int x, int y, int z);
}
