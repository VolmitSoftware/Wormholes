package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.destination.DestinationMenuHost;
import art.arcane.wormholes.menu.destination.DestinationMenuView;
import art.arcane.wormholes.menu.destination.PairOutcome;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MinecraftDestinationMenuHost implements DestinationMenuHost {
    private static final String REMOTE_TUNNEL = "UNIVERSAL";

    private final WormholesModRuntime runtime;

    public MinecraftDestinationMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public List<DestinationMenuView> candidates(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return List.of();
        }
        List<MinecraftPortal> registered = runtime.portals().snapshot();
        List<DestinationMenuView> targets = new ArrayList<>(registered.size());
        for (MinecraftPortal candidate : registered) {
            if (linkable(portal, candidate)) {
                targets.add(view(candidate, distanceSquared(portal, candidate), linkedTo(portal, candidate)));
            }
        }
        if (portal.getType() == PortalType.GATEWAY) {
            for (RemotePortal candidate : runtime.network().remotePortals().all()) {
                if (candidate.getType() == PortalType.GATEWAY) {
                    targets.add(remoteView(candidate, linkedTo(portal, candidate)));
                }
            }
        }
        return targets;
    }

    @Override
    public List<DestinationMenuView> portals() {
        List<MinecraftPortal> registered = runtime.portals().snapshot();
        List<DestinationMenuView> listed = new ArrayList<>(registered.size());
        for (MinecraftPortal candidate : registered) {
            listed.add(view(candidate, Double.MAX_VALUE, false));
        }
        return listed;
    }

    @Override
    public Optional<DestinationMenuView> localDestination(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null || REMOTE_TUNNEL.equals(portal.getTunnelType()) || portal.getDestinationId() == null) {
            return Optional.empty();
        }
        MinecraftPortal target = runtime.portals().get(portal.getDestinationId());
        return target == null ? Optional.empty() : Optional.of(view(target, distanceSquared(portal, target), true));
    }

    @Override
    public void link(MenuViewer viewer, UUID portalId, DestinationMenuView target) {
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        boolean linked = target.remote()
            ? runtime.portals().update(player, portalId, source -> source.linkRemote(target.server(), target.id()))
            : runtime.portals().link(player, portalId, target.id());
        if (linked) {
            invalidate(portalId);
        }
    }

    @Override
    public void unlink(MenuViewer viewer, UUID portalId) {
        if (runtime.portals().link(MinecraftMenuViewer.player(viewer), portalId, null)) {
            invalidate(portalId);
        }
    }

    @Override
    public PairOutcome pair(MenuViewer viewer, UUID portalId, UUID targetId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        MinecraftPortal target = runtime.portals().get(targetId);
        if (portal == null || target == null) {
            return PairOutcome.UNAVAILABLE;
        }
        try {
            runtime.nexus().pair(MinecraftMenuViewer.player(viewer), portal, target, true);
        } catch (IllegalArgumentException denied) {
            return PairOutcome.DENIED;
        }
        invalidate(portalId);
        invalidate(targetId);
        return PairOutcome.PAIRED;
    }

    private boolean linkable(MinecraftPortal portal, MinecraftPortal candidate) {
        return !candidate.getId().equals(portal.getId())
            && candidate.getType() != PortalType.RTP
            && (candidate.getType() == PortalType.GATEWAY) == (portal.getType() == PortalType.GATEWAY)
            && candidate.getDimensionalKind().isGenericDestination()
            && runtime.portals().resolveLevel(candidate) != null;
    }

    private void invalidate(UUID portalId) {
        runtime.menus().invalidate(new MenuScope.Portal(portalId));
    }

    private static boolean linkedTo(MinecraftPortal portal, MinecraftPortal target) {
        return !REMOTE_TUNNEL.equals(portal.getTunnelType()) && target.getId().equals(portal.getDestinationId());
    }

    private static boolean linkedTo(MinecraftPortal portal, RemotePortal target) {
        return REMOTE_TUNNEL.equals(portal.getTunnelType())
            && target.getServer().getName().equals(portal.getDestinationServer())
            && target.getId().equals(portal.getDestinationId());
    }

    private static double distanceSquared(MinecraftPortal portal, MinecraftPortal target) {
        if (!portal.getWorldKey().equals(target.getWorldKey())) {
            return Double.MAX_VALUE;
        }
        Vec3d source = portal.getGeometry().getApertureCenter();
        Vec3d destination = target.getGeometry().getApertureCenter();
        double x = source.x() - destination.x();
        double y = source.y() - destination.y();
        double z = source.z() - destination.z();
        return x * x + y * y + z * z;
    }

    private static DestinationMenuView view(MinecraftPortal target, double distanceSquared, boolean linked) {
        Vec3d center = target.getGeometry().getApertureCenter();
        return new DestinationMenuView(target.getId(), target.getName(), target.getWorldKey(), center.blockX(), center.blockY(), center.blockZ(),
            target.getDirection(), distanceSquared, linked, null, true);
    }

    private static DestinationMenuView remoteView(RemotePortal target, boolean linked) {
        Vec3d origin = target.getOrigin();
        return new DestinationMenuView(target.getId(), target.getName(), Objects.toString(target.getServer().getWorld(), ""), origin.blockX(),
            origin.blockY(), origin.blockZ(), target.getDirection(), Double.MAX_VALUE, linked, target.getServer().getName(), target.isOpen());
    }
}
