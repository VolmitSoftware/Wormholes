package art.arcane.wormholes.menu.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.localization.AccessMessages;
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

public final class AccessMenuController implements MenuController {
    public static final int ENTRIES_PER_PAGE = 225;
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(AccessMessages.MENU_PLACARD, 0),
        Map.entry(AccessMessages.MENU_ADD, 0),
        Map.entry(AccessMessages.MENU_KEY, 1),
        Map.entry(AccessMessages.MENU_GROUPS, 1),
        Map.entry(AccessMessages.MENU_LISTED, 1),
        Map.entry(AccessMessages.MENU_ROLE, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, 1),
        Map.entry(MenuMessages.LIST_PAGE, 0));

    private static final int ROW_WIDTH = 9;
    private static final int MAX_VIEWPORT_HEIGHT = 6;
    private static final int SHORT_ID_LENGTH = 8;
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final Set<MenuInteraction> ROLE = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);
    private static final MenuIcon REMOVE_ICON = MenuIcon.sprite("small/delete");

    private final AccessMenuHost host;

    public AccessMenuController(AccessMenuHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public String id() {
        return AccessRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = AccessRoutes.portalId(context.route());
        if (host.access(portalId).isEmpty() || !host.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        MenuStrings strings = context.strings();
        AccessMenuView view = require(context);
        UUID portalId = view.portalId();
        MenuGuard manage = this::manage;
        List<AccessMenuView.Role> roles = view.roles();
        int pageCount = Math.max(1, (roles.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        int page = Math.min(context.page(), pageCount - 1);
        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(roles.size(), start + ENTRIES_PER_PAGE);
        boolean paged = pageCount > 1;
        List<MenuElement> elements = new ArrayList<>(end - start + 8);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("access-placard", lines(strings, AccessMessages.MENU_PLACARD, MessageArgs.builder()
            .untrusted("portal", view.portalName())
            .untrusted("owner", ownerLabel(strings, view))
            .untrusted("count", roles.size())
            .untrusted("key", view.permissionKey())
            .build()), "minecraft:name_tag", "access", new MenuSlot(-4, 0)).build()));

        elements.add(prompt(strings, common("access-add", lines(strings, AccessMessages.MENU_ADD, MessageArgs.empty()), "minecraft:writable_book",
            "player", new MenuSlot(-1, 0)).interactions(TEXT).build(), "", MenuMessages.PLACEHOLDER_PLAYER, AccessMessages.PROMPT_PLAYER));
        bindings.guard("access-add", manage).on("access-add", MenuInteraction.SET_TEXT, this::addPlayer);

        elements.add(prompt(strings, common("access-key", lines(strings, AccessMessages.MENU_KEY, args("key", view.permissionKey())),
            "minecraft:tripwire_hook", "access", new MenuSlot(0, 0)).interactions(TEXT).build(), view.permissionKey(),
            MenuMessages.PLACEHOLDER_KEY, AccessMessages.PROMPT_KEY));
        bindings.guard("access-key", manage).on("access-key", MenuInteraction.SET_TEXT, this::setKey);

        elements.add(prompt(strings, common("access-groups", lines(strings, AccessMessages.MENU_GROUPS, args("count", view.groupCount())),
            "minecraft:book", "access", new MenuSlot(1, 0)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.SET_TEXT))
                .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY)))
            .interactions(Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_CLEAR), REMOVE_ICON)))
            .build(), "", MenuMessages.PLACEHOLDER_GROUP, AccessMessages.PROMPT_GROUP));
        bindings.guard("access-groups", manage)
            .on("access-groups", MenuInteraction.SET_TEXT, this::addGroup)
            .on("access-groups", MenuInteraction.SECONDARY, this::clearGroups);

        elements.add(new ToggleElement(common("access-listed", lines(strings, AccessMessages.MENU_LISTED,
            args("state", listedLabel(strings, view.listed()))), view.listed() ? "minecraft:ender_eye" : "minecraft:ender_pearl", "atlas",
            new MenuSlot(2, 0)).interactions(PRESS).build(), view.listed()));
        bindings.guard("access-listed", manage).on("access-listed", MenuInteraction.PRIMARY, this::toggleListed);

        List<String> roleOptions = roleLabels(strings);
        for (int index = start; index < end; index++) {
            AccessMenuView.Role entry = roles.get(index);
            int slot = index - start;
            UUID listed = entry.playerId();
            String id = "access-role-" + listed;
            elements.add(new CycleElement(common(id, lines(strings, AccessMessages.MENU_ROLE, MessageArgs.builder()
                    .untrusted("name", playerLabel(strings, listed))
                    .untrusted("state", roleLabel(strings, entry.role()))
                    .build()), roleIcon(entry.role()), "player", new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, 1 + slot / ROW_WIDTH)
                    .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                    .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY))
                    .withClick(ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY))
                    .withClick(ChestClick.MIDDLE, ChestTarget.self(MenuInteraction.TERTIARY)))
                .interactions(ROLE)
                .sideActions(List.of(new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_REMOVE), REMOVE_ICON)))
                .build(), roleOptions, entry.role().ordinal()));
            bindings.guard(id, manage)
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> cycleRole(ctx, listed, true))
                .on(id, MenuInteraction.SECONDARY, (ctx, action) -> cycleRole(ctx, listed, false))
                .on(id, MenuInteraction.TERTIARY, (ctx, action) -> removeRole(ctx, listed));
        }

        int entryRows = (end - start + ROW_WIDTH - 1) / ROW_WIDTH;
        if (paged) {
            int pagingRow = 1 + entryRows;
            if (page > 0) {
                elements.add(pageButton(strings, "access-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, new MenuSlot(-4, pagingRow)));
                bindings.guard("access-previous", manage).on("access-previous", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page - 1));
            }
            elements.add(new LabelElement(common("access-page", lines(strings, MenuMessages.LIST_PAGE, MessageArgs.builder()
                .untrusted("page", page + 1)
                .untrusted("pages", pageCount)
                .untrusted("count", roles.size())
                .build()), "minecraft:paper", null, new MenuSlot(0, pagingRow)).nativeHidden(true).build()));
            if (page + 1 < pageCount) {
                elements.add(pageButton(strings, "access-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, new MenuSlot(4, pagingRow)));
                bindings.guard("access-next", manage).on("access-next", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(page + 1));
            }
        }

        MenuScreen screen = MenuScreen.builder(AccessRoutes.ROLES)
            .title(strings.text(MenuMessages.TITLE_ACCESS))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", view.portalName())
                .untrusted("type", view.mirror()
                    ? PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_MIRROR)
                    : PortalMenuText.type(strings, view.type()))
                .build()))
            .elements(elements)
            .paging(paged ? new MenuPaging(page, pageCount) : null)
            .build();
        int rows = Math.min(MAX_VIEWPORT_HEIGHT, 1 + entryRows + (paged ? 1 : 0));
        return new MenuBuild(screen, new ChestLayout(view.routerTitle(), rows, ChestPane.GRAY, null), bindings.build());
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(AccessRoutes.portalId(route));
    }

    private MenuResult addPlayer(MenuContext context, MenuAction action) {
        String name = text(action);
        CompletableFuture<MenuResult> result = host.resolvePlayer(name).thenApply(resolved -> addResolved(context, name, resolved));
        return MenuResult.pending(result);
    }

    private MenuResult addResolved(MenuContext context, String name, Optional<UUID> resolved) {
        MenuViewer viewer = context.viewer();
        MenuDecision decision = routeGuard(context);
        if (decision instanceof MenuDecision.Deny deny) {
            return MenuResult.close(deny.notice());
        }
        AccessMenuView view = require(context);
        return switch (resolveAddition(view, resolved.orElse(null))) {
            case NOT_FOUND -> MenuResult.stay(chat(viewer, AccessMessages.PLAYER_NOT_FOUND, args("name", name), MenuFeedbackStyle.ERROR));
            case OWNER -> MenuResult.stay(chat(viewer, AccessMessages.ROLE_OWNER_ALWAYS, MessageArgs.empty(), MenuFeedbackStyle.INFO));
            case ALREADY_LISTED -> MenuResult.stay(roleChanged(context, view, resolved.get(), view.role(resolved.get()), MenuFeedbackStyle.INFO));
            case ADD -> host.edit(viewer, view.portalId(), new AccessEdit.SetRole(resolved.get(), PortalRole.USER))
                ? MenuResult.stay(roleChanged(context, view, resolved.get(), PortalRole.USER, MenuFeedbackStyle.SUCCESS))
                : MenuResult.stay();
        };
    }

    private MenuResult setKey(MenuContext context, MenuAction action) {
        MenuViewer viewer = context.viewer();
        String requested = text(action);
        UUID portalId = AccessRoutes.portalId(context.route());
        AccessKeyOutcome outcome = host.setPermissionKey(viewer, portalId, requested);
        MenuNotice notice = switch (outcome) {
            case INVALID -> chat(viewer, AccessMessages.KEY_INVALID, MessageArgs.empty(), MenuFeedbackStyle.ERROR);
            case TAKEN -> chat(viewer, AccessMessages.KEY_TAKEN, args("key", requested), MenuFeedbackStyle.ERROR);
            case SET, UNCHANGED -> {
                AccessMenuView view = require(context);
                yield chat(viewer, AccessMessages.KEY_SET, MessageArgs.builder()
                    .untrusted("portal", view.portalName())
                    .untrusted("key", view.permissionKey())
                    .build(), MenuFeedbackStyle.SUCCESS);
            }
        };
        return MenuResult.stay(notice);
    }

    private MenuResult addGroup(MenuContext context, MenuAction action) {
        String node = text(action);
        if (node.isEmpty()) {
            return MenuResult.stay();
        }
        AccessMenuView view = require(context);
        if (!host.edit(context.viewer(), view.portalId(), new AccessEdit.AddGroup(node))) {
            return MenuResult.stay();
        }
        return MenuResult.stay(chat(context.viewer(), AccessMessages.GROUP_ADDED, MessageArgs.builder()
            .untrusted("portal", view.portalName())
            .untrusted("node", node)
            .build(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult clearGroups(MenuContext context, MenuAction action) {
        AccessMenuView view = require(context);
        if (!host.edit(context.viewer(), view.portalId(), new AccessEdit.ClearGroups())) {
            return MenuResult.stay();
        }
        return MenuResult.stay(chat(context.viewer(), AccessMessages.GROUP_CLEARED, args("portal", view.portalName()), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult toggleListed(MenuContext context, MenuAction action) {
        AccessMenuView view = require(context);
        boolean listed = !view.listed();
        if (!host.edit(context.viewer(), view.portalId(), new AccessEdit.Listed(listed))) {
            return MenuResult.stay();
        }
        return MenuResult.stay(chat(context.viewer(), AccessMessages.LISTED_CHANGED, MessageArgs.builder()
            .untrusted("portal", view.portalName())
            .untrusted("state", listedLabel(context.strings(), listed))
            .build(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult cycleRole(MenuContext context, UUID listed, boolean forward) {
        AccessMenuView view = require(context);
        PortalRole current = view.role(listed);
        if (current == null) {
            return MenuResult.stay();
        }
        PortalRole next = forward ? current.next() : current.previous();
        if (!host.edit(context.viewer(), view.portalId(), new AccessEdit.SetRole(listed, next))) {
            return MenuResult.stay();
        }
        return MenuResult.stay(roleChanged(context, view, listed, next, MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult removeRole(MenuContext context, UUID listed) {
        AccessMenuView view = require(context);
        if (view.role(listed) == null || !host.edit(context.viewer(), view.portalId(), new AccessEdit.RemoveRole(listed))) {
            return MenuResult.stay();
        }
        return MenuResult.stay(chat(context.viewer(), AccessMessages.ROLE_REMOVED, MessageArgs.builder()
            .untrusted("name", playerLabel(context.strings(), listed))
            .untrusted("portal", view.portalName())
            .build(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuNotice roleChanged(MenuContext context, AccessMenuView view, UUID playerId, PortalRole role, MenuFeedbackStyle style) {
        MenuStrings strings = context.strings();
        return chat(context.viewer(), AccessMessages.ROLE_CHANGED, MessageArgs.builder()
            .untrusted("name", playerLabel(strings, playerId))
            .untrusted("state", roleLabel(strings, role))
            .untrusted("portal", view.portalName())
            .build(), style);
    }

    private MenuDecision manage(MenuContext context, MenuAction action) {
        if (host.canManage(context.viewer(), AccessRoutes.portalId(context.route()))) {
            return MenuDecision.allow();
        }
        return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
    }

    private AccessMenuView require(MenuContext context) {
        UUID portalId = AccessRoutes.portalId(context.route());
        return host.access(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " has no access state"));
    }

    private String ownerLabel(MenuStrings strings, AccessMenuView view) {
        UUID owner = view.owner();
        if (owner == null || owner.equals(view.portalId())) {
            return PortalMenuText.plain(strings, WormholesMessages.LABEL_NONE);
        }
        return playerLabel(strings, owner);
    }

    private String playerLabel(MenuStrings strings, UUID playerId) {
        Optional<String> name = host.playerName(playerId);
        if (name.isPresent() && !name.get().isBlank()) {
            return name.get();
        }
        return PortalMenuText.plain(strings, WormholesMessages.DOOR_ACCESS_UNKNOWN_PLAYER,
            args("id", playerId.toString().substring(0, SHORT_ID_LENGTH)));
    }

    static Addition resolveAddition(AccessMenuView view, UUID resolvedId) {
        if (resolvedId == null) {
            return Addition.NOT_FOUND;
        }
        if (resolvedId.equals(view.owner())) {
            return Addition.OWNER;
        }
        return view.role(resolvedId) == null ? Addition.ADD : Addition.ALREADY_LISTED;
    }

    private static TextElement prompt(MenuStrings strings, MenuElementCommon common, String value, TextKey placeholder, TextKey prompt) {
        return new TextElement(common, clip(value), MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(placeholder),
            List.of(strings.text(prompt, args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL)))),
            ChestReopen.ALWAYS, null);
    }

    private static ButtonElement pageButton(MenuStrings strings, String id, LinesKey key, MenuSlot slot) {
        return new ButtonElement(common(id, lines(strings, key, MessageArgs.empty()), "minecraft:arrow", null, slot)
            .interactions(PRESS).nativeHidden(true).build());
    }

    private static List<String> roleLabels(MenuStrings strings) {
        PortalRole[] roles = PortalRole.values();
        List<String> labels = new ArrayList<>(roles.length);
        for (PortalRole role : roles) {
            labels.add(roleLabel(strings, role));
        }
        return labels;
    }

    private static String roleLabel(MenuStrings strings, PortalRole role) {
        TextKey key = switch (role) {
            case OWNER -> AccessMessages.LABEL_OWNER;
            case CO_OWNER -> AccessMessages.LABEL_CO_OWNER;
            case USER -> AccessMessages.LABEL_USER;
            case DENIED -> AccessMessages.LABEL_DENIED;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String roleIcon(PortalRole role) {
        return switch (role) {
            case OWNER -> "minecraft:golden_helmet";
            case CO_OWNER -> "minecraft:iron_helmet";
            case USER -> "minecraft:lime_stained_glass_pane";
            case DENIED -> "minecraft:red_stained_glass_pane";
        };
    }

    private static String listedLabel(MenuStrings strings, boolean listed) {
        return PortalMenuText.plain(strings, listed ? AccessMessages.LABEL_LISTED : AccessMessages.LABEL_UNLISTED);
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

    private static String text(MenuAction action) {
        return ((MenuValue.Text) action.value()).text().trim();
    }

    private static String clip(String value) {
        if (value.codePointCount(0, value.length()) <= MenuLimits.MAX_TEXT_INPUT_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MenuLimits.MAX_TEXT_INPUT_LENGTH));
    }

    enum Addition {
        NOT_FOUND,
        OWNER,
        ALREADY_LISTED,
        ADD
    }
}
