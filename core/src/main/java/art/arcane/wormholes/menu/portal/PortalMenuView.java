package art.arcane.wormholes.menu.portal;

import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;

public record PortalMenuView(
    UUID id,
    String name,
    UUID owner,
    PortalType type,
    boolean mirror,
    QuarterTurn mirrorRotation,
    Frame frame,
    DimensionalPortalKind dimensionalKind,
    boolean twoWayEnd,
    ProjectionMode projection,
    Destination destination,
    String routerTitle,
    PortalPermissionMode permissionMode,
    String permissionNode,
    PortalTravelMode travelMode,
    NetworkViewQuality quality,
    int networkViewDepth,
    int networkViewHeartbeatTicks,
    int networkViewEntityIntervalTicks,
    int networkViewUnsubscribeGraceSeconds,
    boolean settingsSync,
    boolean blackout,
    BlackoutColor blackoutColor,
    int activationRange,
    ProjectionRenderMode renderMode,
    AmbientParticleStyle ambientStyle,
    int ambientColor,
    ShapeDescriptor apertureShape,
    int openCellCount,
    String surfaceSkin,
    String surfaceSkinItemId,
    String fallbackBlock,
    boolean publicLookLabel,
    Cost cost,
    int accessibleCount,
    Rtp rtp
) {
    public PortalMenuView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mirrorRotation, "mirrorRotation");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(dimensionalKind, "dimensionalKind");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(routerTitle, "routerTitle");
        Objects.requireNonNull(permissionMode, "permissionMode");
        Objects.requireNonNull(permissionNode, "permissionNode");
        Objects.requireNonNull(travelMode, "travelMode");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(blackoutColor, "blackoutColor");
        Objects.requireNonNull(renderMode, "renderMode");
        Objects.requireNonNull(ambientStyle, "ambientStyle");
        Objects.requireNonNull(apertureShape, "apertureShape");
        Objects.requireNonNull(surfaceSkin, "surfaceSkin");
        Objects.requireNonNull(fallbackBlock, "fallbackBlock");
        Objects.requireNonNull(cost, "cost");
        if (accessibleCount < 0) {
            throw new IllegalArgumentException("accessible count must not be negative");
        }
        if (rtp != null && type != PortalType.RTP) {
            throw new IllegalArgumentException("rtp settings belong only to RTP portals, not " + type);
        }
    }

    public static Builder builder(UUID id, String name) {
        return new Builder(id, name);
    }

    public Builder toBuilder() {
        return new Builder(id, name)
            .owner(owner)
            .type(type)
            .mirror(mirror)
            .mirrorRotation(mirrorRotation)
            .frame(frame)
            .dimensionalKind(dimensionalKind)
            .twoWayEnd(twoWayEnd)
            .projection(projection)
            .destination(destination)
            .routerTitle(routerTitle)
            .permissionMode(permissionMode)
            .permissionNode(permissionNode)
            .travelMode(travelMode)
            .quality(quality)
            .networkViewDepth(networkViewDepth)
            .networkViewHeartbeatTicks(networkViewHeartbeatTicks)
            .networkViewEntityIntervalTicks(networkViewEntityIntervalTicks)
            .networkViewUnsubscribeGraceSeconds(networkViewUnsubscribeGraceSeconds)
            .settingsSync(settingsSync)
            .blackout(blackout)
            .blackoutColor(blackoutColor)
            .activationRange(activationRange)
            .renderMode(renderMode)
            .ambientStyle(ambientStyle)
            .ambientColor(ambientColor)
            .apertureShape(apertureShape)
            .openCellCount(openCellCount)
            .surfaceSkin(surfaceSkin)
            .surfaceSkinItemId(surfaceSkinItemId)
            .fallbackBlock(fallbackBlock)
            .publicLookLabel(publicLookLabel)
            .cost(cost)
            .accessibleCount(accessibleCount)
            .rtp(rtp);
    }

    public boolean managed() {
        return dimensionalKind.isManagedPortal();
    }

    public boolean receiverOnly() {
        return dimensionalKind.isReceiverOnly(twoWayEnd);
    }

    public PortalTravelMode managedTravel() {
        return dimensionalKind.managedTravel(twoWayEnd);
    }

    public boolean gateway() {
        return type == PortalType.GATEWAY;
    }

    public enum CostMode {
        FREE,
        VANILLA,
        VAULT
    }

    public record Destination(String name, boolean remote, String server) {
        public Destination {
            Objects.requireNonNull(name, "name");
            if (remote && server == null) {
                throw new IllegalArgumentException("a remote destination needs a server");
            }
        }
    }

    public record Cost(CostMode mode, String itemId, String itemLabel, int quantity, String vaultAmount, String templateRef) {
        public static final Cost FREE = new Cost(CostMode.FREE, null, null, 0, null, null);

        public Cost {
            Objects.requireNonNull(mode, "mode");
            if (quantity < 0) {
                throw new IllegalArgumentException("cost quantity must not be negative");
            }
        }
    }

    public record Rtp(RtpAllocationMode allocation, RtpRotationMode rotation, long cycleMillis) {
        public Rtp {
            Objects.requireNonNull(allocation, "allocation");
            Objects.requireNonNull(rotation, "rotation");
            if (cycleMillis < 0L) {
                throw new IllegalArgumentException("cycle must not be negative");
            }
        }
    }

    public static final class Builder {
        private final UUID id;
        private String name;
        private UUID owner;
        private PortalType type = PortalType.PORTAL;
        private boolean mirror;
        private QuarterTurn mirrorRotation = QuarterTurn.DEGREES_0;
        private Frame frame = Frame.canonical(Face.S);
        private DimensionalPortalKind dimensionalKind = DimensionalPortalKind.NONE;
        private boolean twoWayEnd = true;
        private ProjectionMode projection = ProjectionMode.ON;
        private Destination destination;
        private String routerTitle = "";
        private PortalPermissionMode permissionMode = PortalPermissionMode.BLACKLIST;
        private String permissionNode = "";
        private PortalTravelMode travelMode = PortalTravelMode.BOTH;
        private NetworkViewQuality quality = NetworkViewQuality.STANDARD;
        private int networkViewDepth;
        private int networkViewHeartbeatTicks;
        private int networkViewEntityIntervalTicks;
        private int networkViewUnsubscribeGraceSeconds;
        private boolean settingsSync;
        private boolean blackout;
        private BlackoutColor blackoutColor = BlackoutColor.BLACK;
        private int activationRange;
        private ProjectionRenderMode renderMode = ProjectionRenderMode.PANOPTIC;
        private AmbientParticleStyle ambientStyle = AmbientParticleStyle.SPARKS;
        private int ambientColor;
        private ShapeDescriptor apertureShape = ShapeDescriptor.FULL;
        private int openCellCount;
        private String surfaceSkin = "";
        private String surfaceSkinItemId;
        private String fallbackBlock = "minecraft:air";
        private boolean publicLookLabel;
        private Cost cost = Cost.FREE;
        private int accessibleCount;
        private Rtp rtp;

        private Builder(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder owner(UUID owner) {
            this.owner = owner;
            return this;
        }

        public Builder type(PortalType type) {
            this.type = type;
            return this;
        }

        public Builder mirror(boolean mirror) {
            this.mirror = mirror;
            return this;
        }

        public Builder mirrorRotation(QuarterTurn mirrorRotation) {
            this.mirrorRotation = mirrorRotation;
            return this;
        }

        public Builder frame(Frame frame) {
            this.frame = frame;
            return this;
        }

        public Builder dimensionalKind(DimensionalPortalKind dimensionalKind) {
            this.dimensionalKind = dimensionalKind;
            return this;
        }

        public Builder twoWayEnd(boolean twoWayEnd) {
            this.twoWayEnd = twoWayEnd;
            return this;
        }

        public Builder projection(ProjectionMode projection) {
            this.projection = projection;
            return this;
        }

        public Builder destination(Destination destination) {
            this.destination = destination;
            return this;
        }

        public Builder routerTitle(String routerTitle) {
            this.routerTitle = routerTitle;
            return this;
        }

        public Builder permissionMode(PortalPermissionMode permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        public Builder permissionNode(String permissionNode) {
            this.permissionNode = permissionNode;
            return this;
        }

        public Builder travelMode(PortalTravelMode travelMode) {
            this.travelMode = travelMode;
            return this;
        }

        public Builder quality(NetworkViewQuality quality) {
            this.quality = quality;
            return this;
        }

        public Builder networkViewDepth(int networkViewDepth) {
            this.networkViewDepth = networkViewDepth;
            return this;
        }

        public Builder networkViewHeartbeatTicks(int networkViewHeartbeatTicks) {
            this.networkViewHeartbeatTicks = networkViewHeartbeatTicks;
            return this;
        }

        public Builder networkViewEntityIntervalTicks(int networkViewEntityIntervalTicks) {
            this.networkViewEntityIntervalTicks = networkViewEntityIntervalTicks;
            return this;
        }

        public Builder networkViewUnsubscribeGraceSeconds(int networkViewUnsubscribeGraceSeconds) {
            this.networkViewUnsubscribeGraceSeconds = networkViewUnsubscribeGraceSeconds;
            return this;
        }

        public Builder settingsSync(boolean settingsSync) {
            this.settingsSync = settingsSync;
            return this;
        }

        public Builder blackout(boolean blackout) {
            this.blackout = blackout;
            return this;
        }

        public Builder blackoutColor(BlackoutColor blackoutColor) {
            this.blackoutColor = blackoutColor;
            return this;
        }

        public Builder activationRange(int activationRange) {
            this.activationRange = activationRange;
            return this;
        }

        public Builder renderMode(ProjectionRenderMode renderMode) {
            this.renderMode = renderMode;
            return this;
        }

        public Builder ambientStyle(AmbientParticleStyle ambientStyle) {
            this.ambientStyle = ambientStyle;
            return this;
        }

        public Builder ambientColor(int ambientColor) {
            this.ambientColor = ambientColor;
            return this;
        }

        public Builder apertureShape(ShapeDescriptor apertureShape) {
            this.apertureShape = apertureShape;
            return this;
        }

        public Builder openCellCount(int openCellCount) {
            this.openCellCount = openCellCount;
            return this;
        }

        public Builder surfaceSkin(String surfaceSkin) {
            this.surfaceSkin = surfaceSkin;
            return this;
        }

        public Builder surfaceSkinItemId(String surfaceSkinItemId) {
            this.surfaceSkinItemId = surfaceSkinItemId;
            return this;
        }

        public Builder fallbackBlock(String fallbackBlock) {
            this.fallbackBlock = fallbackBlock;
            return this;
        }

        public Builder publicLookLabel(boolean publicLookLabel) {
            this.publicLookLabel = publicLookLabel;
            return this;
        }

        public Builder cost(Cost cost) {
            this.cost = cost;
            return this;
        }

        public Builder accessibleCount(int accessibleCount) {
            this.accessibleCount = accessibleCount;
            return this;
        }

        public Builder rtp(Rtp rtp) {
            this.rtp = rtp;
            return this;
        }

        public PortalMenuView build() {
            return new PortalMenuView(id, name, owner, type, mirror, mirrorRotation, frame, dimensionalKind, twoWayEnd, projection, destination,
                routerTitle, permissionMode, permissionNode, travelMode, quality, networkViewDepth, networkViewHeartbeatTicks,
                networkViewEntityIntervalTicks, networkViewUnsubscribeGraceSeconds, settingsSync, blackout, blackoutColor,
                activationRange, renderMode, ambientStyle, ambientColor, apertureShape, openCellCount, surfaceSkin,
                surfaceSkinItemId, fallbackBlock, publicLookLabel, cost, accessibleCount, rtp);
        }
    }
}
