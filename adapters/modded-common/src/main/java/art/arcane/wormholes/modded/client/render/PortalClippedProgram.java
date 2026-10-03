package art.arcane.wormholes.modded.client.render;

import java.util.Set;

public interface PortalClippedProgram {
    void wormholes$clipping(int program, int distance, Set<Integer> existingDistances);
}
