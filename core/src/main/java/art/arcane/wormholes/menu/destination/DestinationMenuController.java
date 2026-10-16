package art.arcane.wormholes.menu.destination;

import java.util.ArrayList;
import java.util.Comparator;
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
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStateKey;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.LocalPortalDestinationModel;
import art.arcane.wormholes.portal.LocalPortalDestinationModel.SortMode;
import art.arcane.wormholes.portal.PortalType;

public final class DestinationMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.PORTAL_MENU_LOCAL_DESTINATION, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_REMOTE_DESTINATION, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_SORT, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 1),
        Map.entry(NexusMessages.MENU_LINK_AND_RETURN, 1));

    private static final MenuStateKey<SortMode> SORT = new MenuStateKey<>("destination.sort", SortMode.class);
    private static final int ROW_WIDTH = 9;
    private static final int CONTROL_ROW = 5;
    private static final List<SortMode> SORT_MODES = List.of(SortMode.values());
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> CYCLE = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SELECT);

    private final PortalMenuHost portals;
    private final DestinationMenuHost destinations;

    public DestinationMenuController(PortalMenuHost portals, DestinationMenuHost destinations) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
    }

    @Override
    public String id() {
        return PortalRoutes.DESTINATION_CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        if (context.route().screen().equals(DestinationRoutes.SELECT)) {
            return MenuDecision.allow();
        }
        UUID portalId = PortalRoutes.portalId(context.route());
        Optional<PortalMenuView> portal = portals.portal(portalId);
        if (portal.isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        Optional<TextKey> refusal = refusal(portal.get());
        if (refusal.isPresent()) {
            return MenuDecision.deny(PortalMenuText.settingNotice(context.viewer(), portal.get(), refusal.get(), MessageArgs.empty(),
                MenuFeedbackStyle.WARNING));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        return switch (context.route().screen()) {
            case PortalRoutes.DESTINATION_PICKER -> picker(context);
            case DestinationRoutes.SELECT -> select(context);
            default -> throw new IllegalArgumentException("destination menus have no screen " + context.route().screen());
        };
    }

    @Override
    public void entered(MenuContext context) {
        context.state().remove(SORT);
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return route.screen().equals(PortalRoutes.DESTINATION_PICKER) && scope instanceof MenuScope.Portal portal
            && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private MenuBuild picker(MenuContext context) {
        PortalMenuView portal = require(context);
        UUID portalId = portal.id();
        MenuStrings strings = context.strings();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<DestinationMenuView> targets = sorted(destinations.candidates(portalId), context);
        Listing listing = Listing.of(targets.size(), context.page());
        List<MenuElement> elements = new ArrayList<>(listing.end() - listing.start() + 6);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(sortElement(strings, context, bindings));
        for (int index = listing.start(); index < listing.end(); index++) {
            DestinationMenuView target = targets.get(index);
            String id = (target.remote() ? "remote-portal-" : "portal-") + index;
            elements.add(tile(strings, target, id, index - listing.start(), target.linked()));
            bindings.guard(id, manage).on(id, MenuInteraction.PRIMARY, (ctx, action) -> choose(ctx, portalId, target));
        }
        listControls(elements, bindings, strings, listing, targets.size());
        reciprocalTarget(context.viewer(), portal).ifPresent(target -> {
            elements.add(new ButtonElement(common("destination-link-return", lines(strings, NexusMessages.MENU_LINK_AND_RETURN, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("destination", target.name())
                .build()), MenuIcon.item("minecraft:ender_eye"), "link", new MenuSlot(2, CONTROL_ROW)).interactions(PRESS).build()));
            bindings.guard("destination-link-return", MenuGuards.ownerOrAdmin(portals, portalId).and(MenuGuards.ownerOrAdmin(portals, target.id())))
                .on("destination-link-return", MenuInteraction.PRIMARY, (ctx, action) -> linkAndReturn(ctx, portalId, target));
        });
        nextControl(elements, bindings, strings, listing);

        MenuScreen screen = MenuScreen.builder(PortalRoutes.DESTINATION_PICKER)
            .title(strings.text(MenuMessages.TITLE_DESTINATIONS))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(elements)
            .paging(listing.paging())
            .build();
        return new MenuBuild(screen, new ChestLayout(portal.routerTitle(), 6, ChestPane.GRAY, PortalRoutes.home(portalId)), bindings.build());
    }

    private MenuBuild select(MenuContext context) {
        MenuViewer viewer = context.viewer();
        MenuStrings strings = context.strings();
        List<DestinationMenuView> manageable = new ArrayList<>();
        for (DestinationMenuView candidate : destinations.portals()) {
            if (portals.canManage(viewer, candidate.id())) {
                manageable.add(candidate);
            }
        }
        List<DestinationMenuView> targets = sorted(manageable, context);
        Listing listing = Listing.of(targets.size(), context.page());
        List<MenuElement> elements = new ArrayList<>(listing.end() - listing.start() + 5);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(sortElement(strings, context, bindings));
        for (int index = listing.start(); index < listing.end(); index++) {
            DestinationMenuView target = targets.get(index);
            String id = "portal-" + index;
            elements.add(tile(strings, target, id, index - listing.start(), false));
            bindings.guard(id, MenuGuards.manage(portals, target.id()))
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.navigate(PortalRoutes.home(target.id()), NavigateMode.REPLACE, null));
        }
        listControls(elements, bindings, strings, listing, targets.size());
        nextControl(elements, bindings, strings, listing);

        MenuScreen screen = MenuScreen.builder(DestinationRoutes.SELECT)
            .title(strings.text(MenuMessages.TITLE_SELECT))
            .elements(elements)
            .paging(listing.paging())
            .build();
        return new MenuBuild(screen, new ChestLayout("", 6, ChestPane.GRAY, null), bindings.build());
    }

    private CycleElement sortElement(MenuStrings strings, MenuContext context, MenuBindings.Builder bindings) {
        SortMode mode = context.state().getOrDefault(SORT, SortMode.SMART);
        List<String> options = new ArrayList<>(SORT_MODES.size());
        for (SortMode option : SORT_MODES) {
            options.add(sortLabel(strings, option));
        }
        bindings.guard("destination-sort", MenuGuards.none())
            .on("destination-sort", MenuInteraction.PRIMARY, (ctx, action) -> sortBy(ctx, mode.next()))
            .on("destination-sort", MenuInteraction.SELECT, (ctx, action) -> sortBy(ctx, SORT_MODES.get(((MenuValue.Index) action.value()).index())));
        MessageArgs sort = args("mode", sortLabel(strings, mode));
        return new CycleElement(common("destination-sort", lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_SORT, sort),
            MenuIcon.item("minecraft:comparator"), "sort", new MenuSlot(-2, CONTROL_ROW))
            .nativeLabel(strings.settingName(WormholesMessages.PORTAL_MENU_DESTINATION_SORT, sort, "mode"))
            .interactions(CYCLE).build(), options, mode.ordinal());
    }

    private void listControls(List<MenuElement> elements, MenuBindings.Builder bindings, MenuStrings strings, Listing listing, int count) {
        if (count == 0) {
            elements.add(new LabelElement(common("destination-empty", lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY,
                MessageArgs.empty()), MenuIcon.item("minecraft:barrier"), null, new MenuSlot(0, 2)).build()));
        }
        if (listing.page() > 0) {
            elements.add(pageButton(strings, "destination-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, -4));
            bindings.guard("destination-previous", MenuGuards.none())
                .on("destination-previous", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(listing.page() - 1));
        }
        elements.add(new LabelElement(common("destination-page", lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, MessageArgs.builder()
            .untrusted("page", listing.page() + 1)
            .untrusted("pages", listing.pageCount())
            .untrusted("count", count)
            .build()), MenuIcon.item("minecraft:paper"), null, new MenuSlot(0, CONTROL_ROW)).nativeHidden(true).build()));
    }

    private void nextControl(List<MenuElement> elements, MenuBindings.Builder bindings, MenuStrings strings, Listing listing) {
        if (listing.page() + 1 >= listing.pageCount()) {
            return;
        }
        elements.add(pageButton(strings, "destination-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 4));
        bindings.guard("destination-next", MenuGuards.none())
            .on("destination-next", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(listing.page() + 1));
    }

    private MenuResult sortBy(MenuContext context, SortMode mode) {
        context.state().put(SORT, mode);
        return MenuResult.page(0);
    }

    private MenuResult choose(MenuContext context, UUID portalId, DestinationMenuView target) {
        MenuViewer viewer = context.viewer();
        MessageArgs.Builder arguments = MessageArgs.builder()
            .untrusted("portal", require(context).name())
            .untrusted("destination", target.name());
        TextKey key;
        if (linkedNow(portalId, target)) {
            destinations.unlink(viewer, portalId);
            key = WormholesMessages.PORTAL_UNLINKED;
        } else if (target.remote()) {
            destinations.link(viewer, portalId, target);
            arguments.untrusted("server", target.server());
            key = WormholesMessages.PORTAL_LINKED_REMOTE;
        } else {
            destinations.link(viewer, portalId, target);
            key = WormholesMessages.PORTAL_LINKED;
        }
        MessageArgs built = arguments.build();
        portals.announce(portalId, key, built, true);
        return MenuResult.close(MenuNotices.nativeOnly(viewer, key, built, MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult linkAndReturn(MenuContext context, UUID portalId, DestinationMenuView target) {
        PortalMenuView portal = require(context);
        PairOutcome outcome = destinations.pair(context.viewer(), portalId, target.id());
        if (outcome == PairOutcome.UNAVAILABLE) {
            return MenuResult.close();
        }
        boolean paired = outcome == PairOutcome.PAIRED;
        MessageArgs arguments = MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("destination", target.name())
            .build();
        return MenuResult.close(PortalMenuText.settingNotice(context.viewer(), portal,
            paired ? NexusMessages.RECIPROCAL_PAIRED : NexusMessages.RECIPROCAL_DENIED, arguments,
            paired ? MenuFeedbackStyle.SUCCESS : MenuFeedbackStyle.WARNING));
    }

    private boolean linkedNow(UUID portalId, DestinationMenuView target) {
        for (DestinationMenuView candidate : destinations.candidates(portalId)) {
            if (candidate.id().equals(target.id()) && candidate.remote() == target.remote()) {
                return candidate.linked();
            }
        }
        return false;
    }

    private Optional<DestinationMenuView> reciprocalTarget(MenuViewer viewer, PortalMenuView portal) {
        return destinations.localDestination(portal.id())
            .filter(target -> !target.id().equals(portal.id()))
            .filter(target -> portals.ownerOrAdmin(viewer, portal.id()) && portals.ownerOrAdmin(viewer, target.id()));
    }

    private PortalMenuView require(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        return portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private static List<DestinationMenuView> sorted(List<DestinationMenuView> targets, MenuContext context) {
        Comparator<LocalPortalDestinationModel.Entry> order = LocalPortalDestinationModel.comparator(context.state().getOrDefault(SORT, SortMode.SMART));
        List<DestinationMenuView> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparing(DestinationMenuView::entry, order));
        return sorted;
    }

    private static ListEntryElement tile(MenuStrings strings, DestinationMenuView target, String id, int slot, boolean linked) {
        MessageArgs.Builder arguments = MessageArgs.builder()
            .untrusted("portal", target.name())
            .untrusted("x", target.x())
            .untrusted("y", target.y())
            .untrusted("z", target.z())
            .untrusted("world", target.world())
            .untrusted("direction", PortalMenuText.direction(strings, target.direction()));
        if (target.remote()) {
            arguments.untrusted("server", target.server())
                .untrusted("state", PortalMenuText.plain(strings, target.open() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED));
        }
        LinesKey key = target.remote() ? WormholesMessages.PORTAL_MENU_REMOTE_DESTINATION : WormholesMessages.PORTAL_MENU_LOCAL_DESTINATION;
        MenuIcon icon = MenuIcon.item(target.remote() ? "minecraft:end_crystal" : "minecraft:ender_pearl", 1, linked);
        MenuSlot chestSlot = new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH);
        return new ListEntryElement(common(id, lines(strings, key, arguments.build()), icon, target.remote() ? "gateway" : "portal", chestSlot)
            .interactions(PRESS).build(), linked);
    }

    private static ButtonElement pageButton(MenuStrings strings, String id, LinesKey key, int position) {
        return new ButtonElement(common(id, lines(strings, key, MessageArgs.empty()), MenuIcon.item("minecraft:arrow"), null,
            new MenuSlot(position, CONTROL_ROW)).interactions(PRESS).nativeHidden(true).build());
    }

    private static Optional<TextKey> refusal(PortalMenuView portal) {
        if (portal.type() == PortalType.RTP) {
            return Optional.of(WormholesMessages.PORTAL_RTP_CANNOT_LINK);
        }
        if (portal.mirror()) {
            return Optional.of(WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED);
        }
        if (portal.managed()) {
            return Optional.of(WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
        }
        return Optional.empty();
    }

    private static String sortLabel(MenuStrings strings, SortMode mode) {
        TextKey key = switch (mode) {
            case SMART -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART;
            case NAME -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME;
            case WORLD -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_WORLD;
            case DISTANCE -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_DISTANCE;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, MenuIcon icon, String sprite, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(icon)
            .nativeIcon(sprite == null ? null : MenuIcon.sprite(sprite))
            .chestSlot(slot);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private record Listing(int page, int pageCount, int start, int end) {
        static Listing of(int count, int requestedPage) {
            int pageCount = LocalPortalDestinationModel.pageCount(count);
            int page = LocalPortalDestinationModel.clampPage(requestedPage, pageCount);
            return new Listing(page, pageCount, LocalPortalDestinationModel.pageStart(page), LocalPortalDestinationModel.pageEnd(count, page));
        }

        MenuPaging paging() {
            return pageCount > 1 ? new MenuPaging(page, pageCount) : null;
        }
    }
}
