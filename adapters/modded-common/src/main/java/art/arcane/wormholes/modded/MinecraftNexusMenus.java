package art.arcane.wormholes.modded;

import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.nexus.DialMenuModel;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.DestinationMode;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.nexus.PortalNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.HashMap;
import java.util.UUID;
import java.util.Map;

public final class MinecraftNexusMenus implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final Map<UUID, Prompt> prompts = new HashMap<>();
    private final Map<ServerPlayer, MinecraftInventoryMenu> windows = new HashMap<>();

    public MinecraftNexusMenus(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void open(ServerPlayer player, MinecraftPortal portal, int page) {
        if (!allowed(player, portal)) {
            return;
        }
        MinecraftInventoryMenu.open(player, Component.literal(portal.getName()), new MinecraftInventoryMenu.Actions(
            () -> allowed(player, portal), menu -> { windows.put(player, menu); render(menu, player, portal, page); }, click -> click(click, player, portal, page)));
    }

    public void openManagement(ServerPlayer player, MinecraftPortal portal) {
        if (!runtime.portals().canManage(player, portal)) {
            return;
        }
        MinecraftInventoryMenu.open(player, Component.literal(portal.getName()), new MinecraftInventoryMenu.Actions(
            () -> runtime.portals().canManage(player, portal), menu -> {
                windows.put(player, menu);
                management(menu, player, portal);
            }, click -> manage(click, player, portal)));
    }

    public void tick() {
        prompts.values().removeIf(prompt -> prompt.deadline() <= System.currentTimeMillis());
        windows.entrySet().removeIf(entry -> entry.getKey().containerMenu != entry.getValue());
    }

    public boolean chat(ServerPlayer player, String message) {
        Prompt prompt = prompts.remove(player.getUUID());
        if (prompt == null || prompt.deadline() <= System.currentTimeMillis()) {
            return false;
        }
        MinecraftPortal portal = runtime.portals().get(prompt.portalId());
        if (!runtime.portals().canManage(player, portal)) {
            return true;
        }
        String value = message.trim();
        String cancel = MinecraftMenuText.text(player, WormholesMessages.PORTAL_INPUT_CANCEL, Map.of()).getString();
        if (!value.equalsIgnoreCase(cancel)) {
            try {
                MinecraftNexus nexus = runtime.nexus();
                switch (prompt.kind()) {
                    case "create" -> nexus.join(player, nexus.create(player, value), portal, "");
                    case "join" -> nexus.join(player, nexus.networks().byName(value), portal, "");
                    case "address" -> nexus.join(player, nexus.networks().memberOf(portal.getId()), portal, value);
                    default -> throw new IllegalStateException("Unknown network prompt");
                }
            } catch (IllegalArgumentException invalid) {
                player.sendSystemMessage(Component.literal(invalid.getMessage()));
            }
        }
        openManagement(player, portal);
        return true;
    }

    public void disconnected(ServerPlayer player) {
        windows.remove(player);
        prompts.remove(player.getUUID());
    }

    @Override
    public void close() {
        for (Map.Entry<ServerPlayer, MinecraftInventoryMenu> entry : windows.entrySet()) {
            if (entry.getKey().containerMenu == entry.getValue()) {
                entry.getKey().closeContainer();
            }
        }
        windows.clear();
        prompts.clear();
    }

    private void management(MinecraftInventoryMenu menu, ServerPlayer player, MinecraftPortal portal) {
        MinecraftNexus nexus = runtime.nexus();
        PortalNetwork network = nexus.networks().memberOf(portal.getId());
        menu.set(4, MinecraftMenuText.item(player, Items.COMPASS, NexusMessages.MENU_PLACARD,
            Map.of("name", network == null ? "" : network.name(), "address", String.valueOf(portal.setting("nexus.address")),
                "count", network == null ? 0 : network.members().size())));
        if (network == null) {
            menu.set(11, MinecraftMenuText.item(player, Items.ENDER_EYE, NexusMessages.MENU_JOIN, Map.of()));
            menu.set(15, MinecraftMenuText.item(player, Items.NETHER_STAR, NexusMessages.MENU_CREATE, Map.of()));
        } else {
            menu.set(10, MinecraftMenuText.item(player, Items.NAME_TAG, NexusMessages.MENU_ADDRESS,
                Map.of("address", String.valueOf(portal.setting("nexus.address")))));
            menu.set(12, MinecraftMenuText.item(player, Items.ITEM_FRAME, NexusMessages.MENU_VISIBILITY, Map.of("value", network.visibility().name())));
            menu.set(14, MinecraftMenuText.item(player, Items.IRON_BARS, NexusMessages.MENU_TOPOLOGY, Map.of("value", network.topology().name())));
            menu.set(16, MinecraftMenuText.item(player, Items.LEVER, NexusMessages.MENU_DIAL, Map.of()));
            menu.set(28, MinecraftMenuText.item(player, Items.ENDER_CHEST, NexusMessages.MENU_RECIPROCAL,
                Map.of("state", Boolean.TRUE.equals(portal.setting("nexus.reciprocal")))));
            menu.set(30, MinecraftMenuText.item(player, Items.TARGET, NexusMessages.MENU_POLICY,
                Map.of("mode", nexus.policy(portal).mode().name(), "count", nexus.policy(portal).entries().size())));
            FrameIo io = wiring(portal);
            menu.set(32, MinecraftMenuText.item(player, Items.REDSTONE_TORCH, NexusMessages.MENU_REDSTONE,
                Map.of("value", io.action().name() + "/" + io.comparator().name())));
            menu.set(34, MinecraftMenuText.item(player, Items.BARRIER, NexusMessages.MENU_LEAVE, Map.of("name", network.name())));
        }
        menu.set(49, button("<"));
    }

    private void manage(MinecraftInventoryMenu.Click click, ServerPlayer player, MinecraftPortal portal) {
        MinecraftNexus nexus = runtime.nexus();
        PortalNetwork network = nexus.networks().memberOf(portal.getId());
        try {
            if (click.slot() == 49) {
                runtime.menus().open(player, portal.getId());
                return;
            }
            if (network == null) {
                if (click.slot() == 11 || click.slot() == 15) {
                    prompt(player, portal, click.slot() == 11 ? "join" : "create");
                }
                return;
            }
            if (click.slot() == 16) {
                open(player, portal, 0);
                return;
            }
            nexus.requireManage(player, network);
            switch (click.slot()) {
                case 10 -> {
                    if (click.right()) {
                        nexus.join(player, network, portal, "");
                    } else {
                        prompt(player, portal, "address");
                        return;
                    }
                }
                case 12 -> nexus.save(network.withVisibility(network.visibility().next()));
                case 14 -> nexus.save(network.withTopology(network.topology().next()));
                case 28 -> {
                    MinecraftPortal destination = runtime.portals().get(portal.getDestinationId());
                    if (destination != null) {
                        nexus.pair(player, portal, destination, !Boolean.TRUE.equals(portal.setting("nexus.reciprocal")));
                    }
                }
                case 30 -> {
                    DestinationPolicy policy = nexus.policy(portal);
                    nexus.policy(player, portal, click.right() ? DestinationPolicy.empty() : policy.withMode(
                        DestinationMode.values()[(policy.mode().ordinal() + 1) % DestinationMode.values().length]));
                }
                case 32 -> {
                    FrameIo io = wiring(portal);
                    nexus.wire(player, portal, click.right() ? io.withComparator(io.comparator().next()) : io.withAction(io.action().next()));
                }
                case 34 -> nexus.leave(player, portal);
                default -> { }
            }
            click.menu().refresh();
        } catch (IllegalArgumentException denied) {
            player.sendSystemMessage(Component.literal(denied.getMessage()));
        }
    }

    private void prompt(ServerPlayer player, MinecraftPortal portal, String kind) {
        prompts.put(player.getUUID(), new Prompt(portal.getId(), kind, System.currentTimeMillis() + 60_000L));
        player.closeContainer();
        player.sendSystemMessage(MinecraftMenuText.text(player, kind.equals("address") ? NexusMessages.PROMPT_ADDRESS : NexusMessages.PROMPT_NETWORK_NAME,
            Map.of("cancel", MinecraftMenuText.text(player, WormholesMessages.PORTAL_INPUT_CANCEL, Map.of()).getString())));
    }

    private static FrameIo wiring(MinecraftPortal portal) {
        Object encoded = portal.setting("nexus.frameIo");
        return FrameIo.fromMap(encoded instanceof Map<?, ?> ? PortalStateCodec.object(Map.of("value", encoded), "value") : null);
    }

    private record Prompt(UUID portalId, String kind, long deadline) {
    }

    private void render(MinecraftInventoryMenu menu, ServerPlayer player, MinecraftPortal portal, int page) {
        PortalNetwork network = runtime.nexus().networks().memberOf(portal.getId());
        List<NetworkMember> members = DialMenuModel.dialable(network, portal.getId());
        int selected = DialMenuModel.clampPage(page, DialMenuModel.pageCount(members.size()));
        for (int index = DialMenuModel.pageStart(selected); index < DialMenuModel.pageEnd(members.size(), selected); index++) {
            NetworkMember member = members.get(index);
            MinecraftPortal destination = runtime.portals().get(member.portalId());
            ItemStack item = MinecraftMenuText.item(player, Items.ENDER_PEARL, NexusMessages.MENU_DIAL_ENTRY,
                Map.of("address", member.address(), "portal", member.label(), "world", member.isLocal()
                    ? destination == null ? "" : destination.getWorldKey() : member.serverName(), "state",
                    DialMenuModel.isCurrent(member, runtime.nexus().dialedAddress(portal)) ? "*" : ""));
            menu.set(index - DialMenuModel.pageStart(selected), item);
        }
        menu.set(45, button("<"));
        menu.set(49, MinecraftMenuText.item(player, Items.COMPASS, NexusMessages.MENU_PLACARD,
            Map.of("name", network.name(), "address", String.valueOf(portal.setting("nexus.address")), "count", members.size())));
        menu.set(53, button(">"));
    }

    private void click(MinecraftInventoryMenu.Click click, ServerPlayer player, MinecraftPortal portal, int page) {
        List<NetworkMember> members = DialMenuModel.dialable(runtime.nexus().networks().memberOf(portal.getId()), portal.getId());
        int selected = DialMenuModel.clampPage(page, DialMenuModel.pageCount(members.size()));
        if (click.slot() == 45 || click.slot() == 53) {
            open(player, portal, DialMenuModel.clampPage(selected + (click.slot() == 45 ? -1 : 1), DialMenuModel.pageCount(members.size())));
            return;
        }
        int index = DialMenuModel.pageStart(selected) + click.slot();
        if (click.slot() < DialMenuModel.ENTRIES_PER_PAGE && index < members.size()) {
            NetworkMember member = members.get(index);
            if (runtime.nexus().dial(player, portal, member.address())) {
                player.sendSystemMessage(MinecraftMenuText.text(player, NexusMessages.DIALED,
                    Map.of("portal", portal.getName(), "address", member.address(), "destination", member.label())));
            }
            click.menu().refresh();
        }
    }

    private boolean allowed(ServerPlayer player, MinecraftPortal portal) {
        PortalNetwork network = runtime.nexus().networks().memberOf(portal.getId());
        return runtime.portals().get(portal.getId()) == portal && runtime.portals().canDepart(player, portal)
            && network != null && network.visibleTo(player.getUUID(), runtime.nexus().administrator(player));
    }

    private static ItemStack button(String label) {
        ItemStack item = new ItemStack(Items.ARROW);
        item.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        return item;
    }
}
