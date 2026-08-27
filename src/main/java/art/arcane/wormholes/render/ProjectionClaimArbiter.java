package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class ProjectionClaimArbiter {
    static final String BLOCK_MAPPING_FAILURE_REASON = "RENDER_CLAIM_BLOCK_MAPPING_FAILED";
    static final int DENSE_SECTION_SORT_THRESHOLD = 128;
    private static final int RADIX_BUCKET_BITS = 8;
    private static final int RADIX_BUCKET_COUNT = 1 << RADIX_BUCKET_BITS;
    private static final int RADIX_BUCKET_MASK = RADIX_BUCKET_COUNT - 1;

    private final ConcurrentHashMap<UUID, ObserverClaims> observers;
    private final ConcurrentHashMap<BlockData, Integer> blockGlobalIds;
    private final ProjectionWorldViewProvider viewProvider;
    private final ProjectionChunkVisibility chunkVisibility;
    private final LightingFactory lightingFactory;

    public ProjectionClaimArbiter() {
        this(ProjectionWorldViewProvider.live());
    }

    public ProjectionClaimArbiter(ProjectionWorldViewProvider viewProvider) {
        this(viewProvider, WormholesPlatform::isChunkSent);
    }

    public ProjectionClaimArbiter(ProjectionWorldViewProvider viewProvider, ProjectionChunkVisibility chunkVisibility) {
        this(viewProvider, chunkVisibility, () -> new ProjectorLighting(chunkVisibility));
    }

    ProjectionClaimArbiter(ProjectionWorldViewProvider viewProvider,
                           ProjectionChunkVisibility chunkVisibility,
                           LightingFactory lightingFactory) {
        this.observers = new ConcurrentHashMap<UUID, ObserverClaims>();
        this.blockGlobalIds = new ConcurrentHashMap<BlockData, Integer>();
        this.viewProvider = viewProvider;
        this.chunkVisibility = chunkVisibility;
        this.lightingFactory = lightingFactory;
    }

    public void beginFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
        while (true) {
            ObserverClaims state = acquireState(observer, localWorld);
            if (state == null) {
                return;
            }
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                state.frame = new ObserverFrame(observer, localWorld, allowLightingUpdate);
                return;
            }
        }
    }

    public ClaimUpdateResult flushFrame(Player observer) {
        if (observer == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return ClaimUpdateResult.empty();
        }
        synchronized (state) {
            if (state.retired) {
                return ClaimUpdateResult.empty();
            }
            ObserverFrame frame = state.frame;
            state.frame = null;
            if (frame == null) {
                return ClaimUpdateResult.empty();
            }
            if (!state.worldId.equals(worldId(frame.localWorld))
                || !isObserverInWorld(frame.observer, state.worldId)) {
                discardState(state);
                observers.remove(observerId, state);
                return ClaimUpdateResult.empty();
            }
            ClaimUpdateResult result = applyResult(
                frame.observer, frame.localWorld, state, frame.result, frame.allowLightingUpdate, false);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    public ClaimUpdateResult submit(Player observer,
                                    ILocalPortal portal,
                                    World localWorld,
                                    Long2ObjectMap<ProjectedBlockClaim> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate) {
        if (observer == null || portal == null || portal.getId() == null || claims == null) {
            return ClaimUpdateResult.empty();
        }
        return submit(observer, portal.getId(), localWorld, claims, priorityDistance, allowLightingUpdate);
    }

    public ClaimUpdateResult submit(Player observer,
                                    UUID claimOwnerId,
                                    World localWorld,
                                    Long2ObjectMap<ProjectedBlockClaim> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate) {
        if (observer == null || claimOwnerId == null || claims == null) {
            return ClaimUpdateResult.empty();
        }
        while (true) {
            ObserverClaims state = acquireState(observer, localWorld);
            if (state == null) {
                return ClaimUpdateResult.empty();
            }
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                String tieKey = claimOwnerId.toString();
                ProjectionClaimSet.ProjectionClaimSetResult setResult = state.claimSet.replacePortalClaims(
                    claimOwnerId, tieKey, priorityDistance, claims);
                ObserverFrame frame = state.frame;
                if (frame != null) {
                    if (allowLightingUpdate) {
                        frame.allowLightingUpdate = true;
                    }
                    frame.result.merge(setResult);
                    return new ClaimUpdateResult(0, setResult.getConflicts(), setResult.getWinnerChanges(), setResult.getReverts());
                }
                return applyResult(observer, localWorld, state, setResult, allowLightingUpdate, false);
            }
        }
    }

    public ClaimUpdateResult release(Player observer, ILocalPortal portal, World localWorld, boolean allowLightingUpdate) {
        if (observer == null || portal == null || portal.getId() == null) {
            return ClaimUpdateResult.empty();
        }
        return release(observer, portal.getId(), localWorld, allowLightingUpdate);
    }

    public ClaimUpdateResult release(Player observer, UUID claimOwnerId, World localWorld, boolean allowLightingUpdate) {
        if (observer == null || claimOwnerId == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return ClaimUpdateResult.empty();
        }
        synchronized (state) {
            if (state.retired) {
                return ClaimUpdateResult.empty();
            }
            UUID requestedWorldId = worldId(localWorld);
            if (requestedWorldId == null || !state.worldId.equals(requestedWorldId)) {
                return ClaimUpdateResult.empty();
            }
            if (!isObserverInWorld(observer, state.worldId)) {
                discardState(state);
                observers.remove(observerId, state);
                return ClaimUpdateResult.empty();
            }
            ProjectionClaimSet.ProjectionClaimSetResult setResult = state.claimSet.releasePortal(claimOwnerId);
            ObserverFrame frame = state.frame;
            if (frame != null) {
                if (allowLightingUpdate) {
                    frame.allowLightingUpdate = true;
                }
                frame.result.merge(setResult);
                return new ClaimUpdateResult(0, setResult.getConflicts(), setResult.getWinnerChanges(), setResult.getReverts());
            }
            ClaimUpdateResult result = applyResult(observer, localWorld, state, setResult, allowLightingUpdate, false);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    public void discardObserver(UUID observerId) {
        discardObserver(observerId, null);
    }

    public void discardObserver(UUID observerId, UUID expectedWorldId) {
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.retired) {
                return;
            }
            if (expectedWorldId != null && !expectedWorldId.equals(state.worldId)) {
                return;
            }
            discardState(state);
            observers.remove(observerId, state);
        }
    }

    public void clear() {
        for (Map.Entry<UUID, ObserverClaims> entry : observers.entrySet()) {
            ObserverClaims state = entry.getValue();
            synchronized (state) {
                discardState(state);
            }
            observers.remove(entry.getKey(), state);
        }
    }

    public boolean isIdle() {
        return observers.isEmpty();
    }

    public boolean hasPendingLighting(Player observer) {
        if (observer == null) {
            return false;
        }
        ObserverClaims state = observers.get(observer.getUniqueId());
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.retired) {
                return false;
            }
            return hasLightingWork(state);
        }
    }

    public ClaimUpdateResult retryPending(Player observer, World localWorld) {
        if (observer == null) {
            return ClaimUpdateResult.empty();
        }
        UUID observerId = observer.getUniqueId();
        ObserverClaims state = observers.get(observerId);
        if (state == null) {
            return ClaimUpdateResult.empty();
        }
        synchronized (state) {
            UUID requestedWorldId = worldId(localWorld);
            if (state.retired || requestedWorldId == null || !state.worldId.equals(requestedWorldId)) {
                return ClaimUpdateResult.empty();
            }
            if (!isObserverInWorld(observer, state.worldId)) {
                discardState(state);
                observers.remove(observerId, state);
                return ClaimUpdateResult.empty();
            }
            reconcileClientChunks(observer, state);
            if (state.pendingRevertKeys.isEmpty() && state.pendingSendKeys.isEmpty() && !hasLightingWork(state)) {
                return ClaimUpdateResult.empty();
            }
            ObserverFrame frame = state.frame;
            if (frame != null) {
                frame.allowLightingUpdate = true;
                return ClaimUpdateResult.empty();
            }
            ClaimUpdateResult result = applyResult(observer, localWorld, state,
                new ProjectionClaimSet.ProjectionClaimSetResult(), true, true);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    static void groupBySection(Long2ObjectMap<BlockData> blockChanges,
                               Long2IntMap blockIds,
                               Long2ObjectMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sectionsOut,
                               Long2ObjectMap<BlockData> fallbackOut) {
        ObjectIterator<Long2ObjectMap.Entry<BlockData>> changeIterator = Long2ObjectMaps.fastIterator(blockChanges);
        while (changeIterator.hasNext()) {
            Long2ObjectMap.Entry<BlockData> change = changeIterator.next();
            long key = change.getLongKey();
            BlockData data = change.getValue();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            int id = blockIds.get(key);
            if (id < 0 || (id == 0 && !ProjectionWorldView.isAir(data.getMaterial()))) {
                fallbackOut.put(key, data);
                continue;
            }
            long sectionKey = packSectionKey(x >> 4, y >> 4, z >> 4);
            List<WrapperPlayServerMultiBlockChange.EncodedBlock> entries = sectionsOut.get(sectionKey);
            if (entries == null) {
                entries = new ArrayList<WrapperPlayServerMultiBlockChange.EncodedBlock>(8);
                sectionsOut.put(sectionKey, entries);
            }
            entries.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(id, x, y, z));
        }
    }

    static int unpackSectionX(long key) {
        return ProjectionCellKey.unpackX(key);
    }

    static int unpackSectionY(long key) {
        return ProjectionCellKey.unpackY(key);
    }

    static int unpackSectionZ(long key) {
        return ProjectionCellKey.unpackZ(key);
    }

    private ClaimUpdateResult applyResult(Player observer,
                                          World localWorld,
                                          ObserverClaims observerClaims,
                                          ProjectionClaimSet.ProjectionClaimSetResult setResult,
                                          boolean allowLightingUpdate,
                                          boolean clientChunksReconciled) {
        boolean canSend = observerClaims.worldId.equals(worldId(localWorld))
            && isObserverInWorld(observer, observerClaims.worldId);
        if (canSend && !clientChunksReconciled) {
            reconcileClientChunks(observer, observerClaims);
        }
        LongOpenHashSet packetKeys = setResult.getPacketChangeKeys();
        packetKeys.addAll(observerClaims.pendingRevertKeys);
        packetKeys.addAll(observerClaims.pendingSendKeys);
        observerClaims.pendingRevertKeys.clear();
        observerClaims.pendingSendKeys.clear();
        int expectedChanges = packetKeys.size();
        boolean updateLightingNow = allowLightingUpdate || setResult.requiresImmediateLightingUpdate();
        if (expectedChanges == 0) {
            observerClaims.pendingLightingKeys.addAll(setResult.getDirtyLightingKeys());
            applyLighting(observer, localWorld, observerClaims, canSend, updateLightingNow);
            return new ClaimUpdateResult(0, setResult.getConflicts(),
                setResult.getWinnerChanges(), setResult.getReverts());
        }
        int mapCapacity = expectedChanges <= 2 ? 4 : (expectedChanges * 4 / 3) + 2;
        Long2ObjectMap<BlockData> blockChanges = new Long2ObjectOpenHashMap<BlockData>(mapCapacity);
        Long2IntOpenHashMap blockChangeIds = new Long2IntOpenHashMap(mapCapacity);
        blockChangeIds.defaultReturnValue(-1);
        if (canSend) {
            ProjectionWorldView localView = viewProvider.view(localWorld);
            Long2ByteOpenHashMap chunkSentMemo = observerClaims.chunkSentMemo;
            Long2LongOpenHashMap chunkRevisionMemo = observerClaims.chunkRevisionMemo;
            long visibilityRevision = observerClaims.clientChunkRevision;
            if (visibilityRevision == Long.MIN_VALUE || visibilityRevision != observerClaims.chunkMemoRevision) {
                chunkSentMemo.clear();
                chunkRevisionMemo.clear();
                observerClaims.chunkMemoRevision = visibilityRevision;
            }
            LongIterator packetIterator = packetKeys.iterator();
            while (packetIterator.hasNext()) {
                long key = packetIterator.nextLong();
                ProjectedBlockClaim winner = observerClaims.claimSet.getWinningClaim(key);
                int x = ProjectionCellKey.unpackX(key);
                int y = ProjectionCellKey.unpackY(key);
                int z = ProjectionCellKey.unpackZ(key);
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
                byte sentState = chunkSentMemo.get(chunkKey);
                if (sentState == 0) {
                    sentState = chunkVisibility.isChunkSent(observer, chunkX, chunkZ) ? (byte) 1 : (byte) 2;
                    chunkSentMemo.put(chunkKey, sentState);
                }
                if (sentState == 2) {
                    observerClaims.sentBlocks.remove(key);
                    observerClaims.sentBlockChunkRevisions.remove(key);
                    observerClaims.lighting.discardChunk(chunkX, chunkZ);
                    if (winner != null) {
                        observerClaims.pendingSendKeys.add(key);
                        observerClaims.pendingLightingKeys.add(key);
                    }
                    continue;
                }
                if (winner == null) {
                    if (!observerClaims.sentBlocks.containsKey(key)) {
                        continue;
                    }
                    BlockData localData = localView == null ? null : localView.sampleBlockData(x, y, z);
                    if (localData == null) {
                        observerClaims.pendingRevertKeys.add(key);
                        continue;
                    }
                    BlockData sentData = observerClaims.sentBlocks.get(key);
                    observerClaims.sentBlocks.remove(key);
                    observerClaims.sentBlockChunkRevisions.remove(key);
                    if (sentData.equals(localData)) {
                        continue;
                    }
                    blockChanges.put(key, localData);
                    blockChangeIds.put(key, resolveGlobalId(localData));
                } else {
                    BlockData sentData = observerClaims.sentBlocks.get(key);
                    BlockData winnerData = winner.getData();
                    if (sentData != null && sentData.equals(winnerData)) {
                        continue;
                    }
                    observerClaims.sentBlocks.put(key, winnerData);
                    long chunkRevision = chunkRevisionMemo.get(chunkKey);
                    if (chunkRevision == Long.MIN_VALUE) {
                        chunkRevision = chunkVisibility.chunkRevision(observer, chunkX, chunkZ);
                        chunkRevisionMemo.put(chunkKey, chunkRevision);
                    }
                    observerClaims.sentBlockChunkRevisions.put(key, chunkRevision);
                    blockChanges.put(key, winnerData);
                    blockChangeIds.put(key, claimGlobalId(winner, winnerData));
                }
            }
            if (!blockChanges.isEmpty()) {
                sendBlockChanges(observer, localWorld, blockChanges, blockChangeIds);
            }
        } else {
            LongIterator packetIterator = packetKeys.iterator();
            while (packetIterator.hasNext()) {
                long key = packetIterator.nextLong();
                if (observerClaims.claimSet.getWinningClaim(key) == null) {
                    observerClaims.pendingRevertKeys.add(key);
                } else {
                    observerClaims.pendingSendKeys.add(key);
                }
            }
        }

        observerClaims.pendingLightingKeys.addAll(setResult.getDirtyLightingKeys());
        applyLighting(observer, localWorld, observerClaims, canSend, updateLightingNow);

        return new ClaimUpdateResult(blockChanges.size(), setResult.getConflicts(),
            setResult.getWinnerChanges(), setResult.getReverts());
    }

    private void applyLighting(Player observer, World localWorld, ObserverClaims observerClaims, boolean canSend, boolean allowLightingUpdate) {
        if (!canSend) {
            return;
        }
        boolean sourceLightingEnabled = Settings.LIGHTING_FIDELITY;
        boolean fullBrightEnabled = observerClaims.claimSet.hasFullBrightClaims();
        if (!sourceLightingEnabled && !fullBrightEnabled) {
            observerClaims.lighting.revert(observer, viewProvider.view(localWorld));
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (observerClaims.claimSet.isEmpty()) {
            observerClaims.lighting.revert(observer, viewProvider.view(localWorld));
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if ((!allowLightingUpdate && !fullBrightEnabled)
            || (observerClaims.pendingLightingKeys.isEmpty() && !observerClaims.lighting.hasPendingUpdates())) {
            return;
        }
        ProjectionWorldView localView = viewProvider.view(localWorld);
        if (localView == null) {
            return;
        }
        observerClaims.lighting.apply(observer, localView, observerClaims.claimSet.getWinningClaims(),
            observerClaims.pendingLightingKeys, sourceLightingEnabled);
        observerClaims.pendingLightingKeys.clear();
    }

    private static boolean hasLightingWork(ObserverClaims state) {
        if (!state.pendingLightingKeys.isEmpty() || state.lighting.hasPendingUpdates()) {
            return true;
        }
        if (!Settings.LIGHTING_FIDELITY && !state.claimSet.hasFullBrightClaims() && !state.lighting.isIdle()) {
            return true;
        }
        return state.claimSet.isEmpty() && !state.lighting.isIdle();
    }

    private void reconcileClientChunks(Player observer, ObserverClaims state) {
        long revision = chunkVisibility.revision(observer);
        if (revision != Long.MIN_VALUE && revision == state.clientChunkRevision) {
            return;
        }
        state.clientChunkRevision = revision;
        LongOpenHashSet validChunks = new LongOpenHashSet();
        LongOpenHashSet invalidChunks = new LongOpenHashSet();
        LongIterator iterator = state.sentBlocks.keySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int chunkX = ProjectionCellKey.unpackX(key) >> 4;
            int chunkZ = ProjectionCellKey.unpackZ(key) >> 4;
            long chunkKey = packChunkKey(chunkX, chunkZ);
            if (validChunks.contains(chunkKey)) {
                continue;
            }
            if (!invalidChunks.contains(chunkKey)) {
                long currentChunkRevision = chunkVisibility.chunkRevision(observer, chunkX, chunkZ);
                long sentChunkRevision = state.sentBlockChunkRevisions.get(key);
                if (chunkVisibility.isChunkSent(observer, chunkX, chunkZ)
                    && (currentChunkRevision == Long.MIN_VALUE || currentChunkRevision == sentChunkRevision)) {
                    validChunks.add(chunkKey);
                    continue;
                }
                invalidChunks.add(chunkKey);
            }
            iterator.remove();
            state.sentBlockChunkRevisions.remove(key);
            if (state.claimSet.getWinningClaim(key) != null) {
                state.pendingSendKeys.add(key);
                state.pendingLightingKeys.add(key);
            }
        }
        LongIterator invalidIterator = invalidChunks.iterator();
        while (invalidIterator.hasNext()) {
            long chunkKey = invalidIterator.nextLong();
            state.lighting.discardChunk((int) (chunkKey >> 32), (int) chunkKey);
        }
        state.lighting.discardUnsentChunks(observer);
    }

    static void sendBlockChanges(Player observer,
                                 World localWorld,
                                 Long2ObjectMap<BlockData> blockChanges,
                                 Long2IntMap blockChangeIds) {
        if (blockChanges.size() == 1) {
            ObjectIterator<Long2ObjectMap.Entry<BlockData>> singleIterator = Long2ObjectMaps.fastIterator(blockChanges);
            while (singleIterator.hasNext()) {
                Long2ObjectMap.Entry<BlockData> change = singleIterator.next();
                long key = change.getLongKey();
                observer.sendBlockChange(new Location(localWorld, ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key)), change.getValue());
                WormholesTelemetry.countBlockChange();
                WormholesTelemetry.countPacket();
            }
            return;
        }
        Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> sections =
            new Long2ObjectOpenHashMap<List<WrapperPlayServerMultiBlockChange.EncodedBlock>>(8);
        Long2ObjectOpenHashMap<BlockData> fallback = new Long2ObjectOpenHashMap<BlockData>(4);
        groupBySection(blockChanges, blockChangeIds, sections, fallback);
        User batchUser = sections.isEmpty() ? null : PacketEvents.getAPI().getPlayerManager().getUser(observer);
        RuntimeException batchFailure = null;
        try {
            for (Long2ObjectMap.Entry<List<WrapperPlayServerMultiBlockChange.EncodedBlock>> entry : sections.long2ObjectEntrySet()) {
                long sectionKey = entry.getLongKey();
                List<WrapperPlayServerMultiBlockChange.EncodedBlock> entries = entry.getValue();
                WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = orderDenseSectionBlocks(
                    entries.toArray(new WrapperPlayServerMultiBlockChange.EncodedBlock[entries.size()]));
                Vector3i sectionPos = new Vector3i(unpackSectionX(sectionKey), unpackSectionY(sectionKey), unpackSectionZ(sectionKey));
                WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(sectionPos, null, blocks);
                if (batchUser == null) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(observer, wrapper);
                } else {
                    batchUser.writePacket(wrapper);
                }
                WormholesTelemetry.countPacket();
                for (int i = 0; i < blocks.length; i++) {
                    WormholesTelemetry.countBlockChange();
                }
            }
        } catch (RuntimeException error) {
            batchFailure = error;
            throw error;
        } finally {
            flushSectionPackets(batchUser, batchFailure);
        }
        for (Long2ObjectMap.Entry<BlockData> change : fallback.long2ObjectEntrySet()) {
            long key = change.getLongKey();
            observer.sendBlockChange(new Location(localWorld, ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key)), change.getValue());
            WormholesTelemetry.countBlockChange();
            WormholesTelemetry.countPacket();
        }
    }

    static WrapperPlayServerMultiBlockChange.EncodedBlock[] orderDenseSectionBlocks(
        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks) {
        if (blocks.length < DENSE_SECTION_SORT_THRESHOLD) {
            return blocks;
        }
        long encodedMask = 0L;
        for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
            encodedMask |= block.toLong();
        }
        if (encodedMask == 0L) {
            return blocks;
        }
        WrapperPlayServerMultiBlockChange.EncodedBlock[] source = blocks;
        WrapperPlayServerMultiBlockChange.EncodedBlock[] target =
            new WrapperPlayServerMultiBlockChange.EncodedBlock[blocks.length];
        int[] bucketOffsets = new int[RADIX_BUCKET_COUNT];
        int encodedBits = Long.SIZE - Long.numberOfLeadingZeros(encodedMask);
        for (int shift = 0; shift < encodedBits; shift += RADIX_BUCKET_BITS) {
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : source) {
                bucketOffsets[(int) (block.toLong() >>> shift) & RADIX_BUCKET_MASK]++;
            }
            int nextOffset = 0;
            for (int bucket = 0; bucket < RADIX_BUCKET_COUNT; bucket++) {
                int bucketSize = bucketOffsets[bucket];
                bucketOffsets[bucket] = nextOffset;
                nextOffset += bucketSize;
            }
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : source) {
                int bucket = (int) (block.toLong() >>> shift) & RADIX_BUCKET_MASK;
                target[bucketOffsets[bucket]++] = block;
            }
            WrapperPlayServerMultiBlockChange.EncodedBlock[] previousSource = source;
            source = target;
            target = previousSource;
            if (shift + RADIX_BUCKET_BITS < encodedBits) {
                Arrays.fill(bucketOffsets, 0);
            }
        }
        return source;
    }

    private static void flushSectionPackets(User batchUser, RuntimeException batchFailure) {
        if (batchUser == null) {
            return;
        }
        try {
            batchUser.flushPackets();
        } catch (RuntimeException flushFailure) {
            if (batchFailure != null) {
                batchFailure.addSuppressed(flushFailure);
                return;
            }
            throw flushFailure;
        }
    }

    private int claimGlobalId(ProjectedBlockClaim claim, BlockData data) {
        int cached = claim.getGlobalId();
        if (cached != ProjectedBlockClaim.UNRESOLVED_GLOBAL_ID) {
            return cached;
        }
        int resolved = resolveGlobalId(data);
        claim.setGlobalId(resolved);
        return resolved;
    }

    private int resolveGlobalId(BlockData data) {
        Integer cached = blockGlobalIds.get(data);
        if (cached != null) {
            return cached.intValue();
        }
        int id;
        try {
            id = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        } catch (RuntimeException ex) {
            Integer previous = blockGlobalIds.putIfAbsent(data, Integer.valueOf(-1));
            if (previous != null) {
                return previous.intValue();
            }
            noteBlockMappingFailure(data.getAsString(), ex);
            return -1;
        }
        blockGlobalIds.put(data, Integer.valueOf(id));
        return id;
    }

    static void noteBlockMappingFailure(String blockDescription, RuntimeException ex) {
        WormholesTelemetry.countFailure(BLOCK_MAPPING_FAILURE_REASON);
        logger().log(Level.WARNING, "[ClaimArbiter] block state mapping failed for " + blockDescription
            + ", falling back to Bukkit block updates for this state", ex);
    }

    private static Logger logger() {
        Wormholes plugin = Wormholes.instance;
        return plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
    }

    private void removeObserverIfEmpty(UUID observerId, ObserverClaims state) {
        if (state.claimSet.isEmpty() && state.frame == null && state.pendingSendKeys.isEmpty()
            && state.pendingRevertKeys.isEmpty() && state.pendingLightingKeys.isEmpty()
            && state.sentBlocks.isEmpty() && state.lighting.isIdle()) {
            state.retired = true;
            observers.remove(observerId, state);
        }
    }

    private ObserverClaims acquireState(Player observer, World localWorld) {
        if (observer == null || !observer.isOnline() || localWorld == null) {
            return null;
        }
        UUID worldId = worldId(localWorld);
        if (worldId == null || !isObserverInWorld(observer, worldId)) {
            return null;
        }
        UUID observerId = observer.getUniqueId();
        while (true) {
            ObserverClaims state = observers.get(observerId);
            if (state == null) {
                ObserverClaims created = new ObserverClaims(worldId, lightingFactory.create());
                ObserverClaims raced = observers.putIfAbsent(observerId, created);
                state = raced == null ? created : raced;
            }
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                if (state.worldId.equals(worldId)) {
                    return state;
                }
                discardState(state);
                observers.remove(observerId, state);
            }
        }
    }

    private static void discardState(ObserverClaims state) {
        state.claimSet.clear();
        state.pendingLightingKeys.clear();
        state.pendingRevertKeys.clear();
        state.pendingSendKeys.clear();
        state.sentBlocks.clear();
        state.sentBlockChunkRevisions.clear();
        state.chunkSentMemo.clear();
        state.chunkRevisionMemo.clear();
        state.chunkMemoRevision = Long.MIN_VALUE;
        state.lighting.discard();
        state.frame = null;
        state.retired = true;
    }

    private static boolean isObserverInWorld(Player observer, UUID worldId) {
        if (observer == null || !observer.isOnline() || worldId == null) {
            return false;
        }
        World observerWorld = observer.getWorld();
        return observerWorld != null && worldId.equals(observerWorld.getUID());
    }

    private static UUID worldId(World world) {
        return world == null ? null : world.getUID();
    }

    private static long packSectionKey(int sectionX, int sectionY, int sectionZ) {
        return ProjectionCellKey.pack(sectionX, sectionY, sectionZ);
    }

    private static long packChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (((long) chunkZ) & 0xFFFFFFFFL);
    }

    public static final class ClaimUpdateResult {
        private static final ClaimUpdateResult EMPTY = new ClaimUpdateResult(0, 0, 0, 0);

        private final int blockChanges;
        private final int conflicts;
        private final int winnerChanges;
        private final int reverts;

        private ClaimUpdateResult(int blockChanges, int conflicts, int winnerChanges, int reverts) {
            this.blockChanges = blockChanges;
            this.conflicts = conflicts;
            this.winnerChanges = winnerChanges;
            this.reverts = reverts;
        }

        static ClaimUpdateResult empty() {
            return EMPTY;
        }

        public int getBlockChanges() {
            return blockChanges;
        }

        public int getConflicts() {
            return conflicts;
        }

        public int getWinnerChanges() {
            return winnerChanges;
        }

        public int getReverts() {
            return reverts;
        }
    }

    private static final class ObserverClaims {
        private final UUID worldId;
        private final ProjectionClaimSet claimSet;
        private final LongOpenHashSet pendingLightingKeys;
        private final LongOpenHashSet pendingRevertKeys;
        private final LongOpenHashSet pendingSendKeys;
        private final Long2ObjectOpenHashMap<BlockData> sentBlocks;
        private final Long2LongOpenHashMap sentBlockChunkRevisions;
        private final Long2ByteOpenHashMap chunkSentMemo;
        private final Long2LongOpenHashMap chunkRevisionMemo;
        private final ProjectorLighting lighting;
        private ObserverFrame frame;
        private long clientChunkRevision;
        private long chunkMemoRevision;
        private boolean retired;

        private ObserverClaims(UUID worldId, ProjectorLighting lighting) {
            this.worldId = worldId;
            this.claimSet = new ProjectionClaimSet();
            this.pendingLightingKeys = new LongOpenHashSet();
            this.pendingRevertKeys = new LongOpenHashSet();
            this.pendingSendKeys = new LongOpenHashSet();
            this.sentBlocks = new Long2ObjectOpenHashMap<BlockData>(256);
            this.sentBlockChunkRevisions = new Long2LongOpenHashMap(256);
            this.sentBlockChunkRevisions.defaultReturnValue(Long.MIN_VALUE);
            this.chunkSentMemo = new Long2ByteOpenHashMap(64);
            this.chunkRevisionMemo = new Long2LongOpenHashMap(64);
            this.chunkRevisionMemo.defaultReturnValue(Long.MIN_VALUE);
            this.lighting = lighting;
            this.frame = null;
            this.clientChunkRevision = Long.MIN_VALUE;
            this.chunkMemoRevision = Long.MIN_VALUE;
            this.retired = false;
        }
    }

    private static final class ObserverFrame {
        private final Player observer;
        private final World localWorld;
        private final ProjectionClaimSet.ProjectionClaimSetResult result;
        private boolean allowLightingUpdate;

        private ObserverFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
            this.observer = observer;
            this.localWorld = localWorld;
            this.allowLightingUpdate = allowLightingUpdate;
            this.result = new ProjectionClaimSet.ProjectionClaimSetResult();
        }
    }

    @FunctionalInterface
    interface LightingFactory {
        ProjectorLighting create();
    }
}
