package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.Direction;

import java.util.List;

final class ClientTravelTestFixtures {
    private ClientTravelTestFixtures() {
    }

    static ClientPortalGeometry geometry() {
        return geometry(true, 63);
    }

    static ClientPortalGeometry geometry(boolean front, long mask) {
        return new ClientPortalGeometry(0, 0, 0, Direction.N.ordinal(), front, 0, false, 2, 3, new long[]{mask},
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0, 11, List.of());
    }
}
