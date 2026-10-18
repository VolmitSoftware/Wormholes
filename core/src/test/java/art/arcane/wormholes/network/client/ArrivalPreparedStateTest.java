package art.arcane.wormholes.network.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalPreparedStateTest {
    private final UUID token = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private final ArrivalMessage.Prepare prepare = new ArrivalMessage.Prepare(token, 1, world,
        new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 0, false, false, 63, -64, 384),
        new TravelMessage.TravelPose(8, 64, 8, 0, 0), 1, "0123456789abcdef".repeat(4), 0, 0, 0, 0, 100);

    @Test
    void renderAcknowledgementCannotCommitBeforeAllChunksWereSent() {
        ArrivalPreparedState state = new ArrivalPreparedState(prepare, 1000);
        state.ready(new ArrivalMessage.Ready(token, 1, ArrivalMessage.Readiness.RENDER));
        assertFalse(state.commit(world, prepare.arrival(), 1001, false));
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                state.sent(x, z);
            }
        }
        assertFalse(state.commit(world, prepare.arrival(), 1001, false));
        state.ready(new ArrivalMessage.Ready(token, 1, ArrivalMessage.Readiness.DATA));
        assertFalse(state.commit(world, prepare.arrival(), 1001, false));
        state.ready(new ArrivalMessage.Ready(token, 1, ArrivalMessage.Readiness.RENDER));
        assertTrue(state.commit(world, prepare.arrival(), 1001, false));
        assertFalse(state.commit(world, prepare.arrival(), 1001, false));
    }

    @Test
    void changedWorldExpiredOrDirtyTerrainCannotBeCommitted() {
        assertFalse(ready().commit(UUID.randomUUID(), prepare.arrival(), 1001, false));
        assertFalse(ready().commit(world, prepare.arrival(), 7000, false));
        assertFalse(ready().commit(world, prepare.arrival(), 1001, true));
        assertFalse(ready().commit(world, new TravelMessage.TravelPose(40, 64, 8, 0, 0), 1001, false));
    }

    @Test
    void lateReadinessCannotReviveCancelledPreparation() {
        ArrivalPreparedState state = ready();
        state.cancel();
        state.ready(new ArrivalMessage.Ready(token, 1, ArrivalMessage.Readiness.RENDER));
        assertFalse(state.commit(world, prepare.arrival(), 1001, false));
    }

    private ArrivalPreparedState ready() {
        ArrivalPreparedState state = new ArrivalPreparedState(prepare, 1000);
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                state.sent(x, z);
            }
        }
        state.ready(new ArrivalMessage.Ready(token, 1, ArrivalMessage.Readiness.RENDER));
        return state;
    }
}
