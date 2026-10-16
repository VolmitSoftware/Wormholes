package art.arcane.wormholes.menu.host;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.DialMenuHost;
import art.arcane.wormholes.menu.nexus.DialOutcome;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.nexus.Dialer;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.LocalPortal;

public final class BukkitDialMenuHost implements DialMenuHost {
    private final BukkitNexusPortals nexus;

    public BukkitDialMenuHost(PortalManager portals, Supplier<NexusSubsystem> nexus) {
        this.nexus = new BukkitNexusPortals(portals, nexus);
    }

    @Override
    public Optional<NexusPortalView> portal(UUID portalId) {
        return nexus.view(portalId);
    }

    @Override
    public Optional<PortalNetwork> network(UUID networkId) {
        return nexus.network(networkId);
    }

    @Override
    public boolean dialAdmission(MenuViewer viewer, UUID portalId) {
        return nexus.dialAdmission(viewer, portalId);
    }

    @Override
    public String worldName(UUID portalId) {
        return nexus.worldName(portalId);
    }

    @Override
    public DialOutcome dial(MenuViewer viewer, UUID portalId, String address) {
        Optional<LocalPortal> portal = nexus.local(portalId);
        NexusSubsystem subsystem = nexus.subsystem();
        Dialer dialer = subsystem == null ? null : subsystem.dialer();
        if (portal.isEmpty() || dialer == null) {
            return DialOutcome.NOT_ON_NETWORK;
        }
        return switch (dialer.dial(portal.get(), address, viewer.id(), System.currentTimeMillis())) {
            case DIALED -> DialOutcome.DIALED;
            case DEBOUNCED -> DialOutcome.DEBOUNCED;
            case UNKNOWN_ADDRESS -> DialOutcome.UNKNOWN_ADDRESS;
            case NOT_ON_NETWORK -> DialOutcome.NOT_ON_NETWORK;
            case MANAGED_PORTAL -> DialOutcome.MANAGED_PORTAL;
        };
    }
}
