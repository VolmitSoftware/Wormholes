package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.CapturedChunkView;
import art.arcane.wormholes.render.view.PlateCaptureSource;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import art.arcane.optics.scan.PassRevision;
import art.arcane.optics.view.WorldChangeTracker;

final class ProjectorPlates {
    private ProjectorPlates() {
    }

    static boolean enabled(ViewPlateCache<BlockData, World> cache, ILocalPortal portal, PortalProjector.RtpProjectionTarget rtpTarget) {
        return cache != null && FidelitySettings.sharedPlate && portal.getId() != null
            && (rtpTarget == null || FidelitySettings.rtpPlates);
    }

    static boolean frontSide(ILocalPortal portal, Location eye) {
        Face facing = portal.getFrame().getNormal();
        return ((eye.getX() - portal.getOrigin().getX()) * facing.x()
            + (eye.getY() - portal.getOrigin().getY()) * facing.y()
            + (eye.getZ() - portal.getOrigin().getZ()) * facing.z()) >= 0.0D;
    }

    static FidelityPortalExtension fidelity(ILocalPortal portal) {
        if (portal instanceof LocalPortal local) {
            return local.extension(FidelityPortalExtension.class);
        }
        return null;
    }

    static LodPolicy portalLod(FidelityPortalExtension fidelity) {
        return FidelitySettings.lodPolicy(fidelity == null ? null : fidelity.effectiveLodProfile());
    }

    static Target target(ILocalPortal portal, ProjectorDestination destination, boolean eyeFrontSide, boolean buriedCellCulling,
                         PortalProjector.RtpProjectionTarget rtpTarget, FidelityPortalExtension fidelity) {
        Frame localFrame = portal.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        boolean mirrorMode = destination.mirrorMode;
        int quarterTurns = destination.mirrorRotationQuarterTurns;
        Frame remoteFrame = rtpTarget != null ? rtpTarget.frame()
            : mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
        double remoteOriginX = mirrorMode ? localOriginX : destination.originX;
        double remoteOriginY = mirrorMode ? localOriginY : destination.originY;
        double remoteOriginZ = mirrorMode ? localOriginZ : destination.originZ;
        int depth = portal.getNetworkViewDepth();
        int lateral = Math.min(portal.getNetworkViewLateralPad(), FidelitySettings.plateLateralClampBlocks);
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        LodPolicy lod = portalLod(fidelity);
        boolean blockEntities = FidelitySettings.blockEntities && (fidelity == null || fidelity.effectiveBlockEntities());
        long targetIdentity = rtpTarget == null ? 0L : rtpTarget.plateIdentity();
        long transformRevision = PassRevision.mix(PassRevision.transform(localFrame, remoteFrame,
            localOriginX, localOriginY, localOriginZ, remoteOriginX, remoteOriginY, remoteOriginZ, depth, lateral,
            aperturePadding, buriedCellCulling, lod, blockEntities), targetIdentity);
        ViewPlateKey key = new ViewPlateKey(portal.getId(), destination.destView, eyeFrontSide, quarterTurns, targetIdentity);
        return new Target(key, transformRevision, localFrame, remoteFrame, localOriginX, localOriginY, localOriginZ,
            remoteOriginX, remoteOriginY, remoteOriginZ, mirrorMode, quarterTurns, depth, lateral, aperturePadding,
            buriedCellCulling, lod, blockEntities);
    }

    static ViewPlate<BlockData> acquire(ViewPlateCache<BlockData, World> cache, ProjectionWorldViewProvider viewProvider, ILocalPortal portal,
                                        ProjectorDestination destination, BlockData air, Target target, long destinationRevision, boolean urgent) {
        WorldChangeTracker tracker = Wormholes.projectionChangeTracker;
        return cache.current(target.key(), destinationRevision, target.transformRevision(), tracker, urgent, previous -> {
            ProjectionWorldView plateView = destination.plateView();
            long trackerVersion = tracker == null ? Long.MIN_VALUE : tracker.currentVersion();
            ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(target.key(), portal.getStructure(), plateView, target.localFrame(), target.remoteFrame(),
                target.localOriginX(), target.localOriginY(), target.localOriginZ(), target.remoteOriginX(), target.remoteOriginY(), target.remoteOriginZ(),
                target.mirrorMode(), target.quarterTurns(), target.depth(), target.lateral(), target.aperturePadding(), target.buriedCellCulling(), air, target.lod(),
                target.blockEntities(), destinationRevision, target.transformRevision(), trackerVersion, BukkitProjectorBlocks.defaults());
            LongOpenHashSet dirtyChunks = new LongOpenHashSet();
            boolean patch = previous != null && tracker != null && previous.collectDirt(tracker, dirtyChunks) && !dirtyChunks.isEmpty();
            return job(viewProvider, request, plateView.getWorld(), target.blockEntities(), patch ? previous : null, dirtyChunks);
        });
    }

    static ViewPlate<BlockData> acquireSection(ViewPlateCache<BlockData, World> cache, ProjectionWorldViewProvider viewProvider,
                                               ILocalPortal portal, ProjectorDestination destination, BlockData air, Target target,
                                               BlockBox clip, int distance) {
        int boundedDistance = Math.clamp(distance, 32, 512);
        ViewPlateKey original = target.key();
        ViewPlateKey key = new ViewPlateKey(original.portalId(), new MeshSection(original.destinationViewIdentity(), clip),
            original.frontSide(), original.mirrorQuarterTurns(), original.targetIdentity());
        long transform = PassRevision.mix(target.transformRevision(), boundedDistance);
        WorldChangeTracker tracker = Wormholes.projectionChangeTracker;
        long revision = destination.destView.getRevision();
        return cache.current(key, revision, transform, tracker, false, previous -> {
            ProjectionWorldView view = destination.plateView();
            ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = new ViewPlateBuilder.Request<>(key,
                portal.getStructure(), view, target.localFrame(), target.remoteFrame(), target.localOriginX(), target.localOriginY(),
                target.localOriginZ(), target.remoteOriginX(), target.remoteOriginY(), target.remoteOriginZ(), target.mirrorMode(),
                target.quarterTurns(), boundedDistance, boundedDistance, target.aperturePadding(), false, air, LodPolicy.NONE,
                target.blockEntities(), revision, transform, tracker == null ? Long.MIN_VALUE : tracker.currentVersion(),
                BukkitProjectorBlocks.defaults());
            World world = view.getWorld();
            if (world == null || viewProvider.usesRegionSnapshots()) {
                return ViewPlateBuilder.sectionJob(request, clip);
            }
            BlockBox remote = ViewPlateBuilder.sectionDestinationBox(request, clip);
            PlateCaptureSource.Options capture = new PlateCaptureSource.Options(target.blockEntities(), remote.minY(),
                remote.minY() + remote.sizeY() - 1, true);
            return new PlateCaptureJob<>(new PlateCaptureJob.Plan<>(key, world, ViewPlateBuilder.sectionFootprint(request, clip),
                new PlateCaptureSource(capture), captured -> ViewPlateBuilder.sectionJob(
                    request.withDestView(new CapturedChunkView(world, captured)), clip)));
        });
    }

    static void retireTarget(ViewPlateCache<BlockData, World> cache, ILocalPortal portal, PortalProjector.RtpProjectionTarget previous,
                             PortalProjector.RtpProjectionTarget target) {
        if (cache == null || previous == null || portal.getId() == null) {
            return;
        }
        long previousIdentity = previous.plateIdentity();
        if (target == null || previousIdentity != target.plateIdentity()) {
            cache.invalidateTarget(portal.getId(), previousIdentity);
        }
    }

    private static ViewPlateBuilder.Job<BlockData, World> job(ProjectionWorldViewProvider viewProvider,
                                                              ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request,
                                                              World destWorld, boolean blockEntities, ViewPlate<BlockData> previous,
                                                              LongOpenHashSet dirtyChunks) {
        if (destWorld == null || viewProvider.usesRegionSnapshots()) {
            return previous == null ? ViewPlateBuilder.job(request) : ViewPlateBuilder.patch(request, previous, dirtyChunks);
        }
        ViewPlateBuilder.Footprint footprint = previous == null
            ? ViewPlateBuilder.footprint(request)
            : ViewPlateBuilder.patchFootprint(request, dirtyChunks);
        return new PlateCaptureJob<BlockData, World, PlateCaptureSource.CapturedChunk>(new PlateCaptureJob.Plan<BlockData, World, PlateCaptureSource.CapturedChunk>(
            request.key(), destWorld, footprint, new PlateCaptureSource(PlateCaptureSource.Options.column(blockEntities)),
            captured -> {
                ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> captureRequest = request.withDestView(new CapturedChunkView(destWorld, captured));
                return previous == null ? ViewPlateBuilder.job(captureRequest) : ViewPlateBuilder.patch(captureRequest, previous, dirtyChunks);
            }));
    }

    private record MeshSection(Object destination, BlockBox clip) {
    }

    record Target(ViewPlateKey key,
                  long transformRevision,
                  Frame localFrame,
                  Frame remoteFrame,
                  double localOriginX,
                  double localOriginY,
                  double localOriginZ,
                  double remoteOriginX,
                  double remoteOriginY,
                  double remoteOriginZ,
                  boolean mirrorMode,
                  int quarterTurns,
                  int depth,
                  int lateral,
                  double aperturePadding,
                  boolean buriedCellCulling,
                  LodPolicy lod,
                  boolean blockEntities) {
    }
}
