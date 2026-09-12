package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Paged list of the addresses a portal can dial, opening on the page holding the current address. */
public final class DialMenu {
    private static final int ROW_WIDTH = 9;

    private final NetworkRegistry registry;
    private final Dialer dialer;

    public DialMenu(NetworkRegistry registry, Dialer dialer) {
        this.registry = registry;
        this.dialer = dialer;
    }

    public void open(LocalPortal portal, Player viewer) {
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        if (state == null || state.networkId() == null) {
            NexusText.send(viewer, NexusMessages.DIAL_NONE, NexusText.args("portal", portal.getName()));
            return;
        }
        if (!DialAdmission.allows(portal, registry.byId(state.networkId()), viewer)) {
            NexusText.send(viewer, WormholesMessages.PORTAL_ACCESS_DENIED);
            return;
        }
        new Session(portal, state, viewer).open();
    }

    private final class Session {
        private final LocalPortal portal;
        private final NexusPortalExtension state;
        private final Player viewer;
        private final Window window;
        private int page;

        private Session(LocalPortal portal, NexusPortalExtension state, Player viewer) {
            this.portal = portal;
            this.state = state;
            this.viewer = viewer;
            window = new UIWindow(Wormholes.instance, viewer)
                    .setTitle(portal.getRouter(true))
                    .setResolution(WindowResolution.W9_H6)
                    .setDecorator(new UIPaneDecorator(Material.CYAN_STAINED_GLASS_PANE));
            window.setViewportHeight(6);
        }

        private void open() {
            page = DialMenuModel.pageOf(DialMenuModel.indexOf(members(), state.dial().currentAddress()));
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
            return DialMenuModel.dialable(registry.byId(state.networkId()), portal.getId());
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
                window.setElement(0, 2, NexusText.element("dial-empty", WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY,
                        MessageArgs.empty(), Material.BARRIER));
            }
            if (page > 0) {
                UIElement previous = NexusText.element("dial-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS,
                        MessageArgs.empty(), Material.ARROW);
                previous.onLeftClick(event -> {
                    page--;
                    repopulate();
                });
                window.setElement(-4, 5, previous);
            }
            window.setElement(0, 5, NexusText.element("dial-page", WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
                    NexusText.args("page", page + 1, "pages", pageCount, "count", members.size()), Material.PAPER));
            if (page + 1 < pageCount) {
                UIElement next = NexusText.element("dial-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT,
                        MessageArgs.empty(), Material.ARROW);
                next.onLeftClick(event -> {
                    page++;
                    repopulate();
                });
                window.setElement(4, 5, next);
            }
        }

        private UIElement addressElement(NetworkMember member, int index) {
            boolean current = DialMenuModel.isCurrent(member, state.dial().currentAddress());
            UIElement element = NexusText.element("dial-" + index, NexusMessages.MENU_DIAL_ENTRY,
                    NexusText.args(
                            "address", member.address(),
                            "portal", member.label().isEmpty() ? member.address() : member.label(),
                            "world", member.isLocal() ? worldName(member) : member.serverName(),
                            "state", NexusText.openLabel(true)),
                    member.isLocal() ? Material.ENDER_PEARL : Material.END_CRYSTAL);
            element.setEnchanted(current);
            element.onLeftClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> dial(member)));
            return element;
        }

        private void dial(NetworkMember member) {
            window.close();
            Dialer.DialResult result = dialer.dial(portal, member.address(), viewer.getUniqueId(), System.currentTimeMillis());
            switch (result) {
                case DIALED -> NexusText.send(viewer, NexusMessages.DIALED, NexusText.args(
                        "portal", portal.getName(),
                        "address", member.address(),
                        "destination", member.label().isEmpty() ? member.address() : member.label()));
                case DEBOUNCED -> NexusText.send(viewer, NexusMessages.DIAL_DEBOUNCED);
                case UNKNOWN_ADDRESS -> NexusText.send(viewer, NexusMessages.DIAL_UNKNOWN,
                        NexusText.args("address", member.address()));
                case NOT_ON_NETWORK -> NexusText.send(viewer, NexusMessages.DIAL_NONE,
                        NexusText.args("portal", portal.getName()));
                case MANAGED_PORTAL -> NexusText.send(viewer, NexusMessages.DIAL_MANAGED,
                        NexusText.args("portal", portal.getName()));
            }
        }

        private String worldName(NetworkMember member) {
            ILocalPortal target = Wormholes.portalManager == null
                    ? null : Wormholes.portalManager.getLocalPortal(member.portalId());
            if (target == null || target.getStructure() == null || target.getStructure().getWorld() == null) {
                return "";
            }
            return target.getStructure().getWorld().getName();
        }
    }
}
