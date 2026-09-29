package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectedEntityEvent;
import art.arcane.wormholes.render.EntityCandidateCache;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.network.WireMessage;
import java.util.Collection;
import net.minecraft.world.phys.AABB;

import art.arcane.wormholes.ProjectionObserverGeometry;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.EntityRenderLocalOcclusionArbiter;
import art.arcane.wormholes.portal.IPortal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.world.entity.Entity;
import java.util.concurrent.ConcurrentHashMap;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.plate.PlateWorkers;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.ProjectorLighting;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.blockentity.ProjectedBlockEntityLayer;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectionClaimSet;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftProjectionService implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftProjectionService> ACTIVE = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int SERVER_PLATE_CELLS_PER_TICK = 6144;

    private final WormholesModRuntime runtime;
    private final MinecraftProjectorPortalAccess portals;
    private final MinecraftProjectionPackets packets;
    private final EntityRenderLocalOcclusionArbiter<ServerPlayer, Entity> entityVisibility;
    private final EntityCandidateCache<ServerLevel, Entity> localEntityCandidates = new EntityCandidateCache<>(MinecraftProjectionService::queryLocalEntities);
    private final Map<SceneKey, Scene> entityScenes = new HashMap<>();
    private final ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
    private final Map<ServerLevel, MinecraftProjectionWorldView> views = new HashMap<>();
    private final Map<UUID, Observer> observers = new HashMap<>();
    private final ViewPlateCache<BlockState, ServerLevel> plates = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, this::schedulePlate);
    private PlateWorkers<BlockState, ServerLevel> plateWorkers;
    private long generation;
    private long frozenUntil;
    private long tick;
    private int observerCursor;
    private boolean closed;
    private List<MinecraftDoorService.DoorView> doorViews = List.of();

    public MinecraftProjectionService(WormholesModRuntime runtime) {
        this.runtime = runtime;
        this.portals = new MinecraftProjectorPortalAccess(runtime);
        this.packets = new MinecraftProjectionPackets(runtime);
        this.entityVisibility = new EntityRenderLocalOcclusionArbiter<>(new MinecraftEntityVisibility(runtime));
    }

    public Collection<Entity> localEntities(ServerLevel world, MinecraftPortal portal, double range) {
        return localEntityCandidates.nearby(new EntityCandidateCache.Query<>(portal.getId(), world,
            portal.getGeometry().getApertureCenter(), range, runtime.configuration().settings().getRender().entityCandidateCacheTicks),
            System.currentTimeMillis());
    }

    private static Collection<Entity> queryLocalEntities(ServerLevel world, GeometryVector center, int range) {
        return world.getEntities((Entity) null, new AABB(center.x() - range, center.y() - range, center.z() - range,
            center.x() + range, center.y() + range, center.z() + range));
    }

    public void sound(ServerLevel level, Packet<?> packet) {
        AcousticsBridge.Playback sound = MinecraftAcoustics.capture(level, packet);
        if (closed || sound == null) {
            return;
        }
        runtime.requireServerThread();
        if (!observers.isEmpty()) {
            AcousticsBridge.SoundEvent event = new AcousticsBridge.SoundEvent(view(level).worldId(), sound.x(), sound.y(), sound.z(),
                sound.soundKey(), sound.volume(), sound.pitch(), sound.soundClass());
            long now = System.currentTimeMillis();
            for (Observer observer : observers.values()) {
                observer.acoustics.onEvent(event, now);
            }
        }
        runtime.network().forwardSound(level, sound);
    }

    public void remoteSound(String peer, WireMessage.ViewSound sound) {
        AcousticsProfile.SoundClass[] categories = AcousticsProfile.SoundClass.values();
        if (closed || sound.soundClass() < 0 || sound.soundClass() >= categories.length) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Observer observer : observers.values()) {
            observer.acoustics.onRemoteSound(peer, sound.portalId(), sound.x(), sound.y(), sound.z(), sound.soundKey(),
                sound.volume(), sound.pitch(), categories[sound.soundClass()], now);
        }
    }

    public void entityEvent(Entity entity, Packet<? super ClientGamePacketListener> packet) {
        int animation = MinecraftEntityPackets.animationId(packet);
        if (animation != MinecraftEntityPackets.NO_ANIMATION) {
            publishEntityEvent(ProjectedEntityEvent.animation(entity.getUUID(), animation));
        } else if (packet instanceof ClientboundHurtAnimationPacket hurt) {
            publishEntityEvent(ProjectedEntityEvent.hurt(entity.getUUID(), hurt.yaw()));
        }
    }

    public void entityEvent(ProjectedEntityEvent event) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        for (Observer observer : observers.values()) {
            for (MinecraftPortalProjector projector : observer.projectors.values()) {
                projector.entityEvent(event);
            }
        }
    }

    private void publishEntityEvent(ProjectedEntityEvent event) {
        entityEvent(event);
        runtime.network().forwardEntityEvent(event);
    }

    public void start() {
        runtime.requireServerThread();
        closed = false;
        frozenUntil = 0L;
        ACTIVE.put(runtime.server(), this);
        tick = 0;
        observerCursor = 0;
        long startedGeneration = ++generation;
        plateWorkers = new PlateWorkers<>(FidelitySettings.plateWorkers, new PlateWorkers.Host<>() {
            @Override
            public void publish(ViewPlateBuilder.Job<BlockState, ServerLevel> job, ViewPlate<BlockState> plate) {
                if (!closed && generation == startedGeneration) {
                    plates.publish(job, plate);
                }
            }

            @Override
            public void failed(ViewPlateBuilder.Job<BlockState, ServerLevel> job) {
                if (generation == startedGeneration) {
                    plates.buildFailed(job);
                }
            }

            @Override
            public void warning(ViewPlateKey key, RuntimeException failure) {
                LOGGER.error("Wormholes plate build failed for portal {}", key.portalId(), failure);
            }
        });
    }

    public void tick() {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        tick++;
        plates.recap(FidelitySettings.plateMaxBytes);
        plates.refreshDirt(changes);
        plateWorkers.resize(FidelitySettings.plateWorkers);
        ProjectionConfig config = config();
        List<ServerPlayer> players = runtime.server().getPlayerList().getPlayers();
        Set<UUID> online = new HashSet<>(players.size());
        for (ServerPlayer player : players) {
            online.add(player.getUUID());
        }
        Iterator<Map.Entry<UUID, Observer>> stale = observers.entrySet().iterator();
        while (stale.hasNext()) {
            Map.Entry<UUID, Observer> entry = stale.next();
            if (!online.contains(entry.getKey())) {
                entry.getValue().close();
                stale.remove();
            }
        }
        entityScenes.entrySet().removeIf(entry -> tick - entry.getValue().touched > 200L);
        if (players.isEmpty() || System.currentTimeMillis() < frozenUntil) {
            return;
        }
        List<MinecraftPortal> candidates = portals.portals();
        doorViews = runtime.configuration().settings().getDoors().projectionEnabled ? runtime.doors().projectableViews() : List.of();
        long deadline = config.maxFrameMicros <= 0 ? Long.MAX_VALUE : System.nanoTime() + config.maxFrameMicros * 1_000L;
        int projectorBudget = Math.max(1, config.maxProjectorsPerTick);
        int discoveryBudget = Math.max(1, config.maxNewObserverScansPerTick);
        int start = Math.floorMod(observerCursor++, players.size());
        for (int offset = 0; offset < players.size(); offset++) {
            ServerPlayer player = players.get((start + offset) % players.size());
            Observer observer = observers.get(player.getUUID());
            if (observer == null) {
                if (discoveryBudget-- <= 0) {
                    continue;
                }
                observer = new Observer(player);
                observers.put(player.getUUID(), observer);
            }
            if (observer.world != player.level()) {
                observer.close();
                observer = new Observer(player);
                observers.put(player.getUUID(), observer);
            }
            int admitted = Math.min(projectorBudget, Math.max(1, config.maxPortalsPerObserverTick));
            try {
                projectorBudget -= observer.update(candidates, admitted, deadline);
            } catch (RuntimeException failure) {
                LOGGER.error("Wormholes projection failed for observer {}", player.getUUID(), failure);
                observer.close();
                observers.remove(player.getUUID());
            }
        }
    }

    public void freeze(int seconds) {
        runtime.requireServerThread();
        frozenUntil = seconds <= 0 ? 0L : System.currentTimeMillis() + Math.clamp(seconds, 5, 300) * 1000L;
    }

    public int flush() {
        runtime.requireServerThread();
        int count = observers.size();
        for (Observer observer : observers.values()) {
            observer.close();
        }
        observers.clear();
        entityScenes.clear();
        localEntityCandidates.clear();
        MinecraftClientProfiles.clear();
        entityVisibility.clear();
        plates.clear();
        return count;
    }

    public int observerCount() {
        return observers.size();
    }

    public int projectorCount() {
        int count = 0;
        for (Observer observer : observers.values()) {
            count += observer.projectors.size();
        }
        return count;
    }

    public static MinecraftProjectionService forServer(MinecraftServer server) {
        return ACTIVE.get(server);
    }

    public boolean isDoorProjected(UUID observerId, UUID endpointId) {
        Observer observer = observers.get(observerId);
        if (observer == null || !observer.doorwayViews.contains(endpointId)) {
            return false;
        }
        MinecraftPortalProjector projector = observer.projectors.get(endpointId);
        return projector != null && projector.scan().hasProjection();
    }

    public boolean isEntityHidden(UUID observerId, UUID entityId) {
        return entityVisibility.isClaimed(observerId, entityId);
    }

    EntityRenderLocalOcclusionArbiter<ServerPlayer, Entity> entityVisibility() {
        return entityVisibility;
    }

    MinecraftLocalEntityView scene(ServerLevel world, IPortal portal, double range) {
        SceneKey key = new SceneKey(world, portal.getId());
        Scene scene = entityScenes.computeIfAbsent(key, ignored -> new Scene(new MinecraftLocalEntityView(world, portal.getId())));
        scene.touched = tick;
        scene.view.update(portal.getOrigin(), new MinecraftLocalEntityView.Options(range,
            runtime.configuration().settings().getRender().entityCandidateCacheTicks));
        return scene.view;
    }

    public void worldChanged(ServerLevel world, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        MinecraftProjectionWorldView view = views.get(world);
        if (view != null) {
            view.invalidate();
            changes.markChanged(view.worldId(), chunkX << 4, chunkZ << 4);
        }
    }

    public ProjectionWorldChangeTracker changes() {
        return changes;
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        Observer observer = observers.remove(player.getUUID());
        if (observer != null) {
            observer.close();
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        generation++;
        if (plateWorkers != null) {
            plateWorkers.shutdown();
            plateWorkers = null;
        }
        plates.clear();
        for (Observer observer : observers.values()) {
            observer.close();
        }
        observers.clear();
        for (MinecraftProjectionWorldView view : views.values()) {
            view.close();
            changes.clearWorld(view.worldId());
        }
        views.clear();
        entityScenes.clear();
        localEntityCandidates.clear();
        MinecraftClientProfiles.clear();
        entityVisibility.clear();
        ACTIVE.remove(runtime.server(), this);
    }

    private void schedulePlate(ViewPlateBuilder.Job<BlockState, ServerLevel> job) {
        if (closed || plateWorkers == null) {
            plates.buildFailed(job);
            return;
        }
        if (!(job.key().destinationViewIdentity() instanceof MinecraftProjectionWorldView)) {
            plateWorkers.submitAsync(job);
            return;
        }
        long startedGeneration = generation;
        if (!runtime.schedule(() -> stepPlateOnServer(job, startedGeneration), 0L)) {
            plates.buildFailed(job);
        }
    }

    private void stepPlateOnServer(ViewPlateBuilder.Job<BlockState, ServerLevel> job, long startedGeneration) {
        if (closed || generation != startedGeneration || !plates.isBuilding(job)) {
            plates.buildFailed(job);
            return;
        }
        boolean finished;
        try {
            finished = job.step(SERVER_PLATE_CELLS_PER_TICK);
        } catch (RuntimeException failure) {
            plates.buildFailed(job);
            LOGGER.error("Wormholes plate build failed for portal {}", job.key().portalId(), failure);
            return;
        }
        if (finished) {
            plates.publish(job, job.result());
            return;
        }
        if (!runtime.schedule(() -> stepPlateOnServer(job, startedGeneration), 1L)) {
            plates.buildFailed(job);
        }
    }

    private MinecraftProjectionWorldView view(ServerLevel world) {
        return views.computeIfAbsent(world, level -> new MinecraftProjectionWorldView(runtime, level));
    }

    private ProjectionConfig config() {
        return runtime.configuration().settings().getProjection();
    }

    private boolean interested(ServerPlayer player, MinecraftPortal portal, MinecraftProjectorPortalAccess portals) {
        if (!portals.eligible(portal) || portals.world(portal) != player.level()) {
            return false;
        }
        Vec3 position = player.position();
        if (!portals.view(portal).containsPrimitive(position.x, position.y, position.z)) {
            return false;
        }
        if (!portal.isMirrorMode() && !portals.hasDestination(portal)) {
            return false;
        }
        if (!config().foveatedUnrendering) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        GeometryVector origin = portal.getOrigin();
        GeometryVector center = portal.getGeometry().getApertureCenter();
        Direction normal = portal.getFrame().getNormal();
        return ProjectionObserverGeometry.hasStablePortalSide(eye.x, eye.y, eye.z, origin.x(), origin.y(), origin.z(),
            normal.x(), normal.y(), normal.z(), config().sideGraceDot)
            && ProjectionObserverGeometry.isLookingTowardPortal(eye.x, eye.y, eye.z, center.x(), center.y(), center.z(),
                look.x, look.y, look.z, config().observerInterestDot);
    }

    private final class Observer {
        private final ServerPlayer player;
        private final ServerLevel world;
        private final MinecraftDoorProjectionViews doorwayViews = new MinecraftDoorProjectionViews(runtime);
        private final MinecraftProjectorPortalAccess portals = new MinecraftProjectorPortalAccess(runtime);
        private final AcousticsBridge<ServerPlayer> acoustics;
        private final Map<UUID, MinecraftPortalProjector> projectors = new HashMap<>();
        private final Map<UUID, Long> grace = new HashMap<>();
        private final ProjectionClaimSet<ProjectedBlockClaim<BlockState, ProjectionContentView<BlockState, BlockState>>> claims = new ProjectionClaimSet<>();
        private final LongOpenHashSet staged = new LongOpenHashSet();
        private final LongOpenHashSet pending = new LongOpenHashSet();
        private final Long2ObjectMap<LongOpenHashSet> sentChunks = new Long2ObjectOpenHashMap<>();
        private final ProjectedBlockEntityLayer<ServerPlayer> blockEntities =
            new ProjectedBlockEntityLayer<>(new MinecraftBlockEntityPackets(runtime));
        private final ProjectorLighting<ServerPlayer, BlockState, ProjectionContentView<BlockState, BlockState>> lighting =
            new ProjectorLighting<>(new MinecraftProjectorLighting(runtime));
        private final LongOpenHashSet dirtyLight = new LongOpenHashSet();
        private final MinecraftAtmosphere atmosphere;
        private final MinecraftPortalSurfaces surfaces;
        private int cursor;
        private long lastLightTick = Long.MIN_VALUE;

        private Observer(ServerPlayer player) {
            this.player = player;
            this.world = player.level();
            this.acoustics = new AcousticsBridge<>(new AcousticsBridge.Options<>(MinecraftAcoustics::play,
                ignored -> List.of(player), ServerPlayer::getUUID));
            this.portals.setDoorViews(doorwayViews);
            this.portals.observer(player);
            this.atmosphere = new MinecraftAtmosphere(runtime, new MinecraftAtmosphere.Context(player, view(world)));
            this.surfaces = new MinecraftPortalSurfaces(runtime, new MinecraftPortalSurfaces.Context(player, claims, staged));
        }

        private int update(List<MinecraftPortal> candidates, int budget, long deadline) {
            List<MinecraftPortal> available = new ArrayList<>(candidates);
            available.addAll(doorwayViews.update(player, doorViews));
            ArrayList<MinecraftPortal> active = new ArrayList<>();
            Set<UUID> activeIds = new HashSet<>();
            for (MinecraftPortal portal : available) {
                boolean interested = interested(player, portal, portals);
                if (interested) {
                    grace.put(portal.getId(), tick + Math.max(0, config().interestGraceTicks));
                }
                if (interested || grace.getOrDefault(portal.getId(), Long.MIN_VALUE) >= tick) {
                    active.add(portal);
                    activeIds.add(portal.getId());
                }
            }
            grace.keySet().retainAll(activeIds);
            Iterator<Map.Entry<UUID, MinecraftPortalProjector>> iterator = projectors.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, MinecraftPortalProjector> entry = iterator.next();
                if (!activeIds.contains(entry.getKey())) {
                    claims.stagePortalRelease(entry.getKey(), staged);
                    atmosphere.remove(entry.getKey());
                    acoustics.forgetPortal(entry.getKey());
                    entry.getValue().close();
                    iterator.remove();
                }
            }
            Vec3 eye = player.getEyePosition();
            active.sort(Comparator.comparingDouble(portal -> {
                GeometryVector center = portal.getGeometry().getApertureCenter();
                return eye.distanceToSqr(center.x(), center.y(), center.z());
            }));
            int processed = 0;
            if (!active.isEmpty() && budget > 0) {
                int start = Math.floorMod(cursor, active.size());
                for (int offset = 0; offset < active.size() && processed < budget; offset++) {
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    MinecraftPortal portal = active.get((start + offset) % active.size());
                    MinecraftPortalProjector projector = projectors.computeIfAbsent(portal.getId(), ignored ->
                        new MinecraftPortalProjector(runtime, new MinecraftPortalProjector.Context(player, portal,
                            MinecraftProjectionService.this::view, portals, plates)));
                    MinecraftPortalProjector.Result result = projector.update(tick, deadline);
                    if (result == MinecraftPortalProjector.Result.READY) {
                        claims.stagePortalDelta(portal.getId(), portal.getId().toString(), Math.abs(projector.scan().eyeDot()),
                            projector.claimDelta(), staged);
                        projector.commit();
                        atmosphere.update(projector, true);
                    } else if (result == MinecraftPortalProjector.Result.CLOSED) {
                        claims.stagePortalRelease(portal.getId(), staged);
                        projectors.remove(portal.getId());
                        atmosphere.remove(portal.getId());
                        acoustics.forgetPortal(portal.getId());
                        projector.close();
                    }
                    processed++;
                }
                cursor = (start + processed) % active.size();
            }
            surfaces.update(candidates, portals, tick);
            if (!staged.isEmpty()) {
                ProjectionClaimSet.ProjectionClaimSetResult resolved = claims.resolveStaged(staged);
                pending.addAll(resolved.getPacketChangeKeys());
                dirtyLight.addAll(resolved.getDirtyLightingKeys());
                staged.clear();
            }
            for (MinecraftPortalProjector projector : projectors.values()) {
                projector.updateWeather(tick);
                projector.noteAcoustics(acoustics);
            }
            acoustics.tickAmbient(System.currentTimeMillis());
            reconcileSentChunks();
            flushBlocks();
            flushBlockEntities();
            flushLighting();
            atmosphere.flush();
            if (tick % Math.max(1, runtime.configuration().settings().getRender().entityUpdateIntervalTicks) == 0L) {
                entityVisibility.beginFrame(player);
                try {
                    for (MinecraftPortalProjector projector : projectors.values()) {
                        projector.updateEntities();
                    }
                } finally {
                    entityVisibility.flushFrame(player);
                }
            }
            return processed;
        }

        private void reconcileSentChunks() {
            Iterator<Long2ObjectMap.Entry<LongOpenHashSet>> iterator = sentChunks.long2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                Long2ObjectMap.Entry<LongOpenHashSet> entry = iterator.next();
                long chunk = entry.getLongKey();
                if (!world.getChunkSource().chunkMap.isChunkTracked(player, (int) (chunk >> 32), (int) chunk)) {
                    pending.addAll(entry.getValue());
                    blockEntities.invalidateSent();
                    lighting.discardChunk((int) (chunk >> 32), (int) chunk);
                    atmosphere.resend((int) (chunk >> 32), (int) chunk);
                    dirtyLight.addAll(entry.getValue());
                    iterator.remove();
                }
            }
        }

        private void flushBlocks() {
            Long2ObjectMap<BlockState> changed = new Long2ObjectOpenHashMap<>();
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            LongIterator iterator = pending.iterator();
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                int x = ProjectionCellKey.unpackX(key);
                int y = ProjectionCellKey.unpackY(key);
                int z = ProjectionCellKey.unpackZ(key);
                ProjectedBlockClaim<BlockState, ProjectionContentView<BlockState, BlockState>> claim = claims.getWinningClaim(key);
                if (!world.getChunkSource().chunkMap.isChunkTracked(player, x >> 4, z >> 4)) {
                    if (claim == null) {
                        iterator.remove();
                    }
                    continue;
                }
                BlockState block;
                if (claim == null) {
                    LevelChunk chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) {
                        continue;
                    }
                    block = chunk.getBlockState(position.set(x, y, z));
                } else {
                    block = claim.getData();
                }
                changed.put(key, block);
                long chunkKey = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                if (claim == null) {
                    LongOpenHashSet sent = sentChunks.get(chunkKey);
                    if (sent != null) {
                        sent.remove(key);
                        if (sent.isEmpty()) {
                            sentChunks.remove(chunkKey);
                        }
                    }
                } else {
                    sentChunks.computeIfAbsent(chunkKey, ignored -> new LongOpenHashSet()).add(key);
                }
                iterator.remove();
            }
            packets.sendBlocks(player, world, changed);
        }

        private void flushLighting() {
            if (!MinecraftClientProfiles.profile(player).lightingFidelity()) {
                lighting.revert(player, view(world));
                dirtyLight.clear();
                return;
            }
            RenderConfig render = runtime.configuration().settings().getRender();
            boolean refresh = lastLightTick == Long.MIN_VALUE
                || tick - lastLightTick >= Math.clamp(render.lightingRefreshIntervalTicks, 1, 200);
            boolean sourceLighting = render.lightingFidelity;
            if (FidelitySettings.skyLight && !sourceLighting) {
                for (MinecraftPortalProjector projector : projectors.values()) {
                    if (projector.atmosphereMode().promotesSkyLight()) {
                        sourceLighting = true;
                        break;
                    }
                }
            }
            lighting.apply(player, view(world), claims.getWinningClaims(), refresh ? null : dirtyLight, sourceLighting);
            dirtyLight.clear();
            if (refresh) {
                lastLightTick = tick;
            }
        }

        private void flushBlockEntities() {
            Long2ObjectMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<>();
            for (MinecraftPortalProjector projector : projectors.values()) {
                for (Long2ObjectMap.Entry<BlockEntitySample> entry : projector.scan().blockEntities().long2ObjectEntrySet()) {
                    long key = entry.getLongKey();
                    if (pending.contains(key) || projector.scan().claims().get(key) != claims.getWinningClaim(key)) {
                        continue;
                    }
                    int x = ProjectionCellKey.unpackX(key);
                    int z = ProjectionCellKey.unpackZ(key);
                    if (world.getChunkSource().chunkMap.isChunkTracked(player, x >> 4, z >> 4)) {
                        desired.put(key, entry.getValue());
                    }
                }
            }
            blockEntities.update(desired, this::localBlockEntity);
            blockEntities.flush(player, FidelitySettings.blockEntityBudgetPerTick);
        }

        private BlockEntitySample localBlockEntity(int x, int y, int z) {
            if (claims.getWinningClaim(ProjectionCellKey.pack(x, y, z)) != null) {
                return null;
            }
            return view(world).sampleBlockEntity(x, y, z);
        }

        private void close() {
            try {
                surfaces.close();
                if (!player.hasDisconnected() && player.level() == world) {
                    packets.restoreBlocks(player, world, claims.getWinningClaims().keySet());
                    blockEntities.retireAll(view(world)::sampleBlockEntity);
                    blockEntities.flush(player, Integer.MAX_VALUE);
                    lighting.revert(player, view(world));
                }
                atmosphere.close();
            } finally {
                for (MinecraftPortalProjector projector : projectors.values()) {
                    projector.close();
                }
                projectors.clear();
                acoustics.clear();
                blockEntities.clear();
                lighting.discard();
                dirtyLight.clear();
                claims.clear();
                grace.clear();
                pending.clear();
                staged.clear();
                sentChunks.clear();
            }
        }
    }
    private record SceneKey(ServerLevel world, UUID portalId) {
    }

    private static final class Scene {
        private final MinecraftLocalEntityView view;
        private long touched;

        private Scene(MinecraftLocalEntityView view) {
            this.view = view;
        }
    }
}
