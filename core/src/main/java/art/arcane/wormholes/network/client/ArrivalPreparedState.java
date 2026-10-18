package art.arcane.wormholes.network.client;

import java.util.BitSet;
import java.util.Objects;
import java.util.UUID;

public final class ArrivalPreparedState {
    private final ArrivalMessage.Prepare prepare;
    private final long deadlineMillis;
    private final BitSet sent;
    private boolean renderReady;
    private boolean closed;

    public ArrivalPreparedState(ArrivalMessage.Prepare prepare, long nowMillis) {
        this.prepare = Objects.requireNonNull(prepare, "prepare");
        this.deadlineMillis = nowMillis + prepare.lifetimeTicks() * 50L;
        this.sent = new BitSet(prepare.chunkCount());
    }

    public void sent(int x, int z) {
        if (!closed && prepare.contains(x, z)) {
            int width = prepare.radius() * 2 + 1;
            sent.set((z - prepare.centerZ() + prepare.radius()) * width + x - prepare.centerX() + prepare.radius());
        }
    }

    public void ready(ArrivalMessage.Ready ready) {
        if (closed || !prepare.token().equals(ready.token()) || prepare.revision() != ready.revision()) {
            return;
        }
        if (ready.readiness() == ArrivalMessage.Readiness.REJECTED) {
            cancel();
        } else if (sent.cardinality() == prepare.chunkCount() && ready.readiness() == ArrivalMessage.Readiness.RENDER) {
            renderReady = true;
        }
    }

    public boolean commit(UUID worldId, TravelMessage.TravelPose destination, long nowMillis, boolean dirty) {
        if (closed || dirty || nowMillis >= deadlineMillis || !renderReady || !prepare.worldId().equals(worldId)
            || ((int) Math.floor(destination.x()) >> 4) != prepare.centerX()
            || ((int) Math.floor(destination.z()) >> 4) != prepare.centerZ()
            || Math.abs(destination.y() - prepare.arrival().y()) > 8) {
            return false;
        }
        closed = true;
        return true;
    }

    public boolean expired(long nowMillis) {
        return nowMillis >= deadlineMillis;
    }

    public boolean renderReady(long nowMillis) {
        return renderReady && !closed && !expired(nowMillis);
    }

    public void cancel() {
        closed = true;
        renderReady = false;
    }
}
