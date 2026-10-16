package art.arcane.wormholes.modded.menu.host;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.NetworkEdit;
import art.arcane.wormholes.menu.nexus.NetworkMenuHost;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftNetworkMenuHost implements NetworkMenuHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final MinecraftNexusPortals nexus;

    public MinecraftNetworkMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.nexus = new MinecraftNexusPortals(runtime);
    }

    @Override
    public Optional<NexusPortalView> portal(UUID portalId) {
        return nexus.view(portalId);
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return runtime.portals().canManage(MinecraftMenuViewer.player(viewer), runtime.portals().get(portalId));
    }

    @Override
    public boolean dialAdmission(MenuViewer viewer, UUID portalId) {
        return nexus.dialAdmission(viewer, portalId);
    }

    @Override
    public Optional<PortalNetwork> network(UUID networkId) {
        return nexus.network(networkId);
    }

    @Override
    public Optional<PortalNetwork> networkByName(String name) {
        NetworkRegistry registry = nexus.registry();
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.byName(name));
    }

    @Override
    public int networksOwnedBy(UUID playerId) {
        NetworkRegistry registry = nexus.registry();
        return registry == null ? 0 : registry.ownedBy(playerId).size();
    }

    @Override
    public NexusConfig config() {
        return runtime.configuration().settings().getNexus();
    }

    @Override
    public boolean saveNetwork(PortalNetwork network) {
        NetworkRegistry registry = nexus.registry();
        if (registry == null) {
            return false;
        }
        try {
            registry.save(network);
        } catch (IOException failure) {
            LOGGER.warn("nexus could not save network {}", network.name(), failure);
            return false;
        }
        runtime.menus().invalidate(new MenuScope.Network(network.id()));
        return true;
    }

    @Override
    public void edit(MenuViewer viewer, UUID portalId, NetworkEdit edit) {
        Consumer<MinecraftPortal> mutation = switch (edit) {
            case NetworkEdit.Join join -> portal -> {
                portal.setNexusValue("networkId", join.networkId().toString());
                portal.setNexusValue("address", join.address());
                portal.setNexusValue("label", join.label());
            };
            case NetworkEdit.Leave _ -> portal -> {
                portal.setNexusValue("networkId", null);
                portal.setNexusValue("address", null);
                portal.setNexusValue("dial", null);
            };
            case NetworkEdit.Address address -> portal -> portal.setNexusValue("address", address.address());
            case NetworkEdit.Reciprocal reciprocal -> portal -> portal.setNexusValue("reciprocal", reciprocal.enabled());
            case NetworkEdit.Policy policy -> portal -> portal.setNexusValue("policy", policy.policy().toMap());
            case NetworkEdit.Redstone redstone -> portal -> portal.setNexusValue("frameIo", redstone.frameIo().toMap());
        };
        if (runtime.portals().update(MinecraftMenuViewer.player(viewer), portalId, mutation)) {
            runtime.menus().invalidate(new MenuScope.Portal(portalId));
        }
    }

    @Override
    public void tell(MenuViewer viewer, TextKey key, MessageArgs arguments) {
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        player.sendSystemMessage(MinecraftMenuText.text(player, key, arguments));
    }
}
