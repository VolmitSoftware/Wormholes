package art.arcane.wormholes.menu.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestConfirmLayout;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuConfirmRequest;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuHandler;
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
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuText;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleAuthoring;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RuleValidationException;
import art.arcane.wormholes.rules.RulesMenuModel;
import art.arcane.wormholes.rules.TraversalProfile;

public final class RulesMenuController implements MenuController {
    public static final Map<LinesKey, Integer> CHEST_HINTS = Map.ofEntries(
        Map.entry(RulesMessages.MENU_COOLDOWN, 1),
        Map.entry(RulesMessages.MENU_WARMUP, 1),
        Map.entry(RulesMessages.MENU_GROUP, 1),
        Map.entry(RulesMessages.MENU_PUSHBACK, 1),
        Map.entry(RulesMessages.MENU_SOUND, 1),
        Map.entry(RulesMessages.MENU_CHARGES, 1),
        Map.entry(RulesMessages.MENU_DEFAULT_OUTCOME, 1),
        Map.entry(RulesMessages.MENU_RULES, 1),
        Map.entry(RulesMessages.MENU_RULE, 1),
        Map.entry(RulesMessages.MENU_ADD_RULE, 1),
        Map.entry(RulesMessages.MENU_LINE, 1),
        Map.entry(RulesMessages.MENU_ADD_LINE, 1),
        Map.entry(RulesMessages.MENU_TEMPLATES, 1),
        Map.entry(RulesMessages.MENU_TEMPLATE, 1),
        Map.entry(RulesMessages.MENU_BACK, 0),
        Map.entry(RulesMessages.MENU_PAGE, 1));
    public static final String LIST_WINDOW_KEY = RulesRoutes.LIST;

    private static final String COOLDOWN = "rules-" + RulesMessages.MENU_COOLDOWN.id();
    private static final String WARMUP = "rules-" + RulesMessages.MENU_WARMUP.id();
    private static final String PUSHBACK = "rules-" + RulesMessages.MENU_PUSHBACK.id();
    private static final String SOUND = "rules-" + RulesMessages.MENU_SOUND.id();
    private static final String GROUP = "rules-group";
    private static final String CHARGES = "rules-charges";
    private static final String DEFAULT_OUTCOME = "rules-default";
    private static final String LIST = "rules-list";
    private static final String TEMPLATES = "rules-templates";
    private static final String RULE_PREFIX = "rules-rule-";
    private static final String ADD_RULE = "rules-add-rule";
    private static final String LINE_PREFIX = "rules-line-";
    private static final String ADD_LINE = "rules-add-line";
    private static final String TEMPLATE_PREFIX = "rules-template-";
    private static final String PAGE = "rules-page";
    private static final String BACK = "rules-back";
    private static final int ROW_WIDTH = 9;
    private static final long SECOND = 1_000L;
    private static final long SHIFT_STEP = 10L * SECOND;
    private static final double SCALE_STEP = 0.25D;
    private static final double SCALE_MAX = 10.0D;
    private static final int CHARGES_MAX = 100_000;
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final Set<MenuInteraction> SECONDS = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);
    private static final Set<MenuInteraction> STEPS = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.SET_VALUE);
    private static final MenuIcon REMOVE_ICON = MenuIcon.sprite("small/delete");

    private final PortalMenuHost portals;
    private final RulesMenuHost rules;

    public RulesMenuController(PortalMenuHost portals, RulesMenuHost rules) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    public String id() {
        return RulesRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        UUID portalId = RulesRoutes.portalId(context.route());
        if (portals.portal(portalId).isEmpty() || !portals.canManage(context.viewer(), portalId)) {
            return MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
        }
        Optional<RulesMenuView> view = rules.rules(portalId);
        if (view.isEmpty()) {
            return MenuDecision.denyAndClose(unavailable(context.viewer()));
        }
        if (context.route().screen().equals(RulesRoutes.RULE)
            && RulesMenuModel.indexOf(view.get().document(), RulesRoutes.ruleId(context.route())) < 0) {
            return MenuDecision.deny(unavailable(context.viewer()));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        UUID portalId = RulesRoutes.portalId(context.route());
        PortalMenuView portal = portals.portal(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " is gone"));
        RulesMenuView view = rules.rules(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " has no rules"));
        return switch (context.route().screen()) {
            case RulesRoutes.PROFILE -> profile(context, portal, view);
            case RulesRoutes.LIST -> list(context, portal, view);
            case RulesRoutes.RULE -> rule(context, portal, view);
            case RulesRoutes.TEMPLATES -> templates(context, portal);
            default -> throw new IllegalArgumentException("rules menus have no screen " + context.route().screen());
        };
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(RulesRoutes.portalId(route));
    }

    private MenuBuild profile(MenuContext context, PortalMenuView portal, RulesMenuView view) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        TraversalProfile profile = view.document().profile();
        List<MenuElement> elements = new ArrayList<>(9);
        MenuBindings.Builder bindings = MenuBindings.builder();

        elements.add(seconds(strings, COOLDOWN, RulesMessages.MENU_COOLDOWN, "minecraft:clock", new MenuSlot(-3, 0), profile.cooldownMillis(),
            view.limits().cooldownMaxSeconds));
        secondsHandlers(bindings, manage, COOLDOWN, profile.cooldownMillis(), RulesEdit.Cooldown::new);
        elements.add(seconds(strings, WARMUP, RulesMessages.MENU_WARMUP, "minecraft:compass", new MenuSlot(-1, 0), profile.warmupMillis(),
            view.limits().warmupMaxSeconds));
        secondsHandlers(bindings, manage, WARMUP, profile.warmupMillis(), RulesEdit.Warmup::new);

        String group = profile.cooldownGroup();
        elements.add(text(strings, common(GROUP, lines(strings, RulesMessages.MENU_GROUP, args("value", group.isEmpty() ? "-" : group)),
            "minecraft:name_tag", null, new MenuSlot(1, 0)), clip(group, MenuLimits.MAX_TEXT_INPUT_LENGTH), MenuMessages.PLACEHOLDER_GROUP,
            RulesMessages.PROMPT_GROUP, portalId));
        bindings.guard(GROUP, manage)
            .on(GROUP, MenuInteraction.SET_TEXT, (ctx, action) -> store(ctx, new RulesEdit.Group(typed(action)), null));

        int capacity = profile.chargeCapacity();
        elements.add(new NumberElement(common(CHARGES, lines(strings, RulesMessages.MENU_CHARGES, args("count", capacity)),
            "minecraft:amethyst_shard", null, stepSlot(3, 0)).interactions(STEPS).build(),
            capacity, Math.min(0, capacity), Math.max(CHARGES_MAX, capacity), 1.0D, 1.0D, Integer.toString(capacity)));
        bindings.guard(CHARGES, manage)
            .on(CHARGES, MenuInteraction.INCREMENT, (ctx, action) -> store(ctx, new RulesEdit.Charges(capacity + 1), null))
            .on(CHARGES, MenuInteraction.DECREMENT, (ctx, action) -> store(ctx, new RulesEdit.Charges(Math.max(0, capacity - 1)), null))
            .on(CHARGES, MenuInteraction.SET_VALUE, (ctx, action) -> store(ctx,
                new RulesEdit.Charges((int) Math.round(((MenuValue.Number) action.value()).number())), null));

        elements.add(scale(strings, PUSHBACK, RulesMessages.MENU_PUSHBACK, "minecraft:piston", stepSlot(-2, 1), profile.pushbackScale()));
        scaleHandlers(bindings, manage, PUSHBACK, profile.pushbackScale(), RulesEdit.Pushback::new);
        elements.add(scale(strings, SOUND, RulesMessages.MENU_SOUND, "minecraft:note_block", stepSlot(0, 1), profile.soundVolume()));
        scaleHandlers(bindings, manage, SOUND, profile.soundVolume(), RulesEdit.Sound::new);

        RuleOutcome outcome = view.document().defaultOutcome();
        MessageArgs outcomeMode = args("mode", outcome.kind().name());
        elements.add(new ToggleElement(common(DEFAULT_OUTCOME, lines(strings, RulesMessages.MENU_DEFAULT_OUTCOME, outcomeMode),
            MenuIcon.item("minecraft:lever", 1, !outcome.allowed()), null, new MenuSlot(2, 1))
            .nativeLabel(strings.settingName(RulesMessages.MENU_DEFAULT_OUTCOME, outcomeMode, "mode")).interactions(PRESS).build(), outcome.allowed()));
        RuleOutcome flipped = outcome.allowed() ? RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()) : RuleOutcome.allow();
        bindings.guard(DEFAULT_OUTCOME, manage)
            .on(DEFAULT_OUTCOME, MenuInteraction.PRIMARY, (ctx, action) -> store(ctx, new RulesEdit.DefaultOutcome(flipped), null));

        elements.add(new ButtonElement(common(LIST, lines(strings, RulesMessages.MENU_RULES, args("count", view.document().rules().size())),
            "minecraft:book", "settings", new MenuSlot(-1, 2)).interactions(PRESS).build()));
        bindings.guard(LIST, manage).on(LIST, MenuInteraction.PRIMARY, (ctx, action) -> firstPage(RulesRoutes.list(portalId), NavigateMode.PUSH));
        elements.add(new ButtonElement(common(TEMPLATES, lines(strings, RulesMessages.MENU_TEMPLATES, MessageArgs.empty()),
            "minecraft:writable_book", "atlas", new MenuSlot(1, 2)).interactions(PRESS).build()));
        bindings.guard(TEMPLATES, manage)
            .on(TEMPLATES, MenuInteraction.PRIMARY, (ctx, action) -> firstPage(RulesRoutes.templates(portalId), NavigateMode.PUSH));

        return new MenuBuild(screen(RulesRoutes.PROFILE, strings, MenuMessages.TITLE_RULES, portal, elements, null),
            new ChestLayout(portal.routerTitle(), 4, ChestPane.BROWN, null), bindings.build());
    }

    private MenuBuild list(MenuContext context, PortalMenuView portal, RulesMenuView view) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<Rule> all = view.document().rules();
        int page = RulesMenuModel.clampPage(context.page(), all.size());
        List<Rule> visible = RulesMenuModel.page(all, page);
        List<MenuElement> elements = new ArrayList<>(visible.size() + 3);
        MenuBindings.Builder bindings = MenuBindings.builder();

        for (int slot = 0; slot < visible.size(); slot++) {
            Rule rule = visible.get(slot);
            String id = RULE_PREFIX + rule.id();
            String summary = RulesMenuModel.summary(rule);
            MenuLines row = lines(strings, RulesMessages.MENU_RULE, MessageArgs.builder()
                .untrusted("name", rule.id())
                .untrusted("value", summary)
                .build());
            elements.add(new ListEntryElement(common(id, row, "minecraft:paper", null, gridSlot(slot)
                    .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                    .withClick(ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY)))
                .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.TERTIARY))
                .sideActions(List.of(removeAction(strings)))
                .nativeDescription(List.of(summary))
                .build(), false));
            bindings.guard(id, manage)
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> firstPage(RulesRoutes.rule(portalId, rule.id()), NavigateMode.PUSH))
                .on(id, MenuInteraction.TERTIARY, (ctx, action) -> store(ctx, new RulesEdit.RemoveRule(rule.id()),
                    notice(ctx.viewer(), RulesMessages.NOTICE_RULE_REMOVED, args("name", rule.id()), MenuFeedbackStyle.SUCCESS)));
        }

        elements.add(text(strings, common(ADD_RULE, lines(strings, RulesMessages.MENU_ADD_RULE, MessageArgs.empty()), "minecraft:lime_dye", null,
            new MenuSlot(-2, 5)), "", MenuMessages.PLACEHOLDER_RULE_ID, RulesMessages.PROMPT_RULE_ID, portalId));
        bindings.guard(ADD_RULE, manage).on(ADD_RULE, MenuInteraction.SET_TEXT, this::addRule);
        footer(elements, bindings, manage, strings, page, RulesMenuModel.pageCount(all.size()), RulesRoutes.profile(portalId));

        return new MenuBuild(screen(RulesRoutes.LIST, strings, MenuMessages.TITLE_RULES_LIST, portal, elements,
            new MenuPaging(page, RulesMenuModel.pageCount(all.size()))), new ChestLayout(portal.routerTitle(), 6, ChestPane.GRAY, null, LIST_WINDOW_KEY),
            bindings.build());
    }

    private MenuBuild rule(MenuContext context, PortalMenuView portal, RulesMenuView view) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        String ruleId = RulesRoutes.ruleId(context.route());
        int index = RulesMenuModel.indexOf(view.document(), ruleId);
        if (index < 0) {
            throw new IllegalStateException("rule " + ruleId + " is gone from portal " + portalId);
        }
        List<String> all = RulesMenuModel.lines(view.document().rules().get(index));
        int page = RulesMenuModel.clampPage(context.page(), all.size());
        List<String> visible = RulesMenuModel.page(all, page);
        List<MenuElement> elements = new ArrayList<>(visible.size() + 3);
        MenuBindings.Builder bindings = MenuBindings.builder();
        int lineBytes = MenuLimits.MAX_STRING_BYTES - lineOverhead(strings);

        for (int slot = 0; slot < visible.size(); slot++) {
            int line = page * RulesMenuModel.ENTRIES_PER_PAGE + slot;
            String id = LINE_PREFIX + line;
            elements.add(new ListEntryElement(common(id, lines(strings, RulesMessages.MENU_LINE, args("value", fitBytes(visible.get(slot),
                    lineBytes))), "minecraft:string", null, gridSlot(slot)
                    .withClick(ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY)))
                .interactions(Set.of(MenuInteraction.TERTIARY))
                .sideActions(List.of(removeAction(strings)))
                .build(), false));
            bindings.guard(id, manage)
                .on(id, MenuInteraction.TERTIARY, (ctx, action) -> store(ctx, new RulesEdit.RemoveLine(ruleId, line), null));
        }

        elements.add(text(strings, common(ADD_LINE, lines(strings, RulesMessages.MENU_ADD_LINE, MessageArgs.empty()), "minecraft:lime_dye", null,
            new MenuSlot(-2, 5)), "", MenuMessages.PLACEHOLDER_LINE, RulesMessages.PROMPT_LINE, portalId));
        bindings.guard(ADD_LINE, manage)
            .on(ADD_LINE, MenuInteraction.SET_TEXT, (ctx, action) -> change(ctx, new RulesEdit.AddLine(ruleId, typed(action)), true, null));
        footer(elements, bindings, manage, strings, page, RulesMenuModel.pageCount(all.size()), RulesRoutes.list(portalId));

        return new MenuBuild(screen(RulesRoutes.RULE, strings, MenuMessages.TITLE_RULE, portal, elements,
            new MenuPaging(page, RulesMenuModel.pageCount(all.size()))), new ChestLayout(portal.routerTitle(), 6, ChestPane.CYAN, null, LIST_WINDOW_KEY),
            bindings.build());
    }

    private MenuBuild templates(MenuContext context, PortalMenuView portal) {
        MenuStrings strings = context.strings();
        UUID portalId = portal.id();
        MenuGuard manage = MenuGuards.manage(portals, portalId);
        List<String> names = rules.templates();
        int page = RulesMenuModel.clampPage(context.page(), names.size());
        List<String> visible = RulesMenuModel.page(names, page);
        List<MenuElement> elements = new ArrayList<>(visible.size() + 2);
        MenuBindings.Builder bindings = MenuBindings.builder();

        for (int slot = 0; slot < visible.size(); slot++) {
            String name = visible.get(slot);
            String id = TEMPLATE_PREFIX + name;
            elements.add(new ListEntryElement(common(id, lines(strings, RulesMessages.MENU_TEMPLATE, args("name", name)), "minecraft:written_book",
                null, gridSlot(slot).withClick(ChestClick.LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY))).interactions(PRESS).build(),
                false));
            bindings.guard(id, manage)
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> applyTemplate(ctx, name))
                .confirm(id, (ctx, onConfirm) -> templateConfirmation(ctx, portal, name, onConfirm));
        }

        footer(elements, bindings, manage, strings, page, RulesMenuModel.pageCount(names.size()), RulesRoutes.profile(portalId));

        return new MenuBuild(screen(RulesRoutes.TEMPLATES, strings, MenuMessages.TITLE_TEMPLATES, portal, elements,
            new MenuPaging(page, RulesMenuModel.pageCount(names.size()))), new ChestLayout(portal.routerTitle(), 6, ChestPane.LIGHT_BLUE, null),
            bindings.build());
    }

    private void footer(List<MenuElement> elements, MenuBindings.Builder bindings, MenuGuard manage, MenuStrings strings, int page, int pageCount,
                        MenuRoute parent) {
        elements.add(new ButtonElement(common(PAGE, lines(strings, RulesMessages.MENU_PAGE, MessageArgs.builder()
                .untrusted("page", page + 1)
                .untrusted("pages", pageCount)
                .build()), "minecraft:paper", null, new MenuSlot(0, 5)
                .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY))
                .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY)))
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY))
            .nativeHidden(true)
            .build()));
        bindings.guard(PAGE, manage)
            .on(PAGE, MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(Math.min(pageCount - 1, page + 1)))
            .on(PAGE, MenuInteraction.SECONDARY, (ctx, action) -> MenuResult.page(Math.max(0, page - 1)));
        elements.add(new ButtonElement(common(BACK, lines(strings, RulesMessages.MENU_BACK, MessageArgs.empty()), "minecraft:arrow", null,
            new MenuSlot(2, 5)).interactions(PRESS).nativeHidden(true).build()));
        bindings.guard(BACK, manage)
            .on(BACK, MenuInteraction.PRIMARY, (ctx, action) -> firstPage(parent, NavigateMode.POP_TO));
    }

    private MenuResult addRule(MenuContext context, MenuAction action) {
        String ruleId = typed(action);
        return store(context, new RulesEdit.AddRule(ruleId),
            notice(context.viewer(), RulesMessages.NOTICE_RULE_ADDED, args("name", ruleId), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult applyTemplate(MenuContext context, String name) {
        Optional<RuleDocument> template;
        try {
            template = rules.template(name);
        } catch (RuleValidationException | IllegalArgumentException invalid) {
            return MenuResult.stay(rejected(context.viewer(), invalid));
        }
        if (template.isEmpty()) {
            return MenuResult.stay(notice(context.viewer(), RulesMessages.COMMAND_TEMPLATE_MISSING, args("name", name), MenuFeedbackStyle.ERROR));
        }
        UUID portalId = RulesRoutes.portalId(context.route());
        return author(context.viewer(), portalId, view(portalId), template.get(),
            notice(context.viewer(), RulesMessages.NOTICE_APPLIED, args("name", name), MenuFeedbackStyle.SUCCESS));
    }

    private MenuResult store(MenuContext context, RulesEdit edit, MenuNotice success) {
        return change(context, edit, false, success);
    }

    private MenuResult change(MenuContext context, RulesEdit edit, boolean authored, MenuNotice success) {
        UUID portalId = RulesRoutes.portalId(context.route());
        RulesMenuView view = view(portalId);
        RuleDocument next;
        try {
            next = edit.apply(view.document(), view.limits());
        } catch (RuleValidationException | IllegalArgumentException invalid) {
            return MenuResult.stay(rejected(context.viewer(), invalid));
        }
        if (authored) {
            return author(context.viewer(), portalId, view, next, success);
        }
        return outcome(context.viewer(), rules.store(context.viewer(), portalId, next), success);
    }

    private MenuResult author(MenuViewer viewer, UUID portalId, RulesMenuView view, RuleDocument document, MenuNotice success) {
        Optional<RuleDocument> authored = RuleAuthoring.author(document, viewer.id(), viewer.administrator(),
            view.limits().commandEffectsConsoleAllowed);
        if (authored.isEmpty()) {
            return MenuResult.stay(invalid(viewer, RuleAuthoring.CONSOLE_EFFECT_REFUSED));
        }
        return outcome(viewer, rules.store(viewer, portalId, authored.get()), success);
    }

    private RulesMenuView view(UUID portalId) {
        return rules.rules(portalId).orElseThrow(() -> new IllegalStateException("portal " + portalId + " has no rules"));
    }

    private MenuResult outcome(MenuViewer viewer, RulesStoreOutcome outcome, MenuNotice success) {
        return switch (outcome) {
            case RulesStoreOutcome.Stored _ -> MenuResult.stay(success);
            case RulesStoreOutcome.Invalid invalid -> MenuResult.stay(invalid(viewer, String.join("; ", invalid.problems())));
        };
    }

    private MenuConfirmRequest templateConfirmation(MenuContext context, PortalMenuView portal, String name, MenuHandler onConfirm) {
        MenuStrings strings = context.strings();
        MenuLines confirm = strings.lines(MenuMessages.CONFIRM_RULES_TEMPLATE, args("name", name), 0);
        return new MenuConfirmRequest(confirm.label(), confirm.description(), strings.text(MenuMessages.CONFIRM_RULES_TEMPLATE_ACCEPT),
            strings.text(MenuMessages.CHROME_CANCEL), onConfirm, ChestConfirmLayout.standard(portal.routerTitle()));
    }

    private void secondsHandlers(MenuBindings.Builder bindings, MenuGuard manage, String id, long currentMillis, MillisEdit edit) {
        bindings.guard(id, manage)
            .on(id, MenuInteraction.INCREMENT, (ctx, action) -> store(ctx, edit.at(stepMillis(currentMillis, SECOND)), null))
            .on(id, MenuInteraction.DECREMENT, (ctx, action) -> store(ctx, edit.at(stepMillis(currentMillis, -SECOND)), null))
            .on(id, MenuInteraction.BIG_INCREMENT, (ctx, action) -> store(ctx, edit.at(stepMillis(currentMillis, SHIFT_STEP)), null))
            .on(id, MenuInteraction.BIG_DECREMENT, (ctx, action) -> store(ctx, edit.at(stepMillis(currentMillis, -SHIFT_STEP)), null))
            .on(id, MenuInteraction.SET_VALUE, (ctx, action) -> store(ctx,
                edit.at(Math.round(((MenuValue.Number) action.value()).number()) * SECOND), null));
    }

    private void scaleHandlers(MenuBindings.Builder bindings, MenuGuard manage, String id, double current, ScaleEdit edit) {
        bindings.guard(id, manage)
            .on(id, MenuInteraction.INCREMENT, (ctx, action) -> store(ctx, edit.at(Math.min(SCALE_MAX, current + SCALE_STEP)), null))
            .on(id, MenuInteraction.DECREMENT, (ctx, action) -> store(ctx, edit.at(Math.max(0.0D, current - SCALE_STEP)), null))
            .on(id, MenuInteraction.SET_VALUE, (ctx, action) -> store(ctx, edit.at(((MenuValue.Number) action.value()).number()), null));
    }

    private MenuScreen screen(String screenId, MenuStrings strings, TextKey title, PortalMenuView portal, List<MenuElement> elements,
                              MenuPaging paging) {
        return MenuScreen.builder(screenId)
            .title(strings.text(title))
            .context(strings.text(MenuMessages.CONTEXT_PORTAL, MessageArgs.builder()
                .untrusted("portal", portal.name())
                .untrusted("type", PortalMenuText.currentMode(strings, portal))
                .build()))
            .elements(elements)
            .paging(paging)
            .build();
    }

    private static NumberElement seconds(MenuStrings strings, String id, LinesKey key, String item, MenuSlot slot, long millis, int maxSeconds) {
        long seconds = millis / SECOND;
        return new NumberElement(common(id, lines(strings, key, args("seconds", seconds)), item, null, slot).interactions(SECONDS).build(),
            seconds, 0.0D, Math.max(maxSeconds, seconds), 1.0D, 10.0D, Long.toString(seconds));
    }

    private static NumberElement scale(MenuStrings strings, String id, LinesKey key, String item, MenuSlot slot, double value) {
        String display = String.format(Locale.ROOT, "%.2f", Double.valueOf(value));
        return new NumberElement(common(id, lines(strings, key, args("value", display)), item, null, slot)
            .interactions(STEPS).build(), value, Math.min(0.0D, value), Math.max(SCALE_MAX, value), SCALE_STEP, SCALE_STEP, display);
    }

    private static TextElement text(MenuStrings strings, MenuElementCommon.Builder common, String value, TextKey placeholder, TextKey prompt,
                                    UUID portalId) {
        return new TextElement(common.interactions(TEXT).build(), value, MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(placeholder),
            List.of(strings.text(prompt, args("cancel", PortalMenuText.plain(strings, WormholesMessages.PORTAL_INPUT_CANCEL)))), ChestReopen.ALWAYS,
            RulesRoutes.profile(portalId));
    }

    private static int lineOverhead(MenuStrings strings) {
        List<String> empty = rendered(lines(strings, RulesMessages.MENU_LINE, args("value", "")));
        List<String> marked = rendered(lines(strings, RulesMessages.MENU_LINE, args("value", "x")));
        int overhead = 0;
        for (int index = 0; index < marked.size(); index++) {
            if (index >= empty.size() || !empty.get(index).equals(marked.get(index))) {
                overhead = Math.max(overhead, MenuText.encode(marked.get(index)).length - 1);
            }
        }
        return overhead;
    }

    private static List<String> rendered(MenuLines lines) {
        List<String> all = new ArrayList<>(1 + lines.description().size() + lines.chestHints().size());
        all.add(lines.label());
        all.addAll(lines.chestLore());
        return all;
    }

    private static MenuResult firstPage(MenuRoute route, NavigateMode mode) {
        return MenuResult.navigate(route, mode, 0, null);
    }

    private static MenuSideAction removeAction(MenuStrings strings) {
        return new MenuSideAction(MenuInteraction.TERTIARY, strings.text(MenuMessages.ACTION_REMOVE), REMOVE_ICON);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, String item, String sprite, MenuSlot slot) {
        return common(id, lines, MenuIcon.item(item), sprite, slot);
    }

    private static MenuElementCommon.Builder common(String id, MenuLines lines, MenuIcon icon, String sprite, MenuSlot slot) {
        return MenuElementCommon.builder(id)
            .lines(lines)
            .icon(icon)
            .nativeIcon(sprite == null ? null : MenuIcon.sprite(sprite))
            .chestSlot(slot);
    }

    private static MenuSlot gridSlot(int slot) {
        return new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH);
    }

    private static MenuSlot stepSlot(int position, int row) {
        return new MenuSlot(position, row)
            .withClick(ChestClick.LEFT, ChestTarget.self(MenuInteraction.INCREMENT))
            .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.DECREMENT));
    }

    private static long stepMillis(long currentMillis, long deltaMillis) {
        return Math.max(0L, currentMillis + deltaMillis);
    }

    private static String typed(MenuAction action) {
        return ((MenuValue.Text) action.value()).text().trim();
    }

    private static MenuNotice invalid(MenuViewer viewer, String reason) {
        return notice(viewer, RulesMessages.NOTICE_INVALID, args("reason", reason), MenuFeedbackStyle.ERROR);
    }

    private static MenuNotice rejected(MenuViewer viewer, RuntimeException failure) {
        if (failure instanceof RuleValidationException validation) {
            return invalid(viewer, String.join("; ", validation.problems()));
        }
        return invalid(viewer, Objects.toString(failure.getMessage(), ""));
    }

    private static MenuNotice unavailable(MenuViewer viewer) {
        return MenuNotices.nativeOnly(viewer, MenuMessages.FEEDBACK_UNAVAILABLE, MessageArgs.empty(), MenuFeedbackStyle.WARNING);
    }

    private static MenuNotice notice(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return MenuNotices.of(viewer, key, arguments, style, NoticeChannel.HUD);
    }

    private static MenuLines lines(MenuStrings strings, LinesKey key, MessageArgs arguments) {
        return strings.lines(key, arguments, CHEST_HINTS.getOrDefault(key, 0));
    }

    private static MessageArgs args(String name, Object value) {
        return MessageArgs.builder().untrusted(name, value).build();
    }

    private static String fitBytes(String value, int maxBytes) {
        byte[] encoded = MenuText.encode(value);
        if (encoded.length <= maxBytes) {
            return value;
        }
        int end = Math.max(0, maxBytes);
        while (end > 0 && (encoded[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }

    private static String clip(String value, int codePoints) {
        if (MenuText.codePoints(value) <= codePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    @FunctionalInterface
    private interface MillisEdit {
        RulesEdit at(long millis);
    }

    @FunctionalInterface
    private interface ScaleEdit {
        RulesEdit at(double value);
    }
}
