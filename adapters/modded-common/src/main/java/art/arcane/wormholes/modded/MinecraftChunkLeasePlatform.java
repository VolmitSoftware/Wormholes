package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLeasePlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftChunkLeasePlatform implements ChunkLeasePlatform<ServerLevel> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final TicketType PORTAL_VIEW = new TicketType(TicketType.NO_TIMEOUT,
        TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final TicketType PORTAL_ARRIVAL = new TicketType(TicketType.NO_TIMEOUT,
        TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final TicketType PORTAL_HANDOVER = new TicketType(40L,
        TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final int ARRIVAL_RADIUS = 2;

    private final MinecraftServer server;

    public MinecraftChunkLeasePlatform(WormholesModRuntime runtime) {
        this.server = runtime.server();
    }

    public static void holdArrival(ServerLevel world, int chunkX, int chunkZ) {
        world.getChunkSource().addTicketWithRadius(PORTAL_ARRIVAL, new ChunkPos(chunkX, chunkZ), ARRIVAL_RADIUS);
    }

    public static void holdHandover(ServerLevel world, int chunkX, int chunkZ, int radius) {
        world.getChunkSource().addTicketWithRadius(PORTAL_HANDOVER, new ChunkPos(chunkX, chunkZ), Math.max(ARRIVAL_RADIUS, radius));
    }

    public static void releaseArrival(ServerLevel world, int chunkX, int chunkZ) {
        world.getChunkSource().removeTicketWithRadius(PORTAL_ARRIVAL, new ChunkPos(chunkX, chunkZ), ARRIVAL_RADIUS);
    }

    @Override
    public CompletionStage<Boolean> add(ServerLevel world, int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> world.getChunkSource()
                .addTicketAndLoadWithRadius(PORTAL_VIEW, new ChunkPos(chunkX, chunkZ), 0), server)
            .thenCompose(load -> load)
            .thenApplyAsync(ignored -> {
                if (world.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    throw new IllegalStateException("Portal view chunk did not load: " + chunkX + ", " + chunkZ);
                }
                return true;
            }, server);
    }

    @Override
    public CompletionStage<Boolean> remove(ServerLevel world, int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            world.getChunkSource().removeTicketWithRadius(PORTAL_VIEW, new ChunkPos(chunkX, chunkZ), 0);
            return true;
        }, server);
    }

    @Override
    public void reportFailure(Throwable error) {
        LOGGER.error("Wormholes chunk lease failed", error);
    }
}
