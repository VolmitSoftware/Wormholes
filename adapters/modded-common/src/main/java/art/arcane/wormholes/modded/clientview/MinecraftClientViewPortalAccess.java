package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftProjectorBlocks;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.MinecraftViewPlates;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.PortalCoordMap;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectorPassRevision;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientRecursionPlanner;
import art.arcane.wormholes.render.client.session.ClientViewPortalAccess;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.List;
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

    @Override
    public void interested(MinecraftClientViewPeer peer, List<UUID> out) {
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (player == null || portals == null) {
            return;
        }
        MinecraftProjectionService projections = runtime.projections();
        List<MinecraftPortal> frame = candidates;
        for (int i = 0; i < frame.size(); i++) {
            MinecraftPortal portal = frame.get(i);
            if (ownable(portal) && projections.attendable(player, portal, portals)) {
                out.add(portal.getId());
            }
        }
    }

    @Override
    public long geometryRevision(MinecraftClientViewPeer peer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return 0L;
        }
        return revision(peer, player, portal, front(player, portal));
    }

    @Override
    public ClientPortalGeometry geometry(MinecraftClientViewPeer peer, UUID portalId, SessionPalette palette) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerPlayer player = peer.player();
        return portal == null || player == null ? null : geometry(peer, player, portal, palette, front(player, portal));
    }

    @Override
    public ViewPlate<BlockState> plate(MinecraftClientViewPeer peer, UUID portalId, boolean firstAttendance) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerPlayer player = peer.player();
        return portal == null || player == null ? null : plate(peer, portal, front(player, portal), firstAttendance);
    }

    @Override
    public void nested(MinecraftClientViewPeer peer, UUID parent, ClientPortalGeometry parentGeometry, List<UUID> out) {
        MinecraftPortal mirror = runtime.portals().get(parent);
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (!parentGeometry.mirror() || mirror == null || player == null || portals == null) {
            return;
        }
        List<MinecraftPortal> frame = candidates;
        for (int i = 0; i < frame.size(); i++) {
            MinecraftPortal portal = frame.get(i);
            if (portal == mirror || portal.isMirrorMode() || !ownable(portal) || !portals.eligible(portal) || portals.world(portal) != player.level()
                || portal.getType() != PortalType.RTP && !portals.hasDestination(portal)) {
                continue;
            }
            AxisAlignedBB area = portal.getGeometry().getArea();
            if (ClientRecursionPlanner.mirrorReaches(parentGeometry, area)) {
                out.add(portal.getId());
            }
        }
    }

    @Override
    public long nestedGeometryRevision(MinecraftClientViewPeer peer, UUID parent, UUID child) {
        MinecraftPortal portal = runtime.portals().get(child);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return 0L;
        }
        return revision(peer, player, portal, reflectedFront(peer, player, parent, portal));
    }

    @Override
    public ClientPortalGeometry nestedGeometry(MinecraftClientViewPeer peer, UUID parent, UUID child, SessionPalette palette) {
        MinecraftPortal portal = runtime.portals().get(child);
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
        MinecraftPortal portal = runtime.portals().get(child);
        ServerPlayer player = peer.player();
        return portal == null || player == null ? null : plate(peer, portal, reflectedFront(peer, player, parent, portal), false);
    }

    private long revision(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal, boolean front) {
        long stamp = ProjectorPassRevision.mix(portal.getGeometry().getRevision(), front ? 1L : 2L);
        stamp = ProjectorPassRevision.mix(stamp, portal.isMirrorMode() ? peer.portals().mirrorQuarterTurns(portal) + 1L : 0L);
        stamp = ProjectorPassRevision.mix(stamp, portal.isBlackoutBackground() ? 1L : 0L);
        stamp = ProjectorPassRevision.mix(stamp, portal.getBlackoutColor().ordinal());
        stamp = ProjectorPassRevision.mix(stamp, portal.getNetworkViewDepth());
        stamp = ProjectorPassRevision.mix(stamp, portal.isOpen() ? 1L : 0L);
        stamp = ProjectorPassRevision.mix(stamp, MinecraftViewPlates.atmosphereMode(portal).ordinal());
        stamp = ProjectorPassRevision.mix(stamp, Objects.hashCode(MinecraftViewPlates.stringSetting(portal, "fidelity.acoustics")));
        stamp = ProjectorPassRevision.mix(stamp, Objects.hashCode(portal.getDestinationId()));
        stamp = ProjectorPassRevision.mix(stamp, portal.getType() == PortalType.RTP ? runtime.rtp().plateIdentity(player, portal) : 0L);
        stamp = ProjectorPassRevision.mix(stamp, System.identityHashCode(player.level()));
        return ProjectorPassRevision.mix(stamp, System.identityHashCode(runtime.configuration().settings()));
    }

    private ClientPortalGeometry geometry(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal, SessionPalette palette,
                                          boolean front) {
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (portals == null || !ownable(portal) || portals.world(portal) != player.level()) {
            return null;
        }
        WormholesSettings settings = runtime.configuration().settings();
        ProjectionConfig projection = settings.getProjection();
        RenderConfig render = settings.getRender();
        BlockState blackout = MinecraftViewPlates.blackoutState(portal, geometryDestination(peer, player, portal));
        long targetIdentity = portal.getType() == PortalType.RTP ? opaque(runtime.rtp().plateIdentity(player, portal)) : 0L;
        ProjectedBlockClaim.LightingPolicy lighting = render.lightingFidelity ? ProjectedBlockClaim.LightingPolicy.SOURCE : ProjectedBlockClaim.LightingPolicy.LOCAL;
        ClientPortalGeometry.Source source = new ClientPortalGeometry.Source(portal.getGeometry(), portal.getFrame(), front,
            portal.isMirrorMode(), portal.isMirrorMode() ? portals.mirrorQuarterTurns(portal) : 0, projection.nearPlanePadding,
            projection.aperturePaddingBlocks, projection.frustumCullingRatio, portal.getNetworkViewDepth(), Math.max(0, projection.recursivePortalDepth),
            portal.isBlackoutBackground() ? ClientPortalGeometry.BLACKOUT_SHELL : ClientPortalGeometry.BLACKOUT_OFF,
            palette.id(BlockStateParser.serialize(blackout)), ClientPortalGeometry.MASK_AIR_PROJECT, lighting, fidelity(portal, render), kind(portal), 0,
            targetIdentity, List.of());
        return ClientPortalGeometry.fromPortal(source).orElse(null);
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
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return 0L;
        }
        long stamp = ProjectorPassRevision.mix(portal.getGeometry().getRevision(), front(player, portal) ? 1L : 2L);
        stamp = ProjectorPassRevision.mix(stamp, portal.getNetworkViewDepth());
        stamp = ProjectorPassRevision.mix(stamp, kind(portal));
        stamp = ProjectorPassRevision.mix(stamp, System.identityHashCode(player.level()));
        return ProjectorPassRevision.mix(stamp, System.identityHashCode(runtime.configuration().settings()));
    }

    @Override
    public ClientPortalGeometry effectGeometry(MinecraftClientViewPeer peer, UUID portalId, SessionPalette palette) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerPlayer player = peer.player();
        MinecraftProjectorPortalAccess portals = peer.portals();
        if (portal == null || player == null || portals == null || portals.world(portal) != player.level()) {
            return null;
        }
        ProjectionConfig projection = runtime.configuration().settings().getProjection();
        ClientPortalGeometry.Source source = new ClientPortalGeometry.Source(portal.getGeometry(), portal.getFrame(), front(player, portal), false, 0,
            projection.nearPlanePadding, projection.aperturePaddingBlocks, projection.frustumCullingRatio, portal.getNetworkViewDepth(), 0,
            ClientPortalGeometry.BLACKOUT_OFF, ClientViewProtocol.PALETTE_AIR, ClientPortalGeometry.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, kind(portal), 0, 0L, List.of());
        return ClientPortalGeometry.fromPortal(source).orElse(null);
    }

    @Override
    public boolean refused(MinecraftClientViewPeer peer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
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
    public BrickLightSource lightBaseline(MinecraftClientViewPeer peer, UUID portalId, ViewPlate<BlockState> plate) {
        return scene.light(peer, portalId, plate);
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
        if (sourceWorld == null || sourceWorld != player.level()) {
            return null;
        }
        MinecraftPortal destination = portal.isMirrorMode() ? portal : portals.projectionDestination(portal);
        ServerLevel destinationWorld = destination == null ? null : portals.world(destination);
        if (destinationWorld == null) {
            return null;
        }
        MinecraftProjectionWorldView destinationView = runtime.projections().view(destinationWorld);
        GeometryVector origin = destination.getOrigin();
        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = portal.isMirrorMode() ? localFrame.flipNormal() : destination.getFrame();
        return new MinecraftViewPlates.Target(player, portal, destinationView, () -> destinationView, remoteFrame, origin.x(), origin.y(),
            origin.z(), portal.isMirrorMode(), portals.mirrorQuarterTurns(portal), front,
            portal.getRenderMode().usesBuriedCellCulling(), MinecraftViewPlates.blockEntities(portal), MinecraftProjectorBlocks.INSTANCE.air());
    }

    private ProjectionContentView<BlockState, BlockState> geometryDestination(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal) {
        MinecraftProjectorPortalAccess portals = peer.portals();
        MinecraftPortal destination = portal.isMirrorMode() ? portal
            : portal.getType() == PortalType.RTP ? runtime.rtp().knownDestination(player, portal) : portals.projectionDestination(portal);
        ServerLevel world = destination == null ? null : portals.world(destination);
        return world == null ? null : runtime.projections().view(world);
    }

    private long opaque(long identity) {
        return identity == 0L ? 0L : ProjectorPassRevision.mix(ProjectorPassRevision.mix(identitySecret, identity), identitySecret);
    }

    private static int kind(MinecraftPortal portal) {
        if (portal.getType() == PortalType.RTP) {
            return ClientPortalGeometry.KIND_RTP;
        }
        return portal.isManaged() ? ClientPortalGeometry.KIND_VANILLA_REPLACEMENT : ClientPortalGeometry.KIND_FRAME;
    }

    private static int fidelity(MinecraftPortal portal, RenderConfig render) {
        int flags = 0;
        if (render.lightingFidelity) {
            flags |= ClientPortalGeometry.FIDELITY_LIGHTING;
        }
        if (FidelitySettings.weather && MinecraftViewPlates.atmosphereMode(portal).relaysWeather()) {
            flags |= ClientPortalGeometry.FIDELITY_WEATHER;
        }
        if (AcousticsProfile.parse(MinecraftViewPlates.stringSetting(portal, "fidelity.acoustics"), FidelitySettings.acousticsProfileDefault)
            != AcousticsProfile.OFF) {
            flags |= ClientPortalGeometry.FIDELITY_SOUNDS;
        }
        return flags;
    }

    private boolean reflectedFront(MinecraftClientViewPeer peer, ServerPlayer player, UUID parent, MinecraftPortal portal) {
        MinecraftPortal mirror = runtime.portals().get(parent);
        MinecraftProjectorPortalAccess portals = peer.portals();
        Vec3 eye = player.getEyePosition();
        if (mirror == null || portals == null) {
            return front(eye.x, eye.y, eye.z, portal);
        }
        GeometryVector origin = mirror.getOrigin();
        double[] reflected = new double[3];
        PortalCoordMap.mirrorDisplayToSourcePointInto(eye.x, eye.y, eye.z, origin.x(), origin.y(), origin.z(), mirror.getFrame(),
            portals.mirrorQuarterTurns(mirror), reflected);
        return front(reflected[0], reflected[1], reflected[2], portal);
    }

    static boolean front(ServerPlayer player, MinecraftPortal portal) {
        Vec3 eye = player.getEyePosition();
        return front(eye.x, eye.y, eye.z, portal);
    }

    private static boolean front(double eyeX, double eyeY, double eyeZ, MinecraftPortal portal) {
        GeometryVector origin = portal.getOrigin();
        Direction normal = portal.getFrame().getNormal();
        return (eyeX - origin.x()) * normal.x() + (eyeY - origin.y()) * normal.y() + (eyeZ - origin.z()) * normal.z() >= 0.0D;
    }
}
