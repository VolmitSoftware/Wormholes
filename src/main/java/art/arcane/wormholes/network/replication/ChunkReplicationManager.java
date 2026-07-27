package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.view.OccludedMarker;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class ChunkReplicationManager implements BlockChangeFeed {
    public record ReplicationConfig(long maxQueuedDiffsPerPeer) {
        public static ReplicationConfig defaults() {
            return new ReplicationConfig(4096L);
        }
    }

    public record Stats(long bulkSent, long diffsSent, long blocksSent, long resyncRequests, long preShipBulksDeferred) {
    }

    @FunctionalInterface
    public interface ChunkEvictionListener {
        void onChunkEvicted(UUID worldId, long chunkKey);
    }

    @FunctionalInterface
    public interface BulkRetryListener {
        void onBulkRetryRequired(String peerName, ReplicationStreamKey stream);
    }

    private static final class CanonicalHashCache {
        private volatile long hash;

        private CanonicalHashCache() {
            this.hash = 0L;
        }
    }

    private static final class PreShipState {
        private boolean promoted;

        private PreShipState() {
            this.promoted = false;
        }
    }

    private record SubscriptionRef(UUID subscriptionId, boolean preShip) {
    }

    private record PeerStreamKey(String peerName, ReplicationStreamKey stream) {
    }

    private final NetworkManager network;
    private volatile ReplicationConfig config;
    private volatile ChunkEvictionListener evictionListener;
    private volatile BulkRetryListener bulkRetryListener;
    private final Map<String, Map<ReplicationStreamKey, ChunkReplicationState>> peerStates = new ConcurrentHashMap<>();
    private final Map<String, Map<ReplicationStreamKey, Set<SubscriptionRef>>> peerSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>>> worldSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Map<ReplicationStreamKey, CanonicalHashCache>> peerHashes = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Map<ReplicationStreamKey, PreShipState>>> peerPreShip = new ConcurrentHashMap<>();
    private final Map<String, Object> peerGates = new ConcurrentHashMap<>();
    private final AtomicLong bulkSent = new AtomicLong();
    private final AtomicLong diffsSent = new AtomicLong();
    private final AtomicLong blocksSent = new AtomicLong();
    private final AtomicLong resyncRequests = new AtomicLong();
    private final AtomicLong preShipBulksDeferred = new AtomicLong();

    public ChunkReplicationManager(NetworkManager network, ReplicationConfig config) {
        this.network = network;
        this.config = config == null ? ReplicationConfig.defaults() : config;
    }

    public void applyConfig(ReplicationConfig next) {
        this.config = next == null ? ReplicationConfig.defaults() : next;
    }

    public void setEvictionListener(ChunkEvictionListener listener) {
        this.evictionListener = listener;
    }

    public void setBulkRetryListener(BulkRetryListener listener) {
        this.bulkRetryListener = listener;
    }

    public boolean hasVenticularSubscriber(World world, long chunkKey) {
        ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return false;
        }
        for (ChunkReplicationState state : subscribers.values()) {
            if (state.stream().renderMode() == ProjectionRenderMode.VENTICULAR) {
                return true;
            }
        }
        return false;
    }

    public ReplicationConfig config() {
        return config;
    }

    public boolean isBulked(String peerName, ReplicationStreamKey stream) {
        ChunkReplicationState state = stateFor(peerName, stream, false);
        return state != null && state.isBulkSent();
    }

    public boolean isSubscribed(String peerName, ReplicationStreamKey stream) {
        return stateFor(peerName, stream, false) != null;
    }

    public boolean isSubscribed(String peerName, UUID subscriptionId, ReplicationStreamKey stream) {
        synchronized (peerGate(peerName)) {
            return hasSubscriptionLocked(peerName, new SubscriptionRef(subscriptionId, false), stream);
        }
    }

    public void subscribe(String peerName, UUID subscriptionId, World world, ReplicationStreamKey stream) {
        if (subscriptionId == null || !validWorld(world, stream)) {
            return;
        }
        synchronized (peerGate(peerName)) {
            subscribeLocked(peerName, new SubscriptionRef(subscriptionId, false), world, stream);
        }
    }

    public void subscribePreShip(String peerName, UUID portalId, World world, ProjectionRenderMode renderMode, List<Long> chunkKeys) {
        if (peerName == null || portalId == null || world == null || renderMode == null || chunkKeys == null || chunkKeys.isEmpty()) {
            return;
        }
        synchronized (peerGate(peerName)) {
            Map<UUID, Map<ReplicationStreamKey, PreShipState>> portalMap = peerPreShip.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
            Map<ReplicationStreamKey, PreShipState> streamMap = portalMap.computeIfAbsent(portalId, ignored -> new ConcurrentHashMap<>());
            SubscriptionRef subscription = new SubscriptionRef(portalId, true);
            for (Long chunkKey : chunkKeys) {
                ReplicationStreamKey stream = new ReplicationStreamKey(portalId, world.getUID(), chunkKey.longValue(), renderMode);
                subscribeLocked(peerName, subscription, world, stream);
                streamMap.putIfAbsent(stream, new PreShipState());
                preShipBulksDeferred.incrementAndGet();
            }
        }
    }

    public void promotePreShip(String peerName, UUID portalId) {
        Map<UUID, Map<ReplicationStreamKey, PreShipState>> portalMap = peerPreShip.get(peerName);
        if (portalMap == null) {
            return;
        }
        Map<ReplicationStreamKey, PreShipState> streamMap = portalMap.get(portalId);
        if (streamMap == null) {
            return;
        }
        for (PreShipState state : streamMap.values()) {
            state.promoted = true;
        }
    }

    public void cancelPreShip(String peerName, UUID portalId) {
        synchronized (peerGate(peerName)) {
            Map<UUID, Map<ReplicationStreamKey, PreShipState>> portalMap = peerPreShip.get(peerName);
            if (portalMap == null) {
                return;
            }
            Map<ReplicationStreamKey, PreShipState> removed = portalMap.remove(portalId);
            if (removed == null) {
                return;
            }
            SubscriptionRef subscription = new SubscriptionRef(portalId, true);
            for (ReplicationStreamKey stream : removed.keySet()) {
                unsubscribeLocked(peerName, subscription, stream);
            }
            if (portalMap.isEmpty()) {
                peerPreShip.remove(peerName, portalMap);
            }
        }
    }

    public boolean isPreShipPromoted(String peerName, UUID portalId, ReplicationStreamKey stream) {
        Map<UUID, Map<ReplicationStreamKey, PreShipState>> portalMap = peerPreShip.get(peerName);
        if (portalMap == null) {
            return false;
        }
        Map<ReplicationStreamKey, PreShipState> streamMap = portalMap.get(portalId);
        if (streamMap == null) {
            return false;
        }
        PreShipState state = streamMap.get(stream);
        return state != null && state.promoted;
    }

    public void unsubscribe(String peerName, UUID subscriptionId, ReplicationStreamKey stream) {
        if (subscriptionId == null || stream == null) {
            return;
        }
        synchronized (peerGate(peerName)) {
            unsubscribeLocked(peerName, new SubscriptionRef(subscriptionId, false), stream);
        }
    }

    public void unsubscribeAll(String peerName, UUID subscriptionId, List<ReplicationStreamKey> streams) {
        if (subscriptionId == null) {
            return;
        }
        synchronized (peerGate(peerName)) {
            SubscriptionRef subscription = new SubscriptionRef(subscriptionId, false);
            for (ReplicationStreamKey stream : streams) {
                unsubscribeLocked(peerName, subscription, stream);
            }
        }
    }

    public void clearPeer(String peerName) {
        synchronized (peerGate(peerName)) {
            Map<ReplicationStreamKey, ChunkReplicationState> chunks = peerStates.remove(peerName);
            if (chunks != null) {
                for (Map.Entry<UUID, Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>>> worldEntry : worldSubscribers.entrySet()) {
                    Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap = worldEntry.getValue();
                    for (Map.Entry<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> entry : worldMap.entrySet()) {
                        ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> chunkPeers = entry.getValue();
                        boolean removed = chunkPeers.keySet().removeIf(key -> key.peerName().equals(peerName));
                        if (!removed) {
                            continue;
                        }
                        if (chunkPeers.isEmpty()) {
                            worldMap.remove(entry.getKey(), chunkPeers);
                            notifyEviction(worldEntry.getKey(), entry.getKey().longValue());
                        }
                    }
                }
            }
            peerHashes.remove(peerName);
            peerPreShip.remove(peerName);
            peerSubscriptions.remove(peerName);
        }
    }

    public boolean sendBulk(String peerName, UUID subscriptionId, ReplicationStreamKey stream, byte[] payload, long contentHash) {
        return sendBulk(peerName, subscriptionId, stream, payload, contentHash, -1L);
    }

    public boolean sendBulk(String peerName, UUID subscriptionId, ReplicationStreamKey stream, byte[] payload, long contentHash, long expectedGeneration) {
        synchronized (peerGate(peerName)) {
            SubscriptionRef subscription = new SubscriptionRef(subscriptionId, false);
            if (!hasSubscriptionLocked(peerName, subscription, stream)) {
                return false;
            }
            ChunkReplicationState state = stateFor(peerName, stream, false);
            if (state == null) {
                return false;
            }
            if (expectedGeneration >= 0L && state.bulkGeneration() != expectedGeneration) {
                return false;
            }
            if (state.isBulkSent()) {
                return false;
            }
            long sequence = state.nextBroadcastSeq();
            WireMessage.ChunkBulkBatch batch = new WireMessage.ChunkBulkBatch(List.of(new ChunkBulk(stream, sequence, payload)));
            if (!network.send(peerName, batch)) {
                return false;
            }
            state.markBulkSent();
            bulkSent.incrementAndGet();
            if (!state.hasPendingBlocks()) {
                cacheCanonicalHash(peerName, stream, contentHash);
            }
            return true;
        }
    }

    public boolean sendWhenAllBulked(String peerName, UUID subscriptionId, List<ReplicationStreamKey> streams, BooleanSupplier sender) {
        synchronized (peerGate(peerName)) {
            SubscriptionRef subscription = new SubscriptionRef(subscriptionId, false);
            for (ReplicationStreamKey stream : streams) {
                if (!hasSubscriptionLocked(peerName, subscription, stream)) {
                    return false;
                }
                ChunkReplicationState state = stateFor(peerName, stream, false);
                if (state == null || !state.isBulkSent()) {
                    return false;
                }
            }
            return sender.getAsBoolean();
        }
    }

    public long canonicalHash(String peerName, ReplicationStreamKey stream) {
        Map<ReplicationStreamKey, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap == null) {
            return 0L;
        }
        CanonicalHashCache cache = hashMap.get(stream);
        if (cache == null) {
            return 0L;
        }
        return cache.hash;
    }

    public long bulkGeneration(String peerName, ReplicationStreamKey stream) {
        ChunkReplicationState state = stateFor(peerName, stream, false);
        return state == null ? -1L : state.bulkGeneration();
    }

    public void requestResync(String peerName, ReplicationStreamKey stream) {
        synchronized (peerGate(peerName)) {
            ChunkReplicationState state = stateFor(peerName, stream, false);
            if (state == null) {
                return;
            }
            state.resetBulk();
            resyncRequests.incrementAndGet();
            Map<ReplicationStreamKey, CanonicalHashCache> hashMap = peerHashes.get(peerName);
            if (hashMap != null) {
                hashMap.remove(stream);
            }
        }
    }

    public void forceResync(World world, long chunkKey) {
        ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (ChunkReplicationState candidate : subscribers.values()) {
            String peerName = candidate.peerName();
            synchronized (peerGate(peerName)) {
                ReplicationStreamKey stream = candidate.stream();
                ChunkReplicationState state = stateFor(peerName, stream, false);
                if (state != candidate) {
                    continue;
                }
                invalidateForBulkResend(peerName, state);
                resyncRequests.incrementAndGet();
                notifyBulkRetry(peerName, stream);
            }
        }
    }

    @Override
    public void onChunkDrain(World world, long chunkKey, List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities) {
        ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        long capacity = config.maxQueuedDiffsPerPeer();
        boolean hasBlocks = blocks != null && !blocks.isEmpty();
        boolean hasLights = lights != null && !lights.isEmpty();
        boolean hasEntities = entities != null && !entities.isEmpty();
        if (!hasBlocks && !hasLights && !hasEntities) {
            return;
        }
        for (ChunkReplicationState candidate : subscribers.values()) {
            String peerName = candidate.peerName();
            synchronized (peerGate(peerName)) {
                ReplicationStreamKey stream = candidate.stream();
                ChunkReplicationState state = stateFor(peerName, stream, false);
                if (state != candidate) {
                    continue;
                }
                boolean overflowed = false;
                if (hasBlocks) {
                    boolean venticular = stream.renderMode() == ProjectionRenderMode.VENTICULAR;
                    for (int i = 0; i < blocks.size(); i++) {
                        if (!state.appendBlock(transformForPeer(blocks.get(i), venticular), capacity)) {
                            overflowed = true;
                            break;
                        }
                    }
                    markHashDirty(peerName, stream);
                }
                if (!overflowed && hasLights) {
                    for (int i = 0; i < lights.size(); i++) {
                        if (!state.appendLight(lights.get(i), capacity)) {
                            overflowed = true;
                            break;
                        }
                    }
                }
                if (!overflowed && hasEntities) {
                    for (int i = 0; i < entities.size(); i++) {
                        if (!state.appendBlockEntity(entities.get(i), capacity)) {
                            overflowed = true;
                            break;
                        }
                    }
                }
                if (overflowed) {
                    invalidateForBulkResend(peerName, state);
                    resyncRequests.incrementAndGet();
                    notifyBulkRetry(peerName, stream);
                }
            }
        }
    }

    @Override
    public void onTickEnd() {
        flushTick();
    }

    public void flushTick() {
        for (Map.Entry<String, Map<ReplicationStreamKey, ChunkReplicationState>> peerEntry : peerStates.entrySet()) {
            String peerName = peerEntry.getKey();
            synchronized (peerGate(peerName)) {
                Map<ReplicationStreamKey, ChunkReplicationState> streams = peerEntry.getValue();
                if (peerStates.get(peerName) != streams) {
                    continue;
                }
                List<ChunkDiffBatch> batches = new ArrayList<>();
                List<ChunkReplicationState> drainedStates = new ArrayList<>();
                int blockTotal = 0;
                for (ChunkReplicationState state : streams.values()) {
                    if (!state.isBulkSent()) {
                        continue;
                    }
                    if (state.queuedDiffCount() == 0L) {
                        continue;
                    }
                    ChunkReplicationState.DrainResult drained = state.drain();
                    if (drained.isEmpty()) {
                        continue;
                    }
                    long sequence = state.nextBroadcastSeq();
                    batches.add(new ChunkDiffBatch(state.stream(), sequence, drained.blocks(), drained.lights(), drained.entities()));
                    drainedStates.add(state);
                    blockTotal += drained.blocks().size();
                }
                if (batches.isEmpty()) {
                    continue;
                }
                WireMessage.ChunkDiff message = new WireMessage.ChunkDiff(batches);
                if (!network.send(peerName, message)) {
                    for (ChunkReplicationState state : drainedStates) {
                        invalidateForBulkResend(peerName, state);
                        notifyBulkRetry(peerName, state.stream());
                    }
                    continue;
                }
                diffsSent.incrementAndGet();
                blocksSent.addAndGet(blockTotal);
            }
        }
    }

    public List<ReplicationStreamKey> streamsFor(String peerName) {
        Map<ReplicationStreamKey, ChunkReplicationState> streams = peerStates.get(peerName);
        if (streams == null || streams.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(streams.keySet());
    }

    public List<Long> subscribedChunkKeys(UUID worldId) {
        Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap = worldSubscribers.get(worldId);
        if (worldMap == null || worldMap.isEmpty()) {
            return List.of();
        }
        List<Long> keys = new ArrayList<>(worldMap.size());
        for (Map.Entry<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> entry : worldMap.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    public long lastBroadcastSeq(String peerName, ReplicationStreamKey stream) {
        ChunkReplicationState state = stateFor(peerName, stream, false);
        return state == null ? 0L : state.lastBroadcastSeq();
    }

    public Stats statsSnapshot() {
        return new Stats(bulkSent.get(), diffsSent.get(), blocksSent.get(), resyncRequests.get(), preShipBulksDeferred.get());
    }

    public int peerCount() {
        return peerStates.size();
    }

    public int totalSubscriptionCount() {
        int total = 0;
        for (Map<ReplicationStreamKey, ChunkReplicationState> streams : peerStates.values()) {
            total += streams.size();
        }
        return total;
    }

    public boolean hasSubscribers(long chunkKey) {
        for (Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap : worldSubscribers.values()) {
            ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> chunkPeers = worldMap.get(chunkKey);
            if (chunkPeers != null && !chunkPeers.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSubscribers(World world, long chunkKey) {
        if (world == null) {
            return hasSubscribers(chunkKey);
        }
        Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap = worldSubscribers.get(world.getUID());
        if (worldMap == null) {
            return false;
        }
        ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> chunkPeers = worldMap.get(chunkKey);
        return chunkPeers != null && !chunkPeers.isEmpty();
    }

    private void notifyEviction(UUID worldId, long chunkKey) {
        ChunkEvictionListener listener = evictionListener;
        if (listener != null) {
            listener.onChunkEvicted(worldId, chunkKey);
        }
    }

    private void subscribeLocked(String peerName, SubscriptionRef subscription, World world, ReplicationStreamKey stream) {
        Map<ReplicationStreamKey, Set<SubscriptionRef>> peerStreams = peerSubscriptions.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
        Set<SubscriptionRef> subscriptions = peerStreams.computeIfAbsent(stream, ignored -> new HashSet<>());
        subscriptions.add(subscription);
        ChunkReplicationState state = stateFor(peerName, stream, true);
        registerWorldSubscriber(world, state);
    }

    private static BlockChange transformForPeer(BlockChange change, boolean venticular) {
        byte flags = change.flags();
        if ((flags & BlockChange.FLAG_OCCLUDED) == 0) {
            return change;
        }
        byte stripped = (byte) (flags & ~BlockChange.FLAG_OCCLUDED);
        String state = venticular ? OccludedMarker.STATE_STRING : change.state();
        return new BlockChange(change.packedXyz(), state, stripped);
    }

    private boolean hasSubscriptionLocked(String peerName, SubscriptionRef subscription, ReplicationStreamKey stream) {
        Map<ReplicationStreamKey, Set<SubscriptionRef>> peerStreams = peerSubscriptions.get(peerName);
        if (peerStreams == null) {
            return false;
        }
        Set<SubscriptionRef> subscriptions = peerStreams.get(stream);
        return subscriptions != null && subscriptions.contains(subscription);
    }

    private void unsubscribeLocked(String peerName, SubscriptionRef subscription, ReplicationStreamKey stream) {
        Map<ReplicationStreamKey, Set<SubscriptionRef>> peerStreams = peerSubscriptions.get(peerName);
        if (peerStreams == null) {
            return;
        }
        Set<SubscriptionRef> subscriptions = peerStreams.get(stream);
        if (subscriptions == null || !subscriptions.remove(subscription)) {
            return;
        }
        if (!subscriptions.isEmpty()) {
            return;
        }
        peerStreams.remove(stream);
        if (peerStreams.isEmpty()) {
            peerSubscriptions.remove(peerName, peerStreams);
        }
        Map<ReplicationStreamKey, ChunkReplicationState> streams = peerStates.get(peerName);
        if (streams == null || streams.remove(stream) == null) {
            return;
        }
        if (streams.isEmpty()) {
            peerStates.remove(peerName, streams);
        }
        Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap = worldSubscribers.get(stream.sourceWorldId());
        if (worldMap != null) {
            ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> chunkPeers = worldMap.get(stream.chunkKey());
            if (chunkPeers != null) {
                chunkPeers.remove(new PeerStreamKey(peerName, stream));
                if (chunkPeers.isEmpty()) {
                    worldMap.remove(stream.chunkKey(), chunkPeers);
                    notifyEviction(stream.sourceWorldId(), stream.chunkKey());
                }
            }
        }
        Map<ReplicationStreamKey, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap != null) {
            hashMap.remove(stream);
        }
    }

    private void invalidateForBulkResend(String peerName, ChunkReplicationState state) {
        state.resetBulk();
        Map<ReplicationStreamKey, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap != null) {
            hashMap.remove(state.stream());
        }
    }

    private void notifyBulkRetry(String peerName, ReplicationStreamKey stream) {
        BulkRetryListener listener = bulkRetryListener;
        if (listener != null) {
            listener.onBulkRetryRequired(peerName, stream);
        }
    }

    private ChunkReplicationState stateFor(String peerName, ReplicationStreamKey stream, boolean create) {
        if (create) {
            Map<ReplicationStreamKey, ChunkReplicationState> streams = peerStates.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
            return streams.computeIfAbsent(stream, key -> new ChunkReplicationState(peerName, key));
        }
        Map<ReplicationStreamKey, ChunkReplicationState> streams = peerStates.get(peerName);
        if (streams == null) {
            return null;
        }
        return streams.get(stream);
    }

    private void registerWorldSubscriber(World world, ChunkReplicationState state) {
        if (world == null) {
            return;
        }
        ReplicationStreamKey stream = state.stream();
        Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap = worldSubscribers.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> chunkPeers = worldMap.computeIfAbsent(stream.chunkKey(), ignored -> new ConcurrentHashMap<>());
        chunkPeers.put(new PeerStreamKey(state.peerName(), stream), state);
    }

    private ConcurrentHashMap<PeerStreamKey, ChunkReplicationState> subscribersFor(World world, long chunkKey) {
        if (world == null) {
            return null;
        }
        Map<Long, ConcurrentHashMap<PeerStreamKey, ChunkReplicationState>> worldMap = worldSubscribers.get(world.getUID());
        if (worldMap == null) {
            return null;
        }
        return worldMap.get(chunkKey);
    }

    private void cacheCanonicalHash(String peerName, ReplicationStreamKey stream, long hash) {
        Map<ReplicationStreamKey, CanonicalHashCache> hashMap = peerHashes.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
        CanonicalHashCache cache = hashMap.computeIfAbsent(stream, ignored -> new CanonicalHashCache());
        cache.hash = hash;
    }

    private void markHashDirty(String peerName, ReplicationStreamKey stream) {
        Map<ReplicationStreamKey, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap == null) {
            return;
        }
        CanonicalHashCache cache = hashMap.get(stream);
        if (cache != null) {
            cache.hash = 0L;
        }
    }

    private Object peerGate(String peerName) {
        return peerGates.computeIfAbsent(peerName, ignored -> new Object());
    }

    private static boolean validWorld(World world, ReplicationStreamKey stream) {
        return world != null && stream != null && world.getUID().equals(stream.sourceWorldId());
    }
}
