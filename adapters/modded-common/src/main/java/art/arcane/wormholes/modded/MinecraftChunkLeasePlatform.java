package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLeasePlatform;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftChunkLeasePlatform implements ChunkLeasePlatform<ServerLevel>, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final TicketType PORTAL_VIEW = new TicketType(TicketType.NO_TIMEOUT,
        TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final TicketType PORTAL_ARRIVAL = new TicketType(TicketType.NO_TIMEOUT,
        TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final TicketType PORTAL_HANDOVER = new TicketType(40L,
        TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final int ARRIVAL_RADIUS = 2;
    private static final int FULL_LEVEL = ChunkLevel.byStatus(ChunkStatus.FULL);

    private final MinecraftServer server;
    private final ChunkLoadRequests<ServerLevel> requests;

    public MinecraftChunkLeasePlatform(WormholesModRuntime runtime) {
        this.server = runtime.server();
        this.requests = new ChunkLoadRequests<>(new Chunks(server));
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

    public void tick() {
        requests.tick();
    }

    @Override
    public CompletionStage<Boolean> add(ServerLevel world, int chunkX, int chunkZ) {
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        server.execute(() -> requests.request(world, chunkX, chunkZ, ready));
        return ready;
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

    @Override
    public void close() {
        requests.close();
    }

    private record Chunks(MinecraftServer server) implements ChunkLoadRequests.Chunks<ServerLevel> {
        @Override
        public void hold(ServerLevel world, int chunkX, int chunkZ) {
            world.getChunkSource().addTicketWithRadius(PORTAL_VIEW, new ChunkPos(chunkX, chunkZ), 0);
        }

        @Override
        public boolean loaded(ServerLevel world, int chunkX, int chunkZ) {
            return world.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        }

        @Override
        public boolean scheduled(ServerLevel world, int chunkX, int chunkZ) {
            ChunkHolder holder = holder(world, chunkX, chunkZ);
            return holder != null && holder.getTicketLevel() <= FULL_LEVEL;
        }

        @Override
        public boolean present(ServerLevel world) {
            return server.getLevel(world.dimension()) == world;
        }

        @Override
        public CompletionStage<Boolean> load(ServerLevel world, int chunkX, int chunkZ) {
            ServerChunkCache chunks = world.getChunkSource();
            SeamlessChunkMapAccess map = (SeamlessChunkMapAccess) chunks.chunkMap;
            return map.wormholesChunkRangeFuture(holder(world, chunkX, chunkZ), 0, distance -> ChunkStatus.FULL)
                .thenApplyAsync(ignored -> {
                    if (chunks.getChunkNow(chunkX, chunkZ) == null) {
                        throw new IllegalStateException("Portal view chunk did not load: " + chunkX + ", " + chunkZ);
                    }
                    return true;
                }, server);
        }

        private static ChunkHolder holder(ServerLevel world, int chunkX, int chunkZ) {
            return ((SeamlessChunkMapAccess) world.getChunkSource().chunkMap).wormholesVisibleChunk(ChunkPos.pack(chunkX, chunkZ));
        }
    }
}
