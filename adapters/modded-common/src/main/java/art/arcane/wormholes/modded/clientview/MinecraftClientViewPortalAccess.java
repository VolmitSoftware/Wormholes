package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftProjectorBlocks;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.MinecraftViewPlates;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.recursion.ClientRecursionPlanner;
import art.arcane.wormholes.render.client.session.ClientViewPortalAccess;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftClientViewPortalAccess implements ClientViewPortalAccess<MinecraftClientViewPeer, BlockState> {
    private static final String UNIVERSAL_TUNNEL = "UNIVERSAL";

    private final WormholesModRuntime runtime;
    private final long identitySecret;
    private final MinecraftClientViewScene scene;
    private List<MinecraftPortal> candidates;

    public MinecraftClientViewPortalAccess(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.identitySecret = new SecureRandom().nextLong();
        this.candidates = List.of();
        this.scene = new MinecraftClientViewScene(runtime, this);
    }

    public MinecraftClientViewScene scene() {
        return scene;
    }

    public void frame(List<MinecraftPortal> portals) {
        candidates = Objects.requireNonNull(portals, "portals");
    }

    public static boolean ownable(MinecraftPortal portal) {
        return portal.isMirrorMode() || !UNIVERSAL_TUNNEL.equals(portal.getTunnelType());
    }

    MinecraftPortal portal(MinecraftClientViewPeer peer, UUID id) {
        MinecraftClientViewPeer.NestedContext context = peer.nestedContext(id);
        id = context == null ? id : context.portal();
        MinecraftPortal portal = runtime.portals().get(id);
        return portal == null ? peer.door(id) : portal;
    }

    private List<MinecraftPortal> candidates(MinecraftClientViewPeer peer) {
        List<MinecraftPortal> doors = peer.doors();
        if (doors.isEmpty()) {
            return candidates;
        }
        ArrayList<MinecraftPortal> frame = new ArrayList<>(candidates.size() + doors.size());
        frame.addAll(candidates);
        frame.addAll(doors);
        return frame;
    }

    @Override
    public void interested(MinecraftClientViewPeer peer, List<UUID> out) {
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (player == null || portals == null) {
            return;
        }
        MinecraftProjectionService projections = runtime.projections();
        peer.updateDoors(runtime);
        List<MinecraftPortal> frame = candidates(peer);
        for (int i = 0; i < frame.size(); i++) {
            MinecraftPortal portal = frame.get(i);
            if (ownable(portal) && projections.attendable(player, portal, portals)) {
                out.add(portal.getId());
            }
        }
    }

    @Override
    public long geometryRevision(MinecraftClientViewPeer peer, UUID portalId) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return 0L;
        }
        return revision(peer, player, portal, front(player, portal));
    }

    @Override
    public ApertureDescriptor geometry(MinecraftClientViewPeer peer, UUID portalId, SessionPalette palette) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        return portal == null || player == null ? null : geometry(peer, player, portal, palette, front(player, portal));
    }

    @Override
    public ViewPlate<BlockState> plate(MinecraftClientViewPeer peer, UUID portalId, boolean firstAttendance) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        return portal == null || player == null ? null : plate(peer, portal, front(player, portal), firstAttendance);
    }

    @Override
    public int meshDistanceBlocks(MinecraftClientViewPeer peer) {
        ServerPlayer player = peer.player();
        return player == null ? 0 : Math.clamp(player.requestedViewDistance(), 2, 32) * 16;
    }

    @Override
    public Vec3d meshEye(MinecraftClientViewPeer peer) {
        ServerPlayer player = peer.player();
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        return new Vec3d(eye.x, eye.y, eye.z);
    }

    @Override
    public WorldChangeTracker meshChanges(MinecraftClientViewPeer observer) {
        return runtime.projections().changes();
    }

    @Override
    public boolean localMeshWorld(MinecraftClientViewPeer peer, UUID contextId) {
        ServerPlayer player = peer.player();
        if (player == null) {
            return false;
        }
        MinecraftClientViewPeer.NestedContext context = peer.nestedContext(contextId);
        if (context != null) {
            return context.destinationWorld() == player.level();
        }
        MinecraftPortal portal = portal(peer, contextId);
        return portal != null && portal.isMirrorMode() && peer.portals().world(portal) == player.level();
    }

    @Override
    public ViewPlate<BlockState> meshSection(MinecraftClientViewPeer peer, UUID portalId, BlockBox clip, int distance) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        MinecraftViewPlates.Target target = portal == null || player == null ? null : target(peer, portal, front(player, portal));
        MinecraftViewPlates.Resolved resolved = target == null ? null : MinecraftViewPlates.resolve(runtime, target);
        return resolved == null ? null : MinecraftViewPlates.acquireSection(runtime, runtime.projections().plates(), target, resolved, clip, distance);
    }

    @Override
    public boolean meshSectionQueued(MinecraftClientViewPeer peer, UUID portalId, BlockBox clip) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        return portal != null && player != null && sectionQueued(peer, portal, clip, front(player, portal));
    }

    @Override
    public boolean nestedMeshSectionQueued(MinecraftClientViewPeer peer, UUID parent, UUID child, BlockBox clip) {
        MinecraftPortal portal = portal(peer, child);
        ServerPlayer player = peer.player();
        return portal != null && player != null && sectionQueued(peer, portal, clip, reflectedFront(peer, player, parent, portal));
    }

    private boolean sectionQueued(MinecraftClientViewPeer peer, MinecraftPortal portal, BlockBox clip, boolean front) {
        MinecraftViewPlates.Target target = target(peer, portal, front);
        MinecraftViewPlates.Resolved resolved = target == null ? null : MinecraftViewPlates.resolve(runtime, target);
        return resolved != null && MinecraftViewPlates.sectionQueued(runtime.projections().plates(), resolved, clip);
    }

    @Override
    public void prepareNested(MinecraftClientViewPeer peer, UUID context, UUID parentContext, UUID portalId) {
        MinecraftClientViewPeer.NestedContext parent = parentContext == null ? null : peer.nestedContext(parentContext);
        Vec3d eye = parentContext == null ? meshEye(peer) : parent == null ? null : parent.destinationEye();
        MinecraftPortal portal = portal(peer, portalId);
        if (eye == null || portal == null || parent != null && peer.portals().world(portal) != parent.destinationWorld()) {
            peer.nestedContext(context, null);
            return;
        }
        MinecraftClientViewScene.Destination destination = scene.destination(peer, portalId, front(eye.x(), eye.y(), eye.z(), portal));
        if (destination == null) {
            peer.nestedContext(context, null);
            return;
        }
        OpticTransform affine = destination.frame().transform().normalized();
        peer.nestedContext(context, new MinecraftClientViewPeer.NestedContext(portalId, eye,
            affine.inverse().point(new Vec3d(eye.x(), eye.y(), eye.z())), destination.world(), affine));
    }

    @Override
    public void releaseNested(MinecraftClientViewPeer peer, UUID context) {
        peer.nestedContext(context, null);
    }

    @Override
    public Vec3d nestedEye(MinecraftClientViewPeer peer, UUID context) {
        MinecraftClientViewPeer.NestedContext branch = peer.nestedContext(context);
        return branch == null ? null : branch.destinationEye();
    }

    @Override
    public void nested(MinecraftClientViewPeer peer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out) {
        MinecraftPortal mirror = portal(peer, parent);
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (peer.meshDepth() > 0) {
            nestedMesh(peer, parent, parentGeometry, out);
            return;
        }
        if (!parentGeometry.mirror() || mirror == null || player == null || portals == null) {
            return;
        }
        MinecraftClientViewScene.Destination destination = scene.destination(peer, parent, front(player, mirror));
        if (destination == null) {
            return;
        }
        OpticTransform transform = destination.frame().transform().normalized();
        List<MinecraftPortal> frame = candidates(peer);
        for (int i = 0; i < frame.size(); i++) {
            MinecraftPortal portal = frame.get(i);
            if (portal == mirror || portal.isMirrorMode() || !ownable(portal) || !portals.eligible(portal) || portals.world(portal) != player.level()
                || portal.getType() != PortalType.RTP && !portals.hasDestination(portal)) {
                continue;
            }
            Box area = portal.getGeometry().getArea();
            if (ClientRecursionPlanner.destinationReaches(parentGeometry, transform, area)) {
                out.add(portal.getId());
            }
        }
    }

    private void nestedMesh(MinecraftClientViewPeer peer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out) {
        MinecraftClientViewPeer.NestedContext context = peer.nestedContext(parent);
        if (context == null) {
            return;
        }
        MinecraftProjectorPortalAccess portals = peer.portals();
        for (MinecraftPortal child : candidates(peer)) {
            if (child.getId().equals(context.portal()) || !ownable(child) || !portals.eligible(child)
                || portals.world(child) != context.destinationWorld()
                || !child.isMirrorMode() && child.getType() != PortalType.RTP && !portals.hasDestination(child)) {
                continue;
            }
            if (ClientRecursionPlanner.destinationReaches(parentGeometry, context.transform(), child.getGeometry().getArea())) {
                out.add(child.getId());
            }
        }
    }

    @Override
    public long nestedGeometryRevision(MinecraftClientViewPeer peer, UUID parent, UUID child) {
        MinecraftPortal portal = portal(peer, child);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return 0L;
        }
        return revision(peer, player, portal, reflectedFront(peer, player, parent, portal));
    }

    @Override
    public ApertureDescriptor nestedGeometry(MinecraftClientViewPeer peer, UUID parent, UUID child, SessionPalette palette) {
        MinecraftPortal portal = portal(peer, child);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return null;
        }
        boolean front = reflectedFront(peer, player, parent, portal);
        MinecraftViewPlates.Target target = target(peer, portal, front);
        if (target == null || MinecraftViewPlates.resolve(runtime, target) == null) {
            return null;
        }
        return geometry(peer, player, portal, palette, front);
    }

    @Override
    public ViewPlate<BlockState> nestedPlate(MinecraftClientViewPeer peer, UUID parent, UUID child) {
        MinecraftPortal portal = portal(peer, child);
        ServerPlayer player = peer.player();
        return portal == null || player == null ? null : plate(peer, portal, reflectedFront(peer, player, parent, portal), false);
    }

    @Override
    public ViewPlate<BlockState> nestedMeshSection(MinecraftClientViewPeer peer, UUID parent, UUID child, BlockBox clip, int distance) {
        MinecraftPortal portal = portal(peer, child);
        ServerPlayer player = peer.player();
        MinecraftViewPlates.Target target = portal == null || player == null ? null
            : target(peer, portal, reflectedFront(peer, player, parent, portal));
        MinecraftViewPlates.Resolved resolved = target == null ? null : MinecraftViewPlates.resolve(runtime, target);
        return resolved == null ? null : MinecraftViewPlates.acquireSection(runtime, runtime.projections().plates(), target, resolved, clip, distance);
    }

    private long revision(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal, boolean front) {
        long stamp = ProjectorPassRevision.mix(portal.getGeometry().getRevision(), front ? 1L : 2L);
        stamp = ProjectorPassRevision.mix(stamp, peer.portals().routeIdentity(portal));
        if (peer.meshDepth() > 0) {
            stamp = ProjectorPassRevision.mix(stamp, meshTargetRevision(peer, portal, front));
        }
        stamp = ProjectorPassRevision.mix(stamp, portal.isMirrorMode() ? mirrorQuarterTurns(peer, portal) + 1L : 0L);
        if (peer.meshDepth() == 0) {
            stamp = ProjectorPassRevision.mix(stamp, portal.isBlackoutBackground() ? 1L : 0L);
            stamp = ProjectorPassRevision.mix(stamp, portal.getBlackoutColor().ordinal());
        }
        stamp = ProjectorPassRevision.mix(stamp, portal.getNetworkViewDepth());
        stamp = ProjectorPassRevision.mix(stamp, portal.isOpen() ? 1L : 0L);
        stamp = ProjectorPassRevision.mix(stamp, MinecraftViewPlates.atmosphereMode(portal).ordinal());
        stamp = ProjectorPassRevision.mix(stamp, Objects.hashCode(MinecraftViewPlates.stringSetting(portal, "fidelity.acoustics")));
        stamp = ProjectorPassRevision.mix(stamp, Objects.hashCode(portal.getDestinationId()));
        stamp = ProjectorPassRevision.mix(stamp, portal.getType() == PortalType.RTP ? runtime.rtp().plateIdentity(player, portal) : 0L);
        stamp = ProjectorPassRevision.mix(stamp, System.identityHashCode(player.level()));
        return ProjectorPassRevision.mix(stamp, System.identityHashCode(runtime.configuration().settings()));
    }

    private ApertureDescriptor geometry(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal, SessionPalette palette,
                                          boolean front) {
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (portals == null || !ownable(portal) || peer.meshDepth() == 0 && portals.world(portal) != player.level()) {
            return null;
        }
        WormholesSettings settings = runtime.configuration().settings();
        ProjectionConfig projection = settings.getProjection();
        RenderConfig render = settings.getRender();
        boolean blackout = peer.meshDepth() == 0 && portal.isBlackoutBackground();
        int blackoutState = blackout
            ? palette.id(BlockStateParser.serialize(MinecraftViewPlates.blackoutState(portal, geometryDestination(peer, player, portal))))
            : ViewStreamLimits.PALETTE_AIR;
        long targetIdentity = opaque(portal.getType() == PortalType.RTP ? runtime.rtp().plateIdentity(player, portal) : portals.routeIdentity(portal));
        if (peer.meshDepth() > 0) {
            targetIdentity = opaque(ProjectorPassRevision.mix(targetIdentity, meshTargetRevision(peer, portal, front)));
        }
        ProjectedBlockClaim.LightingPolicy lighting = peer.meshDepth() > 0 || render.lightingFidelity
            ? ProjectedBlockClaim.LightingPolicy.SOURCE : ProjectedBlockClaim.LightingPolicy.LOCAL;
        int kind = kind(peer, portal);
        ApertureDescriptor.Source source = new ApertureDescriptor.Source(portal.getGeometry(), portal.getFrame(), front,
            portal.isMirrorMode(), portal.isMirrorMode() ? mirrorQuarterTurns(peer, portal) : 0, projection.nearPlanePadding,
            projection.aperturePaddingBlocks, projection.frustumCullingRatio, portal.getNetworkViewDepth(), Math.max(0, projection.recursivePortalDepth),
            blackout ? ApertureDescriptor.BLACKOUT_SHELL : ApertureDescriptor.BLACKOUT_OFF,
            blackoutState, ApertureDescriptor.MASK_AIR_PROJECT, lighting, fidelity(portal, render), kind,
            DoorApertureFrames.geometryPlaneOffset(kind, portal.getFrame()), 0,
            targetIdentity, List.of());
        return ApertureDescriptor.fromPortal(source).orElse(null);
    }

    private ViewPlate<BlockState> plate(MinecraftClientViewPeer peer, MinecraftPortal portal, boolean front, boolean urgent) {
        MinecraftViewPlates.Target target = target(peer, portal, front);
        if (target == null) {
            return null;
        }
        MinecraftViewPlates.Resolved resolved = MinecraftViewPlates.resolve(runtime, target);
        return resolved == null ? null : MinecraftViewPlates.acquire(runtime, runtime.projections().plates(), target, resolved, urgent);
    }

    @Override
    public void effects(MinecraftClientViewPeer peer, List<UUID> out) {
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (player == null || portals == null || !runtime.configuration().settings().getMain().enableParticles) {
            return;
        }
        List<MinecraftPortal> frame = candidates;
        for (int i = 0; i < frame.size(); i++) {
            MinecraftPortal portal = frame.get(i);
            if ((portal.getAmbientStyle() != AmbientParticleStyle.OFF || portal.getType() == PortalType.RTP) && portals.world(portal) == player.level()
                && portals.view(portal).containsPrimitive(player.getX(), player.getY(), player.getZ())) {
                out.add(portal.getId());
            }
        }
    }

    @Override
    public long effectGeometryRevision(MinecraftClientViewPeer peer, UUID portalId) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return 0L;
        }
        long stamp = ProjectorPassRevision.mix(portal.getGeometry().getRevision(), front(player, portal) ? 1L : 2L);
        stamp = ProjectorPassRevision.mix(stamp, portal.getNetworkViewDepth());
        stamp = ProjectorPassRevision.mix(stamp, kind(peer, portal));
        stamp = ProjectorPassRevision.mix(stamp, System.identityHashCode(player.level()));
        return ProjectorPassRevision.mix(stamp, System.identityHashCode(runtime.configuration().settings()));
    }

    @Override
    public ApertureDescriptor effectGeometry(MinecraftClientViewPeer peer, UUID portalId, SessionPalette palette) {
        MinecraftPortal portal = portal(peer, portalId);
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (portal == null || player == null || portals == null || portals.world(portal) != player.level()) {
            return null;
        }
        ProjectionConfig projection = runtime.configuration().settings().getProjection();
        int kind = kind(peer, portal);
        ApertureDescriptor.Source source = new ApertureDescriptor.Source(portal.getGeometry(), portal.getFrame(), front(player, portal), false, 0,
            projection.nearPlanePadding, projection.aperturePaddingBlocks, projection.frustumCullingRatio, portal.getNetworkViewDepth(), 0,
            ApertureDescriptor.BLACKOUT_OFF, ViewStreamLimits.PALETTE_AIR, ApertureDescriptor.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, kind, DoorApertureFrames.geometryPlaneOffset(kind, portal.getFrame()), 0, 0L,
            List.of());
        return ApertureDescriptor.fromPortal(source).orElse(null);
    }

    @Override
    public boolean refused(MinecraftClientViewPeer peer, UUID portalId) {
        MinecraftPortal portal = portal(peer, portalId);
        if (portal == null || !ownable(portal) || !FidelitySettings.sharedPlate
            || portal.getType() == PortalType.RTP && !FidelitySettings.rtpPlates) {
            return true;
        }
        ServerPlayer player = peer.player();
        MinecraftViewPlates.Target target = player == null ? null : target(peer, portal, front(player, portal));
        MinecraftViewPlates.Resolved resolved = target == null ? null : MinecraftViewPlates.resolve(runtime, target);
        return resolved != null && runtime.projections().plates().isRefused(resolved.key(), resolved.transformRevision());
    }

    @Override
    public ViewPlate<BlockState> standbyPlate(MinecraftClientViewPeer peer, UUID portalId) {
        return null;
    }

    @Override
    public SectionBiomes meshBiomes(MinecraftClientViewPeer peer, UUID portalId, ViewPlate<BlockState> plate) {
        if (plate.environment() == null) {
            throw new IllegalStateException("native mesh section has no captured destination light and biomes for " + portalId);
        }
        return plate.environment().biomes();
    }

    @Override
    public BrickLightSource lightBaseline(MinecraftClientViewPeer peer, UUID portalId, ViewPlate<BlockState> plate) {
        return peer.meshDepth() > 0 && plate.environment() != null ? plate.environment() : scene.light(peer, portalId, plate);
    }

    @Override
    public void releaseVanilla(MinecraftClientViewPeer peer, UUID portalId) {
    }

    MinecraftViewPlates.Target target(MinecraftClientViewPeer peer, MinecraftPortal portal, boolean front) {
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (player == null || portals == null || !ownable(portal)) {
            return null;
        }
        ServerLevel sourceWorld = portals.world(portal);
        if (sourceWorld == null || peer.meshDepth() == 0 && sourceWorld != player.level()) {
            return null;
        }
        MinecraftPortal destination = portal.isMirrorMode() ? portal : portals.projectionDestination(portal);
        ServerLevel destinationWorld = destination == null ? null : portals.world(destination);
        if (destinationWorld == null) {
            return null;
        }
        MinecraftProjectionWorldView destinationView = runtime.projections().view(destinationWorld);
        Vec3d origin = destination.getOrigin();
        Frame localFrame = portal.getFrame();
        Frame remoteFrame = portal.isMirrorMode() ? localFrame.flipNormal() : destination.getFrame();
        return new MinecraftViewPlates.Target(player, portal, destinationView, () -> destinationView, remoteFrame, origin.x(), origin.y(),
            origin.z(), portal.isMirrorMode(), mirrorQuarterTurns(peer, portal), front,
            portal.getRenderMode().scanMode().buriedCellCulling(), MinecraftViewPlates.blockEntities(portal), MinecraftProjectorBlocks.INSTANCE.air(), portals.routeIdentity(portal));
    }

    private ContentView<BlockState, BlockState> geometryDestination(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal) {
        MinecraftProjectorPortalAccess portals = peer.portals();
        MinecraftPortal destination = portal.isMirrorMode() ? portal
            : portal.getType() == PortalType.RTP ? runtime.rtp().knownDestination(player, portal) : portals.projectionDestination(portal);
        ServerLevel world = destination == null ? null : portals.world(destination);
        return world == null ? null : runtime.projections().view(world);
    }

    private long opaque(long identity) {
        return identity == 0L ? 0L : ProjectorPassRevision.mix(ProjectorPassRevision.mix(identitySecret, identity), identitySecret);
    }

    private static int kind(MinecraftClientViewPeer peer, MinecraftPortal portal) {
        if (peer.door(portal.getId()) == portal) {
            return ApertureDescriptor.KIND_DOOR;
        }
        if (portal.getType() == PortalType.RTP) {
            return ApertureDescriptor.KIND_RTP;
        }
        return portal.isManaged() ? ApertureDescriptor.KIND_VANILLA_REPLACEMENT : ApertureDescriptor.KIND_FRAME;
    }

    private static int fidelity(MinecraftPortal portal, RenderConfig render) {
        int flags = 0;
        if (render.lightingFidelity) {
            flags |= ApertureDescriptor.FIDELITY_LIGHTING;
        }
        if (FidelitySettings.weather && MinecraftViewPlates.atmosphereMode(portal).relaysWeather()) {
            flags |= ApertureDescriptor.FIDELITY_WEATHER;
        }
        if (AcousticsProfile.parse(MinecraftViewPlates.stringSetting(portal, "fidelity.acoustics"), FidelitySettings.acousticsProfileDefault)
            != AcousticsProfile.OFF) {
            flags |= ApertureDescriptor.FIDELITY_SOUNDS;
        }
        return flags;
    }

    boolean reflectedFront(MinecraftClientViewPeer peer, ServerPlayer player, UUID parent, MinecraftPortal portal) {
        MinecraftClientViewPeer.NestedContext context = peer.nestedContext(parent);
        if (peer.meshDepth() > 0 && context != null) {
            Vec3d eye = context.destinationEye();
            return front(eye.x(), eye.y(), eye.z(), portal);
        }
        MinecraftPortal mirror = portal(peer, parent);
        MinecraftProjectorPortalAccess portals = peer.portals();
        Vec3 eye = player.getEyePosition();
        if (mirror == null || portals == null) {
            return front(eye.x, eye.y, eye.z, portal);
        }
        Vec3d origin = mirror.getOrigin();
        double[] reflected = new double[3];
        OpticTransform.mirror(mirror.getFrame(), origin, QuarterTurn.of(mirrorQuarterTurns(peer, mirror))).inverse()
            .pointInto(eye.x, eye.y, eye.z, reflected);
        return front(reflected[0], reflected[1], reflected[2], portal);
    }

    private long meshTargetRevision(MinecraftClientViewPeer peer, MinecraftPortal portal, boolean front) {
        MinecraftViewPlates.Target target = target(peer, portal, front);
        MinecraftViewPlates.Resolved resolved = target == null ? null : MinecraftViewPlates.resolve(runtime, target);
        return resolved == null ? 0L : ProjectorPassRevision.mix(resolved.transformRevision(), System.identityHashCode(target.destView()));
    }

    private static int mirrorQuarterTurns(MinecraftClientViewPeer peer, MinecraftPortal portal) {
        if (!portal.isMirrorMode()) {
            return 0;
        }
        return peer.meshDepth() > 0 ? portal.getMirrorRotation().getQuarterTurns() : peer.portals().mirrorTurns(portal).getQuarterTurns();
    }

    static boolean front(ServerPlayer player, MinecraftPortal portal) {
        Vec3 eye = player.getEyePosition();
        return front(eye.x, eye.y, eye.z, portal);
    }

    static boolean front(double eyeX, double eyeY, double eyeZ, MinecraftPortal portal) {
        Vec3d origin = portal.getOrigin();
        Face normal = portal.getFrame().getNormal();
        return (eyeX - origin.x()) * normal.x() + (eyeY - origin.y()) * normal.y() + (eyeZ - origin.z()) * normal.z() >= 0.0D;
    }
}
