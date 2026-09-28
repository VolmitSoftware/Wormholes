package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.nexus.AddressAllocator;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.DialMenuModel;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.PortalStateCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftNexusMenus {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int ROW_WIDTH = 9;

    private final WormholesModRuntime runtime;
    private final Random random = new Random();

    public MinecraftNexusMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void open(ServerPlayer viewer, MinecraftPortal portal) {
        UUID networkId = networkId(portal);
        if (networkId == null) {
            send(viewer, NexusMessages.DIAL_NONE, MinecraftPortalText.arguments("portal", portal.getName()));
            return;
        }
        if (!dialAllowed(portal, registry().byId(networkId), viewer)) {
            send(viewer, WormholesMessages.PORTAL_ACCESS_DENIED, MessageArgs.empty());
            return;
        }
        new DialSession(portal, viewer).open();
    }

    public void openManagement(ServerPlayer viewer, MinecraftPortal portal) {
        new NetworkSession(portal, viewer).open();
    }

    static UUID networkId(MinecraftPortal portal) {
        return portal.setting("nexus.networkId") instanceof String id && !id.isBlank() ? UUID.fromString(id) : null;
    }

    static String address(MinecraftPortal portal) {
        return portal.setting("nexus.address") instanceof String address ? address : "";
    }

    private static boolean reciprocal(MinecraftPortal portal) {
        return Boolean.TRUE.equals(portal.setting("nexus.reciprocal"));
    }

    private static DestinationPolicy policy(MinecraftPortal portal) {
        return DestinationPolicy.fromMap(document(portal, "policy"));
    }

    private static FrameIo frameIo(MinecraftPortal portal) {
        return FrameIo.fromMap(document(portal, "frameIo"));
    }

    private static DialState dialState(MinecraftPortal portal) {
        return DialState.fromMap(document(portal, "dial"));
    }

    private static Map<String, Object> document(MinecraftPortal portal, String key) {
        Object value = portal.setting("nexus." + key);
        return value instanceof Map<?, ?> ? PortalStateCodec.object(Map.of("value", value), "value") : null;
    }

    private boolean dialAllowed(MinecraftPortal portal, PortalNetwork network, ServerPlayer viewer) {
        if (network == null) {
            return false;
        }
        boolean administrator = runtime.access().administrator(viewer);
        if (!administrator && !portal.allows(viewer.getUUID(), false, node -> runtime.access().permission(viewer, node),
            runtime.configuration().settings().getAccess().legacyNameNodeEnabled)) {
            return false;
        }
        return network.visibleTo(viewer.getUUID(), administrator);
    }

    private NetworkRegistry registry() {
        return runtime.nexus().networks();
    }

    private NexusConfig config() {
        return runtime.configuration().settings().getNexus();
    }

    private void prompt(ServerPlayer viewer, TextKey promptKey, Consumer<String> onInput) {
        viewer.closeContainer();
        send(viewer, promptKey, MinecraftPortalText.arguments("cancel", cancelWord(viewer)));
        runtime.chatInput().await(viewer, input -> {
            if (input == null || input.trim().equalsIgnoreCase(cancelWord(viewer))) {
                onInput.accept(null);
                return;
            }
            onInput.accept(input.trim());
        });
    }

    private static String cancelWord(ServerPlayer viewer) {
        return label(viewer, WormholesMessages.PORTAL_INPUT_CANCEL);
    }

    private static String label(ServerPlayer viewer, TextKey key) {
        return MinecraftMenuText.text(viewer, key, MessageArgs.empty()).getString();
    }

    private static MinecraftElement element(ServerPlayer viewer, String id, LinesKey key, MessageArgs arguments, Item material) {
        MinecraftElement element = new MinecraftElement(id);
        element.setMaterial(material);
        MinecraftLegacyText.apply(viewer, element, key, arguments);
        return element;
    }

    private static void send(ServerPlayer viewer, TextKey key, MessageArgs arguments) {
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, key, arguments));
    }

    private final class NetworkSession {
        private final MinecraftPortal portal;
        private final ServerPlayer viewer;
        private final MinecraftWindow window;

        private NetworkSession(MinecraftPortal portal, ServerPlayer viewer) {
            this.portal = portal;
            this.viewer = viewer;
            window = new MinecraftWindow(runtime, viewer)
                .setTitle(MinecraftPortalText.router(runtime, portal, true))
                .setDecorator(Items.STAINED_GLASS_PANE.cyan());
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
            window.setElement(0, 0, element(viewer, "nexus-placard", NexusMessages.MENU_PLACARD,
                MinecraftPortalText.arguments(
                    "name", network == null ? "" : network.name(),
                    "address", address(portal),
                    "count", network == null ? 0 : network.members().size()),
                Items.COMPASS));

            if (network == null) {
                window.setElement(-1, 1, action("nexus-join", NexusMessages.MENU_JOIN, MessageArgs.empty(),
                    Items.ENDER_EYE, this::promptJoin));
                window.setElement(1, 1, action("nexus-create", NexusMessages.MENU_CREATE, MessageArgs.empty(),
                    Items.NETHER_STAR, this::promptCreate));
                return;
            }

            window.setElement(-3, 1, action("nexus-address", NexusMessages.MENU_ADDRESS,
                MinecraftPortalText.arguments("address", address(portal)), Items.NAME_TAG, this::promptAddress,
                this::rerollAddress));
            window.setElement(-1, 1, action("nexus-visibility", NexusMessages.MENU_VISIBILITY,
                MinecraftPortalText.arguments("value", network.visibility().name()), Items.ITEM_FRAME,
                () -> saveNetwork(network.withVisibility(network.visibility().next()))));
            window.setElement(1, 1, action("nexus-topology", NexusMessages.MENU_TOPOLOGY,
                MinecraftPortalText.arguments("value", network.topology().name()), Items.IRON_BARS,
                () -> saveNetwork(network.withTopology(network.topology().next()))));
            window.setElement(3, 1, action("nexus-dial", NexusMessages.MENU_DIAL, MessageArgs.empty(),
                Items.LEVER, () -> {
                    window.close();
                    MinecraftNexusMenus.this.open(viewer, portal);
                }));

            DestinationPolicy policy = policy(portal);
            FrameIo frameIo = frameIo(portal);
            window.setElement(-3, 2, action("nexus-reciprocal", NexusMessages.MENU_RECIPROCAL,
                MinecraftPortalText.arguments("state", Boolean.toString(reciprocal(portal))), Items.ENDER_CHEST,
                this::toggleReciprocal));
            window.setElement(-1, 2, action("nexus-policy", NexusMessages.MENU_POLICY,
                MinecraftPortalText.arguments("mode", policy.mode().name(), "count", policy.entries().size()),
                Items.TARGET, this::cyclePolicyMode, this::clearPolicy));
            window.setElement(1, 2, action("nexus-redstone", NexusMessages.MENU_REDSTONE,
                MinecraftPortalText.arguments("value", frameIo.action().name() + "/" + frameIo.comparator().name()),
                Items.REDSTONE_TORCH, this::cycleRedstoneAction, this::cycleComparator));
            window.setElement(3, 2, action("nexus-leave", NexusMessages.MENU_LEAVE,
                MinecraftPortalText.arguments("name", network.name()), Items.BARRIER, this::leave));
        }

        private PortalNetwork network() {
            UUID networkId = networkId(portal);
            return networkId == null ? null : registry().byId(networkId);
        }

        private MinecraftElement action(String id, LinesKey key, MessageArgs arguments, Item material, Runnable onLeft) {
            return action(id, key, arguments, material, onLeft, null);
        }

        private MinecraftElement action(String id, LinesKey key, MessageArgs arguments, Item material, Runnable onLeft, Runnable onRight) {
            MinecraftElement element = element(viewer, id, key, arguments, material);
            element.onLeftClick(event -> runtime.schedule(onLeft, 1L));
            if (onRight != null) {
                element.onRightClick(event -> runtime.schedule(onRight, 1L));
            }
            return element;
        }

        private void promptCreate() {
            prompt(viewer, NexusMessages.PROMPT_NETWORK_NAME, name -> {
                if (name == null) {
                    return;
                }
                if (registry().byName(name) != null) {
                    send(viewer, NexusMessages.NETWORK_EXISTS, MinecraftPortalText.arguments("name", name));
                    return;
                }
                int limit = config().maxNetworksPerPlayer;
                if (!runtime.access().permission(viewer, "wormholes.admin.nexus") && registry().ownedBy(viewer.getUUID()).size() >= limit) {
                    send(viewer, NexusMessages.NETWORK_LIMIT, MinecraftPortalText.arguments("count", limit));
                    return;
                }
                PortalNetwork created = PortalNetwork.create(UUID.randomUUID(), name, viewer.getUUID());
                join(created);
                send(viewer, NexusMessages.CREATED, MinecraftPortalText.arguments("name", name));
            });
        }

        private void promptJoin() {
            prompt(viewer, NexusMessages.PROMPT_NETWORK_NAME, name -> {
                if (name == null) {
                    return;
                }
                PortalNetwork found = registry().byName(name);
                if (found == null) {
                    send(viewer, NexusMessages.NETWORK_UNKNOWN, MinecraftPortalText.arguments("name", name));
                    return;
                }
                join(found);
            });
        }

        private void join(PortalNetwork network) {
            int limit = config().maxMembersPerNetwork;
            if (network.members().size() >= limit) {
                send(viewer, NexusMessages.NETWORK_FULL, MinecraftPortalText.arguments("name", network.name(), "count", limit));
                return;
            }
            String address = AddressAllocator.next(network, config().addressAlphabet, config().addressLength, random);
            PortalNetwork joined = network.withMember(portal.getId(),
                new NetworkMember(portal.getId(), address, portal.getName(), System.currentTimeMillis(), null));
            if (!saveNetwork(joined)) {
                return;
            }
            portal.setNexusValue("networkId", joined.id().toString());
            portal.setNexusValue("address", address);
            portal.setNexusValue("label", portal.getName());
            runtime.portals().save(portal);
            send(viewer, NexusMessages.JOINED, MinecraftPortalText.arguments("portal", portal.getName(), "name", joined.name(), "address", address));
        }

        private void leave() {
            PortalNetwork network = network();
            if (network == null) {
                return;
            }
            if (!saveNetwork(network.withoutMember(portal.getId()))) {
                return;
            }
            portal.setNexusValue("networkId", null);
            portal.setNexusValue("address", null);
            portal.setNexusValue("dial", null);
            runtime.portals().save(portal);
            send(viewer, NexusMessages.LEFT, MinecraftPortalText.arguments("portal", portal.getName(), "name", network.name()));
            repopulate();
        }

        private void promptAddress() {
            prompt(viewer, NexusMessages.PROMPT_ADDRESS, address -> {
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
            applyAddress(AddressAllocator.next(network.withoutMember(portal.getId()), config().addressAlphabet,
                config().addressLength, random));
        }

        private void applyAddress(String requested) {
            PortalNetwork network = network();
            if (network == null) {
                return;
            }
            String alphabet = config().addressAlphabet;
            if (!AddressAllocator.isValid(requested, alphabet)) {
                send(viewer, NexusMessages.ADDRESS_INVALID, MinecraftPortalText.arguments("value", alphabet));
                return;
            }
            String address = NetworkMember.normalizeAddress(requested);
            UUID holder = network.portalIdAt(address);
            if (holder != null && !holder.equals(portal.getId())) {
                send(viewer, NexusMessages.ADDRESS_TAKEN, MinecraftPortalText.arguments("address", address, "name", network.name()));
                return;
            }
            NetworkMember member = network.member(portal.getId());
            if (member == null) {
                return;
            }
            if (!saveNetwork(network.withMember(portal.getId(), member.withAddress(address)))) {
                return;
            }
            portal.setNexusValue("address", address);
            runtime.portals().save(portal);
            send(viewer, NexusMessages.ADDRESS_SET, MinecraftPortalText.arguments("portal", portal.getName(), "address", address));
            repopulate();
        }

        private void toggleReciprocal() {
            portal.setNexusValue("reciprocal", !reciprocal(portal));
            runtime.portals().save(portal);
            repopulate();
        }

        private void cyclePolicyMode() {
            DestinationPolicy policy = policy(portal);
            portal.setNexusValue("policy", policy.withMode(policy.mode().next()).toMap());
            runtime.portals().save(portal);
            repopulate();
        }

        private void clearPolicy() {
            portal.setNexusValue("policy", DestinationPolicy.empty().toMap());
            runtime.portals().save(portal);
            repopulate();
        }

        private void cycleRedstoneAction() {
            FrameIo frameIo = frameIo(portal);
            FrameIo changed = frameIo.withAction(frameIo.action().next());
            portal.setNexusValue("frameIo", changed.toMap());
            runtime.portals().save(portal);
            send(viewer, NexusMessages.REDSTONE_SET, MinecraftPortalText.arguments("portal", portal.getName(), "value", changed.action().name()));
            repopulate();
        }

        private void cycleComparator() {
            FrameIo frameIo = frameIo(portal);
            FrameIo changed = frameIo.withComparator(frameIo.comparator().next());
            portal.setNexusValue("frameIo", changed.toMap());
            runtime.portals().save(portal);
            send(viewer, NexusMessages.REDSTONE_SET, MinecraftPortalText.arguments("portal", portal.getName(), "value", changed.comparator().name()));
            repopulate();
        }

        private boolean saveNetwork(PortalNetwork network) {
            try {
                registry().save(network);
                return true;
            } catch (IOException failure) {
                LOGGER.warn("nexus could not save network {}", network.name(), failure);
                return false;
            }
        }
    }

    private final class DialSession {
        private final MinecraftPortal portal;
        private final ServerPlayer viewer;
        private final MinecraftWindow window;
        private int page;

        private DialSession(MinecraftPortal portal, ServerPlayer viewer) {
            this.portal = portal;
            this.viewer = viewer;
            window = new MinecraftWindow(runtime, viewer)
                .setTitle(MinecraftPortalText.router(runtime, portal, true))
                .setDecorator(Items.STAINED_GLASS_PANE.cyan());
            window.setViewportHeight(6);
        }

        private void open() {
            page = DialMenuModel.pageOf(DialMenuModel.indexOf(members(), dialState(portal).currentAddress()));
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

        private List<NetworkMember> members() {
            UUID networkId = networkId(portal);
            return DialMenuModel.dialable(networkId == null ? null : registry().byId(networkId), portal.getId());
        }

        private void populate() {
            List<NetworkMember> members = members();
            int pageCount = DialMenuModel.pageCount(members.size());
            page = DialMenuModel.clampPage(page, pageCount);
            int start = DialMenuModel.pageStart(page);
            int end = DialMenuModel.pageEnd(members.size(), page);
            for (int index = start; index < end; index++) {
                int slot = index - start;
                window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, addressElement(members.get(index), index));
            }
            if (members.isEmpty()) {
                window.setElement(0, 2, element(viewer, "dial-empty", WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY,
                    MessageArgs.empty(), Items.BARRIER));
            }
            if (page > 0) {
                MinecraftElement previous = element(viewer, "dial-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS,
                    MessageArgs.empty(), Items.ARROW);
                previous.onLeftClick(event -> {
                    page--;
                    repopulate();
                });
                window.setElement(-4, 5, previous);
            }
            window.setElement(0, 5, element(viewer, "dial-page", WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
                MinecraftPortalText.arguments("page", page + 1, "pages", pageCount, "count", members.size()), Items.PAPER));
            if (page + 1 < pageCount) {
                MinecraftElement next = element(viewer, "dial-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT,
                    MessageArgs.empty(), Items.ARROW);
                next.onLeftClick(event -> {
                    page++;
                    repopulate();
                });
                window.setElement(4, 5, next);
            }
        }

        private MinecraftElement addressElement(NetworkMember member, int index) {
            boolean current = DialMenuModel.isCurrent(member, dialState(portal).currentAddress());
            MinecraftElement element = element(viewer, "dial-" + index, NexusMessages.MENU_DIAL_ENTRY,
                MinecraftPortalText.arguments(
                    "address", member.address(),
                    "portal", member.label().isEmpty() ? member.address() : member.label(),
                    "world", member.isLocal() ? worldName(member) : member.serverName(),
                    "state", label(viewer, WormholesMessages.LABEL_OPEN)),
                member.isLocal() ? Items.ENDER_PEARL : Items.END_CRYSTAL);
            element.setEnchanted(current);
            element.onLeftClick(event -> runtime.schedule(() -> dial(member), 1L));
            return element;
        }

        private void dial(NetworkMember member) {
            window.close();
            switch (dialResult(member.address())) {
                case DIALED -> send(viewer, NexusMessages.DIALED, MinecraftPortalText.arguments(
                    "portal", portal.getName(),
                    "address", member.address(),
                    "destination", member.label().isEmpty() ? member.address() : member.label()));
                case DEBOUNCED -> send(viewer, NexusMessages.DIAL_DEBOUNCED, MessageArgs.empty());
                case UNKNOWN_ADDRESS -> send(viewer, NexusMessages.DIAL_UNKNOWN, MinecraftPortalText.arguments("address", member.address()));
                case NOT_ON_NETWORK -> send(viewer, NexusMessages.DIAL_NONE, MinecraftPortalText.arguments("portal", portal.getName()));
                case MANAGED_PORTAL -> send(viewer, NexusMessages.DIAL_MANAGED, MinecraftPortalText.arguments("portal", portal.getName()));
            }
        }

        private DialResult dialResult(String address) {
            UUID networkId = networkId(portal);
            if (networkId == null) {
                return DialResult.NOT_ON_NETWORK;
            }
            if (portal.isManaged() || portal.isMirrorMode()) {
                return DialResult.MANAGED_PORTAL;
            }
            PortalNetwork network = registry().byId(networkId);
            if (network == null) {
                return DialResult.NOT_ON_NETWORK;
            }
            NetworkMember member = network.memberAt(address);
            if (member == null || member.portalId().equals(portal.getId())) {
                return DialResult.UNKNOWN_ADDRESS;
            }
            if (dialState(portal).withinDebounce(System.currentTimeMillis(), config().dialDebounceMillis)) {
                return DialResult.DEBOUNCED;
            }
            return runtime.nexus().dial(viewer, portal, member.address()) ? DialResult.DIALED : DialResult.MANAGED_PORTAL;
        }

        private String worldName(NetworkMember member) {
            MinecraftPortal target = runtime.portals().get(member.portalId());
            return target == null ? "" : target.getWorldKey();
        }
    }

    private enum DialResult {
        DIALED,
        UNKNOWN_ADDRESS,
        NOT_ON_NETWORK,
        DEBOUNCED,
        MANAGED_PORTAL
    }
}
