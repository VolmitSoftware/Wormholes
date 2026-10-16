package art.arcane.wormholes.menu.cost;

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
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuActionOrigin;
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
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.portal.TravelCurrencyAmount;

public final class CostMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(WormholesMessages.PORTAL_MENU_COST_PLACARD, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_MODE_FREE, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_MODE_VANILLA, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_MODE_VAULT, 1),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_ITEM, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_VAULT_AMOUNT, 2),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_FREE_DETAIL, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_SECONDARY_EMPTY, 0),
        Map.entry(WormholesMessages.PORTAL_MENU_COST_QUANTITY, 5),
        Map.entry(WormholesMessages.PORTAL_MENU_BACK_SETTINGS, 0));

    private static final int QUANTITY_STEP = 1;
    private static final int QUANTITY_BIG_STEP = 8;
    private static final int MAX_ICON_COUNT = 64;
    private static final String SPRITE = "cost";
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> PRESS_OR_CLEAR = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final Set<MenuInteraction> TEXT_OR_CLEAR = Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY);
    private static final Set<MenuInteraction> STEPS = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);
    private static final MenuIcon CLEAR_ICON = MenuIcon.sprite("small/delete");

    private final PortalMenuHost portals;
    private final CostMenuHost costs;

    public CostMenuController(PortalMenuHost portals, CostMenuHost costs) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.costs = Objects.requireNonNull(costs, "costs");
    }

    @Override
    public String id() {
        return PortalRoutes.COST_CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        if (portals.portal(portalId).isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        PortalMenuView portal = require(context);
        UUID portalId = portal.id();
        MenuStrings strings = context.strings();
        PortalMenuView.Cost cost = portal.cost();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<MenuElement> elements = new ArrayList<>(7);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(new LabelElement(common("travel-cost-placard", lines(strings, WormholesMessages.PORTAL_MENU_COST_PLACARD, MessageArgs.builder()
            .untrusted("mode", modeLabel(strings, cost.mode()))
            .untrusted("cost", summary(strings, cost))
            .build()), MenuIcon.item("minecraft:chest"), SPRITE, new MenuSlot(0, 0)).build()));

        boolean free = cost.mode() == PortalMenuView.CostMode.FREE;
        elements.add(new ListEntryElement(common("travel-cost-free", lines(strings, WormholesMessages.PORTAL_MENU_COST_MODE_FREE, MessageArgs.empty()),
            MenuIcon.item("minecraft:feather", 1, free), SPRITE, new MenuSlot(-2, 1)).interactions(PRESS).build(), free));
        bindings.guard("travel-cost-free", manage)
            .on("travel-cost-free", MenuInteraction.PRIMARY, (ctx, action) -> chooseFree(ctx, portalId));

        boolean vanilla = cost.mode() == PortalMenuView.CostMode.VANILLA;
        elements.add(new ItemCaptureElement(common("travel-cost-vanilla", lines(strings, WormholesMessages.PORTAL_MENU_COST_MODE_VANILLA,
            MessageArgs.empty()), vanilla ? MenuIcon.template(cost.itemId(), 1, true, cost.templateRef()) : MenuIcon.item("minecraft:hopper"),
            vanilla ? null : SPRITE, new MenuSlot(0, 1)).interactions(PRESS).build(), itemPrompt(strings)));
        bindings.guard("travel-cost-vanilla", manage.and(captureCompletion(portalId)))
            .on("travel-cost-vanilla", MenuInteraction.PRIMARY, (ctx, action) -> capture(ctx, portalId));

        boolean available = costs.currencyAvailable();
        boolean vault = cost.mode() == PortalMenuView.CostMode.VAULT;
        elements.add(currencyElement("travel-cost-vault", lines(strings, available ? WormholesMessages.PORTAL_MENU_COST_MODE_VAULT
            : WormholesMessages.PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE, MessageArgs.empty()),
            MenuIcon.item(available ? "minecraft:emerald" : "minecraft:redstone", 1, vault), new MenuSlot(2, 1), TEXT, strings,
            vault ? cost.vaultAmount() : ""));
        bindings.guard("travel-cost-vault", manage.and(currencyAvailable()))
            .on("travel-cost-vault", MenuInteraction.SET_TEXT, (ctx, action) -> setCurrency(ctx, portalId, action));

        detailPrimary(elements, bindings, manage, strings, portal);
        detailSecondary(elements, bindings, manage, strings, portal);

        elements.add(new ButtonElement(common("back-to-settings", lines(strings, WormholesMessages.PORTAL_MENU_BACK_SETTINGS, MessageArgs.empty()),
            MenuIcon.item("minecraft:arrow"), null, new MenuSlot(0, 3)).interactions(PRESS).nativeHidden(true).build()));
        bindings.guard("back-to-settings", manage)
            .on("back-to-settings", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.navigate(PortalRoutes.settings(portalId),
                NavigateMode.POP_TO, null));

        return new MenuBuild(screen(strings, portal, elements), new ChestLayout(portal.routerTitle(), 4, ChestPane.BROWN, null), bindings.build());
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(PortalRoutes.portalId(route));
    }

    private void detailPrimary(List<MenuElement> elements, MenuBindings.Builder bindings, MenuGuard manage, MenuStrings strings,
                               PortalMenuView portal) {
        UUID portalId = portal.id();
        PortalMenuView.Cost cost = portal.cost();
        MenuSlot slot = new MenuSlot(-1, 2);
        switch (cost.mode()) {
            case VANILLA -> {
                elements.add(new ItemCaptureElement(common("travel-cost-item", lines(strings, WormholesMessages.PORTAL_MENU_COST_ITEM,
                    args("item", cost.itemLabel())), MenuIcon.template(cost.itemId(), 1, false, cost.templateRef()), null, slot)
                    .interactions(PRESS_OR_CLEAR)
                    .sideActions(clearAction(strings))
                    .build(), itemPrompt(strings)));
                bindings.guard("travel-cost-item", manage.and(captureCompletion(portalId)))
                    .on("travel-cost-item", MenuInteraction.PRIMARY, (ctx, action) -> capture(ctx, portalId))
                    .on("travel-cost-item", MenuInteraction.SECONDARY, (ctx, action) -> clear(ctx, portalId));
            }
            case VAULT -> {
                elements.add(currencyElement("travel-cost-vault-amount", lines(strings, WormholesMessages.PORTAL_MENU_COST_VAULT_AMOUNT,
                    args("amount", cost.vaultAmount())), MenuIcon.item("minecraft:gold_ingot"), slot, TEXT_OR_CLEAR, strings, cost.vaultAmount()));
                bindings.guard("travel-cost-vault-amount", manage)
                    .on("travel-cost-vault-amount", MenuInteraction.SET_TEXT, (ctx, action) -> setCurrency(ctx, portalId, action))
                    .on("travel-cost-vault-amount", MenuInteraction.SECONDARY, (ctx, action) -> clear(ctx, portalId));
            }
            case FREE -> elements.add(new LabelElement(common("travel-cost-free-detail", lines(strings,
                WormholesMessages.PORTAL_MENU_COST_FREE_DETAIL, MessageArgs.empty()), MenuIcon.item("minecraft:white_stained_glass_pane"), null, slot)
                .nativeHidden(true)
                .build()));
        }
    }

    private void detailSecondary(List<MenuElement> elements, MenuBindings.Builder bindings, MenuGuard manage, MenuStrings strings,
                                 PortalMenuView portal) {
        PortalMenuView.Cost cost = portal.cost();
        MenuSlot slot = new MenuSlot(1, 2);
        if (cost.mode() != PortalMenuView.CostMode.VANILLA) {
            elements.add(new LabelElement(common("travel-cost-secondary-empty", lines(strings, WormholesMessages.PORTAL_MENU_COST_SECONDARY_EMPTY,
                MessageArgs.empty()), MenuIcon.item("minecraft:brown_stained_glass_pane"), null, slot).nativeHidden(true).build()));
            return;
        }
        UUID portalId = portal.id();
        int quantity = ExactItemPayment.clampQuantity(cost.quantity());
        elements.add(new NumberElement(common("travel-cost-quantity", lines(strings, WormholesMessages.PORTAL_MENU_COST_QUANTITY,
            MessageArgs.builder()
                .untrusted("quantity", quantity)
                .untrusted("maximum", ExactItemPayment.MAX_QUANTITY)
                .build()), MenuIcon.item("minecraft:chest", Math.min(quantity, MAX_ICON_COUNT), false), SPRITE, slot).interactions(STEPS).build(),
            quantity, 1, ExactItemPayment.MAX_QUANTITY, QUANTITY_STEP, QUANTITY_BIG_STEP, Integer.toString(quantity)));
        bindings.guard("travel-cost-quantity", manage)
            .on("travel-cost-quantity", MenuInteraction.INCREMENT, (ctx, action) -> stepQuantity(ctx, portalId, QUANTITY_STEP))
            .on("travel-cost-quantity", MenuInteraction.DECREMENT, (ctx, action) -> stepQuantity(ctx, portalId, -QUANTITY_STEP))
            .on("travel-cost-quantity", MenuInteraction.BIG_INCREMENT, (ctx, action) -> stepQuantity(ctx, portalId, QUANTITY_BIG_STEP))
            .on("travel-cost-quantity", MenuInteraction.BIG_DECREMENT, (ctx, action) -> stepQuantity(ctx, portalId, -QUANTITY_BIG_STEP))
            .on("travel-cost-quantity", MenuInteraction.SET_VALUE, (ctx, action) -> setQuantity(ctx, portalId, action));
    }

    private MenuResult chooseFree(MenuContext context, UUID portalId) {
        if (require(context).cost().mode() == PortalMenuView.CostMode.FREE) {
            return MenuResult.stay();
        }
        return clear(context, portalId);
    }

    private MenuResult clear(MenuContext context, UUID portalId) {
        costs.edit(context.viewer(), portalId, new CostEdit.Clear());
        return MenuResult.stay(PortalMenuText.settingNotice(context.viewer(), require(context), WormholesMessages.PORTAL_COST_CLEARED,
            MessageArgs.empty(), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult capture(MenuContext context, UUID portalId) {
        MenuViewer viewer = context.viewer();
        CapturedItem item = context.capturedItem();
        if (item == null || !costs.edit(viewer, portalId, new CostEdit.Item(item))) {
            return MenuResult.stay(hud(viewer, WormholesMessages.PORTAL_COST_ITEM_INVALID, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
        }
        String label = Optional.ofNullable(require(context).cost().itemLabel()).orElse(item.label());
        return MenuResult.stay(hud(viewer, WormholesMessages.PORTAL_COST_ITEM_SET, args("item", label), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult setCurrency(MenuContext context, UUID portalId, MenuAction action) {
        MenuViewer viewer = context.viewer();
        String amount = ((MenuValue.Text) action.value()).text().trim();
        if (!costs.edit(viewer, portalId, new CostEdit.Currency(amount))) {
            return MenuResult.stay(PortalMenuText.settingNotice(viewer, require(context), WormholesMessages.PORTAL_COST_VAULT_INVALID,
                args("maximum", TravelCurrencyAmount.MAX_AMOUNT.toPlainString()), MenuFeedbackStyle.WARNING));
        }
        PortalMenuView updated = require(context);
        return MenuResult.stay(PortalMenuText.settingNotice(viewer, updated, WormholesMessages.PORTAL_COST_VAULT_CHANGED,
            args("amount", updated.cost().vaultAmount()), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult stepQuantity(MenuContext context, UUID portalId, int delta) {
        PortalMenuView.Cost previous = require(context).cost();
        if (previous.mode() != PortalMenuView.CostMode.VANILLA) {
            return MenuResult.stay();
        }
        return applyQuantity(context, portalId, previous, previous.quantity() + delta);
    }

    private MenuResult setQuantity(MenuContext context, UUID portalId, MenuAction action) {
        PortalMenuView.Cost previous = require(context).cost();
        if (previous.mode() != PortalMenuView.CostMode.VANILLA) {
            return MenuResult.stay();
        }
        return applyQuantity(context, portalId, previous, (int) Math.round(((MenuValue.Number) action.value()).number()));
    }

    private MenuResult applyQuantity(MenuContext context, UUID portalId, PortalMenuView.Cost previous, int requested) {
        MenuViewer viewer = context.viewer();
        costs.edit(viewer, portalId, new CostEdit.Quantity(ExactItemPayment.clampQuantity(requested)));
        PortalMenuView updated = require(context);
        if (updated.cost().quantity() == previous.quantity()) {
            return MenuResult.stay();
        }
        return MenuResult.stay(PortalMenuText.settingNotice(viewer, updated, WormholesMessages.PORTAL_COST_QUANTITY_CHANGED,
            args("quantity", updated.cost().quantity()), MenuFeedbackStyle.SUCCESS));
    }

    private MenuGuard captureCompletion(UUID portalId) {
        return (context, action) -> action.interaction() != MenuInteraction.PRIMARY || prompting(context)
            || portals.ownerOrAdmin(context.viewer(), portalId)
            ? MenuDecision.allow()
            : MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
    }

    private MenuGuard currencyAvailable() {
        return (context, action) -> costs.currencyAvailable()
            ? MenuDecision.allow()
            : MenuDecision.deny(PortalMenuText.settingNotice(context.viewer(), require(context), WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE_NOTICE,
                MessageArgs.empty(), MenuFeedbackStyle.WARNING));
    }

    private TextElement currencyElement(String id, MenuLines lines, MenuIcon icon, MenuSlot slot, Set<MenuInteraction> interactions,
                                        MenuStrings strings, String value) {
        MenuElementCommon.Builder common = common(id, lines, icon, SPRITE, slot).interactions(interactions);
        if (interactions.contains(MenuInteraction.SECONDARY)) {
            common.sideActions(clearAction(strings));
        }
        return new TextElement(common.build(), value, MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_AMOUNT),
            List.of(strings.text(WormholesMessages.PORTAL_PROMPT_COST_VAULT,
                args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL)))), ChestReopen.ALWAYS, null);
    }

    private MenuScreen screen(MenuStrings strings, PortalMenuView portal, List<MenuElement> elements) {
        return MenuScreen.builder(PortalRoutes.COST_MAIN)
            .title(strings.text(MenuMessages.TITLE_COST))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(elements)
            .build();
    }

    private PortalMenuView require(MenuContext context) {
        UUID portalId = PortalRoutes.portalId(context.route());
        return portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
    }

    private static boolean prompting(MenuContext context) {
        return context.origin() == MenuActionOrigin.CHEST && context.capturedItem() == null;
    }

    private static List<String> itemPrompt(MenuStrings strings) {
        return List.of(strings.text(WormholesMessages.PORTAL_PROMPT_COST_ITEM));
    }

    private static List<MenuSideAction> clearAction(MenuStrings strings) {
        return List.of(new MenuSideAction(MenuInteraction.SECONDARY, strings.text(MenuMessages.ACTION_CLEAR), CLEAR_ICON));
    }

    private static MenuNotice hud(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return MenuNotices.of(viewer, key, arguments, style, NoticeChannel.HUD);
    }

    private static String modeLabel(MenuStrings strings, PortalMenuView.CostMode mode) {
        TextKey key = switch (mode) {
            case FREE -> WormholesMessages.PORTAL_LABEL_COST_FREE;
            case VANILLA -> WormholesMessages.PORTAL_LABEL_COST_VANILLA;
            case VAULT -> WormholesMessages.PORTAL_LABEL_COST_VAULT;
        };
        return PortalMenuText.plain(strings, key);
    }

    private static String summary(MenuStrings strings, PortalMenuView.Cost cost) {
        return switch (cost.mode()) {
            case FREE -> PortalMenuText.plain(strings, WormholesMessages.PORTAL_LABEL_COST_FREE);
            case VANILLA -> cost.quantity() + "x " + cost.itemLabel();
            case VAULT -> cost.vaultAmount();
        };
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
}
