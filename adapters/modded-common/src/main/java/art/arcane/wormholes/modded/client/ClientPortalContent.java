package art.arcane.wormholes.modded.client;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.BlockBox;

public interface ClientPortalContent {
    BlockBox cells();

    int backingState();

    int paletteIdAt(int x, int y, int z);

    BlockEntitySample blockEntityAt(int x, int y, int z);
}
