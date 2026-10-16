package art.arcane.wormholes.menu.host;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.NetworkEdit;
import art.arcane.wormholes.menu.nexus.NetworkMenuHost;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.RedstoneIo;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;

public final class BukkitNetworkMenuHost implements NetworkMenuHost {
    private final Logger logger;
    private final BukkitNexusPortals nexus;

    public BukkitNetworkMenuHost(Logger logger, PortalManager portals, Supplier<NexusSubsystem> nexus) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nexus = new BukkitNexusPortals(portals, nexus);
    }

    @Override
    public Optional<NexusPortalView> portal(UUID portalId) {
        return nexus.view(portalId);
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return nexus.local(portalId).map(portal -> portal.canManage(viewer.id(), viewer.administrator())).orElse(false);
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
        return NexusSubsystem.config();
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
            logger.log(Level.WARNING, "nexus could not save network " + network.name(), failure);
            return false;
        }
        MenuService menus = Wormholes.menus;
        if (menus != null) {
            menus.invalidate(new MenuScope.Network(network.id()));
        }
        return true;
    }

    @Override
    public void edit(MenuViewer viewer, UUID portalId, NetworkEdit edit) {
        Optional<LocalPortal> found = nexus.local(portalId);
        if (found.isEmpty()) {
            return;
        }
        LocalPortal portal = found.get();
        Optional<NexusPortalExtension> extension = nexus.state(portal);
        if (extension.isEmpty()) {
            return;
        }
        NexusPortalExtension state = extension.get();
        switch (edit) {
            case NetworkEdit.Join join -> {
                state.setNetworkId(join.networkId());
                state.setAddress(join.address());
                state.setLabel(join.label());
            }
            case NetworkEdit.Leave _ -> {
                state.setNetworkId(null);
                state.setAddress("");
                state.setDial(DialState.idle());
            }
            case NetworkEdit.Address address -> state.setAddress(address.address());
            case NetworkEdit.Reciprocal reciprocal -> state.setReciprocal(reciprocal.enabled());
            case NetworkEdit.Policy policy -> state.setPolicy(policy.policy());
            case NetworkEdit.Redstone redstone -> state.setFrameIo(redstone.frameIo());
        }
        portal.save();
        if (edit instanceof NetworkEdit.Redstone) {
            syncRedstone(portal);
        }
        portal.invalidateMenus();
    }

    @Override
    public void tell(MenuViewer viewer, TextKey key, MessageArgs arguments) {
        Player player = BukkitMenuViewer.player(viewer);
        WormholesAudience.sendMessage(player, Wormholes.text().component(player, key, arguments));
    }

    private void syncRedstone(LocalPortal portal) {
        NexusSubsystem subsystem = nexus.subsystem();
        RedstoneIo redstone = subsystem == null ? null : subsystem.redstoneIo();
        if (redstone != null) {
            redstone.sync(portal);
        }
    }
}
