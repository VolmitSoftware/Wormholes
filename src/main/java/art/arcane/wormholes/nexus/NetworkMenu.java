package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The portal "Network" page: join, leave or create a network, set the address, change visibility and
 * topology, toggle the return link, cycle the destination policy, and wire redstone.
 */
public final class NetworkMenu {
    private final NetworkRegistry registry;
    private final DialMenu dialMenu;
    private final RedstoneIo redstoneIo;
    private final Random random = new Random();

    public NetworkMenu(NetworkRegistry registry, DialMenu dialMenu, RedstoneIo redstoneIo) {
        this.registry = registry;
        this.dialMenu = dialMenu;
        this.redstoneIo = redstoneIo;
    }

    public void open(LocalPortal portal, Player viewer) {
        new Session(portal, viewer).open();
    }

    private final class Session {
        private final LocalPortal portal;
        private final NexusPortalExtension state;
        private final Player viewer;
        private final Window window;

        private Session(LocalPortal portal, Player viewer) {
            this.portal = portal;
            this.state = portal.extension(NexusPortalExtension.class);
            this.viewer = viewer;
            window = new UIWindow(Wormholes.instance, viewer)
                    .setTitle(portal.getRouter(true))
                    .setResolution(WindowResolution.W9_H6)
                    .setDecorator(new UIPaneDecorator(Material.CYAN_STAINED_GLASS_PANE));
            window.setViewportHeight(4);
        }

        private void open() {
            window.batch(this::populate);
            window.setVisible(true);
        }

        private void repopulate() {
            window.batch(() -> {
                window.clearElements();
                populate();
            });
            window.updateInventory();
        }

        private void populate() {
            PortalNetwork network = network();
            window.setElement(0, 0, NexusText.element("nexus-placard", NexusMessages.MENU_PLACARD,
                    NexusText.args(
                            "name", network == null ? "" : network.name(),
                            "address", state.address(),
                            "count", network == null ? 0 : network.members().size()),
                    Material.COMPASS));

            if (network == null) {
                window.setElement(-1, 1, action("nexus-join", NexusMessages.MENU_JOIN, MessageArgs.empty(),
                        Material.ENDER_EYE, this::promptJoin));
                window.setElement(1, 1, action("nexus-create", NexusMessages.MENU_CREATE, MessageArgs.empty(),
                        Material.NETHER_STAR, this::promptCreate));
                return;
            }

            window.setElement(-3, 1, action("nexus-address", NexusMessages.MENU_ADDRESS,
                    NexusText.args("address", state.address()), Material.NAME_TAG, this::promptAddress,
                    this::rerollAddress));
            window.setElement(-1, 1, action("nexus-visibility", NexusMessages.MENU_VISIBILITY,
                    NexusText.args("value", network.visibility().name()), Material.ITEM_FRAME,
                    () -> saveNetwork(network.withVisibility(network.visibility().next()))));
            window.setElement(1, 1, action("nexus-topology", NexusMessages.MENU_TOPOLOGY,
                    NexusText.args("value", network.topology().name()), Material.IRON_BARS,
                    () -> saveNetwork(network.withTopology(network.topology().next()))));
            window.setElement(3, 1, action("nexus-dial", NexusMessages.MENU_DIAL, MessageArgs.empty(),
                    Material.LEVER, () -> {
                        window.close();
                        dialMenu.open(portal, viewer);
                    }));

            window.setElement(-3, 2, action("nexus-reciprocal", NexusMessages.MENU_RECIPROCAL,
                    NexusText.args("state", Boolean.toString(state.reciprocal())), Material.ENDER_CHEST,
                    this::toggleReciprocal));
            window.setElement(-1, 2, action("nexus-policy", NexusMessages.MENU_POLICY,
                    NexusText.args("mode", state.policy().mode().name(), "count", state.policy().entries().size()),
                    Material.TARGET, this::cyclePolicyMode, this::clearPolicy));
            window.setElement(1, 2, action("nexus-redstone", NexusMessages.MENU_REDSTONE,
                    NexusText.args("value", state.frameIo().action().name() + "/" + state.frameIo().comparator().name()),
                    Material.REDSTONE_TORCH, this::cycleRedstoneAction, this::cycleComparator));
            window.setElement(3, 2, action("nexus-leave", NexusMessages.MENU_LEAVE,
                    NexusText.args("name", network.name()), Material.BARRIER, this::leave));
        }

        private PortalNetwork network() {
            return state.networkId() == null ? null : registry.byId(state.networkId());
        }

        private UIElement action(String id, LinesKey key, MessageArgs arguments, Material material, Runnable onLeft) {
            return action(id, key, arguments, material, onLeft, null);
        }

        private UIElement action(String id, LinesKey key, MessageArgs arguments, Material material,
                                 Runnable onLeft, Runnable onRight) {
            UIElement element = NexusText.element(id, key, arguments, material);
            element.onLeftClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, onLeft));
            if (onRight != null) {
                element.onRightClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, onRight));
            }
            return element;
        }

        private void promptCreate() {
            NexusText.prompt(viewer, NexusMessages.PROMPT_NETWORK_NAME, name -> {
                if (name == null) {
                    return;
                }
                if (registry.byName(name) != null) {
                    NexusText.send(viewer, NexusMessages.NETWORK_EXISTS, NexusText.args("name", name));
                    return;
                }
                int limit = NexusSubsystem.config().maxNetworksPerPlayer;
                if (!viewer.hasPermission("wormholes.admin.nexus") && registry.ownedBy(viewer.getUniqueId()).size() >= limit) {
                    NexusText.send(viewer, NexusMessages.NETWORK_LIMIT, NexusText.args("count", limit));
                    return;
                }
                PortalNetwork created = PortalNetwork.create(UUID.randomUUID(), name, viewer.getUniqueId());
                join(created);
                NexusText.send(viewer, NexusMessages.CREATED, NexusText.args("name", name));
            });
        }

        private void promptJoin() {
            NexusText.prompt(viewer, NexusMessages.PROMPT_NETWORK_NAME, name -> {
                if (name == null) {
                    return;
                }
                PortalNetwork found = registry.byName(name);
                if (found == null) {
                    NexusText.send(viewer, NexusMessages.NETWORK_UNKNOWN, NexusText.args("name", name));
                    return;
                }
                join(found);
            });
        }

        private void join(PortalNetwork network) {
            int limit = NexusSubsystem.config().maxMembersPerNetwork;
            if (network.members().size() >= limit) {
                NexusText.send(viewer, NexusMessages.NETWORK_FULL,
                        NexusText.args("name", network.name(), "count", limit));
                return;
            }
            String address = AddressAllocator.next(network, NexusSubsystem.config().addressAlphabet,
                    NexusSubsystem.config().addressLength, random);
            PortalNetwork joined = network.withMember(portal.getId(),
                    new NetworkMember(portal.getId(), address, portal.getName(), System.currentTimeMillis(), null));
            if (!saveNetwork(joined)) {
                return;
            }
            state.setNetworkId(joined.id());
            state.setAddress(address);
            state.setLabel(portal.getName());
            portal.save();
            NexusText.send(viewer, NexusMessages.JOINED,
                    NexusText.args("portal", portal.getName(), "name", joined.name(), "address", address));
        }

        private void leave() {
            PortalNetwork network = network();
            if (network == null) {
                return;
            }
            if (!saveNetwork(network.withoutMember(portal.getId()))) {
                return;
            }
            state.setNetworkId(null);
            state.setAddress("");
            state.setDial(DialState.idle());
            portal.save();
            NexusText.send(viewer, NexusMessages.LEFT,
                    NexusText.args("portal", portal.getName(), "name", network.name()));
            repopulate();
        }

        private void promptAddress() {
            NexusText.prompt(viewer, NexusMessages.PROMPT_ADDRESS, address -> {
                if (address == null) {
                    return;
                }
                applyAddress(address);
            });
        }

        private void rerollAddress() {
            PortalNetwork network = network();
            if (network == null) {
                return;
            }
            applyAddress(AddressAllocator.next(network.withoutMember(portal.getId()),
                    NexusSubsystem.config().addressAlphabet, NexusSubsystem.config().addressLength, random));
        }

        private void applyAddress(String requested) {
            PortalNetwork network = network();
            if (network == null) {
                return;
            }
            String alphabet = NexusSubsystem.config().addressAlphabet;
            if (!AddressAllocator.isValid(requested, alphabet)) {
                NexusText.send(viewer, NexusMessages.ADDRESS_INVALID, NexusText.args("value", alphabet));
                return;
            }
            String address = NetworkMember.normalizeAddress(requested);
            UUID holder = network.portalIdAt(address);
            if (holder != null && !holder.equals(portal.getId())) {
                NexusText.send(viewer, NexusMessages.ADDRESS_TAKEN,
                        NexusText.args("address", address, "name", network.name()));
                return;
            }
            NetworkMember member = network.member(portal.getId());
            if (member == null) {
                return;
            }
            if (!saveNetwork(network.withMember(portal.getId(), member.withAddress(address)))) {
                return;
            }
            state.setAddress(address);
            portal.save();
            NexusText.send(viewer, NexusMessages.ADDRESS_SET,
                    NexusText.args("portal", portal.getName(), "address", address));
            repopulate();
        }

        private void toggleReciprocal() {
            state.setReciprocal(!state.reciprocal());
            portal.save();
            repopulate();
        }

        private void cyclePolicyMode() {
            state.setPolicy(state.policy().withMode(state.policy().mode().next()));
            portal.save();
            repopulate();
        }

        private void clearPolicy() {
            state.setPolicy(DestinationPolicy.empty());
            portal.save();
            repopulate();
        }

        private void cycleRedstoneAction() {
            state.setFrameIo(state.frameIo().withAction(state.frameIo().action().next()));
            portal.save();
            redstoneIo.sync(portal);
            NexusText.send(viewer, NexusMessages.REDSTONE_SET,
                    NexusText.args("portal", portal.getName(), "value", state.frameIo().action().name()));
            repopulate();
        }

        private void cycleComparator() {
            state.setFrameIo(state.frameIo().withComparator(state.frameIo().comparator().next()));
            portal.save();
            redstoneIo.sync(portal);
            NexusText.send(viewer, NexusMessages.REDSTONE_SET,
                    NexusText.args("portal", portal.getName(), "value", state.frameIo().comparator().name()));
            repopulate();
        }

        private boolean saveNetwork(PortalNetwork network) {
            try {
                registry.save(network);
                return true;
            } catch (IOException failure) {
                Wormholes.instance.getLogger().log(Level.WARNING, "nexus could not save network " + network.name(), failure);
                return false;
            }
        }
    }
}
