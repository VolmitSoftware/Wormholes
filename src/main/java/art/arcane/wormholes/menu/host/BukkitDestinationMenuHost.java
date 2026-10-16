package art.arcane.wormholes.menu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Location;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.destination.DestinationMenuHost;
import art.arcane.wormholes.menu.destination.DestinationMenuView;
import art.arcane.wormholes.menu.destination.PairOutcome;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.nexus.ReciprocalLinks;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;

public final class BukkitDestinationMenuHost implements DestinationMenuHost {
    private final PortalManager portals;
    private final Supplier<ReciprocalLinks> links;

    public BukkitDestinationMenuHost(PortalManager portals, Supplier<ReciprocalLinks> links) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.links = Objects.requireNonNull(links, "links");
    }

    @Override
    public List<DestinationMenuView> candidates(UUID portalId) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        if (found.isEmpty()) {
            return List.of();
        }
        LocalPortal portal = found.get();
        List<ILocalPortal> registered = portals.getLocalPortals();
        List<DestinationMenuView> targets = new ArrayList<>(registered.size());
        for (ILocalPortal candidate : registered) {
            if (linkable(portal, candidate)) {
                targets.add(view(candidate, distanceSquared(portal, candidate), linkedTo(portal, candidate)));
            }
        }
        RemotePortalRegistry remotes = Wormholes.remotePortalRegistry;
        if (portal.isGateway() && remotes != null) {
            for (RemotePortal candidate : remotes.all()) {
                if (candidate.getType() == PortalType.GATEWAY) {
                    targets.add(remoteView(candidate, linkedTo(portal, candidate)));
                }
            }
        }
        return targets;
    }

    @Override
    public List<DestinationMenuView> portals() {
        List<ILocalPortal> registered = portals.getLocalPortals();
        List<DestinationMenuView> listed = new ArrayList<>(registered.size());
        for (ILocalPortal candidate : registered) {
            if (placed(candidate)) {
                listed.add(view(candidate, Double.MAX_VALUE, false));
            }
        }
        return listed;
    }

    @Override
    public Optional<DestinationMenuView> localDestination(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).flatMap(portal -> {
            ITunnel tunnel = portal.getTunnel();
            IPortal destination = tunnel == null ? null : tunnel.getDestination();
            if (!(destination instanceof LocalPortal target) || !placed(target)) {
                return Optional.empty();
            }
            return Optional.of(view(target, distanceSquared(portal, target), true));
        });
    }

    @Override
    public void link(MenuViewer viewer, UUID portalId, DestinationMenuView target) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        IPortal destination = target.remote() ? remote(target) : portals.getLocalPortal(target.id());
        if (found.isPresent() && destination != null) {
            found.get().setDestination(destination);
        }
    }

    @Override
    public void unlink(MenuViewer viewer, UUID portalId) {
        BukkitLocalPortals.local(portals, portalId).ifPresent(LocalPortal::unlink);
    }

    @Override
    public PairOutcome pair(MenuViewer viewer, UUID portalId, UUID targetId) {
        ReciprocalLinks active = links.get();
        Optional<LocalPortal> portal = BukkitLocalPortals.local(portals, portalId);
        Optional<LocalPortal> target = BukkitLocalPortals.local(portals, targetId);
        if (active == null || portal.isEmpty() || target.isEmpty()) {
            return PairOutcome.UNAVAILABLE;
        }
        return active.pair(portal.get(), target.get()) == ReciprocalLinks.PairResult.PAIRED ? PairOutcome.PAIRED : PairOutcome.DENIED;
    }

    private static RemotePortal remote(DestinationMenuView target) {
        RemotePortalRegistry remotes = Wormholes.remotePortalRegistry;
        return remotes == null ? null : remotes.get(target.server(), target.id());
    }

    private static boolean linkable(LocalPortal portal, ILocalPortal candidate) {
        return !candidate.getId().equals(portal.getId())
            && candidate.getType() != PortalType.RTP
            && candidate.isGateway() == portal.isGateway()
            && candidate.getDimensionalPortalKind().isGenericDestination()
            && placed(candidate);
    }

    private static boolean placed(ILocalPortal portal) {
        PortalStructure structure = portal.getStructure();
        return structure != null && structure.getWorld() != null && structure.getCenter() != null;
    }

    private static boolean linkedTo(LocalPortal portal, ILocalPortal target) {
        ITunnel tunnel = portal.getTunnel();
        IPortal destination = tunnel == null ? null : tunnel.getDestination();
        return destination != null && destination.getId().equals(target.getId());
    }

    private static boolean linkedTo(LocalPortal portal, RemotePortal target) {
        return portal.getTunnel() instanceof UniversalTunnel universal
            && target.getServer().getName().equals(universal.getServerName())
            && target.getId().equals(universal.getDestinationId());
    }

    private static double distanceSquared(LocalPortal portal, ILocalPortal target) {
        Location source = portal.getStructure().getCenter();
        Location destination = target.getStructure().getCenter();
        if (source == null || destination == null || source.getWorld() == null || !source.getWorld().equals(destination.getWorld())) {
            return Double.MAX_VALUE;
        }
        return source.distanceSquared(destination);
    }

    private static DestinationMenuView view(ILocalPortal target, double distanceSquared, boolean linked) {
        Location center = target.getStructure().getCenter();
        return new DestinationMenuView(target.getId(), target.getName(), target.getStructure().getWorld().getName(), center.getBlockX(),
            center.getBlockY(), center.getBlockZ(), target.getDirection(), distanceSquared, linked, null, true);
    }

    private static DestinationMenuView remoteView(RemotePortal target, boolean linked) {
        return new DestinationMenuView(target.getId(), target.getName(), Objects.toString(target.getServer().getWorld(), ""),
            target.getOrigin().blockX(), target.getOrigin().blockY(), target.getOrigin().blockZ(), target.getDirection(), Double.MAX_VALUE,
            linked, target.getServer().getName(), target.isOpen());
    }
}
