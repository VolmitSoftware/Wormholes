package art.arcane.wormholes.chunk.presend;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class BukkitChunkPreSendCapture {
    private final ChunkPreSendService<World, Player> service;
    private final ChunkPreSendOrigin<World, Player> origin;

    BukkitChunkPreSendCapture(
        ChunkPreSendService<World, Player> service,
        ChunkPreSendOrigin<World, Player> origin
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    public World sourceWorld() {
        return origin.sourceWorld();
    }

    public int sourceChunkX() {
        return origin.sourceChunkX();
    }

    public int sourceChunkZ() {
        return origin.sourceChunkZ();
    }

    public BukkitChunkPreSendTransaction preSend(Location destination) {
        Location target = Objects.requireNonNull(destination, "destination");
        World destinationWorld = target.getWorld();
        if (destinationWorld == null) {
            return null;
        }
        long started = System.nanoTime();
        ChunkPreSendTicket<World, Player> ticket = service.preSend(
            origin,
            destinationWorld,
            target.getBlockX(),
            target.getBlockZ()
        );
        PreSendTelemetry.record(origin.player(), ticket.outcome(), ticket.sentChunks(), ticket.plannedChunks(), System.nanoTime() - started);
        return ticket.outcome().delivered() ? new BukkitChunkPreSendTransaction(service, ticket) : null;
    }
}
