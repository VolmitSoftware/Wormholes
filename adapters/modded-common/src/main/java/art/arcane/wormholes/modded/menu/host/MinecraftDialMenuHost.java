package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.DialMenuHost;
import art.arcane.wormholes.menu.nexus.DialOutcome;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.modded.MinecraftNexusValues;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.PortalNetwork;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MinecraftDialMenuHost implements DialMenuHost {
    private final WormholesModRuntime runtime;
    private final MinecraftNexusPortals nexus;

    public MinecraftDialMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.nexus = new MinecraftNexusPortals(runtime);
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
        return nexus.portal(portalId).map(MinecraftPortal::getWorldKey).orElse("");
    }

    @Override
    public DialOutcome dial(MenuViewer viewer, UUID portalId, String address) {
        Optional<MinecraftPortal> found = nexus.portal(portalId);
        if (found.isEmpty()) {
            return DialOutcome.NOT_ON_NETWORK;
        }
        MinecraftPortal portal = found.get();
        UUID networkId = MinecraftNexusValues.networkId(portal);
        if (networkId == null) {
            return DialOutcome.NOT_ON_NETWORK;
        }
        if (portal.isManaged() || portal.isMirrorMode()) {
            return DialOutcome.MANAGED_PORTAL;
        }
        Optional<PortalNetwork> network = nexus.network(networkId);
        if (network.isEmpty()) {
            return DialOutcome.NOT_ON_NETWORK;
        }
        NetworkMember member = network.get().memberAt(address);
        if (member == null || member.portalId().equals(portal.getId())) {
            return DialOutcome.UNKNOWN_ADDRESS;
        }
        long debounce = runtime.configuration().settings().getNexus().dialDebounceMillis;
        if (MinecraftNexusValues.dial(portal).withinDebounce(System.currentTimeMillis(), debounce)) {
            return DialOutcome.DEBOUNCED;
        }
        return runtime.nexus().dial(MinecraftMenuViewer.player(viewer), portal, member.address()) ? DialOutcome.DIALED : DialOutcome.MANAGED_PORTAL;
    }
}
