package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.entity.CandidateCache;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.wormholes.network.WireMessage;
import java.util.Collection;

import art.arcane.optics.aperture.ObserverGeometry;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.wormholes.portal.IPortal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import java.util.concurrent.ConcurrentHashMap;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.plate.PlatePipeline;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.view.SectionCache;
import art.arcane.optics.light.ProjectorLighting;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.ProjectedBlockEntityLayer;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectionBlockSlices;
import art.arcane.optics.claim.ProjectionClaimSet;
import art.arcane.optics.volume.GazeScheduler;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class MinecraftProjectionService implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftProjectionService> ACTIVE = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final HashMap<UUID, Long> hurtEventTicks = new HashMap<>();
    private final MinecraftProjectorPortalAccess portals;
    private final MinecraftProjectionPackets packets;
    private final LocalOcclusionArbiter<ServerPlayer, Entity> entityVisibility;
    private final CandidateCache<ServerLevel, Entity> localEntityCandidates = new CandidateCache<>(MinecraftEntityVisualHost.FEED);
    private final Map<SceneKey, Scene> entityScenes = new HashMap<>();
    private final WorldChangeTracker changes = new WorldChangeTracker();
    private final MinecraftPlateSnapshotCache plateSnapshots = new MinecraftPlateSnapshotCache(changes, MinecraftPlateSnapshotCache.VIEW_LIMITS);
    private final Map<ServerLevel, MinecraftProjectionWorldView> views = new HashMap<>();
    private final Map<UUID, Observer> observers = new HashMap<>();
    private final SectionCache<BlockState, BlockState> sections = new SectionCache<>(MinecraftProjectorBlocks.INSTANCE, SectionCache.Limits.from(true, 64, 16, 200));
    private final SectionEviction eviction = new SectionEviction();
    private SectionCache.Limits limits = SectionCache.Limits.from(true, 64, 16, 200);
    private final MinecraftOpticsScheduler scheduler;
    private final PlatePipeline<BlockState, ServerLevel> platePipeline;
    private final ViewPlateCache<BlockState, ServerLevel> plates;
    private long frozenUntil;
    private long tick;
    private int observerCursor;
    private boolean closed;
    private List<MinecraftDoorService.DoorView> doorViews = List.of();

    public MinecraftProjectionService(WormholesModRuntime runtime) {
        this.runtime = runtime;
        this.portals = new MinecraftProjectorPortalAccess(runtime);
        this.packets = new MinecraftProjectionPackets(runtime);
        this.scheduler = new MinecraftOpticsScheduler(runtime, () -> tick);
        this.entityVisibility = new LocalOcclusionArbiter<>(MinecraftEntityVisualHost.FEED, new MinecraftEntityPackets(runtime), scheduler);
        this.platePipeline = new PlatePipeline<>(FidelitySettings.plateMaxBytes, scheduler,
            (message, failure) -> LOGGER.error("Wormholes plate " + message, failure));
        this.plates = platePipeline.cache();
    }

    public Collection<Entity> localEntities(ServerLevel world, MinecraftPortal portal, double range) {
        return localEntityCandidates.nearby(new CandidateCache.Query<>(portal.getId(), world,
            portal.getGeometry().getApertureCenter(), range, runtime.configuration().settings().getRender().entityCandidateCacheTicks),
            System.currentTimeMillis());
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

    public void noteClientViewAcoustics(ServerPlayer player, UUID portalId, ServerLevel destination, double destinationX, double destinationY,
                                        double destinationZ, Vec3d aperture, AcousticsProfile profile) {
        Observer observer = observers.get(player.getUUID());
        if (observer == null || destination == null) {
            return;
        }
        observer.acoustics.noteDestination(portalId, view(destination).worldId(), destinationX, destinationY, destinationZ, aperture.x(), aperture.y(),
            aperture.z(), profile, MinecraftAcoustics.environment(destination), destination.isRaining(), System.currentTimeMillis());
    }

    public AcousticsBridge.Playback ambientBed(ServerPlayer player, UUID portalId) {
        Observer observer = observers.get(player.getUUID());
        return observer == null ? null : observer.acoustics.ambientBed(portalId);
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
        } else if (packet instanceof ClientboundHurtAnimationPacket || packet instanceof ClientboundDamageEventPacket) {
            Long previous = hurtEventTicks.put(entity.getUUID(), (long) tick);
            if (previous != null && previous == tick) {
                return;
            }
            float yaw = packet instanceof ClientboundHurtAnimationPacket hurt ? hurt.yaw() : entity.getYRot();
            publishEntityEvent(ProjectedEntityEvent.hurt(entity.getUUID(), yaw));
        }
    }

    public void entityEvent(ProjectedEntityEvent event) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        runtime.clientViews().entityEvent(event);
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
        hurtEventTicks.clear();
        observerCursor = 0;
        limits = limits(config());
        sections.configure(limits);
        changes.addListener(eviction);
        scheduler.start(FidelitySettings.plateWorkers);
    }

    public void tick() {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        tick++;
        if (tick % 100 == 0) {
            hurtEventTicks.values().removeIf(eventTick -> tick - eventTick > 100);
        }
        ProjectionConfig config = config();
        plates.recap(FidelitySettings.plateMaxBytes);
        plates.refreshDirt(changes);
        SectionCache.Limits configured = limits(config);
        if (!configured.equals(limits)) {
            limits = configured;
            sections.configure(configured);
        }
        sections.tick((int) tick);
        platePipeline.tickCaptures(FidelitySettings.plateCaptureChunksPerTick, FidelitySettings.plateUrgentCaptureChunksPerTick);
        scheduler.resize(FidelitySettings.plateWorkers);
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
        List<MinecraftPortal> candidates = portals.endpoints();
        doorViews = runtime.doors().projectableViews();
        MinecraftClientViewService clientViews = runtime.clientViews();
        clientViews.tick(tick, players, candidates);
        long deadline = config.maxFrameMicros <= 0 ? Long.MAX_VALUE : System.nanoTime() + config.maxFrameMicros * 1_000L;
        int projectorBudget = Math.max(1, config.maxProjectorsPerTick);
        int discoveryBudget = Math.max(1, config.maxNewObserverScansPerTick);
        int start = Math.floorMod(observerCursor++, players.size());
        for (int offset = 0; offset < players.size(); offset++) {
            ServerPlayer player = players.get((start + offset) % players.size());
            if (clientViews.holdsVanilla(player.getUUID())) {
                continue;
            }
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
        platePipeline.clear();
        plateSnapshots.clear();
        sections.clear();
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

    public ViewPlateCache<BlockState, ServerLevel> plates() {
        return plates;
    }

    MinecraftPlateSnapshotCache plateSnapshots() {
        runtime.requireServerThread();
        return plateSnapshots;
    }

    public Executor lanes() {
        return scheduler.compute();
    }

    public MinecraftOpticsScheduler scheduler() {
        return scheduler;
    }

    public int plateCaptureQueueSize() {
        return platePipeline.queuedCaptures();
    }

    public long sectionCacheBytes() {
        return sections.bytes();
    }

    public int sectionCacheSections() {
        return sections.sectionCount();
    }

    public static MinecraftProjectionService forServer(MinecraftServer server) {
        return ACTIVE.get(server);
    }

    public boolean isDoorProjected(UUID observerId, UUID endpointId) {
        if (runtime.clientViews().owns(observerId, endpointId)) {
            return true;
        }
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

    LocalOcclusionArbiter<ServerPlayer, Entity> entityVisibility() {
        return entityVisibility;
    }

    public MinecraftLocalEntityView scene(ServerLevel world, IPortal portal, double range) {
        SceneKey key = new SceneKey(world, portal.getId());
        Scene scene = entityScenes.computeIfAbsent(key, ignored -> new Scene(new MinecraftLocalEntityView(world, portal.getId())));
        scene.touched = tick;
        scene.view.update(portal.getOrigin(), new MinecraftLocalEntityView.Options(range,
            runtime.configuration().settings().getRender().entityCandidateCacheTicks));
        return scene.view;
    }

    public void blockChanged(ServerLevel world, BlockPos position) {
        runtime.requireServerThread();
        MinecraftProjectionWorldView view = views.get(world);
        if (view != null) {
            changes.markChanged(view.worldId(), position.getX(), position.getY(), position.getZ());
        }
    }

    public void columnChanged(ServerLevel world, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        MinecraftProjectionWorldView view = views.get(world);
        if (view != null) {
            changes.markChanged(view.worldId(), chunkX << 4, chunkZ << 4);
        }
    }

    public WorldChangeTracker changes() {
        return changes;
    }

    public List<MinecraftDoorService.DoorView> projectableDoors() {
        return doorViews;
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        runtime.clientViews().disconnected(player);
        Observer observer = observers.remove(player.getUUID());
        if (observer != null) {
            observer.close();
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        scheduler.shutdown();
        platePipeline.clear();
        plateSnapshots.clear();
        changes.removeListener(eviction);
        for (Observer observer : observers.values()) {
            observer.close();
        }
        observers.clear();
        for (MinecraftProjectionWorldView view : views.values()) {
            sections.release(view.sections());
            view.close();
            changes.clearWorld(view.worldId());
        }
        views.clear();
        sections.clear();
        entityScenes.clear();
        localEntityCandidates.clear();
        MinecraftClientProfiles.clear();
        entityVisibility.clear();
        ACTIVE.remove(runtime.server(), this);
    }

    public MinecraftProjectionWorldView view(ServerLevel world) {
        runtime.requireServerThread();
        return views.computeIfAbsent(world, this::createView);
    }

    private MinecraftProjectionWorldView createView(ServerLevel level) {
        SectionCache<BlockState, BlockState>.WorldSections worldSections = sections.world(
            new MinecraftSectionSource(level, Blocks.AIR.defaultBlockState()), level.getMinSectionY(), level.getMaxSectionY());
        return new MinecraftProjectionWorldView(runtime, level, worldSections);
    }

    private MinecraftProjectionWorldView viewById(UUID worldId) {
        for (MinecraftProjectionWorldView view : views.values()) {
            if (view.worldId().equals(worldId)) {
                return view;
            }
        }
        return null;
    }

    private ProjectionConfig config() {
        return runtime.configuration().settings().getProjection();
    }

    static boolean blockPassTick(long tick, int refreshIntervalTicks) {
        return tick % Math.max(1, refreshIntervalTicks) == 0L;
    }

    static List<GazeScheduler.Candidate<MinecraftPortal>> blockCandidates(List<MinecraftPortal> active, Predicate<MinecraftPortal> pendingScan,
                                                                          Predicate<MinecraftPortal> destinationChanged, boolean passTick) {
        List<GazeScheduler.Candidate<MinecraftPortal>> candidates = new ArrayList<>(active.size());
        for (MinecraftPortal portal : active) {
            boolean pending = pendingScan.test(portal);
            if (passTick || pending) {
                candidates.add(gazeCandidate(portal, pending, destinationChanged.test(portal)));
            }
        }
        return candidates;
    }

    static GazeScheduler.Candidate<MinecraftPortal> gazeCandidate(MinecraftPortal portal, boolean pendingScan, boolean destinationChanged) {
        Box area = portal.getGeometry().getArea();
        if (area != null) {
            return new GazeScheduler.Candidate<>(portal, portal.getId(), area.getXa(), area.getYa(), area.getZa(),
                area.getXb(), area.getYb(), area.getZb(), pendingScan, destinationChanged, false);
        }
        Vec3d origin = portal.getOrigin();
        return new GazeScheduler.Candidate<>(portal, portal.getId(), origin.x() - 0.5D, origin.y() - 0.5D, origin.z() - 0.5D,
            origin.x() + 0.5D, origin.y() + 0.5D, origin.z() + 0.5D, pendingScan, destinationChanged, false);
    }

    private static GazeScheduler.Options gazeOptions(ProjectionConfig config) {
        return new GazeScheduler.Options(config.gazeFovDegrees, config.gazeLookaheadTicks, config.gazeMaxStarveTicks);
    }

    private static SectionCache.Limits limits(ProjectionConfig config) {
        return SectionCache.Limits.from(config.sectionCache, config.sectionCacheMaxMb, config.sectionCacheChunksPerTick, config.sectionCacheTtlTicks);
    }

    public boolean attendable(ServerPlayer player, MinecraftPortal portal, MinecraftProjectorPortalAccess portals) {
        if (!portals.eligible(portal) || portals.world(portal) != player.level()) {
            return false;
        }
        Vec3 position = player.position();
        if (!portals.view(portal).containsPrimitive(position.x, position.y, position.z)) {
            return false;
        }
        return portal.isMirrorMode() || portals.hasDestination(portal);
    }

    public boolean interested(ServerPlayer player, MinecraftPortal portal, MinecraftProjectorPortalAccess portals) {
        if (!attendable(player, portal, portals)) {
            return false;
        }
        if (!config().foveatedUnrendering) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3d origin = portal.getOrigin();
        Vec3d center = portal.getGeometry().getApertureCenter();
        Face normal = portal.getFrame().getNormal();
        return ObserverGeometry.hasStablePortalSide(eye.x, eye.y, eye.z, origin.x(), origin.y(), origin.z(),
            normal.x(), normal.y(), normal.z(), config().sideGraceDot)
            && ObserverGeometry.isLookingTowardPortal(eye.x, eye.y, eye.z, center.x(), center.y(), center.z(),
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
        private final ProjectionClaimSet<ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>>> claims = new ProjectionClaimSet<>();
        private final LongOpenHashSet staged = new LongOpenHashSet();
        private final LongOpenHashSet displacedClaimKeys = new LongOpenHashSet();
        private final LongOpenHashSet restoredClaimKeys = new LongOpenHashSet();
        private final LongOpenHashSet pending = new LongOpenHashSet();
        private final Long2ObjectMap<LongOpenHashSet> sentChunks = new Long2ObjectOpenHashMap<>();
        private final ProjectedBlockEntityLayer<ServerPlayer> blockEntities = new ProjectedBlockEntityLayer<>(WormholesTelemetry.metrics());
        private final MinecraftProjectionOutput output;
        private final ProjectorLighting<ServerPlayer, BlockState, ContentView<BlockState, BlockState>> lighting;
        private final LongOpenHashSet dirtyLight = new LongOpenHashSet();
        private final MinecraftAtmosphere atmosphere;
        private final MinecraftPortalSurfaces surfaces;
        private final GazeScheduler gaze = new GazeScheduler();
        private long lastLightTick = Long.MIN_VALUE;

        private Observer(ServerPlayer player) {
            this.player = player;
            this.world = player.level();
            this.output = new MinecraftProjectionOutput(runtime, player);
            this.lighting = new ProjectorLighting<>(output, () -> changes, WormholesTelemetry.metrics());
            this.acoustics = new AcousticsBridge<>(new AcousticsBridge.Options<>(output, ServerPlayer::getUUID, FidelitySettings::snapshot));
            this.portals.setDoorViews(doorwayViews);
            this.portals.observer(player);
            this.atmosphere = new MinecraftAtmosphere(runtime, new MinecraftAtmosphere.Context(player, view(world), output));
            this.surfaces = new MinecraftPortalSurfaces(runtime, new MinecraftPortalSurfaces.Context(player, claims, staged));
        }

        private int update(List<MinecraftPortal> candidates, int budget, long deadline) {
            List<MinecraftPortal> available = new ArrayList<>(candidates);
            available.addAll(doorwayViews.update(player, doorViews, false));
            ArrayList<MinecraftPortal> active = new ArrayList<>();
            Set<UUID> activeIds = new HashSet<>();
            MinecraftClientViewService clientViews = runtime.clientViews();
            for (MinecraftPortal portal : available) {
                if (clientViews.owns(player.getUUID(), portal.getId())) {
                    continue;
                }
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
            List<GazeScheduler.Candidate<MinecraftPortal>> gazeCandidates = blockCandidates(active, this::pendingScan,
                this::destinationChanged, blockPassTick(tick, config().refreshIntervalTicks));
            Vec3 eye = player.getEyePosition();
            List<MinecraftPortal> selected = gaze.select(player.getUUID(),
                new GazeScheduler.Eye(eye.x, eye.y, eye.z, player.getYRot(), player.getXRot()),
                gazeCandidates, budget, tick, gazeOptions(config()));
            gaze.retain(player.getUUID(), activeIds);
            ProjectionBlockSlices slices = new ProjectionBlockSlices(selected.size());
            int processed = 0;
            for (MinecraftPortal portal : selected) {
                if (System.nanoTime() >= deadline) {
                    break;
                }
                MinecraftPortalProjector projector = projectors.computeIfAbsent(portal.getId(), ignored ->
                    new MinecraftPortalProjector(runtime, new MinecraftPortalProjector.Context(player, portal,
                        MinecraftProjectionService.this::view, portals, plates)));
                boolean losingResync = projector.scan().losingClaimsUnsynced();
                if (claims.drainLosingTransitions(portal.getId(), displacedClaimKeys, restoredClaimKeys, losingResync) || losingResync) {
                    projector.scan().exposeLosingClaims(displacedClaimKeys, restoredClaimKeys, losingResync);
                    displacedClaimKeys.clear();
                    restoredClaimKeys.clear();
                }
                MinecraftPortalProjector.Result result = projector.update(tick, slices.next(deadline));
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
            surfaces.update(candidates, portals, tick);
            if (!staged.isEmpty()) {
                ProjectionClaimSet.ProjectionClaimSetResult resolved = claims.resolveStaged(staged);
                pending.addAll(resolved.getPacketChangeKeys());
                dirtyLight.addAll(resolved.getDirtyLightingKeys());
                staged.clear();
            }
            for (MinecraftPortalProjector projector : projectors.values()) {
                projector.updateWeather(tick, output);
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

        private boolean pendingScan(MinecraftPortal portal) {
            MinecraftPortalProjector existing = projectors.get(portal.getId());
            return existing != null && existing.scan().hasPending();
        }

        private boolean destinationChanged(MinecraftPortal portal) {
            MinecraftPortalProjector existing = projectors.get(portal.getId());
            return existing != null && existing.destinationChangePending();
        }

        private void reconcileSentChunks() {
            Iterator<Long2ObjectMap.Entry<LongOpenHashSet>> iterator = sentChunks.long2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                Long2ObjectMap.Entry<LongOpenHashSet> entry = iterator.next();
                long chunk = entry.getLongKey();
                if (!world.getChunkSource().chunkMap.isChunkTracked(player, CellKeys.chunkX(chunk), CellKeys.chunkZ(chunk))) {
                    pending.addAll(entry.getValue());
                    blockEntities.invalidateSent();
                    lighting.discardChunk(CellKeys.chunkX(chunk), CellKeys.chunkZ(chunk));
                    atmosphere.resend(CellKeys.chunkX(chunk), CellKeys.chunkZ(chunk));
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
                int x = CellKeys.unpackX(key);
                int y = CellKeys.unpackY(key);
                int z = CellKeys.unpackZ(key);
                ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>> claim = claims.getWinningClaim(key);
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
                long chunkKey = CellKeys.chunkKey(x >> 4, z >> 4);
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
                    int x = CellKeys.unpackX(key);
                    int z = CellKeys.unpackZ(key);
                    if (world.getChunkSource().chunkMap.isChunkTracked(player, x >> 4, z >> 4)) {
                        desired.put(key, entry.getValue());
                    }
                }
            }
            blockEntities.update(desired, this::localBlockEntity);
            blockEntities.flush(player, FidelitySettings.blockEntityBudgetPerTick, output);
        }

        private BlockEntitySample localBlockEntity(int x, int y, int z) {
            if (claims.getWinningClaim(CellKeys.pack(x, y, z)) != null) {
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
                    blockEntities.flush(player, Integer.MAX_VALUE, output);
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
    private final class SectionEviction implements WorldChangeTracker.ChangeListener {
        @Override
        public void blockChanged(UUID worldId, long blockKey) {
            MinecraftProjectionWorldView view = viewById(worldId);
            if (view != null) {
                view.sections().blockChanged(CellKeys.unpackX(blockKey), CellKeys.unpackY(blockKey),
                    CellKeys.unpackZ(blockKey));
            }
        }

        @Override
        public void columnChanged(UUID worldId, int chunkX, int chunkZ) {
            MinecraftProjectionWorldView view = viewById(worldId);
            if (view != null) {
                view.sections().columnChanged(chunkX, chunkZ);
            }
        }

        @Override
        public void worldCleared(UUID worldId) {
            plateSnapshots.clearWorld(worldId);
            MinecraftProjectionWorldView view = viewById(worldId);
            if (view != null) {
                view.sections().clear();
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
