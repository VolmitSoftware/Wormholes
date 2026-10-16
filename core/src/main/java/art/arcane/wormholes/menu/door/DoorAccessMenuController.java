package art.arcane.wormholes.menu.door;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
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
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalMenuText;

public final class DoorAccessMenuController implements MenuController {
    public static final int ENTRIES_PER_PAGE = 225;
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.DOOR_MENU_ACCESS_PLACARD, 0),
        Map.entry(WormholesMessages.DOOR_MENU_ACCESS_OPEN_STATE, 1),
        Map.entry(WormholesMessages.DOOR_MENU_ACCESS_ADD_PLAYER, 1),
        Map.entry(WormholesMessages.DOOR_MENU_ACCESS_ENTRY, 2),
        Map.entry(DoorViewMessages.MENU_PROJECTION, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 1),
        Map.entry(MenuMessages.LIST_PAGE, 0));

    private static final int ROW_WIDTH = 9;
    private static final int MAX_VIEWPORT_HEIGHT = 6;
    private static final int SHORT_ID_LENGTH = 8;
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> ENTRY = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);

    private final DoorAccessMenuHost host;

    public DoorAccessMenuController(DoorAccessMenuHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public String id() {
        return DoorRoutes.ACCESS_CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        MenuViewer viewer = context.viewer();
        UUID doorId = DoorRoutes.doorId(context.route());
        if (host.door(doorId).isEmpty()) {
            return MenuDecision.denyAndClose(chat(viewer, WormholesMessages.DOOR_ACCESS_UNAVAILABLE, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        if (!host.canManage(viewer, doorId)) {
            return MenuDecision.denyAndClose(chat(viewer, WormholesMessages.DOOR_ACCESS_EDIT_DENIED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        MenuStrings strings = context.strings();
        DoorAccessMenuView door = require(context);
        DoorAccessRecord record = door.record();
        List<UUID> listed = record.listedPlayers();
        int pageCount = Math.max(1, (listed.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        int page = Math.min(context.page(), pageCount - 1);
        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(listed.size(), start + ENTRIES_PER_PAGE);
        boolean paged = pageCount > 1;
        String kind = kindLabel(strings, door.kind());
        MenuGuard manage = this::manage;
        List<MenuElement> elements = new ArrayList<>(end - start + 7);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("door-access-placard", lines(strings, WormholesMessages.DOOR_MENU_ACCESS_PLACARD, MessageArgs.builder()
            .untrusted("kind", kind)
            .untrusted("owner", playerLabel(strings, record.ownerId()))
            .untrusted("whitelisted", count(record, DoorAccessState.WHITELIST))
            .untrusted("blacklisted", count(record, DoorAccessState.BLACKLIST))
            .untrusted("count", record.players().size())
            .build()), door.placardItemId(), null, new MenuSlot(-1, 0)).build()));

        boolean open = door.openState() == DoorOpenState.OPEN;
        MessageArgs openState = MessageArgs.builder()
            .untrusted("state", openStateLabel(strings, door.openState()))
            .untrusted("next", openStateLabel(strings, door.openState().flipped()))
            .build();
        elements.add(new ToggleElement(common("door-access-open-state", lines(strings, WormholesMessages.DOOR_MENU_ACCESS_OPEN_STATE, openState),
            open ? "minecraft:lime_dye" : "minecraft:gray_dye", "door", new MenuSlot(0, 0))
            .nativeLabel(strings.settingName(WormholesMessages.DOOR_MENU_ACCESS_OPEN_STATE, openState, "state")).interactions(PRESS).build(), open));
        bindings.guard("door-access-open-state", manage).on("door-access-open-state", MenuInteraction.PRIMARY, this::toggleOpenState);

        elements.add(new TextElement(common("door-access-add", lines(strings, WormholesMessages.DOOR_MENU_ACCESS_ADD_PLAYER, MessageArgs.empty()),
            "minecraft:writable_book", "player", new MenuSlot(1, 0)).interactions(Set.of(MenuInteraction.SET_TEXT)).build(), "",
            MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_PLAYER),
            List.of(strings.text(WormholesMessages.DOOR_ACCESS_PROMPT_PLAYER,
                args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL)))), ChestReopen.ALWAYS, null));
        bindings.guard("door-access-add", manage).on("door-access-add", MenuInteraction.SET_TEXT, this::addPlayer);

        MessageArgs projection = args("state", projectionLabel(strings, door.projection()));
        elements.add(new CycleElement(common("door-access-projection", lines(strings, DoorViewMessages.MENU_PROJECTION, projection),
            "minecraft:spyglass", "projection", new MenuSlot(2, 0))
            .nativeLabel(strings.settingName(DoorViewMessages.MENU_PROJECTION, projection, "state"))
            .interactions(PRESS).build(), projectionLabels(strings), door.projection().ordinal()));
        bindings.guard("door-access-projection", manage.and(this::projectionEnabled))
            .on("door-access-projection", MenuInteraction.PRIMARY, this::cycleProjection);

        List<MenuSideAction> entryActions = List.of(
            new MenuSideAction(MenuInteraction.PRIMARY, strings.text(MenuMessages.ACTION_WHITELIST), MenuIcon.sprite("small/confirm")),
            new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_BLACKLIST), MenuIcon.sprite("small/cancel")),
            new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_REMOVE), MenuIcon.sprite("small/delete")));
        for (int index = start; index < end; index++) {
            UUID playerId = listed.get(index);
            DoorAccessState state = record.stateOf(playerId);
            int slot = index - start;
            String id = "door-access-" + playerId;
            elements.add(new ListEntryElement(common(id, lines(strings, WormholesMessages.DOOR_MENU_ACCESS_ENTRY, MessageArgs.builder()
                    .untrusted("name", playerLabel(strings, playerId))
                    .untrusted("state", stateLabel(strings, state))
                    .build()), stateIcon(state), "player", new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, 1 + slot / ROW_WIDTH)
                    .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                    .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY))
                    .withClick(ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY))
                    .withClick(ChestClick.MIDDLE, ChestTarget.self(MenuInteraction.TERTIARY)))
                .interactions(ENTRY)
                .sideActions(entryActions)
                .build(), false));
            bindings.guard(id, manage)
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> applyState(ctx, playerId, DoorAccessState.WHITELIST))
                .on(id, MenuInteraction.SECONDARY, (ctx, action) -> applyState(ctx, playerId, DoorAccessState.BLACKLIST))
                .on(id, MenuInteraction.TERTIARY, (ctx, action) -> removePlayer(ctx, playerId));
        }

        int entryRows = (end - start + ROW_WIDTH - 1) / ROW_WIDTH;
        if (paged) {
            int pagingRow = 1 + entryRows;
            if (page > 0) {
                elements.add(pageButton(strings, "door-access-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, new MenuSlot(-4, pagingRow)));
                bindings.guard("door-access-previous", manage)
                    .on("door-access-previous", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page - 1));
            }
            elements.add(new LabelElement(common("door-access-page", lines(strings, MenuMessages.LIST_PAGE,
                MessageArgs.builder()
                    .untrusted("page", page + 1)
                    .untrusted("pages", pageCount)
                    .untrusted("count", listed.size())
                    .build()), "minecraft:paper", null, new MenuSlot(0, pagingRow)).nativeHidden(true).build()));
            if (page + 1 < pageCount) {
                elements.add(pageButton(strings, "door-access-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, new MenuSlot(4, pagingRow)));
                bindings.guard("door-access-next", manage)
                    .on("door-access-next", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page + 1));
            }
        }

        MenuScreen screen = MenuScreen.builder(DoorRoutes.ACCESS)
            .title(strings.text(MenuMessages.TITLE_DOOR))
            .context(strings.text(MenuMessages.CONTEXT_DOOR, args("kind", kind)))
            .elements(elements)
            .paging(paged ? new MenuPaging(page, pageCount) : null)
            .build();
        int rows = Math.min(MAX_VIEWPORT_HEIGHT, 1 + entryRows + (paged ? 1 : 0));
        ChestLayout chest = new ChestLayout(strings.text(WormholesMessages.DOOR_MENU_ACCESS_TITLE, args("kind", kind)), rows, ChestPane.GRAY, null);
        return new MenuBuild(screen, chest, bindings.build());
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Door door && door.doorId().equals(DoorRoutes.doorId(route));
    }

    private MenuResult toggleOpenState(MenuContext context, MenuAction action) {
        DoorAccessMenuView door = require(context);
        return save(context, new DoorAccessEdit.OpenState(door.openState().flipped()), changed -> MenuResult.stay());
    }

    private MenuResult cycleProjection(MenuContext context, MenuAction action) {
        DoorProjectionState next = require(context).projection().next();
        return save(context, new DoorAccessEdit.Projection(next), changed -> changed
            ? MenuResult.stay(chat(context.viewer(), projectionNotice(next), MessageArgs.empty(), MenuFeedbackStyle.SUCCESS))
            : MenuResult.stay());
    }

    private MenuResult applyState(MenuContext context, UUID playerId, DoorAccessState state) {
        DoorAccessRecord record = require(context).record();
        if (!record.isListed(playerId) || record.stateOf(playerId) == state) {
            return MenuResult.stay();
        }
        return save(context, new DoorAccessEdit.PlayerState(playerId, state), changed -> changed
            ? MenuResult.stay(chat(context.viewer(), WormholesMessages.DOOR_ACCESS_STATE_CHANGED, MessageArgs.builder()
                .untrusted("name", playerLabel(context.strings(), playerId))
                .untrusted("state", stateLabel(context.strings(), state))
                .build(), MenuFeedbackStyle.SUCCESS))
            : MenuResult.stay());
    }

    private MenuResult removePlayer(MenuContext context, UUID playerId) {
        if (!require(context).record().isListed(playerId)) {
            return MenuResult.stay();
        }
        return save(context, new DoorAccessEdit.RemovePlayer(playerId), changed -> changed
            ? MenuResult.stay(chat(context.viewer(), WormholesMessages.DOOR_ACCESS_REMOVED, args("name", playerLabel(context.strings(), playerId)),
                MenuFeedbackStyle.SUCCESS))
            : MenuResult.stay());
    }

    private MenuResult addPlayer(MenuContext context, MenuAction action) {
        String name = ((MenuValue.Text) action.value()).text().trim();
        return MenuResult.pending(host.resolvePlayer(name).thenCompose(resolved -> addResolved(context, name, resolved.orElse(null))));
    }

    private CompletableFuture<MenuResult> addResolved(MenuContext context, String name, UUID resolved) {
        MenuViewer viewer = context.viewer();
        MenuDecision decision = routeGuard(context);
        if (decision instanceof MenuDecision.Deny deny) {
            return CompletableFuture.completedFuture(MenuResult.close(deny.notice()));
        }
        DoorAccessRecord record = require(context).record();
        MenuStrings strings = context.strings();
        return switch (resolveAddition(record, resolved)) {
            case NOT_FOUND -> CompletableFuture.completedFuture(MenuResult.stay(chat(viewer, WormholesMessages.DOOR_ACCESS_PLAYER_NOT_FOUND,
                args("name", name), MenuFeedbackStyle.ERROR)));
            case OWNER -> CompletableFuture.completedFuture(MenuResult.stay(chat(viewer, WormholesMessages.DOOR_ACCESS_OWNER_ALWAYS,
                MessageArgs.empty(), MenuFeedbackStyle.INFO)));
            case ALREADY_LISTED -> CompletableFuture.completedFuture(MenuResult.stay(chat(viewer, WormholesMessages.DOOR_ACCESS_ALREADY_LISTED,
                args("name", playerLabel(strings, resolved)), MenuFeedbackStyle.INFO)));
            case ADD -> saveLater(context, new DoorAccessEdit.AddPlayer(resolved), changed -> changed
                ? MenuResult.stay(chat(viewer, WormholesMessages.DOOR_ACCESS_ADDED, args("name", playerLabel(strings, resolved)),
                    MenuFeedbackStyle.SUCCESS))
                : MenuResult.stay());
        };
    }

    private MenuResult save(MenuContext context, DoorAccessEdit edit, Function<Boolean, MenuResult> onSaved) {
        return MenuResult.pending(saveLater(context, edit, onSaved));
    }

    private CompletableFuture<MenuResult> saveLater(MenuContext context, DoorAccessEdit edit, Function<Boolean, MenuResult> onSaved) {
        UUID doorId = DoorRoutes.doorId(context.route());
        return host.edit(context.viewer(), doorId, edit).handle((changed, failure) -> {
            if (failure == null) {
                return onSaved.apply(Boolean.TRUE.equals(changed));
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            host.saveFailed(doorId, cause);
            return MenuResult.stay(chat(context.viewer(), WormholesMessages.DOOR_ACCESS_SAVE_FAILED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        });
    }

    private MenuDecision manage(MenuContext context, MenuAction action) {
        if (host.canManage(context.viewer(), DoorRoutes.doorId(context.route()))) {
            return MenuDecision.allow();
        }
        return MenuDecision.denyAndClose(chat(context.viewer(), WormholesMessages.DOOR_ACCESS_EDIT_DENIED, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
    }

    private MenuDecision projectionEnabled(MenuContext context, MenuAction action) {
        if (host.projectionEnabled()) {
            return MenuDecision.allow();
        }
        return MenuDecision.deny(chat(context.viewer(), DoorViewMessages.DISABLED, MessageArgs.empty(), MenuFeedbackStyle.WARNING));
    }

    private DoorAccessMenuView require(MenuContext context) {
        UUID doorId = DoorRoutes.doorId(context.route());
        return host.door(doorId).orElseThrow(() -> new IllegalStateException("door " + doorId + " has no access record"));
    }

    private String playerLabel(MenuStrings strings, UUID playerId) {
        Optional<String> name = host.playerName(playerId);
        if (name.isPresent() && !name.get().isBlank()) {
            return name.get();
        }
        return PortalMenuText.plain(strings, WormholesMessages.DOOR_ACCESS_UNKNOWN_PLAYER,
            args("id", playerId.toString().substring(0, SHORT_ID_LENGTH)));
    }

    static Addition resolveAddition(DoorAccessRecord record, UUID resolvedId) {
        if (resolvedId == null) {
            return Addition.NOT_FOUND;
        }
        if (record.ownerId().equals(resolvedId)) {
            return Addition.OWNER;
        }
        return record.isListed(resolvedId) ? Addition.ALREADY_LISTED : Addition.ADD;
    }

    private static int count(DoorAccessRecord record, DoorAccessState state) {
        int total = 0;
        for (DoorAccessState listed : record.players().values()) {
            if (listed == state) {
                total++;
            }
        }
        return total;
    }

    private static ButtonElement pageButton(MenuStrings strings, String id, LinesKey key, MenuSlot slot) {
        return new ButtonElement(common(id, lines(strings, key, MessageArgs.empty()), "minecraft:arrow", null, slot)
            .interactions(PRESS).nativeHidden(true).build());
    }

    private static String stateIcon(DoorAccessState state) {
        return switch (state) {
            case NEUTRAL -> "minecraft:black_stained_glass_pane";
            case WHITELIST -> "minecraft:green_stained_glass_pane";
            case BLACKLIST -> "minecraft:red_stained_glass_pane";
        };
    }

    private static String stateLabel(MenuStrings strings, DoorAccessState state) {
        TextKey key = switch (state) {
            case NEUTRAL -> WormholesMessages.DOOR_ACCESS_LABEL_NEUTRAL;
            case WHITELIST -> WormholesMessages.DOOR_ACCESS_LABEL_WHITELIST;
            case BLACKLIST -> WormholesMessages.DOOR_ACCESS_LABEL_BLACKLIST;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String kindLabel(MenuStrings strings, DoorKind kind) {
        TextKey key = switch (kind) {
            case PAIR -> WormholesMessages.DOOR_ACCESS_KIND_PAIR;
            case PERSONAL -> WormholesMessages.DOOR_ACCESS_KIND_PERSONAL;
            case PUBLIC -> WormholesMessages.DOOR_ACCESS_KIND_PUBLIC;
            case RETURN -> WormholesMessages.DOOR_ACCESS_KIND_RETURN;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String openStateLabel(MenuStrings strings, DoorOpenState state) {
        return PortalMenuText.plain(strings, state == DoorOpenState.OPEN ? WormholesMessages.DOOR_OPEN_STATE_OPEN : WormholesMessages.DOOR_OPEN_STATE_CLOSED);
    }

    private static List<String> projectionLabels(MenuStrings strings) {
        DoorProjectionState[] states = DoorProjectionState.values();
        List<String> labels = new ArrayList<>(states.length);
        for (DoorProjectionState state : states) {
            labels.add(projectionLabel(strings, state));
        }
        return labels;
    }

    private static String projectionLabel(MenuStrings strings, DoorProjectionState state) {
        TextKey key = switch (state) {
            case INHERIT -> DoorViewMessages.STATE_INHERIT;
            case ON -> DoorViewMessages.STATE_ON;
            case OFF -> DoorViewMessages.STATE_OFF;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static TextKey projectionNotice(DoorProjectionState state) {
        return switch (state) {
            case INHERIT -> DoorViewMessages.TOGGLE_INHERIT;
            case ON -> DoorViewMessages.TOGGLE_ON;
            case OFF -> DoorViewMessages.TOGGLE_OFF;
        };
    }

    private static MenuNotice chat(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return MenuNotices.of(viewer, key, arguments, style, NoticeChannel.CHAT);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, String item, String sprite, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(MenuIcon.item(item))
            .nativeIcon(sprite == null ? null : MenuIcon.sprite(sprite))
            .chestSlot(slot);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    enum Addition {
        NOT_FOUND,
        OWNER,
        ALREADY_LISTED,
        ADD
    }
}
