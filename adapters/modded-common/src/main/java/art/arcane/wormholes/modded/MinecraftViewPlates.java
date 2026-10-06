package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.fidelity.FogPlatePolicy;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.volume.LodProfile;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.util.Objects;
import java.util.function.Supplier;

public final class MinecraftViewPlates {
    private MinecraftViewPlates() {
    }

    public static Resolved resolve(WormholesModRuntime runtime, Target target) {
        MinecraftPortal portal = target.portal();
        boolean rtp = portal.getType() == PortalType.RTP;
        if (!FidelitySettings.sharedPlate || rtp && !FidelitySettings.rtpPlates) {
            return null;
        }
        long targetIdentity = rtp ? runtime.rtp().plateIdentity(target.observer(), portal) : target.routeIdentity();
        if (rtp && targetIdentity == 0L) {
            return null;
        }
        Frame localFrame = portal.getFrame();
        Vec3d origin = portal.getOrigin();
        LodPolicy lod = FidelitySettings.lodPolicy(LodProfile.parse(stringSetting(portal, "fidelity.lod"), LodProfile.BALANCED));
        int depth = portal.getNetworkViewDepth();
        int lateral = Math.min(portal.getNetworkViewLateralPad(), FidelitySettings.plateLateralClampBlocks);
        double padding = runtime.configuration().settings().getProjection().aperturePaddingBlocks;
        long transform = ProjectorPassRevision.transform(localFrame, target.remoteFrame(), origin.x(), origin.y(), origin.z(),
            target.originX(), target.originY(), target.originZ(), depth, lateral, padding, target.culling(), lod, target.blockEntities());
        transform = ProjectorPassRevision.mix(transform, targetIdentity);
        transform = ProjectorPassRevision.mix(transform, portal.getGeometry().getRevision());
        long transformRevision = ProjectorPassRevision.mix(transform, Objects.hashCode(portal.getNetworkViewFallbackBlock()));
        long destinationRevision = target.destView() instanceof MinecraftProjectionWorldView ? 0L : target.destView().getRevision();
        ViewPlateKey key = new ViewPlateKey(portal.getId(), target.destView(), target.front(), target.mirrorQuarterTurns(), targetIdentity);
        return new Resolved(key, destinationRevision, transformRevision, lod, depth, lateral, padding);
    }

    public static ViewPlate<BlockState> acquire(WormholesModRuntime runtime, ViewPlateCache<BlockState, ServerLevel> plates, Target target) {
        Resolved resolved = resolve(runtime, target);
        return resolved == null ? null : acquire(runtime, plates, target, resolved, false);
    }

    public static ViewPlate<BlockState> acquire(WormholesModRuntime runtime, ViewPlateCache<BlockState, ServerLevel> plates, Target target,
                                                Resolved resolved, boolean urgent) {
        WorldChangeTracker tracker = runtime.projections().changes();
        MinecraftPortal portal = target.portal();
        Vec3d origin = portal.getOrigin();
        return plates.current(resolved.key(), resolved.destinationRevision(), resolved.transformRevision(), tracker, urgent, previous -> {
            ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> request = new ViewPlateBuilder.Request<>(
                resolved.key(), portal.getGeometry(), target.plateView().get(), portal.getFrame(), target.remoteFrame(), origin.x(), origin.y(),
                origin.z(), target.originX(), target.originY(), target.originZ(), target.mirrorMode(), target.mirrorQuarterTurns(),
                resolved.depth(), resolved.lateral(), resolved.padding(), target.culling(), target.air(), resolved.lod(), target.blockEntities(),
                resolved.destinationRevision(), resolved.transformRevision(), tracker.currentVersion(), MinecraftProjectorBlocks.INSTANCE);
            LongOpenHashSet dirtyChunks = new LongOpenHashSet();
            boolean patch = previous != null && previous.collectDirt(tracker, dirtyChunks) && !dirtyChunks.isEmpty();
            return job(runtime, request, target.blockEntities(), patch ? previous : null, dirtyChunks);
        });
    }

    public static ViewPlate<BlockState> acquireSection(WormholesModRuntime runtime, ViewPlateCache<BlockState, ServerLevel> plates,
                                                       Target target, Resolved resolved, PlateBox clip, int distance) {
        int boundedDistance = Math.clamp(distance, 32, 512);
        ViewPlateKey original = resolved.key();
        ViewPlateKey key = new ViewPlateKey(original.portalId(), new MeshSection(original.destinationViewIdentity(), clip),
            original.frontSide(), original.mirrorQuarterTurns(), original.targetIdentity());
        long transform = ProjectorPassRevision.mix(resolved.transformRevision(), boundedDistance);
        WorldChangeTracker tracker = runtime.projections().changes();
        MinecraftPortal portal = target.portal();
        Vec3d origin = portal.getOrigin();
        return plates.current(key, resolved.destinationRevision(), transform, tracker, false, previous -> {
            ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> request = new ViewPlateBuilder.Request<>(
                key, portal.getGeometry(), target.plateView().get(), portal.getFrame(), target.remoteFrame(), origin.x(), origin.y(),
                origin.z(), target.originX(), target.originY(), target.originZ(), target.mirrorMode(), target.mirrorQuarterTurns(),
                boundedDistance, boundedDistance, resolved.padding(), false, target.air(), LodPolicy.NONE, target.blockEntities(),
                resolved.destinationRevision(), transform, tracker.currentVersion(), MinecraftProjectorBlocks.INSTANCE);
            if (!(request.destView() instanceof MinecraftProjectionWorldView local)) {
                return ViewPlateBuilder.sectionJob(request, clip);
            }
            PlateBox remote = ViewPlateBuilder.sectionDestinationBox(request, clip);
            MinecraftPlateCaptureSource.Options capture = new MinecraftPlateCaptureSource.Options(local.worldId(), target.blockEntities(),
                remote.minY(), remote.minY() + remote.sizeY() - 1, true);
            return new PlateCaptureJob<>(new PlateCaptureJob.Plan<>(key, local.getWorld(), ViewPlateBuilder.sectionFootprint(request, clip),
                new MinecraftPlateCaptureSource(runtime, capture), captured -> ViewPlateBuilder.sectionJob(
                    request.withDestView(new MinecraftCapturedChunkView(local.worldId(), local.getMinHeight(), local.getWorld().getMaxY() + 1,
                        request.destinationRevision(), captured)), clip)));
        });
    }

    public static boolean blockEntities(MinecraftPortal portal) {
        return FidelitySettings.blockEntities && (!(portal.setting("fidelity.block_entities") instanceof Boolean enabled) || enabled);
    }

    public static boolean sectionQueued(ViewPlateCache<BlockState, ServerLevel> plates, Resolved resolved, PlateBox clip) {
        ViewPlateKey original = resolved.key();
        return plates.captureQueued(new ViewPlateKey(original.portalId(), new MeshSection(original.destinationViewIdentity(), clip),
            original.frontSide(), original.mirrorQuarterTurns(), original.targetIdentity()));
    }

    public static AtmosphereMode atmosphereMode(MinecraftPortal portal) {
        return AtmosphereMode.parse(stringSetting(portal, "fidelity.atmosphere"), FidelitySettings.atmosphereModeDefault);
    }

    public static BlockState blackoutState(MinecraftPortal portal, ContentView<BlockState, BlockState> destView) {
        BlockState state = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(portal.getBlackoutColor().blockState()))
            .orElse(Blocks.CONCRETE.pick(DyeColor.BLACK)).defaultBlockState();
        if (!FogPlatePolicy.applies(FidelitySettings.fogPlate, atmosphereMode(portal)) || !(destView instanceof MinecraftProjectionWorldView local)) {
            return state;
        }
        ServerLevel world = local.getWorld();
        FogPlatePolicy.Dimension dimension = world.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD) ? FogPlatePolicy.Dimension.OVERWORLD
            : world.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER) ? FogPlatePolicy.Dimension.NETHER
            : world.dimensionTypeRegistration().is(BuiltinDimensionTypes.END) ? FogPlatePolicy.Dimension.END : FogPlatePolicy.Dimension.CUSTOM;
        String shell = FogPlatePolicy.shellState(dimension);
        return shell == null ? state : BuiltInRegistries.BLOCK.getValue(Identifier.parse(shell)).defaultBlockState();
    }

    public static String stringSetting(MinecraftPortal portal, String name) {
        Object value = portal.setting(name);
        return value instanceof String text ? text : "";
    }

    private static ViewPlateBuilder.Job<BlockState, ServerLevel> job(WormholesModRuntime runtime,
                                                                     ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> request,
                                                                     boolean blockEntities, ViewPlate<BlockState> previous, LongOpenHashSet dirtyChunks) {
        if (!(request.destView() instanceof MinecraftProjectionWorldView local)) {
            return previous == null ? ViewPlateBuilder.job(request) : ViewPlateBuilder.patch(request, previous, dirtyChunks);
        }
        ViewPlateBuilder.Footprint footprint = previous == null
            ? ViewPlateBuilder.footprint(request)
            : ViewPlateBuilder.patchFootprint(request, dirtyChunks);
        return new PlateCaptureJob<>(new PlateCaptureJob.Plan<>(request.key(), local.getWorld(), footprint,
            new MinecraftPlateCaptureSource(runtime, MinecraftPlateCaptureSource.Options.column(local.worldId(), blockEntities)), captured -> {
                ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> captureRequest = request.withDestView(
                    new MinecraftCapturedChunkView(local.worldId(), local.getMinHeight(), local.getMaxHeight(), request.destinationRevision(), captured));
                return previous == null ? ViewPlateBuilder.job(captureRequest) : ViewPlateBuilder.patch(captureRequest, previous, dirtyChunks);
            }));
    }

    private record MeshSection(Object destination, PlateBox clip) {
    }

    public record Target(ServerPlayer observer,
                         MinecraftPortal portal,
                         ContentView<BlockState, BlockState> destView,
                         Supplier<ContentView<BlockState, BlockState>> plateView,
                         Frame remoteFrame,
                         double originX,
                         double originY,
                         double originZ,
                         boolean mirrorMode,
                         int mirrorQuarterTurns,
                         boolean front,
                         boolean culling,
                         boolean blockEntities,
                         BlockState air,
                         long routeIdentity) {
        public Target {
            Objects.requireNonNull(portal, "portal");
            Objects.requireNonNull(destView, "destView");
            Objects.requireNonNull(plateView, "plateView");
            Objects.requireNonNull(remoteFrame, "remoteFrame");
            Objects.requireNonNull(air, "air");
        }
    }

    public record Resolved(ViewPlateKey key, long destinationRevision, long transformRevision, LodPolicy lod, int depth, int lateral,
                           double padding) {
    }
}
