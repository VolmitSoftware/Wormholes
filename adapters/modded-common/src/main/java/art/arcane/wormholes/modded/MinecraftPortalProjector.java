package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectedEntityEvent;
import art.arcane.wormholes.render.atmosphere.WeatherRelay;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.ProjectionCellKey;
import net.minecraft.core.particles.ParticleTypes;
import java.util.Random;

import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.view.ProjectionEntityData;
import art.arcane.wormholes.render.view.RemoteProjectionView;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.syncher.SynchedEntityData;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.atmosphere.FogPlatePolicy;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import art.arcane.wormholes.render.Frustum4D;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionBlackout;
import art.arcane.wormholes.render.ProjectionClaimSet;
import art.arcane.wormholes.render.ProjectorCellScan;
import art.arcane.wormholes.render.ProjectorFrustumFit;
import art.arcane.wormholes.render.ProjectorPassRevision;
import art.arcane.wormholes.render.ProjectorResampleSchedule;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.render.ProjectorSampleMemo;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.ProjectorSampler;
import art.arcane.wormholes.render.ProjectorScanDestination;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.PlateCaptureJob;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.lod.LodProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class MinecraftPortalProjector implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final ServerPlayer observer;
    private final MinecraftPortal portal;
    private final Function<ServerLevel, MinecraftProjectionWorldView> views;
    private final MinecraftProjectorPortalAccess portals;
    private final ProjectorSampleMemo<BlockState, BlockState, ProjectionContentView<BlockState, BlockState>> memo;
    private final ProjectorSampler<BlockState, BlockState, ServerLevel, MinecraftPortal, ProjectionContentView<BlockState, BlockState>> sampler;
    private final ProjectorCellScan<BlockState, BlockState, ServerLevel, MinecraftPortal, ProjectionContentView<BlockState, BlockState>> scan;
    private final ProjectorFrustumFit fit;
    private final ProjectorResampleSchedule schedule;
    private final ViewPlateCache<BlockState, ServerLevel> plates;
    private final Blackout blackout = new Blackout();
    private Destination pendingDestination;
    private GeometryVector pendingEye;
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
        ProjectionWorldChangeTracker changes = runtime.projections().changes();
        this.memo = new ProjectorSampleMemo<>(MinecraftProjectorBlocks.INSTANCE, () -> changes);
        this.schedule = new ProjectorResampleSchedule(portal, () -> changes, this::cadence);
        this.sampler = new ProjectorSampler<>(new ProjectorSampler.Options<>(memo,
            portals.createRecursiveIndex(), views::apply,
                view -> view instanceof MinecraftProjectionWorldView local ? local.getWorld() : null));
        this.scan = new ProjectorCellScan<>(new ProjectorCellScan.Context<>(portal, portal.getGeometry(), sampler, memo,
            blackout, block -> MinecraftProjectorBlocks.INSTANCE.isOccluding(block), this::scanSettings));
        this.fit = new ProjectorFrustumFit(fitOptions());
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
        GeometryVector eye = eye();
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
        GeometryVector eye = eye();
        Frustum4D frustum = fit.fit(portal.getGeometry(), portal.getFrame(), eye,
            projectionDepth(), portal.getNetworkViewLateralPad());
        Destination destination = pendingDestination;
        PortalFrame localFrame = portal.getFrame();
        GeometryVector origin = portal.getOrigin();
        Direction normal = localFrame.getNormal();
        boolean front = (eye.x() - origin.x()) * normal.x() + (eye.y() - origin.y()) * normal.y()
            + (eye.z() - origin.z()) * normal.z() >= 0.0D;
        PortalFrame remoteFrame = destination.mirrorMode() ? localFrame.flipNormal() : destination.destAnchor().getFrame();
        localFrame = localFrame.view(front);
        remoteFrame = remoteFrame.view(front);
        scan.updateEntityOcclusionEye(eye, destination, localFrame, remoteFrame);
        ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> data = destination.dest() == null ? remoteView
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
        GeometryVector center = portal.getGeometry().getApertureCenter();
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
            false, false, ProjectionCellKey.unpackX(cell) + 0.5D, ProjectionCellKey.unpackY(cell) + 0.5D, ProjectionCellKey.unpackZ(cell) + 0.5D,
            1, 0.4D, 0.5D, 0.4D, 0.0D);
    }

    public AtmosphereMode atmosphereMode() {
        return AtmosphereMode.parse(stringSetting("fidelity.atmosphere"), FidelitySettings.atmosphereModeDefault);
    }

    public ProjectionContentView<BlockState, BlockState> destinationView() {
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

    public ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockState, ProjectionContentView<BlockState, BlockState>>> claimDelta() {
        return scan.claimDelta();
    }

    public ProjectorCellScan<BlockState, BlockState, ServerLevel, MinecraftPortal, ProjectionContentView<BlockState, BlockState>> scan() {
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

    private void prepare(Destination destination, GeometryVector eye, long tick) {
        schedule.beginBlockPass();
        ProjectionRenderMode mode = portal.getRenderMode();
        boolean culling = mode.usesBuriedCellCulling();
        boolean cullingChanged = sampler.setBuriedCellCullingPass(culling);
        LodProfile profile = LodProfile.parse(stringSetting("fidelity.lod"), LodProfile.BALANCED);
        LodPolicy lod = FidelitySettings.lodPolicy(profile);
        fit.setOptions(fitOptions());
        fit.setLodPolicy(lod);
        Frustum4D frustum = fit.fit(portal.getGeometry(), portal.getFrame(), eye,
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
        blackout.data = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(portal.getBlackoutColor().blockState()))
            .orElse(Blocks.CONCRETE.pick(DyeColor.BLACK)).defaultBlockState();
        if (FogPlatePolicy.applies(FidelitySettings.fogPlate, atmosphereMode())
            && destination.destView() instanceof MinecraftProjectionWorldView local) {
            FogPlatePolicy.Dimension dimension = local.getWorld().dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) ? FogPlatePolicy.Dimension.OVERWORLD
                : local.getWorld().dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER) ? FogPlatePolicy.Dimension.NETHER
                : local.getWorld().dimensionTypeRegistration().is(BuiltinDimensionTypes.END) ? FogPlatePolicy.Dimension.END : FogPlatePolicy.Dimension.CUSTOM;
            String shell = FogPlatePolicy.shellState(dimension);
            if (shell != null) {
                blackout.data = BuiltInRegistries.BLOCK.getValue(Identifier.parse(shell)).defaultBlockState();
            }
        }
        boolean blockEntities = FidelitySettings.blockEntities
            && (!(portal.setting("fidelity.block_entities") instanceof Boolean enabled) || enabled);
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
            fullSendPasses > 0, cameraMoved, culling, mode, acquirePlate(destination, eye, culling, blockEntities), blockEntities, lod);
    }

    private ViewPlate<BlockState> acquirePlate(Destination destination, GeometryVector eye, boolean culling, boolean blockEntities) {
        if (plates == null || !FidelitySettings.sharedPlate) {
            return null;
        }
        boolean rtp = portal.getType() == PortalType.RTP;
        if (rtp && !FidelitySettings.rtpPlates) {
            return null;
        }
        long targetIdentity = rtp ? runtime.rtp().plateIdentity(observer, portal) : 0L;
        if (rtp && targetIdentity == 0L) {
            return null;
        }
        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = destination.mirrorMode() ? localFrame.flipNormal() : destination.destAnchor().getFrame();
        GeometryVector origin = portal.getOrigin();
        Direction normal = localFrame.getNormal();
        boolean front = (eye.x() - origin.x()) * normal.x() + (eye.y() - origin.y()) * normal.y()
            + (eye.z() - origin.z()) * normal.z() >= 0.0D;
        LodPolicy lod = FidelitySettings.lodPolicy(LodProfile.parse(stringSetting("fidelity.lod"), LodProfile.BALANCED));
        int depth = portal.getNetworkViewDepth();
        int lateral = Math.min(portal.getNetworkViewLateralPad(), FidelitySettings.plateLateralClampBlocks);
        double padding = config().aperturePaddingBlocks;
        long transform = ProjectorPassRevision.transform(localFrame, remoteFrame, origin.x(), origin.y(), origin.z(),
            destination.originX(), destination.originY(), destination.originZ(), depth, lateral, padding, culling, lod, blockEntities);
        transform = ProjectorPassRevision.mix(transform, targetIdentity);
        transform = ProjectorPassRevision.mix(transform, portal.getGeometry().getRevision());
        long transformRevision = ProjectorPassRevision.mix(transform, Objects.hashCode(portal.getNetworkViewFallbackBlock()));
        long destinationRevision = destination.destView() instanceof MinecraftProjectionWorldView ? 0L : destination.destView().getRevision();
        ViewPlateKey key = new ViewPlateKey(portal.getId(), destination.destView(), front, destination.mirrorRotationQuarterTurns(), targetIdentity);
        ProjectionWorldChangeTracker tracker = runtime.projections().changes();
        return plates.current(key, destinationRevision, transformRevision, tracker, previous -> {
            ProjectionContentView<BlockState, BlockState> plateView = destination.destView();
            if (!(plateView instanceof MinecraftProjectionWorldView)) {
                plateView = new RemoteProjectionView<>(remoteSource, new RemoteProjectionView.Options<>(parseFallback(remoteFallbackState), state -> state));
            }
            ViewPlateBuilder.Request<BlockState, BlockState, ProjectionContentView<BlockState, BlockState>> request = new ViewPlateBuilder.Request<>(
                key, portal.getGeometry(), plateView, localFrame, remoteFrame, origin.x(), origin.y(), origin.z(),
                destination.originX(), destination.originY(), destination.originZ(), destination.mirrorMode(), destination.mirrorRotationQuarterTurns(),
                depth, lateral, padding, culling, sampler.air(), lod, blockEntities, destinationRevision, transformRevision,
                tracker.currentVersion(), MinecraftProjectorBlocks.INSTANCE);
            LongOpenHashSet dirtyChunks = new LongOpenHashSet();
            boolean patch = previous != null && previous.collectDirt(tracker, dirtyChunks) && !dirtyChunks.isEmpty();
            return plateJob(request, blockEntities, patch ? previous : null, dirtyChunks);
        });
    }

    private ViewPlateBuilder.Job<BlockState, ServerLevel> plateJob(ViewPlateBuilder.Request<BlockState, BlockState, ProjectionContentView<BlockState, BlockState>> request,
                                                                   boolean blockEntities, ViewPlate<BlockState> previous, LongOpenHashSet dirtyChunks) {
        if (!(request.destView() instanceof MinecraftProjectionWorldView local)) {
            return previous == null ? ViewPlateBuilder.job(request) : ViewPlateBuilder.patch(request, previous, dirtyChunks);
        }
        ViewPlateBuilder.Footprint footprint = previous == null
            ? ViewPlateBuilder.footprint(request)
            : ViewPlateBuilder.patchFootprint(request, dirtyChunks);
        return new PlateCaptureJob<>(new PlateCaptureJob.Plan<>(request.key(), local.getWorld(), footprint,
            new MinecraftPlateCaptureSource(runtime, local.worldId(), blockEntities), captured -> {
                ViewPlateBuilder.Request<BlockState, BlockState, ProjectionContentView<BlockState, BlockState>> captureRequest = request.withDestView(
                    new MinecraftCapturedChunkView(local.worldId(), local.getMinHeight(), local.getMaxHeight(), request.destinationRevision(), captured));
                return previous == null ? ViewPlateBuilder.job(captureRequest) : ViewPlateBuilder.patch(captureRequest, previous, dirtyChunks);
            }));
    }

    private boolean samePendingDestination(Destination destination, GeometryVector eye) {
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

    private long presentationRevision(Destination destination, GeometryVector eye) {
        PortalFrame frame = portal.getFrame();
        GeometryVector origin = portal.getOrigin();
        Direction normal = frame.getNormal();
        boolean front = (eye.x() - origin.x()) * normal.x() + (eye.y() - origin.y()) * normal.y()
            + (eye.z() - origin.z()) * normal.z() >= 0.0D;
        PortalFrame remoteFrame = destination.mirrorMode() ? frame.flipNormal() : destination.destAnchor().getFrame();
        LodPolicy lod = FidelitySettings.lodPolicy(LodProfile.parse(stringSetting("fidelity.lod"), LodProfile.BALANCED));
        if (fit.fittedCoarse()) {
            lod = lod.withMergeRuns();
        }
        boolean blockEntities = FidelitySettings.blockEntities
            && (!(portal.setting("fidelity.block_entities") instanceof Boolean enabled) || enabled);
        long revision = ProjectorPassRevision.transform(frame, remoteFrame, origin.x(), origin.y(), origin.z(),
            destination.originX(), destination.originY(), destination.originZ(), portal.getNetworkViewDepth(),
            portal.getNetworkViewLateralPad(), config().aperturePaddingBlocks, portal.getRenderMode().usesBuriedCellCulling(), lod, blockEntities);
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
                runtime.network().subscriptions().touch(portal.getDestinationServer(), target.getId(), portal.getNetworkViewUnsubscribeGraceSeconds());
            String fallback = portal.getNetworkViewFallbackBlock();
            if (source != remoteSource || !Objects.equals(remoteFallbackState, fallback)) {
                remoteSource = source;
                remoteFallbackState = fallback;
                remoteView = new RemoteProjectionView<>(source,
                    new RemoteProjectionView.Options<>(parseFallback(fallback), state -> state));
            }
            GeometryVector origin = target.getOrigin();
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
        GeometryVector origin = target.getOrigin();
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

    private GeometryVector eye() {
        Vec3 eye = observer.getEyePosition();
        return new GeometryVector(eye.x, eye.y, eye.z);
    }

    private String stringSetting(String name) {
        Object value = portal.setting(name);
        return value instanceof String text ? text : "";
    }

    private ProjectionConfig config() {
        return runtime.configuration().settings().getProjection();
    }

    private ProjectorResampleSchedule.Cadence cadence() {
        ProjectionConfig projection = config();
        RenderConfig render = runtime.configuration().settings().getRender();
        return new ProjectorResampleSchedule.Cadence(Math.clamp(projection.refreshIntervalTicks, 1, 20),
            Math.clamp(projection.stableCellResampleIntervalTicks, 1, 200), Math.clamp(render.lightingRefreshIntervalTicks, 1, 40),
            Math.clamp(render.entityUpdateIntervalTicks, 1, 20));
    }

    private double projectionDepth() {
        return config().clientViewDistanceCap ? ProjectorFrustumFit.capDistance(portal.getNetworkViewDepth(),
            runtime.server().getPlayerList().getViewDistance(), observer.requestedViewDistance()) : portal.getNetworkViewDepth();
    }

    private ProjectorFrustumFit.Options fitOptions() {
        ProjectionConfig settings = config();
        return new ProjectorFrustumFit.Options(settings.maxProjectedCells, settings.nearPlanePadding,
            settings.frustumCullingRatio, settings.aperturePaddingBlocks);
    }

    private ProjectorCellScan.ScanSettings scanSettings() {
        ProjectionConfig settings = config();
        return new ProjectorCellScan.ScanSettings(settings.recursivePortalDepth, settings.occlusionRevealMarginDegrees,
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

    private record Destination(ProjectionContentView<BlockState, BlockState> localView, ProjectionContentView<BlockState, BlockState> destView,
                               MinecraftPortal dest, IPortal destAnchor, double originX, double originY, double originZ,
                               boolean mirrorMode, int mirrorRotationQuarterTurns)
        implements ProjectorScanDestination<MinecraftPortal, ProjectionContentView<BlockState, BlockState>> {
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
