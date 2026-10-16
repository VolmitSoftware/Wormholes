package art.arcane.wormholes.menu.nexus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.nexus.DialMenuModel;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.PortalNetwork;

public final class DialMenuController implements MenuController {
    public static final int REFRESH_TICKS = 40;
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(NexusMessages.MENU_DIAL_ENTRY, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, 0));

    private static final int ROW_WIDTH = 9;
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);

    private final DialMenuHost host;

    public DialMenuController(DialMenuHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public String id() {
        return NexusRoutes.DIAL_CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        MenuViewer viewer = context.viewer();
        UUID portalId = NexusRoutes.portalId(context.route());
        Optional<NexusPortalView> portal = host.portal(portalId);
        if (portal.isEmpty()) {
            return MenuDecision.denyAndClose(chat(viewer, WormholesMessages.PORTAL_ACCESS_DENIED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        if (!portal.get().joined()) {
            return MenuDecision.denyAndClose(chat(viewer, NexusMessages.DIAL_NONE, args("portal", portal.get().name()), MenuFeedbackStyle.ERROR));
        }
        if (host.network(portal.get().networkId()).isEmpty() || !host.dialAdmission(viewer, portalId)) {
            return MenuDecision.denyAndClose(chat(viewer, WormholesMessages.PORTAL_ACCESS_DENIED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        return MenuDecision.allow();
    }

    @Override
    public int initialPage(MenuContext context) {
        return host.portal(NexusRoutes.portalId(context.route()))
            .map(portal -> DialMenuModel.pageOf(DialMenuModel.indexOf(
                DialMenuModel.dialable(host.network(portal.networkId()).orElse(null), portal.id()), portal.dial().currentAddress())))
            .orElse(0);
    }

    @Override
    public MenuBuild build(MenuContext context) {
        MenuStrings strings = context.strings();
        NexusPortalView portal = require(context);
        PortalNetwork network = host.network(portal.networkId())
            .orElseThrow(() -> new IllegalStateException("portal " + portal.id() + " left its network"));
        List<NetworkMember> members = DialMenuModel.dialable(network, portal.id());
        int pageCount = DialMenuModel.pageCount(members.size());
        int page = DialMenuModel.clampPage(context.page(), pageCount);
        int start = DialMenuModel.pageStart(page);
        int end = DialMenuModel.pageEnd(members.size(), page);
        MenuGuard admitted = this::admitted;
        List<MenuElement> elements = new ArrayList<>(end - start + 4);
        MenuBindings.Builder bindings = MenuBindings.builder();

        for (int index = start; index < end; index++) {
            NetworkMember member = members.get(index);
            int slot = index - start;
            String id = "dial-" + index;
            boolean current = DialMenuModel.isCurrent(member, portal.dial().currentAddress());
            elements.add(new ListEntryElement(MenuElementCommon.builder(id)
                .lines(lines(strings, NexusMessages.MENU_DIAL_ENTRY, MessageArgs.builder()
                    .untrusted("address", member.address())
                    .untrusted("portal", destination(member))
                    .untrusted("world", member.isLocal() ? host.worldName(member.portalId()) : member.serverName())
                    .untrusted("state", PortalMenuText.plain(strings, WormholesMessages.LABEL_OPEN))
                    .build()))
                .icon(MenuIcon.item(member.isLocal() ? "minecraft:ender_pearl" : "minecraft:end_crystal", 1, current))
                .nativeIcon(MenuIcon.sprite(member.isLocal() ? "portal" : "gateway"))
                .chestSlot(new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH))
                .interactions(PRESS)
                .build(), current));
            bindings.guard(id, admitted).on(id, MenuInteraction.PRIMARY, (ctx, action) -> dial(ctx, member));
        }
        if (members.isEmpty()) {
            elements.add(new LabelElement(MenuElementCommon.builder("dial-empty")
                .lines(lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, MessageArgs.empty()))
                .icon(MenuIcon.item("minecraft:barrier"))
                .chestSlot(new MenuSlot(0, 2))
                .build()));
        }
        if (page > 0) {
            elements.add(pageButton(strings, "dial-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, -4));
            bindings.guard("dial-previous", admitted).on("dial-previous", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page - 1));
        }
        elements.add(new LabelElement(MenuElementCommon.builder("dial-page")
            .lines(lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, MessageArgs.builder()
                .untrusted("page", page + 1)
                .untrusted("pages", pageCount)
                .untrusted("count", members.size())
                .build()))
            .icon(MenuIcon.item("minecraft:paper"))
            .chestSlot(new MenuSlot(0, 5))
            .nativeHidden(true)
            .build()));
        if (page + 1 < pageCount) {
            elements.add(pageButton(strings, "dial-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 4));
            bindings.guard("dial-next", admitted).on("dial-next", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page + 1));
        }

        MenuScreen screen = MenuScreen.builder(NexusRoutes.DIAL)
            .title(strings.text(MenuMessages.TITLE_DIAL))
            .context(strings.text(MenuMessages.CONTEXT_NETWORK, args("name", network.name())))
            .elements(elements)
            .paging(new MenuPaging(page, pageCount))
            .build();
        return new MenuBuild(screen, new ChestLayout(portal.routerTitle(), 6, ChestPane.CYAN, null), bindings.build());
    }

    @Override
    public int refreshIntervalTicks(MenuRoute route) {
        return REFRESH_TICKS;
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        UUID portalId = NexusRoutes.portalId(route);
        return switch (scope) {
            case MenuScope.Portal portal -> portal.portalId().equals(portalId);
            case MenuScope.Network _ -> true;
            default -> false;
        };
    }

    private MenuResult dial(MenuContext context, NetworkMember member) {
        MenuViewer viewer = context.viewer();
        NexusPortalView portal = require(context);
        DialOutcome outcome = host.dial(viewer, portal.id(), member.address());
        MenuNotice notice = switch (outcome) {
            case DIALED -> chat(viewer, NexusMessages.DIALED, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("address", member.address())
                .untrusted("destination", destination(member))
                .build(), MenuFeedbackStyle.SUCCESS);
            case DEBOUNCED -> chat(viewer, NexusMessages.DIAL_DEBOUNCED, MessageArgs.empty(), MenuFeedbackStyle.WARNING);
            case UNKNOWN_ADDRESS -> chat(viewer, NexusMessages.DIAL_UNKNOWN, args("address", member.address()), MenuFeedbackStyle.ERROR);
            case NOT_ON_NETWORK -> chat(viewer, NexusMessages.DIAL_NONE, args("portal", portal.name()), MenuFeedbackStyle.ERROR);
            case MANAGED_PORTAL -> chat(viewer, NexusMessages.DIAL_MANAGED, args("portal", portal.name()), MenuFeedbackStyle.ERROR);
        };
        return MenuResult.close(notice);
    }

    private MenuDecision admitted(MenuContext context, MenuAction action) {
        if (host.dialAdmission(context.viewer(), NexusRoutes.portalId(context.route()))) {
            return MenuDecision.allow();
        }
        return MenuDecision.denyAndClose(chat(context.viewer(), WormholesMessages.PORTAL_ACCESS_DENIED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
    }

    private NexusPortalView require(MenuContext context) {
        UUID portalId = NexusRoutes.portalId(context.route());
        return host.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private static ButtonElement pageButton(MenuStrings strings, String id, LinesKey key, int position) {
        return new ButtonElement(MenuElementCommon.builder(id)
            .lines(lines(strings, key, MessageArgs.empty()))
            .icon(MenuIcon.item("minecraft:arrow"))
            .chestSlot(new MenuSlot(position, 5))
            .interactions(PRESS)
            .nativeHidden(true)
            .build());
    }

    private static String destination(NetworkMember member) {
        return member.label().isEmpty() ? member.address() : member.label();
    }

    private static MenuNotice chat(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return MenuNotices.of(viewer, key, arguments, style, NoticeChannel.CHAT);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }
}
