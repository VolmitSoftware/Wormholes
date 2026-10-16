package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.modded.MinecraftNexus;
import art.arcane.wormholes.modded.MinecraftNexusValues;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class MinecraftNexusPortals {
    private final WormholesModRuntime runtime;

    MinecraftNexusPortals(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    Optional<MinecraftPortal> portal(UUID portalId) {
        return Optional.ofNullable(runtime.portals().get(portalId));
    }

    Optional<NexusPortalView> view(UUID portalId) {
        return portal(portalId).map(portal -> new NexusPortalView(portal.getId(), portal.getName(), portal.getType(), portal.isMirrorMode(),
            MinecraftPortalText.router(runtime, portal, true), MinecraftNexusValues.networkId(portal), MinecraftNexusValues.address(portal),
            MinecraftNexusValues.reciprocal(portal), MinecraftNexusValues.policy(portal), MinecraftNexusValues.frameIo(portal),
            MinecraftNexusValues.dial(portal)));
    }

    Optional<PortalNetwork> network(UUID networkId) {
        NetworkRegistry registry = registry();
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.byId(networkId));
    }

    boolean dialAdmission(MenuViewer viewer, UUID portalId) {
        Optional<MinecraftPortal> found = portal(portalId);
        if (found.isEmpty()) {
            return false;
        }
        MinecraftPortal portal = found.get();
        Optional<PortalNetwork> network = network(MinecraftNexusValues.networkId(portal));
        if (network.isEmpty()) {
            return false;
        }
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        boolean administrator = runtime.access().administrator(player);
        if (!administrator && !portal.allows(player.getUUID(), false, node -> runtime.access().permission(player, node),
            runtime.configuration().settings().getAccess().legacyNameNodeEnabled)) {
            return false;
        }
        return network.get().visibleTo(player.getUUID(), administrator);
    }

    NetworkRegistry registry() {
        MinecraftNexus nexus = runtime.nexus();
        return nexus == null ? null : nexus.networks();
    }
}
