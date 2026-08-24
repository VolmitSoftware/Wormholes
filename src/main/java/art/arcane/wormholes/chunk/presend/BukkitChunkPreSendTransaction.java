package art.arcane.wormholes.chunk.presend;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class BukkitChunkPreSendTransaction {
    private final ChunkPreSendService<World, Player> service;
    private final ChunkPreSendTicket<World, Player> ticket;

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
        return service.rollback(ticket);
    }

    public boolean commit() {
        return ticket.claim();
    }
}
