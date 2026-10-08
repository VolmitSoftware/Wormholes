package art.arcane.wormholes.render;

import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.door.view.AbstractApertureFacade;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.SessionPalette;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.scan.PassRevision;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.transit.TransitPortalExtension;
import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.ScaleRule;

public final class ClientViewPortalSource {
    private static final long REVISION_SEED = 1469598103934665603L;

    private final ILocalPortal portal;
    private final ProjectionWorldViewProvider views;
    private final ViewPlateCache<BlockData, World> plates;
    private final ProjectorDestination destination;
    private final ProjectorBlackoutSeal blackout;
    private final BlockData air;
    private long updatedTick;
    private long touchedTick;
    private ProjectorDestination.Outcome outcome;
    private PortalProjector.RtpProjectionTarget target;
    private boolean frontSide;
    private ViewPlate<BlockData> plate;
    private ProjectorPlates.Target plateTarget;
    private boolean plateResolved;
    private boolean refused;
    private boolean nativeMesh;
    private World viewWorld;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private String blackoutState;
    private long geometryRevision;

    public ClientViewPortalSource(ILocalPortal portal, ProjectionWorldViewProvider views, ViewPlateCache<BlockData, World> plates) {
        this.portal = Objects.requireNonNull(portal, "portal");
        this.views = Objects.requireNonNull(views, "views");
        this.plates = plates;
        this.destination = new ProjectorDestination(portal, views);
        this.blackout = new ProjectorBlackoutSeal();
        this.air = BukkitProjectorBlocks.defaults().air();
        this.updatedTick = Long.MIN_VALUE;
        this.outcome = ProjectorDestination.Outcome.WAIT;
    }

    public ILocalPortal portal() {
        return portal;
    }

    public long touchedTick() {
        return touchedTick;
    }

    public void touch(long tick) {
        touchedTick = tick;
    }

    public void update(Player observer, Location eye, PortalProjector.RtpProjectionTarget rtpTarget, long tick, boolean nativeMesh) {
        if (tick == updatedTick && this.nativeMesh == nativeMesh && Objects.equals(target, rtpTarget)
            && Objects.equals(viewWorld, eye.getWorld()) && eyeX == eye.getX() && eyeY == eye.getY() && eyeZ == eye.getZ()) {
            return;
        }
        this.nativeMesh = nativeMesh;
        viewWorld = eye.getWorld();
        eyeX = eye.getX();
        eyeY = eye.getY();
        eyeZ = eye.getZ();
        updatedTick = tick;
        touchedTick = tick;
        PortalProjector.RtpProjectionTarget previous = target;
        target = rtpTarget;
        if (previous != null && (rtpTarget == null || rtpTarget.requiresDestinationInvalidation(previous))) {
            ProjectorPlates.retireTarget(plates, portal, previous, rtpTarget);
        }
        plate = null;
        plateTarget = null;
        plateResolved = false;
        refused = false;
        blackoutState = null;
        if (!portal.isOpen() || portal.getFrame() == null || portal.getOrigin() == null || portal.getStructure() == null) {
            outcome = ProjectorDestination.Outcome.CLOSE;
            geometryRevision = 0L;
            return;
        }
        outcome = destination.resolve(observer, viewWorld, rtpTarget, nativeMesh ? Math.clamp(observer.getClientViewDistance(), 2, 32) * 16 : 0);
        if (nativeMesh && destination.mirrorMode) {
            destination.mirrorRotationQuarterTurns = portal.getMirrorRotation().getQuarterTurns();
        }
        frontSide = ProjectorPlates.frontSide(portal, eye);
        FidelityPortalExtension fidelity = ProjectorPlates.fidelity(portal);
        if (!nativeMesh && portal.isBlackoutBackground()) {
            World destinationWorld = outcome == ProjectorDestination.Outcome.READY ? destination.destWorld : null;
            blackout.beginPass(portal.getBlackoutColor(), destinationWorld == null ? null : destinationWorld.getEnvironment(),
                atmosphereMode(fidelity));
            BlockData shell = blackout.data();
            blackoutState = shell == null ? null : shell.getAsString();
        } else {
            blackout.disable();
        }
        geometryRevision = revision(fidelity);
        if (outcome != ProjectorDestination.Outcome.READY) {
            return;
        }
        plateTarget = ProjectorPlates.target(portal, destination, frontSide, portal.getRenderMode().scanMode().buriedCellCulling(), rtpTarget, fidelity);
        if (nativeMesh) {
            geometryRevision = PassRevision.mix(geometryRevision, meshTargetRevision());
        }
        if (!ProjectorPlates.enabled(plates, portal, rtpTarget)) {
            refused = true;
            return;
        }
        refused = plates.isRefused(plateTarget.key(), plateTarget.transformRevision());
    }

    public boolean unlinked() {
        return outcome == ProjectorDestination.Outcome.CLOSE;
    }

    public boolean refused() {
        return refused;
    }

    public ViewPlate<BlockData> plate(boolean urgent) {
        if (plateResolved) {
            return plate;
        }
        plateResolved = true;
        ProjectorPlates.Target target = plateTarget;
        if (target == null || refused) {
            return null;
        }
        plate = ProjectorPlates.acquire(plates, views, portal, destination, air, target, destination.destView.getRevision(), urgent);
        refused = plate == null && plates.isRefused(target.key(), target.transformRevision());
        return plate;
    }


    public ViewPlate<BlockData> meshSection(BlockBox clip, int distance) {
        if (plateTarget == null || !ProjectorPlates.enabled(plates, portal, target)) {
            return null;
        }
        return ProjectorPlates.acquireSection(plates, views, portal, destination, air, plateTarget, clip, distance);
    }

    public long geometryRevision() {
        return geometryRevision;
    }

    public ViewWindow transformFrame() {
        ProjectorPlates.Target target = plateTarget;
        if (target == null || outcome != ProjectorDestination.Outcome.READY) {
            return null;
        }
        Vec3d localOrigin = new Vec3d(target.localOriginX(), target.localOriginY(), target.localOriginZ());
        return ViewWindow.of(target.mirrorMode(), QuarterTurn.of(target.quarterTurns()), localOrigin, target.localFrame(),
            new Vec3d(target.remoteOriginX(), target.remoteOriginY(), target.remoteOriginZ()), target.remoteFrame(), frontSide, target.depth());
    }

    public World destinationWorld() {
        return outcome == ProjectorDestination.Outcome.READY ? destination.destWorld : null;
    }

    public ProjectionWorldView destinationView() {
        return outcome == ProjectorDestination.Outcome.READY ? destination.destView : null;
    }

    public boolean regionSnapshots() {
        return views.usesRegionSnapshots();
    }

    public IPortal destinationAnchor() {
        return outcome == ProjectorDestination.Outcome.READY ? destination.destAnchor : null;
    }

    public float viewScale() {
        TransitPortalExtension transit = portal instanceof LocalPortal local ? local.extension(TransitPortalExtension.class) : null;
        ScaleRule rule = transit == null ? ScaleRule.OFF : transit.scaleRule();
        if (rule.mode() == ScaleRule.Mode.OFF || !(destinationAnchor() instanceof ILocalPortal anchor) || portal.getFrame() == null
            || anchor.getFrame() == null || portal.getArea() == null || anchor.getArea() == null) {
            return 1.0F;
        }
        return (float) (1.0D / rule.travelScale(SizeRatio.between(portal.getFrame(), portal.getArea(), anchor.getFrame(), anchor.getArea())));
    }

    public BlockClaim.LightingPolicy lightingPolicy() {
        return lightingPolicy(ProjectorPlates.fidelity(portal));
    }

    public boolean relaysWeather() {
        return FidelitySettings.weather && atmosphereMode(ProjectorPlates.fidelity(portal)).relaysWeather();
    }

    public ApertureDescriptor geometry(SessionPalette palette, long identitySalt) {
        if (outcome == ProjectorDestination.Outcome.CLOSE) {
            return null;
        }
        PortalProjector.RtpProjectionTarget rtpTarget = target;
        boolean mirror = rtpTarget == null && portal.isMirrorMode();
        int blackoutPolicy = blackoutState == null ? ApertureDescriptor.BLACKOUT_OFF : ApertureDescriptor.BLACKOUT_SHELL;
        int blackoutId = blackoutState == null ? ViewStreamLimits.PALETTE_AIR : palette.id(blackoutState);
        FidelityPortalExtension fidelity = ProjectorPlates.fidelity(portal);
        int kind = kind();
        return ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(portal.getStructure(), portal.getFrame(), frontSide,
            mirror, mirror ? destination.mirrorRotationQuarterTurns : 0, Settings.NEAR_PLANE_PADDING,
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS, Settings.FRUSTUM_CULLING_RATIO, portal.getNetworkViewDepth(),
            Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH,
            blackoutPolicy, blackoutId, ApertureDescriptor.MASK_AIR_PROJECT, lightingPolicy(fidelity), fidelityFlags(fidelity),
            kind, DoorApertureFrames.geometryPlaneOffset(kind, portal.getFrame()), 0,
            nativeMesh ? PassRevision.mix(identitySalt, meshTargetRevision()) : targetIdentity(rtpTarget, identitySalt),
            portal.getStructure().getApertureShape(), List.of())).orElse(null);
    }

    public void noteAcoustics(AcousticsBridge<Player> bridge, long nowMillis) {
        World destinationWorld = destinationWorld();
        Location center = portal.getCenter();
        if (bridge == null || portal.getId() == null || destination.destAnchor == null || destinationWorld == null || center == null) {
            return;
        }
        FidelityPortalExtension fidelity = ProjectorPlates.fidelity(portal);
        AcousticsProfile profile = fidelity == null ? FidelitySettings.acousticsProfileDefault : fidelity.effectiveAcousticsProfile();
        bridge.noteDestination(portal.getId(), destinationWorld.getUID(), destination.originX, destination.originY, destination.originZ,
            center.getX(), center.getY(), center.getZ(), profile, AcousticsBridge.Environment.valueOf(destinationWorld.getEnvironment().name()),
            destinationWorld.hasStorm(), nowMillis);
    }

    public static long effectGeometryRevision(ILocalPortal portal, Location eye) {
        PortalStructure structure = portal.getStructure();
        Frame frame = portal.getFrame();
        if (structure == null || frame == null || portal.getOrigin() == null || eye == null) {
            return 0L;
        }
        long hash = PassRevision.mix(REVISION_SEED, System.identityHashCode(structure));
        hash = PassRevision.mix(hash, structure.getRevision());
        hash = PassRevision.mix(hash, structure.getApertureShape().hashCode());
        hash = PassRevision.mix(hash, frame.getNormal().ordinal());
        hash = PassRevision.mix(hash, frame.getRight().ordinal());
        hash = PassRevision.mix(hash, frame.getUp().ordinal());
        hash = PassRevision.mix(hash, ProjectorPlates.frontSide(portal, eye) ? 1L : 2L);
        hash = PassRevision.mix(hash, portal.getNetworkViewDepth());
        return PassRevision.mix(hash, effectKind(portal));
    }

    public static ApertureDescriptor effectGeometry(ILocalPortal portal, Location eye) {
        PortalStructure structure = portal.getStructure();
        Frame frame = portal.getFrame();
        if (structure == null || frame == null || portal.getOrigin() == null || eye == null) {
            return null;
        }
        int kind = effectKind(portal);
        return ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(structure, frame, ProjectorPlates.frontSide(portal, eye), false, 0,
            Settings.NEAR_PLANE_PADDING, Settings.PROJECTION_APERTURE_PADDING_BLOCKS, Settings.FRUSTUM_CULLING_RATIO, portal.getNetworkViewDepth(), 0,
            ApertureDescriptor.BLACKOUT_OFF, ViewStreamLimits.PALETTE_AIR, ApertureDescriptor.MASK_AIR_PROJECT,
            BlockClaim.LightingPolicy.LOCAL, 0, kind, DoorApertureFrames.geometryPlaneOffset(kind, frame), 0, 0L, structure.getApertureShape(),
            List.of())).orElse(null);
    }

    private static int effectKind(ILocalPortal portal) {
        if (portal.getType() == PortalType.RTP) {
            return ApertureKind.RTP;
        }
        if (portal instanceof AbstractApertureFacade) {
            return ApertureKind.DOOR;
        }
        DimensionalPortalKind dimensional = portal.getDimensionalPortalKind();
        return dimensional != null && dimensional.isManagedPortal()
            ? ApertureKind.VANILLA_REPLACEMENT
            : ApertureKind.FRAME;
    }

    private long meshTargetRevision() {
        return plateTarget == null ? 0L : PassRevision.mix(plateTarget.transformRevision(),
            System.identityHashCode(plateTarget.key().destinationViewIdentity()));
    }

    private static long targetIdentity(PortalProjector.RtpProjectionTarget rtpTarget, long identitySalt) {
        if (rtpTarget == null) {
            return 0L;
        }
        long identity = PassRevision.mix(identitySalt, rtpTarget.plateIdentity());
        return identity == 0L ? 1L : identity;
    }

    private long revision(FidelityPortalExtension fidelity) {
        PortalStructure structure = portal.getStructure();
        Frame frame = portal.getFrame();
        Box area = structure.getArea();
        long hash = PassRevision.mix(REVISION_SEED, System.identityHashCode(structure));
        hash = PassRevision.mix(hash, structure.getRevision());
        hash = PassRevision.mix(hash, structure.getApertureShape().hashCode());
        if (area != null) {
            hash = PassRevision.mix(hash, Double.doubleToLongBits(area.getXa()));
            hash = PassRevision.mix(hash, Double.doubleToLongBits(area.getYa()));
            hash = PassRevision.mix(hash, Double.doubleToLongBits(area.getZa()));
            hash = PassRevision.mix(hash, Double.doubleToLongBits(area.getXb()));
            hash = PassRevision.mix(hash, Double.doubleToLongBits(area.getYb()));
            hash = PassRevision.mix(hash, Double.doubleToLongBits(area.getZb()));
        }
        hash = PassRevision.mix(hash, frame.getNormal().ordinal());
        hash = PassRevision.mix(hash, frame.getRight().ordinal());
        hash = PassRevision.mix(hash, frame.getUp().ordinal());
        hash = PassRevision.mix(hash, frontSide ? 1L : 2L);
        hash = PassRevision.mix(hash, target == null && portal.isMirrorMode() ? 3L + destination.mirrorRotationQuarterTurns : 0L);
        hash = PassRevision.mix(hash, portal.getNetworkViewDepth());
        hash = PassRevision.mix(hash, Double.doubleToLongBits(Settings.NEAR_PLANE_PADDING));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(Settings.FRUSTUM_CULLING_RATIO));
        hash = PassRevision.mix(hash, blackoutState == null ? 0L : blackoutState.hashCode());
        hash = PassRevision.mix(hash, lightingPolicy(fidelity).ordinal());
        hash = PassRevision.mix(hash, fidelityFlags(fidelity));
        hash = PassRevision.mix(hash, kind());
        return PassRevision.mix(hash, target == null ? 0L : target.plateIdentity());
    }

    private BlockClaim.LightingPolicy lightingPolicy(FidelityPortalExtension fidelity) {
        if (nativeMesh) {
            return BlockClaim.LightingPolicy.SOURCE;
        }
        if (blackoutState != null) {
            return BlockClaim.LightingPolicy.FULL_BRIGHT;
        }
        boolean sourceLighting = FidelitySettings.skyLight && atmosphereMode(fidelity).promotesSkyLight();
        return Settings.LIGHTING_FIDELITY || sourceLighting
            ? BlockClaim.LightingPolicy.SOURCE
            : BlockClaim.LightingPolicy.LOCAL;
    }

    private static int fidelityFlags(FidelityPortalExtension fidelity) {
        int flags = 0;
        if (Settings.ENTITY_SPOOFING) {
            flags |= ApertureDescriptor.FIDELITY_DISPLAY_ENTITIES;
        }
        if (Settings.LIGHTING_FIDELITY) {
            flags |= ApertureDescriptor.FIDELITY_LIGHTING;
        }
        if (FidelitySettings.weather && atmosphereMode(fidelity).relaysWeather()) {
            flags |= ApertureDescriptor.FIDELITY_WEATHER;
        }
        AcousticsProfile acoustics = fidelity == null ? FidelitySettings.acousticsProfileDefault : fidelity.effectiveAcousticsProfile();
        if (acoustics != AcousticsProfile.OFF) {
            flags |= ApertureDescriptor.FIDELITY_SOUNDS;
        }
        return flags;
    }

    private int kind() {
        if (portal instanceof AbstractApertureFacade) {
            return ApertureKind.DOOR;
        }
        if (target != null) {
            return ApertureKind.RTP;
        }
        DimensionalPortalKind dimensional = portal.getDimensionalPortalKind();
        return dimensional != null && dimensional.isManagedPortal()
            ? ApertureKind.VANILLA_REPLACEMENT
            : ApertureKind.FRAME;
    }

    private static AtmosphereMode atmosphereMode(FidelityPortalExtension fidelity) {
        return fidelity == null ? FidelitySettings.atmosphereModeDefault : fidelity.effectiveAtmosphereMode();
    }
}
