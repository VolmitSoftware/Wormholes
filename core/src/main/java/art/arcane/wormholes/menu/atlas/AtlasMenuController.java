package art.arcane.wormholes.menu.atlas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.localization.AtlasMessages;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.CycleElement;
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
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuState;
import art.arcane.wormholes.menu.MenuStateKey;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.nexus.NexusRoutes;
import art.arcane.wormholes.menu.portal.PortalMenuText;

public final class AtlasMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(AtlasMessages.MENU_ROW, 1),
        Map.entry(AtlasMessages.MENU_FAVORITES, 1),
        Map.entry(AtlasMessages.MENU_RECENTS, 1),
        Map.entry(AtlasMessages.MENU_GUIDE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_SORT, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, 0));
    public static final MenuStateKey<AtlasModel.Filter> FILTER = new MenuStateKey<>("atlas.filter", AtlasModel.Filter.class);
    public static final MenuStateKey<AtlasModel.SortMode> SORT = new MenuStateKey<>("atlas.sort", AtlasModel.SortMode.class);

    private static final int ROW_WIDTH = 9;
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> ROW = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);

    private final AtlasMenuHost host;

    public AtlasMenuController(AtlasMenuHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public String id() {
        return AtlasRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        MenuViewer viewer = context.viewer();
        if (!viewer.hasPermission(AtlasRoutes.PERMISSION)) {
            return MenuDecision.denyAndClose(chat(viewer, AtlasMessages.NO_PERMISSION, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        if (!host.settings().enabled) {
            return MenuDecision.denyAndClose(chat(viewer, AtlasMessages.DISABLED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        MenuStrings strings = context.strings();
        AtlasConfig settings = host.settings();
        AtlasMenuView view = host.atlas(context.viewer());
        AtlasPlayerState state = view.state();
        AtlasModel.Filter filter = filter(context);
        AtlasModel.SortMode sort = context.state().getOrDefault(SORT, AtlasModel.SortMode.SMART);
        List<AtlasModel.Row> rows = AtlasModel.sorted(AtlasModel.visible(view.candidates(), state, settings.discoveryRequired, filter), state, sort);
        int pageCount = AtlasModel.pageCount(rows.size());
        int page = AtlasModel.clampPage(context.page(), pageCount);
        int start = AtlasModel.pageStart(page);
        int end = AtlasModel.pageEnd(rows.size(), page);
        MenuGuard atlas = this::atlasAllowed;
        List<MenuElement> elements = new ArrayList<>(end - start + 8);
        MenuBindings.Builder bindings = MenuBindings.builder();

        MessageArgs sortArguments = args("mode", sortLabel(strings, sort));
        elements.add(new CycleElement(common("atlas-sort", lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_SORT, sortArguments),
            MenuIcon.item("minecraft:comparator"), "sort", AtlasModel.Control.SORT)
            .nativeLabel(strings.settingName(WormholesMessages.PORTAL_MENU_DESTINATION_SORT, sortArguments, "mode")).interactions(PRESS).build(),
            sortLabels(strings), sort.ordinal()));
        bindings.guard("atlas-sort", atlas).on("atlas-sort", MenuInteraction.PRIMARY, (ctx, action) -> {
            ctx.state().put(SORT, ctx.state().getOrDefault(SORT, AtlasModel.SortMode.SMART).next());
            return MenuResult.page(0);
        });

        elements.add(filterToggle(strings, "atlas-favorites", AtlasMessages.MENU_FAVORITES, "minecraft:nether_star", "favorite",
            AtlasModel.Control.FAVORITES, filter == AtlasModel.Filter.FAVORITES));
        bindings.guard("atlas-favorites", atlas)
            .on("atlas-favorites", MenuInteraction.PRIMARY, (ctx, action) -> toggleFilter(ctx, AtlasModel.Filter.FAVORITES));

        elements.add(filterToggle(strings, "atlas-recents", AtlasMessages.MENU_RECENTS, "minecraft:clock", "atlas",
            AtlasModel.Control.RECENTS, filter == AtlasModel.Filter.RECENTS));
        bindings.guard("atlas-recents", atlas)
            .on("atlas-recents", MenuInteraction.PRIMARY, (ctx, action) -> toggleFilter(ctx, AtlasModel.Filter.RECENTS));

        UUID guided = state.guideTarget();
        if (guided != null) {
            elements.add(new ButtonElement(common("atlas-guide", lines(strings, AtlasMessages.MENU_GUIDE, args("portal", host.portalName(guided))),
                MenuIcon.item("minecraft:compass"), "link", AtlasModel.Control.GUIDE).interactions(PRESS).build()));
            bindings.guard("atlas-guide", atlas).on("atlas-guide", MenuInteraction.PRIMARY, this::clearGuide);
        }

        for (int index = start; index < end; index++) {
            AtlasModel.Row row = rows.get(index);
            int slot = index - start;
            String id = "atlas-" + index;
            boolean favorite = state.isFavorite(row.portalId());
            elements.add(new ListEntryElement(MenuElementCommon.builder(id)
                .lines(lines(strings, AtlasMessages.MENU_ROW, MessageArgs.builder()
                    .untrusted("portal", row.name())
                    .untrusted("world", row.world())
                    .untrusted("destination", row.destination().isEmpty() ? row.address() : row.destination())
                    .untrusted("state", openLabel(strings, row))
                    .build()))
                .icon(MenuIcon.item(row.isNetworked() ? "minecraft:compass" : "minecraft:ender_pearl", 1, favorite))
                .nativeIcon(MenuIcon.sprite(row.isNetworked() ? "wormhole" : "portal"))
                .chestSlot(new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH))
                .interactions(ROW)
                .sideActions(List.of(
                    new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_FAVORITE), MenuIcon.sprite("small/favorite")),
                    new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_GUIDE), MenuIcon.sprite("small/link"))))
                .build(), favorite));
            bindings.guard(id, atlas)
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> dial(ctx, row))
                .on(id, MenuInteraction.SECONDARY, (ctx, action) -> pin(ctx, row))
                .on(id, MenuInteraction.TERTIARY, (ctx, action) -> guide(ctx, row));
        }
        if (rows.isEmpty()) {
            elements.add(new LabelElement(MenuElementCommon.builder("atlas-empty")
                .lines(lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, MessageArgs.empty()))
                .icon(MenuIcon.item("minecraft:barrier"))
                .chestSlot(new MenuSlot(0, 2))
                .build()));
        }
        if (page > 0) {
            elements.add(pageButton(strings, "atlas-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, AtlasModel.Control.PREVIOUS));
            bindings.guard("atlas-previous", atlas).on("atlas-previous", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page - 1));
        }
        elements.add(new LabelElement(common("atlas-page", lines(strings, WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, MessageArgs.builder()
            .untrusted("page", page + 1)
            .untrusted("pages", pageCount)
            .untrusted("count", rows.size())
            .build()), MenuIcon.item("minecraft:paper"), null, AtlasModel.Control.PAGE).nativeHidden(true).build()));
        if (page + 1 < pageCount) {
            elements.add(pageButton(strings, "atlas-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, AtlasModel.Control.NEXT));
            bindings.guard("atlas-next", atlas).on("atlas-next", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page + 1));
        }

        MenuScreen screen = MenuScreen.builder(AtlasRoutes.LIST)
            .title(strings.text(MenuMessages.TITLE_ATLAS))
            .elements(elements)
            .paging(new MenuPaging(page, pageCount))
            .build();
        ChestLayout chest = new ChestLayout(strings.text(AtlasMessages.TITLE, args("count", rows.size())), 6, ChestPane.BLUE, null);
        return new MenuBuild(screen, chest, bindings.build());
    }

    private MenuResult dial(MenuContext context, AtlasModel.Row row) {
        return switch (host.link(row.portalId())) {
            case MISSING -> MenuResult.stay();
            case STANDALONE -> MenuResult.stay(chat(context.viewer(), AtlasMessages.ROW, MessageArgs.builder()
                .untrusted("portal", row.name())
                .untrusted("destination", row.destination().isEmpty() ? row.world() : row.destination())
                .untrusted("state", openLabel(context.strings(), row))
                .build(), MenuFeedbackStyle.INFO));
            case NETWORKED -> MenuResult.push(NexusRoutes.dial(row.portalId()));
        };
    }

    private MenuResult pin(MenuContext context, AtlasModel.Row row) {
        MenuViewer viewer = context.viewer();
        MenuNotice notice = switch (host.toggleFavorite(viewer, row.portalId())) {
            case ADDED -> chat(viewer, AtlasMessages.FAVORITE_ADDED, args("portal", row.name()), MenuFeedbackStyle.SUCCESS);
            case REMOVED -> chat(viewer, AtlasMessages.FAVORITE_REMOVED, args("portal", row.name()), MenuFeedbackStyle.SUCCESS);
            case FULL -> chat(viewer, AtlasMessages.FAVORITE_FULL, args("count", host.settings().favoritesLimit), MenuFeedbackStyle.ERROR);
        };
        return MenuResult.stay(notice);
    }

    private MenuResult guide(MenuContext context, AtlasModel.Row row) {
        host.setGuideTarget(context.viewer(), row.portalId());
        return MenuResult.stay(chat(context.viewer(), AtlasMessages.GUIDE_SET, args("portal", row.name()), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult clearGuide(MenuContext context, MenuAction action) {
        host.clearGuide(context.viewer());
        return MenuResult.stay(chat(context.viewer(), AtlasMessages.GUIDE_CLEARED, MessageArgs.empty(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult toggleFilter(MenuContext context, AtlasModel.Filter requested) {
        MenuState state = context.state();
        state.put(FILTER, filter(context) == requested ? AtlasModel.Filter.ALL : requested);
        return MenuResult.page(0);
    }

    private MenuDecision atlasAllowed(MenuContext context, MenuAction action) {
        return routeGuard(context);
    }

    private static AtlasModel.Filter filter(MenuContext context) {
        return context.state().getOrDefault(FILTER, AtlasRoutes.filter(context.route()));
    }

    private static ToggleElement filterToggle(MenuStrings strings, String id, LinesKey key, String item, String sprite, AtlasModel.Control control,
                                              boolean active) {
        MessageArgs state = args("state", Boolean.toString(active));
        return new ToggleElement(common(id, lines(strings, key, state), MenuIcon.item(item), sprite, control)
            .nativeLabel(strings.settingName(key, state, "state")).interactions(PRESS).build(), active);
    }

    private static ButtonElement pageButton(MenuStrings strings, String id, LinesKey key, AtlasModel.Control control) {
        return new ButtonElement(common(id, lines(strings, key, MessageArgs.empty()), MenuIcon.item("minecraft:arrow"), null, control)
            .interactions(PRESS).nativeHidden(true).build());
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, MenuIcon icon, String sprite, AtlasModel.Control control) {
        int slot = control.slot();
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(icon)
            .nativeIcon(sprite == null ? null : MenuIcon.sprite(sprite))
            .chestSlot(new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH));
    }

    private static List<String> sortLabels(MenuStrings strings) {
        AtlasModel.SortMode[] modes = AtlasModel.SortMode.values();
        List<String> labels = new ArrayList<>(modes.length);
        for (AtlasModel.SortMode mode : modes) {
            labels.add(sortLabel(strings, mode));
        }
        return labels;
    }

    private static String sortLabel(MenuStrings strings, AtlasModel.SortMode mode) {
        TextKey key = switch (mode) {
            case SMART -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART;
            case NAME -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME;
            case WORLD -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_WORLD;
            case DISTANCE -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_DISTANCE;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String openLabel(MenuStrings strings, AtlasModel.Row row) {
        return PortalMenuText.plain(strings, row.open() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED);
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
