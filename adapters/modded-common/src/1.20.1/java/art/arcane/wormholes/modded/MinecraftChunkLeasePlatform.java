package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLeasePlatform;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import com.mojang.datafixers.util.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftChunkLeasePlatform implements ChunkLeasePlatform<ServerLevel>, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final TicketType<ChunkPos> PORTAL_VIEW = TicketType.create("wormholes_portal_view", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<ChunkPos> PORTAL_ARRIVAL = TicketType.create("wormholes_portal_arrival", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<ChunkPos> PORTAL_HANDOVER = TicketType.create("wormholes_portal_handover", Comparator.comparingLong(ChunkPos::toLong), 40);
    private static final int ARRIVAL_RADIUS = 2;
    private static final int FULL_LEVEL = ChunkLevel.byStatus(ChunkStatus.FULL);

    private final MinecraftServer server;
    private final ChunkLoadRequests<ServerLevel> requests;

    public MinecraftChunkLeasePlatform(WormholesModRuntime runtime) {
        this.server = runtime.server();
        this.requests = new ChunkLoadRequests<>(new Chunks(server));
    }

    public static void holdArrival(ServerLevel world, int chunkX, int chunkZ) {
        world.getChunkSource().addRegionTicket(PORTAL_ARRIVAL, new ChunkPos(chunkX, chunkZ), ARRIVAL_RADIUS, new ChunkPos(chunkX, chunkZ));
    }

    public static void holdHandover(ServerLevel world, int chunkX, int chunkZ, int radius) {
        world.getChunkSource().addRegionTicket(PORTAL_HANDOVER, new ChunkPos(chunkX, chunkZ), Math.max(ARRIVAL_RADIUS, radius), new ChunkPos(chunkX, chunkZ));
    }

    public static void releaseArrival(ServerLevel world, int chunkX, int chunkZ) {
        world.getChunkSource().removeRegionTicket(PORTAL_ARRIVAL, new ChunkPos(chunkX, chunkZ), ARRIVAL_RADIUS, new ChunkPos(chunkX, chunkZ));
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
            requests.cancel(world, chunkX, chunkZ);
            world.getChunkSource().removeRegionTicket(PORTAL_VIEW, new ChunkPos(chunkX, chunkZ), 0, new ChunkPos(chunkX, chunkZ));
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
            world.getChunkSource().addRegionTicket(PORTAL_VIEW, new ChunkPos(chunkX, chunkZ), 0, new ChunkPos(chunkX, chunkZ));
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
        public CompletionStage<Boolean> load(ChunkLoadRequests.Request<ServerLevel> request) {
            ServerLevel world = request.world();
            int chunkX = request.chunkX();
            int chunkZ = request.chunkZ();
            ServerChunkCache chunks = world.getChunkSource();
            // On 1.20.1 this future completes inside the chunk sorter's mailbox; finishing inline there would let lease holders load
            // chunks synchronously and deadlock that mailbox, so the completion always runs as a separate server task.
            return holder(world, chunkX, chunkZ).getOrScheduleFuture(ChunkStatus.FULL, chunks.chunkMap)
                .thenApplyAsync(result -> finishLoad(request, chunks, result), task -> server.tell(new TickTask(server.getTickCount(), task)));
        }

        @Override
        public void reportFailure(Throwable failure) {
            LOGGER.error("Wormholes chunk lease failed", failure);
        }

        private static boolean finishLoad(ChunkLoadRequests.Request<ServerLevel> request, ServerChunkCache chunks,
                                          Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result) {
            int chunkX = request.chunkX();
            int chunkZ = request.chunkZ();
            if (result == ChunkHolder.UNLOADED_CHUNK) {
                throw new CancellationException("Portal view chunk load cancelled: " + chunkX + ", " + chunkZ);
            }
            if (result.left().isEmpty()) {
                throw new IllegalStateException("Portal view chunk load failed: " + chunkX + ", " + chunkZ + ": " + result.right().map(Object::toString).orElse("unknown"));
            }
            if (request.cancelled()) {
                throw new CancellationException("Portal view chunk request released: " + chunkX + ", " + chunkZ);
            }
            if (chunks.getChunkNow(chunkX, chunkZ) == null) {
                throw new IllegalStateException("Portal view chunk did not load: " + chunkX + ", " + chunkZ);
            }
            return true;
        }

        private static ChunkHolder holder(ServerLevel world, int chunkX, int chunkZ) {
            return ((SeamlessChunkMapAccess) world.getChunkSource().chunkMap).wormholesVisibleChunk(ChunkPos.asLong(chunkX, chunkZ));
        }
    }
}
