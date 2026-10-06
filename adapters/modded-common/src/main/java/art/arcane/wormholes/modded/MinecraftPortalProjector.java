package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.math.CellKeys;
import net.minecraft.core.particles.ParticleTypes;
import java.util.Random;

import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.view.EntityData;
import art.arcane.wormholes.render.view.RemoteProjectionView;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.syncher.SynchedEntityData;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.claim.ProjectionBlackout;
import art.arcane.optics.claim.ProjectionClaimSet;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.volume.FrustumFit;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.scan.ResampleSchedule;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.ProjectorViewSettings;
import art.arcane.optics.math.Face;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.scan.ProjectorSampler;
import art.arcane.optics.scan.ScanDestination;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.optics.volume.LodProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class MinecraftPortalProjector implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final ServerPlayer observer;
    private final MinecraftPortal portal;
    private final Function<ServerLevel, MinecraftProjectionWorldView> views;
    private final MinecraftProjectorPortalAccess portals;
    private final ProjectorSampleMemo<BlockState, BlockState, ContentView<BlockState, BlockState>> memo;
    private final ProjectorSampler<BlockState, BlockState, ServerLevel, MinecraftPortal, ContentView<BlockState, BlockState>> sampler;
    private final CellScan<BlockState, BlockState, ServerLevel, MinecraftPortal, ContentView<BlockState, BlockState>> scan;
    private final FrustumFit fit;
    private final ResampleSchedule schedule;
    private final ViewPlateCache<BlockState, ServerLevel> plates;
    private final Blackout blackout = new Blackout();
    private Destination pendingDestination;
    private Vec3d pendingEye;
    private long pendingGeometryRevision;
    private long pendingTargetGeometryRevision;
    private long pendingPresentationRevision;
    private long lastPassTick = Long.MIN_VALUE;
    private int fullSendPasses;
    private boolean firstPassDone;
    private boolean closed;
    private RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> remoteSource;
    private RemoteProjectionView<BlockState, BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> remoteView;
    private String remoteFallbackState;
    private MinecraftProjectedEntities entities;
    private final WeatherRelay weather = new WeatherRelay();
    private final Random weatherRandom = new Random();

    public MinecraftPortalProjector(WormholesModRuntime runtime, Context context) {
        this.runtime = Objects.requireNonNull(runtime);
        this.observer = Objects.requireNonNull(context.observer());
        this.portal = Objects.requireNonNull(context.portal());
        this.views = Objects.requireNonNull(context.views());
        this.portals = Objects.requireNonNull(context.portals());
        this.plates = context.plates();
        WorldChangeTracker changes = runtime.projections().changes();
        this.memo = new ProjectorSampleMemo<>(MinecraftProjectorBlocks.INSTANCE, () -> changes);
        this.schedule = new ResampleSchedule(() -> ProjectorViewSettings.viewCadence(portal), () -> changes, this::cadence);
        this.sampler = new ProjectorSampler<>(new ProjectorSampler.Options<>(memo,
            portals.createRecursiveIndex(), views::apply,
                view -> view instanceof MinecraftProjectionWorldView local ? local.getWorld() : null));
        this.scan = new CellScan<>(new CellScan.Context<>(portal, portal.getGeometry(), sampler, memo,
            blackout, block -> MinecraftProjectorBlocks.INSTANCE.isOccluding(block), this::scanSettings));
        this.fit = new FrustumFit(fitOptions());
        this.fullSendPasses = Math.max(0, config().initialResendPasses);
    }

    public Result update(long tick, long deadlineNanos) {
        runtime.requireServerThread();
        if (closed || observer.hasDisconnected() || !portals.current(portal)
            || !portals.eligible(portal) || observer.level() != portals.world(portal)) {
            return Result.CLOSED;
        }
        Destination destination = resolveDestination();
        if (destination == null) {
            return Result.CLOSED;
        }
        Vec3d eye = eye();
        if (scan.hasPending() && !samePendingDestination(destination, eye)) {
            scan.cancelPending();
            schedule.invalidateDestination();
        }
        if (scan.hasPending()) {
            return scan.advance(deadlineNanos) ? Result.READY : Result.PENDING;
        }
        if (lastPassTick != Long.MIN_VALUE && tick - lastPassTick < Math.max(1, config().refreshIntervalTicks)) {
            return Result.IDLE;
        }
        prepare(destination, eye, tick);
        return scan.advance(deadlineNanos) ? Result.READY : Result.PENDING;
    }

    public void entityEvent(ProjectedEntityEvent event) {
        if (entities != null) {
            entities.event(event);
        }
    }

    public void updateEntities() {
        if (closed || pendingDestination == null || !scan.hasProjection()) {
            return;
        }
        Vec3d eye = eye();
        ViewVolume frustum = fit.fit(portal.getGeometry(), portal.getFrame(), eye,
            projectionDepth(), portal.getNetworkViewLateralPad());
        Destination destination = pendingDestination;
        Frame localFrame = portal.getFrame();
        Vec3d origin = portal.getOrigin();
        Face normal = localFrame.getNormal();
        boolean front = (eye.x() - origin.x()) * normal.x() + (eye.y() - origin.y()) * normal.y()
            + (eye.z() - origin.z()) * normal.z() >= 0.0D;
        Frame remoteFrame = destination.mirrorMode() ? localFrame.flipNormal() : destination.destAnchor().getFrame();
        localFrame = localFrame.view(front);
        remoteFrame = remoteFrame.view(front);
        scan.updateEntityOcclusionEye(eye, destination, localFrame, remoteFrame);
        EntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> data = destination.dest() == null ? remoteView
            : runtime.projections().scene(portals.world(destination.dest()), destination.dest(),
                Math.min(runtime.configuration().settings().getRender().entitySpoofRange, fit.fittedDepth()));
        if (data == null) {
            return;
        }
        if (entities == null) {
            entities = new MinecraftProjectedEntities(runtime, new MinecraftProjectedEntities.Context(observer, portal, portals.createRecursiveIndex()));
        }
        entities.apply(new MinecraftProjectedEntities.View(destination.dest(), destination.destAnchor(),
            destination.dest() == null ? null : portals.world(destination.dest()), data, localFrame, remoteFrame, frustum, eye,
            destination.mirrorMode(), destination.mirrorRotationQuarterTurns(), scan.entityOcclusion(), fit.fittedDepth()));
    }

    public void noteAcoustics(AcousticsBridge<ServerPlayer> bridge) {
        if (closed || pendingDestination == null || !scan.hasProjection()) {
            return;
        }
        Destination destination = pendingDestination;
        Vec3d center = portal.getGeometry().getApertureCenter();
        AcousticsProfile profile = AcousticsProfile.parse(stringSetting("fidelity.acoustics"), FidelitySettings.acousticsProfileDefault);
        long now = System.currentTimeMillis();
        if (destination.dest() != null) {
            ServerLevel world = portals.world(destination.dest());
            bridge.noteDestination(portal.getId(), views.apply(world).worldId(), destination.originX(), destination.originY(), destination.originZ(),
                center.x(), center.y(), center.z(), profile, MinecraftAcoustics.environment(world), world.isRaining(), now);
        } else if (remoteSource != null) {
            bridge.noteRemoteDestination(portal.getId(), remoteSource.getPeerName(), remoteSource.getPortalId(),
                destination.originX(), destination.originY(), destination.originZ(), center.x(), center.y(), center.z(), profile, now);
        }
    }

    public void updateWeather(long tick) {
        if (closed || pendingDestination == null || !scan.hasProjection() || !FidelitySettings.weather || !atmosphereMode().relaysWeather()) {
            return;
        }
        Destination destination = pendingDestination;
        ServerLevel world = destination.dest() == null ? null : portals.world(destination.dest());
        boolean storm = world != null ? world.isRaining() : remoteSource != null && remoteSource.hasStorm();
        boolean thunder = world != null ? world.isThundering() : remoteSource != null && remoteSource.isThundering();
        String biome = destination.destView().sampleBiome((int) Math.floor(destination.originX()),
            (int) Math.floor(destination.originY()), (int) Math.floor(destination.originZ()));
        WeatherRelay.Burst burst = weather.plan(storm, thunder, biome, tick);
        if (burst != null) {
            weather.spawn(new WeatherRelay.Emission<>(scan.claims(), burst, weatherRandom, BlockState::isAir), this::spawnWeather);
        }
    }

    private void spawnWeather(WeatherRelay.Precipitation particle, long cell) {
        observer.level().sendParticles(observer, particle == WeatherRelay.Precipitation.SNOWFLAKE ? ParticleTypes.SNOWFLAKE : ParticleTypes.RAIN,
            false, false, CellKeys.unpackX(cell) + 0.5D, CellKeys.unpackY(cell) + 0.5D, CellKeys.unpackZ(cell) + 0.5D,
            1, 0.4D, 0.5D, 0.4D, 0.0D);
    }

    public AtmosphereMode atmosphereMode() {
        return MinecraftViewPlates.atmosphereMode(portal);
    }

    public ContentView<BlockState, BlockState> destinationView() {
        return pendingDestination == null ? null : pendingDestination.destView();
    }

    public UUID portalId() {
        return portal.getId();
    }

    public long passCount() {
        return schedule.passCount();
    }

    public double priorityDistance() {
        return portal.getOrigin().distance(eye());
    }

    public ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>>> claimDelta() {
        return scan.claimDelta();
    }

    public CellScan<BlockState, BlockState, ServerLevel, MinecraftPortal, ContentView<BlockState, BlockState>> scan() {
        return scan;
    }

    public void commit() {
        scan.commit();
        if (fullSendPasses > 0) {
            fullSendPasses--;
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        if (entities != null) {
            entities.close();
            entities = null;
        }
        scan.clear();
        memo.discard();
        sampler.clearRecursivePortals();
    }

    private void prepare(Destination destination, Vec3d eye, long tick) {
        schedule.beginBlockPass();
        ProjectionRenderMode mode = portal.getRenderMode();
        boolean culling = mode.scanMode().buriedCellCulling();
        boolean cullingChanged = sampler.setBuriedCellCullingPass(culling);
        LodProfile profile = LodProfile.parse(stringSetting("fidelity.lod"), LodProfile.BALANCED);
        LodPolicy lod = FidelitySettings.lodPolicy(profile);
        fit.setOptions(fitOptions());
        fit.setLodPolicy(lod);
        ViewVolume frustum = fit.fit(portal.getGeometry(), portal.getFrame(), eye,
            projectionDepth(), portal.getNetworkViewLateralPad());
        if (fit.fittedCoarse()) {
            lod = lod.withMergeRuns();
        }
        int memoBudget = ProjectorSampleMemo.budgetFor(scan.claims().size(), fit.fittedCandidateWork());
        boolean remote = !(destination.destView() instanceof MinecraftProjectionWorldView);
        UUID destWorldId = remote ? null : destination.destView().worldId();
        long revision = destination.destView().getRevision();
        boolean stable = schedule.stableResample(firstPassDone, revision, remote, destWorldId,
            destination.originX(), destination.originZ(), scan.remoteFootprint());
        boolean localDirty = memo.localRegionDirty(destination.localView(), destination.localView().worldId());
        if (localDirty) {
            scan.revokeConeHolds();
        }
        boolean scheduled = schedule.consumeForcedResample(stable);
        boolean contentStale = scheduled || cullingChanged || sampler.recursiveSamplesCached();
        if (contentStale) {
            scan.dropHolds();
        }
        boolean dirty = !contentStale && memo.destinationStale(revision, destWorldId != null,
            since -> schedule.destinationUnaffectedThrough(destWorldId, destination.originX(), destination.originZ(), since,
                scan.remoteFootprint()));
        boolean destinationStale = contentStale || dirty || memo.destinationOverBudget(memoBudget);
        if (destinationStale) {
            scan.restartRemoteFootprint();
            sampler.clearRecursivePortals();
            memo.clearDestinationSamples();
            memo.refreshDestination(revision);
        }
        sampler.resetRecursiveSamplesCached();
        boolean localStale = memo.refreshLocal(scheduled, localDirty, destination.localView().getRevision(), memoBudget);
        memo.expandLocalRegionRect(frustum.getRegion());
        memo.markLocalScanned();
        blackout.enabled = portal.isBlackoutBackground();
        blackout.data = MinecraftViewPlates.blackoutState(portal, destination.destView());
        boolean blockEntities = MinecraftViewPlates.blockEntities(portal);
        long presentation = presentationRevision(destination, eye);
        boolean presentationChanged = firstPassDone && presentation != pendingPresentationRevision;
        if (presentationChanged) {
            scan.invalidateContent();
        }
        pendingDestination = destination;
        boolean cameraMoved = pendingEye == null || !pendingEye.equals(eye);
        pendingEye = eye;
        pendingGeometryRevision = portal.getGeometry().getRevision();
        pendingTargetGeometryRevision = targetRevision(destination);
        pendingPresentationRevision = presentation;
        lastPassTick = tick;
        schedule.noteSourceViewRevision(revision);
        firstPassDone = true;
        if (!destinationStale && !localStale && !presentationChanged && fullSendPasses == 0
            && scan.canResumeOcclusion(destination, eye, frustum)) {
            scan.resumeOcclusion();
            return;
        }
        scan.begin(destination, null, eye, frustum, fit.fittedDepth(), destinationStale || localStale || presentationChanged,
            fullSendPasses > 0, cameraMoved, mode.scanMode(), acquirePlate(destination, eye, culling, blockEntities), blockEntities, lod);
    }

    private ViewPlate<BlockState> acquirePlate(Destination destination, Vec3d eye, boolean culling, boolean blockEntities) {
        if (plates == null) {
            return null;
        }
        Frame localFrame = portal.getFrame();
        Frame remoteFrame = destination.mirrorMode() ? localFrame.flipNormal() : destination.destAnchor().getFrame();
        Vec3d origin = portal.getOrigin();
        Face normal = localFrame.getNormal();
        boolean front = (eye.x() - origin.x()) * normal.x() + (eye.y() - origin.y()) * normal.y()
            + (eye.z() - origin.z()) * normal.z() >= 0.0D;
        return MinecraftViewPlates.acquire(runtime, plates, new MinecraftViewPlates.Target(observer, portal, destination.destView(),
            () -> plateView(destination), remoteFrame, destination.originX(), destination.originY(), destination.originZ(),
            destination.mirrorMode(), destination.mirrorRotationQuarterTurns(), front, culling, blockEntities, sampler.air(), portals.routeIdentity(portal)));
    }

    private ContentView<BlockState, BlockState> plateView(Destination destination) {
        ContentView<BlockState, BlockState> plateView = destination.destView();
        if (plateView instanceof MinecraftProjectionWorldView) {
            return plateView;
        }
        return new RemoteProjectionView<>(remoteSource, new RemoteProjectionView.Options<>(parseFallback(remoteFallbackState), state -> state));
    }

    private boolean samePendingDestination(Destination destination, Vec3d eye) {
        return pendingDestination.dest() == destination.dest()
            && pendingDestination.localView() == destination.localView()
            && pendingDestination.destView() == destination.destView()
            && pendingDestination.mirrorMode() == destination.mirrorMode()
            && pendingDestination.mirrorRotationQuarterTurns() == destination.mirrorRotationQuarterTurns()
            && pendingGeometryRevision == portal.getGeometry().getRevision()
            && pendingDestination.destAnchor() == destination.destAnchor()
            && pendingTargetGeometryRevision == targetRevision(destination)
            && pendingPresentationRevision == presentationRevision(destination, eye);
    }

    private long presentationRevision(Destination destination, Vec3d eye) {
        Frame frame = portal.getFrame();
        Vec3d origin = portal.getOrigin();
        Face normal = frame.getNormal();
        boolean front = (eye.x() - origin.x()) * normal.x() + (eye.y() - origin.y()) * normal.y()
            + (eye.z() - origin.z()) * normal.z() >= 0.0D;
        Frame remoteFrame = destination.mirrorMode() ? frame.flipNormal() : destination.destAnchor().getFrame();
        LodPolicy lod = FidelitySettings.lodPolicy(LodProfile.parse(stringSetting("fidelity.lod"), LodProfile.BALANCED));
        if (fit.fittedCoarse()) {
            lod = lod.withMergeRuns();
        }
        boolean blockEntities = MinecraftViewPlates.blockEntities(portal);
        long revision = ProjectorPassRevision.transform(frame, remoteFrame, origin.x(), origin.y(), origin.z(),
            destination.originX(), destination.originY(), destination.originZ(), portal.getNetworkViewDepth(),
            portal.getNetworkViewLateralPad(), config().aperturePaddingBlocks, portal.getRenderMode().scanMode().buriedCellCulling(), lod, blockEntities);
        revision = ProjectorPassRevision.mix(revision, destination.mirrorRotationQuarterTurns());
        revision = ProjectorPassRevision.mix(revision, portal.isBlackoutBackground() ? 1L : 0L);
        revision = ProjectorPassRevision.mix(revision, portal.getBlackoutColor().ordinal());
        revision = ProjectorPassRevision.mix(revision, atmosphereMode().ordinal());
        revision = ProjectorPassRevision.mix(revision, Double.doubleToLongBits(config().nearPlanePadding));
        revision = ProjectorPassRevision.mix(revision, Double.doubleToLongBits(config().frustumCullingRatio));
        revision = ProjectorPassRevision.mix(revision, Double.doubleToLongBits(config().occlusionRevealMarginDegrees));
        revision = ProjectorPassRevision.mix(revision, config().maxProjectedCells);
        revision = ProjectorPassRevision.mix(revision, config().recursivePortalDepth);
        return ProjectorPassRevision.mix(revision, front ? 1L : 0L);
    }

    private long targetRevision(Destination destination) {
        return destination.dest() == null ? 0L : destination.dest().getGeometry().getRevision();
    }

    private Destination resolveDestination() {
        ServerLevel sourceWorld = portals.world(portal);
        if (sourceWorld == null) {
            return null;
        }
        if (!portal.isMirrorMode() && "UNIVERSAL".equals(portal.getTunnelType())) {
            RemotePortal target = portals.remoteDestination(portal);
            if (target == null) {
                return null;
            }
            RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> source =
                runtime.network().subscriptions().touch(portal.getDestinationServer(), target.getId(), new ViewSubscriptionManager.Request(portal.getNetworkViewUnsubscribeGraceSeconds(), 0));
            String fallback = portal.getNetworkViewFallbackBlock();
            if (source != remoteSource || !Objects.equals(remoteFallbackState, fallback)) {
                remoteSource = source;
                remoteFallbackState = fallback;
                remoteView = new RemoteProjectionView<>(source,
                    new RemoteProjectionView.Options<>(parseFallback(fallback), state -> state));
            }
            Vec3d origin = target.getOrigin();
            return new Destination(views.apply(sourceWorld), remoteView, null, target,
                origin.x(), origin.y(), origin.z(), false, 0);
        }
        MinecraftPortal target = portal.isMirrorMode() ? portal : portals.projectionDestination(portal);
        if (target == null) {
            return null;
        }
        ServerLevel targetWorld = portals.world(target);
        if (targetWorld == null) {
            return null;
        }
        Vec3d origin = target.getOrigin();
        return new Destination(views.apply(sourceWorld), views.apply(targetWorld), target, target,
            origin.x(), origin.y(), origin.z(), portal.isMirrorMode(), portals.mirrorQuarterTurns(portal));
    }

    private static BlockState parseFallback(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (CommandSyntaxException invalid) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private Vec3d eye() {
        Vec3 eye = observer.getEyePosition();
        return new Vec3d(eye.x, eye.y, eye.z);
    }

    private String stringSetting(String name) {
        return MinecraftViewPlates.stringSetting(portal, name);
    }

    private ProjectionConfig config() {
        return runtime.configuration().settings().getProjection();
    }

    private ResampleSchedule.Cadence cadence() {
        ProjectionConfig projection = config();
        RenderConfig render = runtime.configuration().settings().getRender();
        return new ResampleSchedule.Cadence(Math.clamp(projection.refreshIntervalTicks, 1, 20),
            Math.clamp(projection.stableCellResampleIntervalTicks, 1, 200), Math.clamp(render.lightingRefreshIntervalTicks, 1, 40),
            Math.clamp(render.entityUpdateIntervalTicks, 1, 20));
    }

    private double projectionDepth() {
        return config().clientViewDistanceCap ? FrustumFit.capDistance(portal.getNetworkViewDepth(),
            runtime.server().getPlayerList().getViewDistance(), observer.requestedViewDistance()) : portal.getNetworkViewDepth();
    }

    private FrustumFit.Options fitOptions() {
        ProjectionConfig settings = config();
        return new FrustumFit.Options(settings.maxProjectedCells, settings.nearPlanePadding,
            settings.frustumCullingRatio, settings.aperturePaddingBlocks);
    }

    private CellScan.ScanSettings scanSettings() {
        ProjectionConfig settings = config();
        return new CellScan.ScanSettings(settings.recursivePortalDepth, settings.occlusionRevealMarginDegrees,
            settings.aperturePaddingBlocks, false, settings.holdInvisibleClaims, Math.max(0, settings.maxHeldCellsPerPortal),
            settings.finishInSlot);
    }

    public record Context(ServerPlayer observer, MinecraftPortal portal,
                          Function<ServerLevel, MinecraftProjectionWorldView> views,
                          MinecraftProjectorPortalAccess portals, ViewPlateCache<BlockState, ServerLevel> plates) {
    }

    public enum Result {
        READY, PENDING, IDLE, CLOSED
    }

    private record Destination(ContentView<BlockState, BlockState> localView, ContentView<BlockState, BlockState> destView,
                               MinecraftPortal dest, IPortal destAnchor, double originX, double originY, double originZ,
                               boolean mirrorMode, int mirrorRotationQuarterTurns)
        implements ScanDestination<MinecraftPortal, ContentView<BlockState, BlockState>> {
    }

    private static final class Blackout implements ProjectionBlackout<BlockState> {
        private boolean enabled;
        private BlockState data;

        public boolean isEnabled() {
            return enabled;
        }

        public BlockState data() {
            return data;
        }
    }
}
