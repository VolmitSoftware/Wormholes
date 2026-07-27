package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.platform.WormholesPlatform;

import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class ViewBulkPipeline {
    private record BulkCompleteKey(UUID subscriptionId, String peerName) {
    }

    private final ViewSessionRegistry registry;
    private final ViewTimeDelivery timeDelivery;
    private final Map<BlockData, String> blockDataStrings = new ConcurrentHashMap<>();
    private final ChunkBulkBuilder chunkBulkBuilder;
    private final BulkRetryCoordinator<ViewServer.BulkRetryKey> bulkRetryCoordinator =
        new BulkRetryCoordinator<>(ViewServer.MAX_BULK_RETRY_DELAY_TICKS);
    private final Set<BulkCompleteKey> bulkCompleteRetries = ConcurrentHashMap.newKeySet();

    ViewBulkPipeline(ViewSessionRegistry registry, ViewTimeDelivery timeDelivery) {
        this.registry = registry;
        this.timeDelivery = timeDelivery;
        this.chunkBulkBuilder = new ChunkBulkBuilder(blockDataStrings);
    }

    void clear() {
        bulkRetryCoordinator.clear();
        bulkCompleteRetries.clear();
    }

    void onChunkResyncRequest(String peerName, ChunkResyncRequest request) {
        ChunkReplicationManager replication = registry.replication();
        ReplicationStreamKey stream = request.stream();
        if (!replication.isBulked(peerName, stream)) {
            // The initial bulk for this chunk is still in flight; an early diff merely outran it. The
            // pending bulk will deliver current state, so do NOT re-bulk from a (stale) fresh snapshot
            // here -- that is the spurious resync loop that was clobbering live block edits.
            return;
        }
        replication.requestResync(peerName, stream);
        ViewSession session = registry.get(stream.portalId());
        if (!matches(session, peerName, stream)) {
            return;
        }
        int chunkX = (int) (stream.chunkKey() >> 32);
        int chunkZ = (int) stream.chunkKey();
        sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ);
    }

    void retryCanonicalBulk(String peerName, ReplicationStreamKey stream) {
        if (!registry.isActive()) {
            return;
        }
        ViewSession session = registry.get(stream.portalId());
        if (!matches(session, peerName, stream)) {
            return;
        }
        int chunkX = (int) (stream.chunkKey() >> 32);
        int chunkZ = (int) stream.chunkKey();
        sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ);
    }

    CompletableFuture<Boolean> sendInitialBulkWithRetry(ViewSession session, String peerName, int chunkX, int chunkZ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        continueInitialBulkRetry(session, peerName, chunkX, chunkZ, result);
        return result;
    }

    void sendBulkCompleteWithRetry(ViewSession session, String peerName) {
        BulkCompleteKey key = new BulkCompleteKey(session.subscriptionId, peerName);
        if (!bulkCompleteRetries.add(key)) {
            return;
        }
        attemptBulkComplete(session, peerName, key);
    }

    private void continueInitialBulkRetry(ViewSession session, String peerName, int chunkX, int chunkZ, CompletableFuture<Boolean> result) {
        if (result.isDone()) {
            return;
        }
        ChunkReplicationManager replication = registry.replication();
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        ReplicationStreamKey stream = session.streamFor(chunkKey);
        if (!registry.isSessionChunkActive(session, peerName, chunkKey)) {
            result.complete(false);
            return;
        }
        if (replication.isBulked(peerName, stream)) {
            result.complete(true);
            return;
        }
        long bulkGeneration = replication.bulkGeneration(peerName, stream);
        if (bulkGeneration < 0L) {
            result.complete(false);
            return;
        }
        ViewServer.BulkRetryKey key = new ViewServer.BulkRetryKey(session.subscriptionId, peerName, stream, bulkGeneration);
        CompletableFuture<Boolean> generationResult = bulkRetryCoordinator.run(
            key,
            () -> registry.isSessionChunkActive(session, peerName, chunkKey)
                && replication.bulkGeneration(peerName, stream) == bulkGeneration
                && !replication.isBulked(peerName, stream),
            () -> sendInitialBulk(session, peerName, chunkX, chunkZ, bulkGeneration),
            (retry, delayTicks) -> FoliaScheduler.runAsync(Wormholes.instance, retry, delayTicks)
        );
        generationResult.whenComplete((accepted, error) -> {
            if (result.isDone()) {
                return;
            }
            if (!registry.isSessionChunkActive(session, peerName, chunkKey)) {
                result.complete(false);
                return;
            }
            if (replication.isBulked(peerName, stream)) {
                result.complete(true);
                return;
            }
            long currentGeneration = replication.bulkGeneration(peerName, stream);
            if (currentGeneration != bulkGeneration && currentGeneration >= 0L) {
                continueInitialBulkRetry(session, peerName, chunkX, chunkZ, result);
                return;
            }
            result.complete(false);
        });
    }

    private CompletableFuture<Boolean> sendInitialBulk(ViewSession session, String peerName, int chunkX, int chunkZ, long bulkGeneration) {
        ChunkReplicationManager replication = registry.replication();
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        ReplicationStreamKey stream = session.streamFor(chunkKey);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (!registry.isSessionChunkActive(session, peerName, chunkKey)
            || replication.bulkGeneration(peerName, stream) != bulkGeneration) {
            done.complete(false);
            return done;
        }
        WormholesPlatform.loadChunk(Wormholes.instance, session.world, chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null || chunk == null || !registry.isSessionChunkActive(session, peerName, chunkKey)) {
                done.complete(false);
                return;
            }
            boolean snapshotScheduled = FoliaScheduler.runRegion(Wormholes.instance, session.world, chunkX, chunkZ, () -> {
                if (!registry.isSessionChunkActive(session, peerName, chunkKey)) {
                    done.complete(false);
                    return;
                }
                ChunkSnapshot snapshot = WormholesPlatform.chunkSnapshot(chunk, false, true, false, true);
                boolean encodeScheduled = FoliaScheduler.runAsync(Wormholes.instance, () -> {
                    try {
                        if (!registry.isSessionChunkActive(session, peerName, chunkKey)) {
                            done.complete(false);
                            return;
                        }
                        ViewSlice slice = chunkBulkBuilder.buildSlice(session.box, chunkX, chunkZ, snapshot, session.renderMode);
                        if (slice == null) {
                            done.complete(registry.isSessionChunkActive(session, peerName, chunkKey));
                            return;
                        }
                        byte[] payload;
                        try {
                            payload = ChunkBulkBuilder.encodeSliceBytes(slice);
                        } catch (IOException e) {
                            Wormholes.v("net: failed to encode chunk bulk for " + peerName + " (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                            done.complete(false);
                            return;
                        }
                        if (!registry.isSessionChunkActive(session, peerName, chunkKey)) {
                            done.complete(false);
                            return;
                        }
                        boolean accepted = replication.sendBulk(peerName, session.subscriptionId, stream, payload, slice.contentHash(), bulkGeneration);
                        done.complete(accepted);
                    } catch (Throwable errorDuringBulk) {
                        done.complete(false);
                    }
                });
                if (!encodeScheduled) {
                    done.complete(false);
                }
            });
            if (!snapshotScheduled) {
                done.complete(false);
            }
        });
        return done;
    }

    private void attemptBulkComplete(ViewSession session, String peerName, BulkCompleteKey key) {
        if (!registry.isSessionPeerActive(session, peerName)) {
            bulkCompleteRetries.remove(key);
            return;
        }
        ViewServer.TimeDeliveryState timeState = session.timeDeliveryStates.get(peerName);
        if (timeState == null || !timeState.hasAcceptedInitial()) {
            if (timeState != null) {
                timeDelivery.start(session, peerName, timeState);
            }
            scheduleBulkCompleteRetry(session, peerName, key);
            return;
        }
        ChunkReplicationManager replication = registry.replication();
        if (replication.sendWhenAllBulked(peerName, session.subscriptionId, session.streamKeys,
            () -> registry.network().send(peerName, new WireMessage.ViewBulkComplete(session.portalId)))) {
            bulkCompleteRetries.remove(key);
            return;
        }
        scheduleBulkCompleteRetry(session, peerName, key);
    }

    private static boolean matches(ViewSession session, String peerName, ReplicationStreamKey stream) {
        return session != null
            && session.peers.contains(peerName)
            && session.world.getUID().equals(stream.sourceWorldId())
            && session.renderMode == stream.renderMode()
            && session.containsChunk((int) (stream.chunkKey() >> 32), (int) stream.chunkKey());
    }

    private void scheduleBulkCompleteRetry(ViewSession session, String peerName, BulkCompleteKey key) {
        boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance,
            () -> attemptBulkComplete(session, peerName, key), ViewServer.BULK_COMPLETE_RETRY_DELAY_TICKS);
        if (!scheduled) {
            bulkCompleteRetries.remove(key);
        }
    }
}
