package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.server.level.ServerPlayer;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.aperture.EndpointDirectory;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.math.Box;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinecraftProjectorPortalAccess implements EndpointDirectory<ServerLevel, MinecraftPortal> {
    private final WormholesModRuntime runtime;
    private final MinecraftEndReturnPreview endReturns;
    private MinecraftDoorProjectionViews doors;
    private ServerPlayer observer;
    private final Map<UUID, ViewBounds> views = new HashMap<>();

    public MinecraftProjectorPortalAccess(WormholesModRuntime runtime) {
        this.runtime = runtime;
        this.endReturns = new MinecraftEndReturnPreview(runtime);
    }

    public void observer(ServerPlayer observer) {
        this.observer = observer;
    }

    public void setDoorViews(MinecraftDoorProjectionViews doors) {
        this.doors = doors;
    }

    public long routeIdentity(MinecraftPortal portal) {
        if (portal.getDimensionalKind() == DimensionalPortalKind.END_EXIT) {
            MinecraftPortal destination = endReturns.destination(observer, portal);
            return destination == null ? 0L : destination.getId().getMostSignificantBits() ^ destination.getId().getLeastSignificantBits();
        }
        return doors == null ? 0L : doors.routeIdentity(portal);
    }

    public boolean current(MinecraftPortal portal) {
        return runtime.portals().get(portal.getId()) == portal || doors != null && doors.current(portal);
    }

    public RecursiveEndpoints<ServerLevel, MinecraftPortal> createRecursiveIndex() {
        return new RecursiveEndpoints<>(this, () -> {
            ProjectionConfig config = runtime.configuration().settings().getProjection();
            return new RecursiveEndpoints.Options(config.aperturePaddingBlocks, config.depthBlocks);
        });
    }

    @Override
    public List<MinecraftPortal> endpoints() {
        List<MinecraftPortal> portals = runtime.portals().snapshot();
        if (doors != null) {
            portals = new ArrayList<>(portals);
            portals.addAll(doors.sources());
        }
        views.keySet().removeIf(id -> runtime.portals().get(id) == null && (doors == null || !doors.contains(id)));
        return portals;
    }

    @Override
    public ServerLevel world(MinecraftPortal portal) {
        return runtime.portals().resolveLevel(portal);
    }

    @Override
    public CellAperture aperture(MinecraftPortal portal) {
        return portal.getGeometry();
    }

    @Override
    public Box view(MinecraftPortal portal) {
        double configuredRange = portal.setting("activationRange") instanceof Number value ? value.doubleValue() : 0.0D;
        double range = configuredRange > 0.0D ? configuredRange : runtime.configuration().settings().getProjection().range;
        long revision = portal.getGeometry().getRevision();
        ViewBounds cached = views.get(portal.getId());
        if (cached == null || cached.revision() != revision || cached.range() != range) {
            cached = new ViewBounds(revision, range, portal.getGeometry().captureZone(range));
            views.put(portal.getId(), cached);
        }
        return cached.bounds();
    }

    @Override
    public boolean eligible(MinecraftPortal portal) {
        if (!portal.isOpen() || portal.getProjectionMode() != ProjectionMode.ON) {
            return false;
        }
        String skin = portal.setting("surfaceSkin") instanceof String value ? PortalSurfaceSkins.normalizeSkin(value) : "";
        return skin.isEmpty() || PortalSurfaceSkins.isTransparentSkin(skin, MinecraftProjectorPortalAccess::isNonOccludingBlock);
    }

    @Override
    public boolean mirror(MinecraftPortal portal) {
        return portal.isMirrorMode();
    }

    @Override
    public QuarterTurn mirrorTurns(MinecraftPortal portal) {
        int degrees = portal.setting("mirrorRotationDegrees") instanceof Number value ? value.intValue() : 0;
        return QuarterTurn.fromDegrees(degrees).coherentFor(portal.getFrame());
    }

    @Override
    public MinecraftPortal destination(MinecraftPortal portal) {
        if (portal.getDimensionalKind() == DimensionalPortalKind.END_EXIT) {
            return endReturns.destination(observer, portal);
        }
        if (portal.getType() == PortalType.RTP) {
            return observer == null ? null : runtime.rtp().knownDestination(observer, portal);
        }
        return linkedDestination(portal);
    }

    public MinecraftPortal projectionDestination(MinecraftPortal portal) {
        if (portal.getDimensionalKind() == DimensionalPortalKind.END_EXIT) {
            return endReturns.destination(observer, portal);
        }
        if (portal.getType() == PortalType.RTP) {
            return observer == null ? null : runtime.rtp().projectionDestination(observer, portal);
        }
        return linkedDestination(portal);
    }

    public RemotePortal remoteDestination(MinecraftPortal portal) {
        return "UNIVERSAL".equals(portal.getTunnelType())
            ? runtime.network().remotePortals().get(portal.getDestinationServer(), portal.getDestinationId()) : null;
    }

    public boolean hasDestination(MinecraftPortal portal) {
        return projectionDestination(portal) != null || remoteDestination(portal) != null;
    }

    private MinecraftPortal linkedDestination(MinecraftPortal portal) {
        if (doors != null && doors.current(portal)) {
            return doors.destination(portal);
        }
        return portal.getTunnelType().equals("LOCAL") || portal.getTunnelType().equals("DIMENSIONAL")
            ? runtime.portals().get(portal.getDestinationId()) : null;
    }

    private static boolean isNonOccludingBlock(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.BLOCK.getOptional(key)
            .map(block -> !MinecraftProjectorBlocks.occluding(block)).orElse(false);
    }

    private record ViewBounds(long revision, double range, Box bounds) {
    }
}
