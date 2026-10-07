package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.FogPlatePolicy;
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
import art.arcane.optics.claim.ProjectionOutput;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.volume.FrustumFit;
import art.arcane.optics.scan.PassInputs;
import art.arcane.optics.scan.PassPlan;
import art.arcane.optics.scan.PassPlanner;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.scan.ResampleSchedule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.ProjectorViewSettings;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.scan.ProjectorSampler;
import art.arcane.optics.scan.ScanDestination;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.optics.volume.ProjectionVolume;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class MinecraftPortalProjector implements AutoCloseable {
    private static final long IDENTITY_SEED = 0x3C6EF372FE94F82BL;

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
    private final PassInputs passInputs = new PassInputs();
    private Frame flippedFrameSource;
    private Frame flippedFrame;
    private Destination pendingDestination;
    private Vec3d pendingEye;
    private long pendingRevision;
    private boolean pendingCoarse;
    private Vec3d committedEye;
    private long committedRevision;
    private boolean committedCoarse;
    private long lastPassTick = Long.MIN_VALUE;
    private int fullSendPasses;
    private boolean closed;
    private RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> remoteSource;
    private RemoteProjectionView<BlockState, BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> remoteView;
    private String remoteFallbackState;
    private ToIntFunction<String> remoteBiomeIds;
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
            blackout, this::scanSettings));
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
        if (scan.hasPending() && !samePendingContext(destination, describePass(destination, eye))) {
            scan.cancelPending();
            schedule.invalidateDestination();
        }
        if (scan.hasPending()) {
            return scan.advance(deadlineNanos) ? Result.READY : Result.PENDING;
        }
        if (lastPassTick != Long.MIN_VALUE && tick - lastPassTick < Math.max(1, config().refreshIntervalTicks)) {
            return Result.IDLE;
        }
        lastPassTick = tick;
        schedule.beginBlockPass();
        boolean remote = !(destination.destView() instanceof MinecraftProjectionWorldView);
        UUID destWorldId = remote ? null : destination.destView().worldId();
        boolean stable = schedule.stableResample(committedEye != null, destination.destView().getRevision(), remote, destWorldId,
            destination.originX(), destination.originZ(), scan.remoteFootprint());
        boolean localDirty = memo.localRegionDirty(destination.localView(), destination.localView().worldId());
        if (localDirty) {
            scan.revokeConeHolds();
        }
        if (reusable(destination, eye, stable, localDirty)) {
            return Result.IDLE;
        }
        prepare(destination, eye, stable, localDirty);
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
        boolean front = ProjectionVolume.side(localFrame, origin.x(), origin.y(), origin.z(), eye.x(), eye.y(), eye.z());
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
        OpticTransform transform = destination.mirrorMode()
            ? OpticTransform.mirror(portal.getFrame(), origin, QuarterTurn.of(destination.mirrorRotationQuarterTurns()))
            : OpticTransform.between(remoteFrame, destination.destAnchor().getOrigin(), localFrame, origin);
        entities.apply(new MinecraftProjectedEntities.View(destination.dest(), destination.destAnchor(),
            destination.dest() == null ? null : portals.world(destination.dest()), data, transform, frustum, eye, scan.entityOcclusion(),
            fit.fittedDepth()));
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

    public void updateWeather(long tick, ProjectionOutput<ServerPlayer> output) {
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
            weather.spawn(new WeatherRelay.Emission<>(scan.claims(), burst, weatherRandom, BlockState::isAir), output, observer);
        }
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
        committedEye = pendingEye;
        committedRevision = pendingRevision;
        committedCoarse = pendingCoarse;
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

    private boolean reusable(Destination destination, Vec3d eye, boolean stable, boolean localDirty) {
        describePass(destination, eye);
        Vec3d camera = committedEye;
        passInputs.committed(camera != null, committedRevision, committedCoarse);
        if (camera == null) {
            passInputs.camera(false, 0.0D, 0.0D, 0.0D);
        } else {
            passInputs.camera(true, camera.x(), camera.y(), camera.z());
        }
        passInputs.projection(scan.hasProjection(), false, fullSendPasses > 0);
        passInputs.dissolving(false);
        passInputs.unresolvedOcclusion(scan.hasUnresolvedOcclusion());
        passInputs.resample(stable, schedule.isRemoteResamplePending());
        passInputs.lightingDue(false);
        passInputs.localDirty(localDirty);
        passInputs.holdsExposed(scan.holdsExposed());
        passInputs.sampler(sampler.buriedCellCullingPass() != portal.getRenderMode().scanMode().buriedCellCulling(),
            sampler.recursiveSamplesCached());
        return PassPlanner.reusable(passInputs);
    }

    private void prepare(Destination destination, Vec3d eye, boolean stable, boolean localDirty) {
        ProjectionRenderMode mode = portal.getRenderMode();
        boolean culling = mode.scanMode().buriedCellCulling();
        LodPolicy lod = FidelitySettings.lodPolicy(MinecraftViewPlates.lodProfile(portal));
        fit.setOptions(fitOptions());
        fit.setLodPolicy(lod);
        ViewVolume frustum = fit.fit(portal.getGeometry(), portal.getFrame(), eye,
            projectionDepth(), portal.getNetworkViewLateralPad());
        boolean coarse = fit.fittedCoarse();
        if (coarse) {
            lod = lod.withMergeRuns();
        }
        sampler.setBuriedCellCullingPass(culling);
        schedule.consumeForcedResample(stable);
        int memoBudget = ProjectorSampleMemo.budgetFor(scan.claims().size(), fit.fittedCandidateWork());
        boolean remote = !(destination.destView() instanceof MinecraftProjectionWorldView);
        UUID destWorldId = remote ? null : destination.destView().worldId();
        long revision = destination.destView().getRevision();
        long localRevision = destination.localView().getRevision();
        blackout.enabled = portal.isBlackoutBackground();
        blackout.data = MinecraftViewPlates.blackoutState(portal, destination.destView());
        passInputs.samples(memo.destinationStale(revision, destWorldId != null,
                since -> schedule.destinationUnaffectedThrough(destWorldId, destination.originX(), destination.originZ(), since,
                    scan.remoteFootprint())),
            memo.destinationOverBudget(memoBudget), memo.localStale(localDirty, localRevision, memoBudget));
        passInputs.fitted(coarse, scan.canResumeOcclusion(destination, eye, frustum));
        PassPlan plan = PassPlanner.plan(passInputs);
        if (plan.has(PassPlan.DESTINATION_CONTENT_STALE)) {
            scan.dropHolds();
        }
        if (plan.has(PassPlan.DESTINATION_SAMPLES_STALE)) {
            scan.restartRemoteFootprint();
            sampler.clearRecursivePortals();
            memo.clearDestinationSamples();
            memo.refreshDestination(revision);
        }
        sampler.resetRecursiveSamplesCached();
        memo.refreshLocal(plan.has(PassPlan.LOCAL_CONTENT_RESAMPLE), localDirty, localRevision, memoBudget);
        memo.expandLocalRegionRect(frustum.getRegion());
        memo.markLocalScanned();
        if (plan.has(PassPlan.CONTENT_INVALIDATED)) {
            scan.invalidateContent();
        }
        pendingDestination = destination;
        pendingEye = eye;
        pendingRevision = plan.revision();
        pendingCoarse = coarse;
        schedule.noteSourceViewRevision(revision);
        if (plan.resumes()) {
            scan.resumeOcclusion();
            return;
        }
        boolean blockEntities = MinecraftViewPlates.blockEntities(portal);
        scan.begin(destination, null, eye, frustum, fit.fittedDepth(), plan.has(PassPlan.CONTENT_INVALIDATED),
            plan.has(PassPlan.FULL_SEND), plan.has(PassPlan.REFRESH_VISIBILITY), mode.scanMode(),
            acquirePlate(destination, eye, culling, blockEntities), blockEntities, lod);
    }

    private ViewPlate<BlockState> acquirePlate(Destination destination, Vec3d eye, boolean culling, boolean blockEntities) {
        if (plates == null) {
            return null;
        }
        Frame localFrame = portal.getFrame();
        Frame remoteFrame = destination.mirrorMode() ? localFrame.flipNormal() : destination.destAnchor().getFrame();
        Vec3d origin = portal.getOrigin();
        boolean front = ProjectionVolume.side(localFrame, origin.x(), origin.y(), origin.z(), eye.x(), eye.y(), eye.z());
        return MinecraftViewPlates.acquire(runtime, plates, new MinecraftViewPlates.Target(observer, portal, destination.destView(),
            () -> plateView(destination), remoteFrame, destination.originX(), destination.originY(), destination.originZ(),
            destination.mirrorMode(), destination.mirrorRotationQuarterTurns(), front, culling, blockEntities, sampler.air(), portals.routeIdentity(portal)));
    }

    private ContentView<BlockState, BlockState> plateView(Destination destination) {
        ContentView<BlockState, BlockState> plateView = destination.destView();
        if (plateView instanceof MinecraftProjectionWorldView) {
            return plateView;
        }
        return new RemoteProjectionView<>(remoteSource, new RemoteProjectionView.Options<>(parseFallback(remoteFallbackState), state -> state,
            remoteBiomeIds));
    }

    private boolean samePendingContext(Destination destination, long revision) {
        return pendingDestination.localView() == destination.localView()
            && pendingDestination.destView() == destination.destView()
            && pendingRevision == revision;
    }

    private long describePass(Destination destination, Vec3d eye) {
        Frame frame = portal.getFrame();
        Vec3d origin = portal.getOrigin();
        Frame remoteFrame = destination.mirrorMode() ? flipped(frame) : destination.destAnchor().getFrame();
        ProjectionConfig config = config();
        AtmosphereMode atmosphere = atmosphereMode();
        passInputs.eye(eye.x(), eye.y(), eye.z());
        passInputs.local(frame, origin.x(), origin.y(), origin.z());
        passInputs.remote(remoteFrame, destination.originX(), destination.originY(), destination.originZ());
        passInputs.mirror(destination.mirrorMode(), destination.mirrorRotationQuarterTurns());
        passInputs.extent(portal.getNetworkViewDepth(), portal.getNetworkViewLateralPad(), projectionDepth());
        passInputs.padding(config.aperturePaddingBlocks, config.nearPlanePadding);
        passInputs.frustum(config.frustumCullingRatio, config.occlusionRevealMarginDegrees);
        passInputs.limits(config.maxProjectedCells, config.recursivePortalDepth);
        passInputs.scanMode(portal.getRenderMode().scanMode());
        passInputs.lod(MinecraftViewPlates.lodProfile(portal), FidelitySettings.lodMergeRuns, FidelitySettings.lodDistanceBlocks,
            FidelitySettings.lodDetailCutoffBlocks);
        passInputs.blockEntities(MinecraftViewPlates.blockEntities(portal));
        passInputs.blackout(portal.isBlackoutBackground(), portal.getBlackoutColor().ordinal(),
            FogPlatePolicy.applies(FidelitySettings.fogPlate, atmosphere));
        passInputs.atmosphere(atmosphere);
        passInputs.identity(portal.getGeometry().getRevision(), destination.identity());
        return PassPlanner.revision(passInputs);
    }

    private Frame flipped(Frame frame) {
        if (frame != flippedFrameSource) {
            flippedFrameSource = frame;
            flippedFrame = frame.flipNormal();
        }
        return flippedFrame;
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
            MinecraftProjectionWorldView local = views.apply(sourceWorld);
            if (source != remoteSource || !Objects.equals(remoteFallbackState, fallback)) {
                remoteSource = source;
                remoteFallbackState = fallback;
                remoteBiomeIds = local.biomeIds();
                remoteView = new RemoteProjectionView<>(source,
                    new RemoteProjectionView.Options<>(parseFallback(fallback), state -> state, remoteBiomeIds));
            }
            Vec3d origin = target.getOrigin();
            long identity = mixId(IDENTITY_SEED, target.getId());
            identity = ProjectorPassRevision.mix(identity, Objects.hashCode(portal.getDestinationServer()));
            identity = ProjectorPassRevision.mix(identity, Objects.hashCode(fallback));
            return new Destination(local, remoteView, null, target,
                origin.x(), origin.y(), origin.z(), false, 0, identity);
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
        MinecraftProjectionWorldView destinationView = views.apply(targetWorld);
        long identity = mixId(mixId(IDENTITY_SEED, target.getId()), destinationView.worldId());
        identity = ProjectorPassRevision.mix(identity, target.getGeometry().getRevision());
        return new Destination(views.apply(sourceWorld), destinationView, target, target,
            origin.x(), origin.y(), origin.z(), portal.isMirrorMode(), portals.mirrorTurns(portal).getQuarterTurns(), identity);
    }

    private static long mixId(long hash, UUID id) {
        if (id == null) {
            return ProjectorPassRevision.mix(hash, 0L);
        }
        return ProjectorPassRevision.mix(ProjectorPassRevision.mix(hash, id.getMostSignificantBits()), id.getLeastSignificantBits());
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
        return new ResampleSchedule.Cadence(projection.refreshIntervalTicks, projection.stableCellResampleIntervalTicks,
            render.lightingRefreshIntervalTicks, render.entityUpdateIntervalTicks);
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
            settings.aperturePaddingBlocks, false, settings.holdInvisibleClaims, settings.maxHeldCellsPerPortal,
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
                               boolean mirrorMode, int mirrorRotationQuarterTurns, long identity)
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
