package art.arcane.wormholes.network.view;

import java.util.UUID;
import art.arcane.optics.entity.EntitySnapshot;

public final class EntitySendState {
    private final UUID entityId;
    private EntitySnapshot lastSentSnapshot;
    private int nextSequence;
    private boolean forceFullNext;
    private long lastFullSentTick;
    private long nextEligibleTick;

    public EntitySendState(UUID entityId) {
        this.entityId = entityId;
        this.lastSentSnapshot = null;
        this.nextSequence = 0;
        this.forceFullNext = true;
        this.lastFullSentTick = 0L;
        this.nextEligibleTick = 0L;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public EntitySnapshot getLastSentSnapshot() {
        return lastSentSnapshot;
    }

    public int getNextSequence() {
        return nextSequence;
    }

    public boolean isForceFullNext() {
        return forceFullNext;
    }

    public long getLastFullSentTick() {
        return lastFullSentTick;
    }

    public long getNextEligibleTick() {
        return nextEligibleTick;
    }

    public void setNextEligibleTick(long tick) {
        this.nextEligibleTick = tick;
    }

    public int allocateSequence() {
        int allocated = nextSequence;
        nextSequence = (nextSequence + 1) & 0xFFFF;
        return allocated;
    }

    public void recordSent(EntitySnapshot fullSnapshot, boolean wasFullMode, long entityTick) {
        this.lastSentSnapshot = fullSnapshot;
        if (wasFullMode) {
            this.forceFullNext = false;
            this.lastFullSentTick = entityTick;
        }
    }

    public boolean isSidebandFullDue(long entityTick, long baseIntervalTicks, long jitterTicks) {
        long jitter = Math.floorMod(entityId.hashCode(), Math.max(1L, jitterTicks));
        return entityTick - lastFullSentTick >= baseIntervalTicks + jitter;
    }

    public void requestFull() {
        this.forceFullNext = true;
    }

    public void reset() {
        this.lastSentSnapshot = null;
        this.nextSequence = 0;
        this.forceFullNext = true;
        this.lastFullSentTick = 0L;
        this.nextEligibleTick = 0L;
    }
}
