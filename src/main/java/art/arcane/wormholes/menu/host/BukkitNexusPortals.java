package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.nexus.DialAdmission;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

final class BukkitNexusPortals {
    private final PortalManager portals;
    private final Supplier<NexusSubsystem> nexus;

    BukkitNexusPortals(PortalManager portals, Supplier<NexusSubsystem> nexus) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.nexus = Objects.requireNonNull(nexus, "nexus");
    }

    Optional<LocalPortal> local(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId);
    }

    Optional<NexusPortalExtension> state(LocalPortal portal) {
        return Optional.ofNullable(portal.extension(NexusPortalExtension.class));
    }

    Optional<NexusPortalView> view(UUID portalId) {
        return local(portalId).flatMap(portal -> state(portal).map(state -> new NexusPortalView(portal.getId(), portal.getName(),
            portal.getType(), portal.isMirrorMode(), portal.getRouter(true), state.networkId(), state.address(), state.reciprocal(),
            state.policy(), state.frameIo(), state.dial())));
    }

    Optional<PortalNetwork> network(UUID networkId) {
        NetworkRegistry registry = registry();
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.byId(networkId));
    }

    boolean dialAdmission(MenuViewer viewer, UUID portalId) {
        Optional<LocalPortal> found = local(portalId);
        if (found.isEmpty()) {
            return false;
        }
        LocalPortal portal = found.get();
        Optional<NexusPortalExtension> state = state(portal);
        return state.isPresent()
            && DialAdmission.allows(portal, network(state.get().networkId()).orElse(null), BukkitMenuViewer.player(viewer));
    }

    String worldName(UUID portalId) {
        ILocalPortal portal = portals.getLocalPortal(portalId);
        if (portal == null || portal.getStructure() == null || portal.getStructure().getWorld() == null) {
            return "";
        }
        return portal.getStructure().getWorld().getName();
    }

    NetworkRegistry registry() {
        NexusSubsystem active = nexus.get();
        return active == null ? null : active.registry();
    }

    NexusSubsystem subsystem() {
        return nexus.get();
    }
}
