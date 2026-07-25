package art.arcane.wormholes.chunk.presend;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkPreSendTicket<W, P> {
    private final ChunkPreSendOutcome outcome;
    private final P player;
    private final W sourceWorld;
    private final ChunkPreSendRollback rollback;
    private final int sentChunks;
    private final boolean viewCenterAnnounced;
    private final AtomicBoolean consumed;

    ChunkPreSendTicket(
        ChunkPreSendOutcome outcome,
        P player,
        W sourceWorld,
        ChunkPreSendRollback rollback,
        int sentChunks,
        boolean viewCenterAnnounced
    ) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.player = player;
        this.sourceWorld = sourceWorld;
        this.rollback = Objects.requireNonNull(rollback, "rollback");
        this.sentChunks = sentChunks;
        this.viewCenterAnnounced = viewCenterAnnounced;
        this.consumed = new AtomicBoolean();
    }

    static <W, P> ChunkPreSendTicket<W, P> rejected(ChunkPreSendOutcome outcome, P player) {
        return new ChunkPreSendTicket<>(outcome, player, null, ChunkPreSendRollback.none(0, 0), 0, false);
    }

    public ChunkPreSendOutcome outcome() {
        return outcome;
    }

    public P player() {
        return player;
    }

    public W sourceWorld() {
        return sourceWorld;
    }

    public ChunkPreSendRollback rollback() {
        return rollback;
    }

    public int sentChunks() {
        return sentChunks;
    }

    public boolean viewCenterAnnounced() {
        return viewCenterAnnounced;
    }

    public boolean rollbackRequired() {
        return sourceWorld != null && viewCenterAnnounced;
    }

    boolean claim() {
        return consumed.compareAndSet(false, true);
    }
}
