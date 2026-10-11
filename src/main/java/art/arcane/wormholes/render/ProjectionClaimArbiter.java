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
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.fidelity.BlockEntityLayer;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.BedrockProfile;
import art.arcane.wormholes.render.bedrock.ClientProfileService;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.ClaimSet;
import art.arcane.optics.light.LightOverlay;
import art.arcane.optics.math.CellKeys;

public final class ProjectionClaimArbiter {
    static final String BLOCK_MAPPING_FAILURE_REASON = "RENDER_CLAIM_BLOCK_MAPPING_FAILED";
    static final int DENSE_SECTION_SORT_THRESHOLD = 128;
    private static final int RADIX_BUCKET_BITS = 8;
    private static final int RADIX_BUCKET_COUNT = 1 << RADIX_BUCKET_BITS;
    private static final int RADIX_BUCKET_MASK = RADIX_BUCKET_COUNT - 1;

    private final ConcurrentHashMap<UUID, ObserverClaims> observers;
    private final ConcurrentHashMap<BlockData, Integer> blockGlobalIds;
    private final ProjectionWorldViewProvider viewProvider;
    private final BukkitProjectionOutput output;
    private final ProjectionChunkVisibility chunkVisibility;

    public ProjectionClaimArbiter(ProjectionWorldViewProvider viewProvider, BukkitProjectionOutput output) {
        this.observers = new ConcurrentHashMap<UUID, ObserverClaims>();
        this.blockGlobalIds = new ConcurrentHashMap<BlockData, Integer>();
        this.viewProvider = viewProvider;
        this.output = output;
        this.chunkVisibility = output.visibility();
    }

    public BukkitProjectionOutput output() {
        return output;
    }

    public void trackBlockEntities(Player observer, UUID ownerId, World localWorld,
                                   BlockEntityLayer<Player> layer,
                                   Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims) {
        ObserverClaims state = acquireState(observer, localWorld);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (!state.retired) {
                state.restoringBlockEntities.remove(layer);
                state.blockEntities.put(ownerId, new BlockEntityOwner(layer, claims));
            }
        }
    }

    public BlockEntitySample localBlockEntity(Player observer, World localWorld, int x, int y, int z) {
        if (!isObserverInWorld(observer, worldId(localWorld))) {
            return null;
        }
        ProjectionWorldView local = viewProvider.authoritativeView(localWorld);
        return local == null || !local.isChunkReady(x >> 4, z >> 4) ? null : local.sampleBlockEntity(x, y, z);
    }

    public int restoreBlockEntities(Player observer, World localWorld, BlockEntityLayer<Player> layer, int budget) {
        if (!isObserverInWorld(observer, worldId(localWorld))) {
            layer.clear();
            return 0;
        }
        if (!chunkVisibility.tracksWriteCompletion()) {
            ObserverClaims existing = observers.get(observer.getUniqueId());
            return layer.flush(observer, budget, output, key -> chunkVisibility.isChunkSent(observer,
                CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4)
                && (existing == null || existing.claimSet.getWinningClaim(key) == null));
        }
        ObserverClaims state = acquireState(observer, localWorld);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            if (state.retired) {
                return 0;
            }
            NbtRestorationLayer restoring = state.restoringBlockEntities.computeIfAbsent(layer, ignored -> new NbtRestorationLayer(layer));
            return flushRestoringBlockEntities(observer, state, restoring, budget);
        }
    }

    private int flushRestoringBlockEntities(Player observer, ObserverClaims state, NbtRestorationLayer restoring, int budget) {
        return restoring.layer.flush(observer, budget, output, key -> {
            if (state.claimSet.getWinningClaim(key) != null) {
                restoring.layer.discardCell(key);
                return false;
            }
            ProjectionWorldView local = viewProvider.authoritativeView(observer.getWorld());
            return !restoring.writes.containsKey(key) && local != null
                && local.isChunkReady(CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4)
                && chunkVisibility.isChunkSent(observer, CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4);
        }, (key, sample) -> {
            BlockEntitySample current = localBlockEntity(observer, observer.getWorld(), CellKeys.unpackX(key.longValue()),
                CellKeys.unpackY(key.longValue()), CellKeys.unpackZ(key.longValue()));
            if (current == null) {
                return;
            }
            NbtRestoration receipt = new NbtRestoration(current, chunkVisibility.chunkRevision(observer,
                CellKeys.unpackX(key.longValue()) >> 4, CellKeys.unpackZ(key.longValue()) >> 4));
            restoring.writes.put(key.longValue(), receipt);
            Consumer<Boolean> completion = success -> state.blockRepair.completed(
                () -> completeNbtRestoration(observer, state, restoring, key.longValue(), receipt, success));
            try {
                ProjectionPacketOrigin.withCompletion(completion, () -> output.blockEntity(observer, key.longValue(), current));
            } catch (RuntimeException failure) {
                restoring.writes.remove(key.longValue());
                throw failure;
            }
        });
    }

    private void completeNbtRestoration(Player observer, ObserverClaims state, NbtRestorationLayer restoring,
                                         long key, NbtRestoration receipt, boolean success) {
        if (state.retired || observers.get(state.observerId) != state
            || state.restoringBlockEntities.get(restoring.layer) != restoring || restoring.writes.get(key) != receipt) {
            return;
        }
        restoring.writes.remove(key);
        if (!isObserverInWorld(observer, state.worldId)) {
            discardState(state);
            observers.remove(state.observerId, state);
            return;
        }
        long revision = chunkVisibility.chunkRevision(observer, CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4);
        if (!success && revision == receipt.chunkRevision() && state.claimSet.getWinningClaim(key) == null) {
            restoring.layer.restoreCell(key, receipt.sample());
        }
    }

    private void retryRestoringBlockEntities(Player observer, ObserverClaims state) {
        Iterator<NbtRestorationLayer> iterator = state.restoringBlockEntities.values().iterator();
        int budget = 64;
        while (iterator.hasNext()) {
            NbtRestorationLayer restoring = iterator.next();
            if (budget > 0) {
                budget -= flushRestoringBlockEntities(observer, state, restoring, budget);
            }
            if (!restoring.layer.hasPending() && restoring.writes.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public int flushBlockEntities(Player observer, UUID ownerId, BlockEntityLayer<Player> layer, int budget) {
        ObserverClaims state = observers.get(observer.getUniqueId());
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            BlockEntityOwner owner = state.blockEntities.get(ownerId);
            if (state.retired || owner == null || owner.layer() != layer || !isObserverInWorld(observer, state.worldId)) {
                return 0;
            }
            return layer.flush(observer, budget, output, key -> {
                BlockClaim<BlockData, ProjectionWorldView> winner = state.claimSet.getWinningClaim(key);
                return winner != null && winner == owner.claims().get(key)
                    && chunkVisibility.isChunkSent(observer, CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4);
            });
        }
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
                if (state.frame != null) {
                    state.frame.allowLightingUpdate |= allowLightingUpdate;
                    return;
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
            ClaimSet.Result setResult = state.claimSet.resolveStaged(frame.affectedKeys);
            ClaimUpdateResult result = applyResult(
                frame.observer, frame.localWorld, state, setResult, frame.allowLightingUpdate, false);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    public ClaimUpdateResult submit(Player observer,
                                    ILocalPortal portal,
                                    World localWorld,
                                    Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate) {
        return submit(observer, portal, localWorld, claims, priorityDistance, allowLightingUpdate, false);
    }

    public ClaimUpdateResult submit(Player observer,
                                    ILocalPortal portal,
                                    World localWorld,
                                    Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate,
                                    boolean sourceLighting) {
        if (observer == null || portal == null || portal.getId() == null || claims == null) {
            return ClaimUpdateResult.empty();
        }
        return submit(observer, portal.getId(), localWorld, claims, priorityDistance, allowLightingUpdate, sourceLighting);
    }

    public ClaimUpdateResult submit(Player observer,
                                    UUID claimOwnerId,
                                    World localWorld,
                                    Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate) {
        return submit(observer, claimOwnerId, localWorld, claims, priorityDistance, allowLightingUpdate, false);
    }

    /**
     * @param sourceLighting promote destination sky-light rebasing for this owner even while the global
     *                       lighting-fidelity switch is off (per-portal atmosphere tint_light and full)
     */
    public ClaimUpdateResult submit(Player observer,
                                    UUID claimOwnerId,
                                    World localWorld,
                                    Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims,
                                    double priorityDistance,
                                    boolean allowLightingUpdate,
                                    boolean sourceLighting) {
        return submitClaims(observer, claimOwnerId, localWorld, claims, priorityDistance,
            allowLightingUpdate, sourceLighting, null);
    }

    ClaimUpdateResult submitDelta(Player observer, ILocalPortal portal, World localWorld,
                                   ClaimSet.ClaimDelta<BlockClaim<BlockData, ProjectionWorldView>> delta, double priorityDistance,
                                   boolean allowLightingUpdate, boolean sourceLighting) {
        if (portal == null || delta == null) {
            return ClaimUpdateResult.empty();
        }
        return submitClaims(observer, portal.getId(), localWorld, delta.claims(), priorityDistance,
            allowLightingUpdate, sourceLighting, delta);
    }

    private ClaimUpdateResult submitClaims(Player observer, UUID claimOwnerId, World localWorld,
                                           Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims, double priorityDistance,
                                           boolean allowLightingUpdate, boolean sourceLighting,
                                           ClaimSet.ClaimDelta<BlockClaim<BlockData, ProjectionWorldView>> delta) {
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
                if (sourceLighting) {
                    state.sourceLightingPortals.add(claimOwnerId);
                } else {
                    state.sourceLightingPortals.remove(claimOwnerId);
                }
                String tieKey = claimOwnerId.toString();
                ObserverFrame frame = state.frame;
                if (frame != null) {
                    if (delta == null) {
                        state.claimSet.stagePortalClaims(claimOwnerId, tieKey, priorityDistance, claims, frame.affectedKeys);
                    } else {
                        state.claimSet.stagePortalDelta(claimOwnerId, tieKey, priorityDistance, delta, frame.affectedKeys);
                    }
                    frame.allowLightingUpdate |= allowLightingUpdate;
                    return ClaimUpdateResult.empty();
                }
                ClaimSet.Result setResult = delta == null
                    ? state.claimSet.replacePortalClaims(claimOwnerId, tieKey, priorityDistance, claims)
                    : state.claimSet.replacePortalDelta(claimOwnerId, tieKey, priorityDistance, delta);
                return applyResult(observer, localWorld, state, setResult, allowLightingUpdate, false);
            }
        }
    }

    /**
     * Replaces the biome overrides one portal contributes for this observer. Quart cells are keyed with
     * quart coordinates; an empty map releases the portal's cells. Chunk columns whose grid changed are
     * resent immediately, always as full columns (local biomes plus the surviving overrides).
     */
    public void submitBiomes(Player observer, UUID portalId, World localWorld, Long2IntMap overrides) {
        if (observer == null || portalId == null || overrides == null) {
            return;
        }
        while (true) {
            ObserverClaims state = acquireState(observer, localWorld);
            if (state == null) {
                return;
            }
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                if (state.biomes == null) {
                    if (overrides.isEmpty()) {
                        return;
                    }
                    ProjectionWorldView localView = viewProvider.authoritativeView(localWorld);
                    if (localView == null) {
                        return;
                    }
                    state.biomes = new BiomeClaimSet(localView.getMinHeight(), localView.getMaxHeight(), localView);
                }
                state.blockRepair.trackBiomes(true);
                List<BiomeClaimSet.ChunkBiomes> changed = state.biomes.apply(portalId, overrides);
                sendBiomes(observer, state, changed);
                return;
            }
        }
    }

    public int retintedBiomeCells(Player observer) {
        if (observer == null) {
            return 0;
        }
        ObserverClaims state = observers.get(observer.getUniqueId());
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.biomes == null ? 0 : state.biomes.retintedCells();
        }
    }

    private void sendBiomes(Player observer, ObserverClaims state, List<BiomeClaimSet.ChunkBiomes> chunks) {
        for (BiomeClaimSet.ChunkBiomes chunk : chunks) {
            state.biomeVersions.put(CellKeys.chunkKey(chunk.chunkX(), chunk.chunkZ()), ++state.biomeSequence);
            state.pendingBiomeChunks.add(CellKeys.chunkKey(chunk.chunkX(), chunk.chunkZ()));
        }
        replayBiomes(observer, state);
    }

    private void replayBiomes(Player observer, ObserverClaims state) {
        if (state.biomes == null || !isObserverInWorld(observer, state.worldId)) {
            return;
        }
        LongIterator iterator = state.pendingBiomeChunks.iterator();
        int remaining = 8;
        while (iterator.hasNext() && remaining > 0) {
            long key = iterator.nextLong();
            int x = CellKeys.chunkX(key);
            int z = CellKeys.chunkZ(key);
            ProjectionWorldView local = viewProvider.authoritativeView(observer.getWorld());
            if (state.biomeWrites.containsKey(key) || !chunkVisibility.isChunkSent(observer, x, z) || local == null || !local.isChunkReady(x, z)) {
                continue;
            }
            if (chunkVisibility.tracksWriteCompletion()) {
                PendingBiomeWrite receipt = new PendingBiomeWrite(state.biomeVersions.get(key));
                state.biomeWrites.put(key, receipt);
                Consumer<Boolean> completion = success -> state.blockRepair.completed(
                    () -> completeBiomeWrite(observer, state, key, receipt, success));
                try {
                    ProjectionPacketOrigin.withCompletion(completion, () -> output.biomes(observer, List.of(state.biomes.current(x, z))));
                } catch (RuntimeException failure) {
                    state.biomeWrites.remove(key);
                    throw failure;
                }
            } else {
                output.biomes(observer, List.of(state.biomes.current(x, z)));
                iterator.remove();
            }
            remaining--;
        }
        if (state.pendingBiomeChunks.isEmpty() && state.biomes.isEmpty()) {
            state.biomes = null;
            state.blockRepair.trackBiomes(false);
        }
    }

    private void completeBiomeWrite(Player observer, ObserverClaims state, long key, PendingBiomeWrite receipt, boolean success) {
        if (state.retired || observers.get(state.observerId) != state || state.biomeWrites.get(key) != receipt) {
            return;
        }
        state.biomeWrites.remove(key);
        if (!isObserverInWorld(observer, state.worldId)) {
            discardState(state);
            observers.remove(state.observerId, state);
            return;
        }
        if (success && state.biomeVersions.get(key) == receipt.version()) {
            state.pendingBiomeChunks.remove(key);
            state.biomeVersions.remove(key);
        }
    }

    private void restoreBiomes(Player observer, ObserverClaims state, UUID claimOwnerId) {
        state.sourceLightingPortals.remove(claimOwnerId);
        if (state.biomes == null) {
            return;
        }
        sendBiomes(observer, state, state.biomes.release(claimOwnerId));
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
            state.blockEntities.remove(claimOwnerId);
            restoreBiomes(observer, state, claimOwnerId);
            ObserverFrame frame = state.frame;
            if (frame != null) {
                state.claimSet.stagePortalRelease(claimOwnerId, frame.affectedKeys);
                frame.allowLightingUpdate |= allowLightingUpdate;
                return ClaimUpdateResult.empty();
            }
            ClaimSet.Result setResult = state.claimSet.releasePortal(claimOwnerId);
            ClaimUpdateResult result = applyResult(observer, localWorld, state, setResult, allowLightingUpdate, false);
            removeObserverIfEmpty(observerId, state);
            return result;
        }
    }

    boolean drainLosingTransitions(Player observer, UUID claimOwnerId, LongSet displaced, LongSet restored, boolean resync) {
        if (observer == null || claimOwnerId == null) {
            return false;
        }
        ObserverClaims state = observers.get(observer.getUniqueId());
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return !state.retired && state.claimSet.drainLosingTransitions(claimOwnerId, displaced, restored, resync);
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
            retryRestoringBlockEntities(observer, state);
            if (state.pendingRevertKeys.isEmpty() && state.pendingSendKeys.isEmpty() && !hasLightingWork(state)) {
                removeObserverIfEmpty(observerId, state);
                return ClaimUpdateResult.empty();
            }
            ObserverFrame frame = state.frame;
            if (frame != null) {
                frame.allowLightingUpdate = true;
                return ClaimUpdateResult.empty();
            }
            ClaimUpdateResult result = applyResult(observer, localWorld, state,
                new ClaimSet.Result(), true, true);
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
            int x = CellKeys.unpackX(key);
            int y = CellKeys.unpackY(key);
            int z = CellKeys.unpackZ(key);
            int id = blockIds.get(key);
            if (id < 0 || (id == 0 && !ProjectionWorldView.isAir(data.getMaterial()))) {
                fallbackOut.put(key, data);
                continue;
            }
            long sectionKey = CellKeys.sectionKey(x >> 4, y >> 4, z >> 4);
            List<WrapperPlayServerMultiBlockChange.EncodedBlock> entries = sectionsOut.get(sectionKey);
            if (entries == null) {
                entries = new ArrayList<WrapperPlayServerMultiBlockChange.EncodedBlock>(8);
                sectionsOut.put(sectionKey, entries);
            }
            entries.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(id, x, y, z));
        }
    }

    static int unpackSectionX(long key) {
        return CellKeys.unpackX(key);
    }

    static int unpackSectionY(long key) {
        return CellKeys.unpackY(key);
    }

    static int unpackSectionZ(long key) {
        return CellKeys.unpackZ(key);
    }

    private ClaimUpdateResult applyResult(Player observer,
                                          World localWorld,
                                          ObserverClaims observerClaims,
                                          ClaimSet.Result setResult,
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
        BlockChangeBatch batch = observerClaims.blockBatch;
        observerClaims.blockBatch = null;
        if (batch == null) {
            batch = new BlockChangeBatch(expectedChanges);
        }
        Long2ObjectMap<BlockData> blockChanges = batch.blocks;
        Long2IntOpenHashMap blockChangeIds = batch.ids;
        try {
            if (canSend) {
                ProjectionWorldView localView = null;
                boolean localViewResolved = false;
                boolean restoringBlocks = false;
                LongOpenHashSet discardedLightingChunks = observerClaims.discardedLightingChunks;
                discardedLightingChunks.clear();
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
                    BlockClaim<BlockData, ProjectionWorldView> winner = observerClaims.claimSet.getWinningClaim(key);
                    int x = CellKeys.unpackX(key);
                    int y = CellKeys.unpackY(key);
                    int z = CellKeys.unpackZ(key);
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    long chunkKey = CellKeys.chunkKey(chunkX, chunkZ);
                    byte sentState = chunkSentMemo.get(chunkKey);
                    if (sentState == 0) {
                        sentState = chunkVisibility.isChunkSent(observer, chunkX, chunkZ) ? (byte) 1 : (byte) 2;
                        chunkSentMemo.put(chunkKey, sentState);
                    }
                    if (sentState == 2) {
                        observerClaims.sentBlocks.remove(key);
                        removeSentChunkKey(observerClaims, chunkKey, key);
                        if (discardedLightingChunks.add(chunkKey)) {
                            observerClaims.lighting.discardChunk(chunkX, chunkZ);
                        }
                        if (winner != null) {
                            observerClaims.pendingSendKeys.add(key);
                            observerClaims.pendingLightingKeys.add(key);
                        }
                        continue;
                    }
                    if (winner == null) {
                        if (observerClaims.restoreWrites.containsKey(key)) {
                            continue;
                        }
                        observerClaims.blockRepair.release(key);
                        BlockData sentData = observerClaims.sentBlocks.get(key);
                        if (sentData == null) {
                            continue;
                        }
                        if (!localViewResolved) {
                            localView = viewProvider.authoritativeView(localWorld);
                            localViewResolved = true;
                        }
                        BlockData localData = localView == null ? null : localView.sampleBlockData(x, y, z);
                        if (localData == null) {
                            observerClaims.pendingRevertKeys.add(key);
                            continue;
                        }
                        if (sentData.equals(localData)) {
                            observerClaims.sentBlocks.remove(key);
                            removeSentChunkKey(observerClaims, chunkKey, key);
                            continue;
                        }
                        blockChanges.put(key, localData);
                        blockChangeIds.put(key, resolveGlobalId(localData));
                        restoringBlocks = true;
                    } else {
                        if (observerClaims.restoreWrites.remove(key) != null) {
                            observerClaims.sentBlocks.remove(key);
                        }
                        observerClaims.blockRepair.takeReceipt(key);
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
                        SentChunk sentChunk = observerClaims.sentChunks.get(chunkKey);
                        if (sentChunk == null) {
                            sentChunk = new SentChunk(chunkRevision);
                            observerClaims.sentChunks.put(chunkKey, sentChunk);
                        }
                        sentChunk.keys.add(key);
                        blockChanges.put(key, winnerData);
                        int globalId = claimGlobalId(winner, winnerData);
                        blockChangeIds.put(key, globalId);
                        observerClaims.blockRepair.claim(key, globalId);
                    }
                }
                if (!blockChanges.isEmpty()) {
                    if (chunkVisibility.tracksWriteCompletion()) {
                        prepareRestorations(observer, observerClaims, blockChanges);
                    }
                    try {
                        sendBlockChanges(observer, localWorld, blockChanges, blockChangeIds,
                            ClientProfileService.profileFor(observer).blockBatchLimit(), observerClaims.blockRepair);
                    } catch (RuntimeException error) {
                        LongIterator attempted = blockChanges.keySet().iterator();
                        while (attempted.hasNext()) {
                            long key = attempted.nextLong();
                            observerClaims.restoreWrites.remove(key);
                            observerClaims.blockRepair.takeReceipt(key);
                        }
                        retryFailedBlocks(observerClaims, blockChanges);
                        observerClaims.pendingLightingKeys.addAll(setResult.getDirtyLightingKeys());
                        throw error;
                    }
                    if (restoringBlocks) {
                        commitRestoredBlocks(observerClaims, blockChanges);
                    }
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
        } finally {
            batch.blocks.clear();
            batch.ids.clear();
            if (expectedChanges <= 65_536 && !observerClaims.retired) {
                observerClaims.blockBatch = batch;
            }
        }
    }

    private void prepareRestorations(Player observer, ObserverClaims state, Long2ObjectMap<BlockData> changes) {
        LongIterator iterator = changes.keySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (state.claimSet.getWinningClaim(key) != null) {
                continue;
            }
            PendingRestoration receipt = new PendingRestoration(chunkVisibility.chunkRevision(observer,
                CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4));
            state.restoreWrites.put(key, receipt);
            state.blockRepair.receipt(key, success -> state.blockRepair.completed(
                () -> completeRestoration(observer, state, key, receipt, success)));
        }
    }

    private void completeRestoration(Player observer, ObserverClaims state, long key, PendingRestoration receipt, boolean success) {
        if (state.retired || observers.get(state.observerId) != state || state.restoreWrites.get(key) != receipt) {
            return;
        }
        state.restoreWrites.remove(key);
        if (!isObserverInWorld(observer, state.worldId)) {
            discardState(state);
            observers.remove(state.observerId, state);
            return;
        }
        if (state.claimSet.getWinningClaim(key) != null) {
            return;
        }
        long revision = chunkVisibility.chunkRevision(observer, CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4);
        if (success || revision != receipt.chunkRevision()) {
            state.sentBlocks.remove(key);
            removeSentChunkKey(state, CellKeys.chunkKey(CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4), key);
        } else {
            state.pendingRevertKeys.add(key);
        }
    }

    private static void retryFailedBlocks(ObserverClaims state, Long2ObjectMap<BlockData> changes) {
        LongIterator keys = changes.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            if (state.claimSet.getWinningClaim(key) == null) {
                state.pendingRevertKeys.add(key);
            } else {
                state.sentBlocks.remove(key);
                removeSentChunkKey(state, CellKeys.chunkKey(CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4), key);
                state.pendingSendKeys.add(key);
            }
        }
    }

    private static void commitRestoredBlocks(ObserverClaims state, Long2ObjectMap<BlockData> changes) {
        LongIterator keys = changes.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            if (state.claimSet.getWinningClaim(key) == null && !state.restoreWrites.containsKey(key)) {
                state.sentBlocks.remove(key);
                removeSentChunkKey(state, CellKeys.chunkKey(CellKeys.unpackX(key) >> 4, CellKeys.unpackZ(key) >> 4), key);
            }
        }
    }

    private void applyLighting(Player observer, World localWorld, ObserverClaims observerClaims, boolean canSend, boolean allowLightingUpdate) {
        if (!canSend) {
            return;
        }
        BedrockProfile profile = ClientProfileService.profileFor(observer);
        if (!profile.lightingFidelity()) {
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        boolean sourceLightingEnabled = sourceLightingEnabled(observerClaims);
        boolean fullBrightEnabled = observerClaims.claimSet.hasFullBrightClaims();
        if (!sourceLightingEnabled && !fullBrightEnabled) {
            observerClaims.lighting.revert(observer, viewProvider.authoritativeView(localWorld));
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if (observerClaims.claimSet.isEmpty()) {
            observerClaims.lighting.revert(observer, viewProvider.authoritativeView(localWorld));
            observerClaims.pendingLightingKeys.clear();
            return;
        }
        if ((!allowLightingUpdate && !fullBrightEnabled)
            || (observerClaims.pendingLightingKeys.isEmpty() && !observerClaims.lighting.hasPendingUpdates())) {
            return;
        }
        ProjectionWorldView localView = viewProvider.authoritativeView(localWorld);
        if (localView == null) {
            return;
        }
        observerClaims.blockRepair.trackLight(true);
        observerClaims.lighting.apply(observer, localView, observerClaims.claimSet.getWinningClaims(),
            observerClaims.pendingLightingKeys, sourceLightingEnabled);
        observerClaims.pendingLightingKeys.clear();
    }

    private static boolean hasLightingWork(ObserverClaims state) {
        if (!state.pendingLightingKeys.isEmpty() || state.lighting.hasPendingUpdates()) {
            return true;
        }
        if (!sourceLightingEnabled(state) && !state.claimSet.hasFullBrightClaims() && !state.lighting.isIdle()) {
            return true;
        }
        return state.claimSet.isEmpty() && !state.lighting.isIdle();
    }

    private static boolean sourceLightingEnabled(ObserverClaims state) {
        return Settings.LIGHTING_FIDELITY || !state.sourceLightingPortals.isEmpty();
    }

    private void reconcileClientChunks(Player observer, ObserverClaims state) {
        long lifecycle = state.blockRepair.lifecycle();
        if (state.repairLifecycle != lifecycle) {
            state.repairLifecycle = lifecycle;
            LongIterator restoring = state.restoreWrites.keySet().iterator();
            while (restoring.hasNext()) {
                long key = restoring.nextLong();
                if (state.claimSet.getWinningClaim(key) == null) {
                    state.sentBlocks.remove(key);
                }
            }
            state.restoreWrites.clear();
            state.lighting.discard();
            state.pendingLightingKeys.addAll(state.claimSet.getWinningClaims().keySet());
            state.biomeWrites.clear();
            if (state.biomes != null && state.biomes.isEmpty()) {
                state.biomes = null;
                state.pendingBiomeChunks.clear();
                state.biomeVersions.clear();
            }
            for (NbtRestorationLayer layer : state.restoringBlockEntities.values()) {
                layer.layer.clear();
            }
            state.restoringBlockEntities.clear();
            for (BlockEntityOwner owner : state.blockEntities.values()) {
                owner.layer().invalidateAll();
            }
        }
        state.blockRepair.drainCompletions();
        LongOpenHashSet overwritten = state.externalBlockKeys;
        state.blockRepair.drain(overwritten);
        LongIterator updates = overwritten.iterator();
        while (updates.hasNext()) {
            long key = updates.nextLong();
            if (state.claimSet.getWinningClaim(key) != null) {
                state.sentBlocks.remove(key);
                state.pendingSendKeys.add(key);
                for (BlockEntityOwner owner : state.blockEntities.values()) {
                    owner.layer().invalidateCell(key);
                }
            } else {
                state.blockRepair.release(key);
            }
        }
        overwritten.clear();
        reconcileFidelity(observer, state);
        replayBiomes(observer, state);
        long revision = chunkVisibility.revision(observer);
        if (revision != Long.MIN_VALUE && revision == state.clientChunkRevision) {
            return;
        }
        state.clientChunkRevision = revision;
        ObjectIterator<Long2ObjectMap.Entry<SentChunk>> iterator = Long2ObjectMaps.fastIterator(state.sentChunks);
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<SentChunk> entry = iterator.next();
            long chunkKey = entry.getLongKey();
            SentChunk sentChunk = entry.getValue();
            int chunkX = CellKeys.chunkX(chunkKey);
            int chunkZ = CellKeys.chunkZ(chunkKey);
            long currentChunkRevision = chunkVisibility.chunkRevision(observer, chunkX, chunkZ);
            if (chunkVisibility.isChunkSent(observer, chunkX, chunkZ)
                && (currentChunkRevision == Long.MIN_VALUE || currentChunkRevision == sentChunk.revision)) {
                continue;
            }
            iterator.remove();
            LongIterator keys = sentChunk.keys.iterator();
            while (keys.hasNext()) {
                long key = keys.nextLong();
                state.restoreWrites.remove(key);
                state.blockRepair.takeReceipt(key);
                state.sentBlocks.remove(key);
                if (state.claimSet.getWinningClaim(key) != null) {
                    state.pendingSendKeys.add(key);
                    state.pendingLightingKeys.add(key);
                }
            }
            if (!state.externalFidelityChunks.containsKey(chunkKey)) {
                replayChunkFidelity(state, chunkX, chunkZ, ProjectionBlockUpdateRepair.ALL_FIDELITY | ProjectionBlockUpdateRepair.REPLACED);
            }
        }
        replayBiomes(observer, state);
        state.lighting.discardUnsentChunks(observer);
    }

    private void reconcileFidelity(Player observer, ObserverClaims state) {
        Long2IntOpenHashMap chunks = state.externalFidelityChunks;
        LongOpenHashSet cells = state.externalBlockEntityKeys;
        Long2ObjectOpenHashMap<int[][]> baselines = state.externalBiomeBaselines;
        chunks.clear();
        cells.clear();
        baselines.clear();
        state.externalLightBaselines.clear();
        state.blockRepair.drainFidelity(chunks, cells, baselines, state.externalLightBaselines);
        for (Long2IntMap.Entry entry : chunks.long2IntEntrySet()) {
            long key = entry.getLongKey();
            replayChunkFidelity(state, CellKeys.chunkX(key), CellKeys.chunkZ(key), entry.getIntValue());
            LightOverlay.ChunkLight light = state.externalLightBaselines.get(key);
            if (light != null) {
                state.lighting.refreshBaseline(light);
            }
            int[][] baseline = baselines.get(key);
            if (state.biomes != null && baseline != null) {
                state.biomes.refreshLocalColumn(CellKeys.chunkX(key), CellKeys.chunkZ(key), baseline);
            }
        }
        for (BlockEntityOwner owner : state.blockEntities.values()) {
            LongIterator iterator = cells.iterator();
            while (iterator.hasNext()) {
                owner.layer().invalidateCell(iterator.nextLong());
            }
        }
    }

    private static void replayChunkFidelity(ObserverClaims state, int chunkX, int chunkZ, int channels) {
        if ((channels & ProjectionBlockUpdateRepair.REPLACED) != 0) {
            state.lighting.discardChunk(chunkX, chunkZ);
        }
        if ((channels & ProjectionBlockUpdateRepair.BLOCK_ENTITIES) != 0) {
            for (BlockEntityOwner owner : state.blockEntities.values()) {
                owner.layer().invalidateChunk(chunkX, chunkZ);
            }
        }
        if ((channels & ProjectionBlockUpdateRepair.BIOMES) != 0 && state.biomes != null) {
            if ((channels & (ProjectionBlockUpdateRepair.REPLACED | ProjectionBlockUpdateRepair.BASELINE_CHANGED)) != 0) {
                state.biomes.invalidateLocalColumn(chunkX, chunkZ);
            }
            if (state.biomes.claimsChunk(chunkX, chunkZ)) {
                long key = CellKeys.chunkKey(chunkX, chunkZ);
                state.pendingBiomeChunks.add(key);
                state.biomeVersions.put(key, ++state.biomeSequence);
            }
        }
        if ((channels & ProjectionBlockUpdateRepair.LIGHT) != 0) {
            state.lighting.invalidateChunk(chunkX, chunkZ);
            LongIterator iterator = state.claimSet.getWinningClaims().keySet().iterator();
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                if ((CellKeys.unpackX(key) >> 4) == chunkX && (CellKeys.unpackZ(key) >> 4) == chunkZ) {
                    state.pendingLightingKeys.add(key);
                }
            }
        }
    }

    private static void removeSentChunkKey(ObserverClaims state, long chunkKey, long key) {
        SentChunk sentChunk = state.sentChunks.get(chunkKey);
        if (sentChunk != null && sentChunk.keys.remove(key) && sentChunk.keys.isEmpty()) {
            state.sentChunks.remove(chunkKey);
        }
    }

    static void sendBlockChanges(Player observer,
                                 World localWorld,
                                 Long2ObjectMap<BlockData> blockChanges,
                                 Long2IntMap blockChangeIds) {
        sendBlockChanges(observer, localWorld, blockChanges, blockChangeIds, Integer.MAX_VALUE);
    }

    /** Splits one section's encoded blocks into packets of at most {@code limit} entries. */
    public static List<WrapperPlayServerMultiBlockChange.EncodedBlock[]> splitBatches(
        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks, int limit) {
        if (limit <= 0 || blocks.length <= limit) {
            return List.<WrapperPlayServerMultiBlockChange.EncodedBlock[]>of(blocks);
        }
        List<WrapperPlayServerMultiBlockChange.EncodedBlock[]> batches =
            new ArrayList<WrapperPlayServerMultiBlockChange.EncodedBlock[]>((blocks.length + limit - 1) / limit);
        for (int start = 0; start < blocks.length; start += limit) {
            batches.add(Arrays.copyOfRange(blocks, start, Math.min(blocks.length, start + limit)));
        }
        return batches;
    }

    static void sendBlockChanges(Player observer,
                                 World localWorld,
                                 Long2ObjectMap<BlockData> blockChanges,
                                 Long2IntMap blockChangeIds,
                                 int batchLimit) {
        sendBlockChanges(observer, localWorld, blockChanges, blockChangeIds, batchLimit, null);
    }

    private static void sendBlockChanges(Player observer,
                                        World localWorld,
                                        Long2ObjectMap<BlockData> blockChanges,
                                        Long2IntMap blockChangeIds,
                                        int batchLimit,
                                        ProjectionBlockUpdateRepair repair) {
        if (blockChanges.size() == 1) {
            ObjectIterator<Long2ObjectMap.Entry<BlockData>> singleIterator = Long2ObjectMaps.fastIterator(blockChanges);
            while (singleIterator.hasNext()) {
                Long2ObjectMap.Entry<BlockData> change = singleIterator.next();
                long key = change.getLongKey();
                sendSingleBlockChange(observer, localWorld, key, change.getValue(), blockChangeIds.get(key), repair);
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
                WrapperPlayServerMultiBlockChange.EncodedBlock[] ordered = orderDenseSectionBlocks(
                    entries.toArray(new WrapperPlayServerMultiBlockChange.EncodedBlock[entries.size()]));
                Vector3i sectionPos = new Vector3i(unpackSectionX(sectionKey), unpackSectionY(sectionKey), unpackSectionZ(sectionKey));
                for (WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks : splitBatches(ordered, batchLimit)) {
                    WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(sectionPos, null, blocks);
                    long[] keys = new long[blocks.length];
                    for (int index = 0; index < blocks.length; index++) {
                        keys[index] = CellKeys.pack(blocks[index].getX(), blocks[index].getY(), blocks[index].getZ());
                    }
                    Consumer<Boolean> completion = repair == null ? success -> { } : repair.takeReceipts(keys);
                    try {
                        ProjectionPacketOrigin.withCompletion(completion, () -> {
                            ProjectionPacketOrigin.prepare(observer, wrapper);
                            if (batchUser == null) {
                                PacketEvents.getAPI().getPlayerManager().sendPacket(observer, wrapper);
                            } else {
                                batchUser.writePacket(wrapper);
                            }
                        });
                    } catch (RuntimeException failure) {
                        ProjectionPacketOrigin.discard(wrapper);
                        throw failure;
                    }
                    WormholesTelemetry.countPacket();
                    for (int i = 0; i < blocks.length; i++) {
                        WormholesTelemetry.countBlockChange();
                    }
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
            sendSingleBlockChange(observer, localWorld, key, change.getValue(), blockChangeIds.get(key), repair);
            WormholesTelemetry.countBlockChange();
            WormholesTelemetry.countPacket();
        }
    }

    private static void sendSingleBlockChange(Player observer, World world, long key, BlockData data, int globalId,
                                              ProjectionBlockUpdateRepair repair) {
        if (globalId >= 0 && repair != null && repair.confirmsWrites()) {
            Consumer<Boolean> receipt = repair.takeReceipt(key);
            ProjectionPacketOrigin.withCompletion(receipt == null ? success -> { } : receipt,
                () -> ProjectionPacketOrigin.send(observer, new WrapperPlayServerBlockChange(
                    new Vector3i(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)), globalId)));
            return;
        }
        if (repair != null) {
            repair.expectBukkitBlockChange(key);
        }
        try {
            observer.sendBlockChange(new Location(world, CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)), data);
        } catch (RuntimeException error) {
            if (repair != null) {
                repair.consumeBukkitBlockChange(key);
            }
            throw error;
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

    private int claimGlobalId(BlockClaim<BlockData, ProjectionWorldView> claim, BlockData data) {
        int cached = claim.getGlobalId();
        if (cached != BlockClaim.UNRESOLVED_GLOBAL_ID) {
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
            && state.sentBlocks.isEmpty() && state.restoreWrites.isEmpty() && state.restoringBlockEntities.isEmpty()
            && state.pendingBiomeChunks.isEmpty() && state.biomeWrites.isEmpty() && state.lighting.isIdle()
            && (state.biomes == null || state.biomes.isEmpty())) {
            state.retired = true;
            state.blockRepair.close();
            chunkVisibility.unwatchBlockUpdates(observerId, state.blockRepair);
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
                ObserverClaims created = new ObserverClaims(new ObserverIdentity(observerId, worldId), output.lighting());
                created.blockRepair = new ProjectionBlockUpdateRepair(
                    task -> output.scheduleBlockRepair(observer, task), () -> repairBlocks(observer, created));
                created.blockRepair.confirmWrites(chunkVisibility.tracksWriteCompletion());
                if (chunkVisibility.tracksWriteCompletion()) {
                    created.lighting.localLightSender((player, light, completion) ->
                        ProjectionPacketOrigin.withCompletion(success -> created.blockRepair.completed(() -> {
                            if (!created.retired && observers.get(created.observerId) == created
                                && isObserverInWorld(player, created.worldId)) {
                                completion.accept(success);
                            }
                        }), () -> output.light(player, light)));
                }
                ObserverClaims raced = observers.putIfAbsent(observerId, created);
                state = raced == null ? created : raced;
            }
            synchronized (state) {
                if (state.retired) {
                    continue;
                }
                if (state.worldId.equals(worldId)) {
                    chunkVisibility.watchBlockUpdates(observerId, state.blockRepair);
                    return state;
                }
                discardState(state);
                observers.remove(observerId, state);
            }
        }
    }

    private void discardState(ObserverClaims state) {
        state.blockRepair.close();
        chunkVisibility.unwatchBlockUpdates(state.observerId, state.blockRepair);
        state.claimSet.clear();
        state.pendingLightingKeys.clear();
        state.pendingRevertKeys.clear();
        state.pendingSendKeys.clear();
        state.externalBlockKeys.clear();
        state.sentBlocks.clear();
        state.restoreWrites.clear();
        state.restoringBlockEntities.clear();
        state.blockBatch = null;
        state.sentChunks.clear();
        state.chunkSentMemo.clear();
        state.chunkRevisionMemo.clear();
        state.discardedLightingChunks.clear();
        state.chunkMemoRevision = Long.MIN_VALUE;
        state.lighting.discard();
        state.biomes = null;
        state.pendingBiomeChunks.clear();
        state.biomeWrites.clear();
        state.biomeVersions.clear();
        state.blockEntities.clear();
        state.sourceLightingPortals.clear();
        state.frame = null;
        state.retired = true;
    }

    private void repairBlocks(Player observer, ObserverClaims state) {
        synchronized (state) {
            if (state.retired || observers.get(state.observerId) != state) {
                return;
            }
            retryPending(observer, observer.getWorld());
        }
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
        private final UUID observerId;
        private final UUID worldId;
        private final ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> claimSet;
        private final LongOpenHashSet pendingLightingKeys;
        private final LongOpenHashSet pendingRevertKeys;
        private final LongOpenHashSet pendingSendKeys;
        private final LongOpenHashSet externalBlockKeys;
        private final Long2ObjectOpenHashMap<BlockData> sentBlocks;
        private final Long2ObjectOpenHashMap<SentChunk> sentChunks;
        private final Long2ByteOpenHashMap chunkSentMemo;
        private final Long2LongOpenHashMap chunkRevisionMemo;
        private final LongOpenHashSet discardedLightingChunks;
        private final LightOverlay<Player, BlockData, ProjectionWorldView> lighting;
        private final Set<UUID> sourceLightingPortals;
        private final Map<UUID, BlockEntityOwner> blockEntities = new HashMap<UUID, BlockEntityOwner>();
        private final Long2ObjectOpenHashMap<PendingRestoration> restoreWrites = new Long2ObjectOpenHashMap<PendingRestoration>();
        private final Map<BlockEntityLayer<Player>, NbtRestorationLayer> restoringBlockEntities = new HashMap<BlockEntityLayer<Player>, NbtRestorationLayer>();
        private final Long2ObjectOpenHashMap<PendingBiomeWrite> biomeWrites = new Long2ObjectOpenHashMap<PendingBiomeWrite>();
        private final Long2LongOpenHashMap biomeVersions = new Long2LongOpenHashMap();
        private long biomeSequence;
        private final LongOpenHashSet pendingBiomeChunks = new LongOpenHashSet();
        private final Long2IntOpenHashMap externalFidelityChunks = new Long2IntOpenHashMap();
        private final Long2ObjectOpenHashMap<LightOverlay.ChunkLight> externalLightBaselines = new Long2ObjectOpenHashMap<LightOverlay.ChunkLight>();
        private final LongOpenHashSet externalBlockEntityKeys = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<int[][]> externalBiomeBaselines = new Long2ObjectOpenHashMap<int[][]>();
        private BiomeClaimSet biomes;
        private ObserverFrame frame;
        private BlockChangeBatch blockBatch;
        private long repairLifecycle;
        private long clientChunkRevision;
        private long chunkMemoRevision;
        private boolean retired;
        private ProjectionBlockUpdateRepair blockRepair;

        private ObserverClaims(ObserverIdentity identity, LightOverlay<Player, BlockData, ProjectionWorldView> lighting) {
            this.observerId = identity.observerId();
            this.worldId = identity.worldId();
            this.claimSet = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
            this.pendingLightingKeys = new LongOpenHashSet();
            this.pendingRevertKeys = new LongOpenHashSet();
            this.pendingSendKeys = new LongOpenHashSet();
            this.externalBlockKeys = new LongOpenHashSet(16);
            this.sentBlocks = new Long2ObjectOpenHashMap<BlockData>(256);
            this.sentChunks = new Long2ObjectOpenHashMap<SentChunk>(16);
            this.chunkSentMemo = new Long2ByteOpenHashMap(64);
            this.chunkRevisionMemo = new Long2LongOpenHashMap(64);
            this.chunkRevisionMemo.defaultReturnValue(Long.MIN_VALUE);
            this.discardedLightingChunks = new LongOpenHashSet(16);
            this.lighting = lighting;
            this.sourceLightingPortals = new HashSet<UUID>(4);
            this.biomes = null;
            this.frame = null;
            this.clientChunkRevision = Long.MIN_VALUE;
            this.chunkMemoRevision = Long.MIN_VALUE;
            this.retired = false;
        }
    }

    private static final class NbtRestorationLayer {
        private final BlockEntityLayer<Player> layer;
        private final Long2ObjectOpenHashMap<NbtRestoration> writes = new Long2ObjectOpenHashMap<NbtRestoration>();

        private NbtRestorationLayer(BlockEntityLayer<Player> layer) {
            this.layer = layer;
        }
    }

    private record NbtRestoration(BlockEntitySample sample, long chunkRevision) {
    }

    private record PendingBiomeWrite(long version) {
    }

    private record PendingRestoration(long chunkRevision) {
    }

    private record BlockEntityOwner(BlockEntityLayer<Player> layer,
                                    Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims) {
    }

    private record ObserverIdentity(UUID observerId, UUID worldId) {
    }

    private static final class BlockChangeBatch {
        private final Long2ObjectOpenHashMap<BlockData> blocks;
        private final Long2IntOpenHashMap ids;

        private BlockChangeBatch(int expectedChanges) {
            blocks = new Long2ObjectOpenHashMap<BlockData>(expectedChanges);
            ids = new Long2IntOpenHashMap(expectedChanges);
            ids.defaultReturnValue(-1);
        }
    }

    private static final class SentChunk {
        private final LongOpenHashSet keys;
        private final long revision;

        private SentChunk(long revision) {
            this.keys = new LongOpenHashSet(64);
            this.revision = revision;
        }
    }

    private static final class ObserverFrame {
        private final Player observer;
        private final World localWorld;
        private final LongOpenHashSet affectedKeys;
        private boolean allowLightingUpdate;

        private ObserverFrame(Player observer, World localWorld, boolean allowLightingUpdate) {
            this.observer = observer;
            this.localWorld = localWorld;
            this.allowLightingUpdate = allowLightingUpdate;
            this.affectedKeys = new LongOpenHashSet(256);
        }
    }
}
