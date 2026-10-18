package art.arcane.wormholes.chunk.presend;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BukkitChunkPreSendTransaction {
    private final ChunkPreSendService<World, Player> service;
    private final ChunkPreSendTicket<World, Player> ticket;
    private final AtomicBoolean terminal = new AtomicBoolean();

    BukkitChunkPreSendTransaction(
        ChunkPreSendService<World, Player> service,
        ChunkPreSendTicket<World, Player> ticket
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
    }

    public ChunkPreSendOutcome outcome() {
        return ticket.outcome();
    }

    public int sentChunks() {
        return ticket.sentChunks();
    }

    public int plannedChunks() {
        return ticket.plannedChunks();
    }

    public World sourceWorld() {
        return ticket.sourceWorld();
    }

    public int sourceChunkX() {
        return ticket.rollback().sourceCenterX();
    }

    public int sourceChunkZ() {
        return ticket.rollback().sourceCenterZ();
    }

    public ChunkPreSendRollbackOutcome rollback() {
        if (!terminal.compareAndSet(false, true)) {
            return ChunkPreSendRollbackOutcome.ALREADY_CONSUMED;
        }
        service.cancel(ticket);
        return ChunkPreSendRollbackOutcome.NOT_NEEDED;
    }

    public boolean commit() {
        if (!terminal.compareAndSet(false, true)) {
            return false;
        }
        boolean scheduled = service.complete(ticket);
        if (!scheduled) {
            service.cancel(ticket);
        }
        return scheduled;
    }

    public void cancel() {
        if (terminal.compareAndSet(false, true)) {
            service.cancel(ticket);
        }
    }
}
