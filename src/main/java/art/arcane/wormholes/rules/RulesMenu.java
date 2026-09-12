package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The in-game rules editor. The profile page holds the portal's cooldown, warmup, pushback, sound and charge
 * pool; the rule list pages through the document's rules; the rule page edits one rule's conditions, costs and
 * effects as the same lines a template uses, so one form covers every kind and every edit is validated before
 * it is stored.
 */
public final class RulesMenu {
    private static final int ROW_WIDTH = 9;
    private static final long SECOND = 1000L;
    private static final long SHIFT_STEP = 10L;

    private final LocalPortal portal;

    public RulesMenu(LocalPortal portal) {
        this.portal = portal;
    }

    public void open(Player viewer) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null) {
            return;
        }
        UIWindow window = window(viewer, Material.BROWN_STAINED_GLASS_PANE, 4);
        populateProfile(window, viewer, extension);
        window.setVisible(true);
    }

    private UIWindow window(Player viewer, Material pane, int viewportHeight) {
        UIWindow window = new UIWindow(Wormholes.instance, viewer);
        window.setTitle(portal.getRouter(true));
        window.setResolution(WindowResolution.W9_H6);
        window.setDecorator(new UIPaneDecorator(pane));
        window.setViewportHeight(viewportHeight);
        return window;
    }

    private void populateProfile(UIWindow window, Player viewer, RulesPortalExtension extension) {
        TraversalProfile profile = extension.document().profile();
        window.setElement(-3, 0, seconds(window, viewer, extension, RulesMessages.MENU_COOLDOWN, Material.CLOCK,
            profile.cooldownMillis(), millis -> reprofile(extension, current -> new TraversalProfile(millis,
                current.cooldownGroup(), current.warmupMillis(), current.pushbackScale(), current.soundVolume(),
                current.chargeCapacity(), current.chargeRegenPerInterval()))));
        window.setElement(-1, 0, seconds(window, viewer, extension, RulesMessages.MENU_WARMUP, Material.COMPASS,
            profile.warmupMillis(), millis -> reprofile(extension, current -> new TraversalProfile(current.cooldownMillis(),
                current.cooldownGroup(), millis, current.pushbackScale(), current.soundVolume(),
                current.chargeCapacity(), current.chargeRegenPerInterval()))));
        window.setElement(1, 0, groupElement(viewer, extension, profile));
        window.setElement(3, 0, chargesElement(window, viewer, extension, profile));
        window.setElement(-2, 1, scaleElement(window, viewer, extension, RulesMessages.MENU_PUSHBACK, Material.PISTON,
            profile.pushbackScale(), value -> reprofile(extension, current -> new TraversalProfile(current.cooldownMillis(),
                current.cooldownGroup(), current.warmupMillis(), value, current.soundVolume(),
                current.chargeCapacity(), current.chargeRegenPerInterval()))));
        window.setElement(0, 1, scaleElement(window, viewer, extension, RulesMessages.MENU_SOUND, Material.NOTE_BLOCK,
            profile.soundVolume(), value -> reprofile(extension, current -> new TraversalProfile(current.cooldownMillis(),
                current.cooldownGroup(), current.warmupMillis(), current.pushbackScale(), value,
                current.chargeCapacity(), current.chargeRegenPerInterval()))));
        window.setElement(2, 1, defaultOutcomeElement(window, viewer, extension));
        window.setElement(-1, 2, rulesElement(viewer, extension));
        window.setElement(1, 2, templatesElement(viewer));
    }

    private UIElement seconds(UIWindow window, Player viewer, RulesPortalExtension extension, LinesKey key, Material material, long currentMillis, Consumer<Long> apply) {
        UIElement element = RulesMenuText.element("rules-" + key.id(), key,
            RulesMenuText.arguments("seconds", Long.valueOf(currentMillis / SECOND)), material);
        element.onLeftClick(event -> step(window, viewer, extension, currentMillis, SECOND, apply));
        element.onRightClick(event -> step(window, viewer, extension, currentMillis, -SECOND, apply));
        element.onShiftLeftClick(event -> step(window, viewer, extension, currentMillis, SECOND * SHIFT_STEP, apply));
        element.onShiftRightClick(event -> step(window, viewer, extension, currentMillis, -SECOND * SHIFT_STEP, apply));
        return element;
    }

    private void step(UIWindow window, Player viewer, RulesPortalExtension extension, long currentMillis, long deltaMillis,
                      Consumer<Long> apply) {
        apply.accept(Long.valueOf(Math.max(0L, currentMillis + deltaMillis)));
        refresh(window, viewer, extension);
    }

    private UIElement scaleElement(UIWindow window, Player viewer, RulesPortalExtension extension,
                                   LinesKey key, Material material, double current, Consumer<Double> apply) {
        UIElement element = RulesMenuText.element("rules-" + key.id(), key,
            RulesMenuText.arguments("value", String.format(Locale.ROOT, "%.2f", Double.valueOf(current))), material);
        element.onLeftClick(event -> {
            apply.accept(Double.valueOf(Math.min(10.0D, current + 0.25D)));
            refresh(window, viewer, extension);
        });
        element.onRightClick(event -> {
            apply.accept(Double.valueOf(Math.max(0.0D, current - 0.25D)));
            refresh(window, viewer, extension);
        });
        return element;
    }

    private UIElement chargesElement(UIWindow window, Player viewer, RulesPortalExtension extension, TraversalProfile profile) {
        UIElement element = RulesMenuText.element("rules-charges", RulesMessages.MENU_CHARGES,
            RulesMenuText.arguments("count", Integer.valueOf(profile.chargeCapacity())), Material.AMETHYST_SHARD);
        element.onLeftClick(event -> {
            reprofile(extension, current -> withCharges(current, current.chargeCapacity() + 1));
            refresh(window, viewer, extension);
        });
        element.onRightClick(event -> {
            reprofile(extension, current -> withCharges(current, Math.max(0, current.chargeCapacity() - 1)));
            refresh(window, viewer, extension);
        });
        return element;
    }

    private static TraversalProfile withCharges(TraversalProfile current, int capacity) {
        return new TraversalProfile(current.cooldownMillis(), current.cooldownGroup(), current.warmupMillis(),
            current.pushbackScale(), current.soundVolume(), capacity,
            Math.min(current.chargeRegenPerInterval(), Math.max(0, capacity)));
    }

    private UIElement defaultOutcomeElement(UIWindow window, Player viewer, RulesPortalExtension extension) {
        RuleOutcome outcome = extension.document().defaultOutcome();
        UIElement element = RulesMenuText.element("rules-default", RulesMessages.MENU_DEFAULT_OUTCOME,
            RulesMenuText.arguments("mode", outcome.kind().name()), Material.LEVER);
        element.setEnchanted(!outcome.allowed());
        element.onLeftClick(event -> {
            extension.setDocument(extension.document().withDefaultOutcome(outcome.allowed()
                ? RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id())
                : RuleOutcome.allow()));
            refresh(window, viewer, extension);
        });
        return element;
    }

    private UIElement groupElement(Player viewer, RulesPortalExtension extension, TraversalProfile profile) {
        UIElement element = RulesMenuText.element("rules-group", RulesMessages.MENU_GROUP,
            RulesMenuText.arguments("value", profile.cooldownGroup().isEmpty() ? "-" : profile.cooldownGroup()),
            Material.NAME_TAG);
        element.onLeftClick(event -> prompt(viewer, RulesMessages.PROMPT_GROUP, input -> {
            reprofile(extension, current -> new TraversalProfile(current.cooldownMillis(), input, current.warmupMillis(),
                current.pushbackScale(), current.soundVolume(), current.chargeCapacity(), current.chargeRegenPerInterval()));
            open(viewer);
        }));
        return element;
    }

    private UIElement rulesElement(Player viewer, RulesPortalExtension extension) {
        UIElement element = RulesMenuText.element("rules-list", RulesMessages.MENU_RULES,
            RulesMenuText.arguments("count", Integer.valueOf(extension.document().rules().size())), Material.BOOK);
        element.onLeftClick(event -> openRules(viewer, 0));
        return element;
    }

    private UIElement templatesElement(Player viewer) {
        UIElement element = RulesMenuText.element("rules-templates", RulesMessages.MENU_TEMPLATES,
            MessageArgs.empty(), Material.WRITABLE_BOOK);
        element.onLeftClick(event -> openTemplates(viewer, 0));
        return element;
    }

    private void refresh(UIWindow window, Player viewer, RulesPortalExtension extension) {
        window.batch(() -> {
            window.clearElements();
            populateProfile(window, viewer, extension);
        });
        window.updateInventory();
    }

    private void openRules(Player viewer, int page) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null) {
            return;
        }
        UIWindow window = window(viewer, Material.GRAY_STAINED_GLASS_PANE, 6);
        populateRules(window, viewer, extension, page);
        window.setVisible(true);
    }

    private void populateRules(UIWindow window, Player viewer, RulesPortalExtension extension, int requestedPage) {
        List<Rule> rules = extension.document().rules();
        int page = RulesMenuModel.clampPage(requestedPage, rules.size());
        List<Rule> visible = RulesMenuModel.page(rules, page);
        for (int slot = 0; slot < visible.size(); slot++) {
            Rule rule = visible.get(slot);
            UIElement element = RulesMenuText.element("rules-rule-" + rule.id(), RulesMessages.MENU_RULE,
                RulesMenuText.arguments("name", rule.id(), "value", RulesMenuModel.summary(rule)), Material.PAPER);
            element.onLeftClick(event -> openRule(viewer, rule.id(), 0));
            element.onShiftLeftClick(event -> {
                extension.setDocument(RulesMenuModel.removeRule(extension.document(), rule.id()));
                notice(viewer, RulesMessages.NOTICE_RULE_REMOVED, RulesMenuText.arguments("name", rule.id()));
                reopenRules(window, viewer, extension, page);
            });
            window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, element);
        }
        UIElement add = RulesMenuText.element("rules-add-rule", RulesMessages.MENU_ADD_RULE, MessageArgs.empty(), Material.LIME_DYE);
        add.onLeftClick(event -> prompt(viewer, RulesMessages.PROMPT_RULE_ID, input -> {
            extension.setDocument(RulesMenuModel.addRule(extension.document(), input));
            notice(viewer, RulesMessages.NOTICE_RULE_ADDED, RulesMenuText.arguments("name", input));
            openRules(viewer, page);
        }));
        window.setElement(-2, 5, add);
        window.setElement(0, 5, pageElement(page, RulesMenuModel.pageCount(rules.size()),
            next -> reopenRules(window, viewer, extension, next)));
        window.setElement(2, 5, backElement(() -> open(viewer)));
    }

    private void reopenRules(UIWindow window, Player viewer, RulesPortalExtension extension, int page) {
        window.batch(() -> {
            window.clearElements();
            populateRules(window, viewer, extension, page);
        });
        window.updateInventory();
    }

    private void openRule(Player viewer, String ruleId, int page) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null || RulesMenuModel.indexOf(extension.document(), ruleId) < 0) {
            return;
        }
        UIWindow window = window(viewer, Material.CYAN_STAINED_GLASS_PANE, 6);
        populateRule(window, viewer, extension, ruleId, page);
        window.setVisible(true);
    }

    private void populateRule(UIWindow window, Player viewer, RulesPortalExtension extension, String ruleId, int requestedPage) {
        int index = RulesMenuModel.indexOf(extension.document(), ruleId);
        if (index < 0) {
            return;
        }
        Rule rule = extension.document().rules().get(index);
        List<String> lines = RulesMenuModel.lines(rule);
        int page = RulesMenuModel.clampPage(requestedPage, lines.size());
        List<String> visible = RulesMenuModel.page(lines, page);
        for (int slot = 0; slot < visible.size(); slot++) {
            int lineIndex = page * RulesMenuModel.ENTRIES_PER_PAGE + slot;
            UIElement element = RulesMenuText.element("rules-line-" + lineIndex, RulesMessages.MENU_LINE,
                RulesMenuText.arguments("value", visible.get(slot)), Material.STRING);
            element.onShiftLeftClick(event -> {
                extension.setDocument(RulesMenuModel.replaceRule(extension.document(),
                    RulesMenuModel.removeLine(rule, lineIndex)));
                reopenRule(window, viewer, extension, ruleId, page);
            });
            window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, element);
        }
        UIElement add = RulesMenuText.element("rules-add-line", RulesMessages.MENU_ADD_LINE, MessageArgs.empty(), Material.LIME_DYE);
        add.onLeftClick(event -> prompt(viewer, RulesMessages.PROMPT_LINE, input -> {
            try {
                extension.setDocument(RulesMenuModel.replaceRule(extension.document(),
                    RulesMenuModel.addLine(rule, input)));
            } catch (RuleValidationException invalid) {
                notice(viewer, RulesMessages.NOTICE_INVALID,
                    RulesMenuText.arguments("reason", String.join("; ", invalid.problems())));
            }
            openRule(viewer, ruleId, page);
        }));
        window.setElement(-2, 5, add);
        window.setElement(0, 5, pageElement(page, RulesMenuModel.pageCount(lines.size()),
            next -> reopenRule(window, viewer, extension, ruleId, next)));
        window.setElement(2, 5, backElement(() -> openRules(viewer, 0)));
    }

    private void reopenRule(UIWindow window, Player viewer, RulesPortalExtension extension, String ruleId, int page) {
        window.batch(() -> {
            window.clearElements();
            populateRule(window, viewer, extension, ruleId, page);
        });
        window.updateInventory();
    }

    private void openTemplates(Player viewer, int page) {
        UIWindow window = window(viewer, Material.LIGHT_BLUE_STAINED_GLASS_PANE, 6);
        populateTemplates(window, viewer, page);
        window.setVisible(true);
    }

    private void populateTemplates(UIWindow window, Player viewer, int requestedPage) {
        RuleTemplates templates = new RuleTemplates(Wormholes.instance.getDataFolder().toPath());
        List<String> names = templates.list();
        int page = RulesMenuModel.clampPage(requestedPage, names.size());
        List<String> visible = RulesMenuModel.page(names, page);
        for (int slot = 0; slot < visible.size(); slot++) {
            String name = visible.get(slot);
            UIElement element = RulesMenuText.element("rules-template-" + name, RulesMessages.MENU_TEMPLATE,
                RulesMenuText.arguments("name", name), Material.WRITTEN_BOOK);
            element.onLeftClick(event -> applyTemplate(viewer, templates, name));
            window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, element);
        }
        window.setElement(0, 5, pageElement(page, RulesMenuModel.pageCount(names.size()), next -> {
            window.batch(() -> {
                window.clearElements();
                populateTemplates(window, viewer, next);
            });
            window.updateInventory();
        }));
        window.setElement(2, 5, backElement(() -> open(viewer)));
    }

    private void applyTemplate(Player viewer, RuleTemplates templates, String name) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null) {
            return;
        }
        try {
            RuleDocument document = templates.load(name);
            if (document == null) {
                notice(viewer, RulesMessages.COMMAND_TEMPLATE_MISSING, RulesMenuText.arguments("name", name));
                return;
            }
            extension.setDocument(document);
            notice(viewer, RulesMessages.NOTICE_APPLIED, RulesMenuText.arguments("name", name));
        } catch (RuleValidationException invalid) {
            notice(viewer, RulesMessages.NOTICE_INVALID,
                RulesMenuText.arguments("reason", String.join("; ", invalid.problems())));
        }
    }

    private UIElement pageElement(int page, int pageCount, Consumer<Integer> open) {
        UIElement element = RulesMenuText.element("rules-page", RulesMessages.MENU_PAGE,
            RulesMenuText.arguments("page", Integer.valueOf(page + 1), "pages", Integer.valueOf(pageCount)), Material.PAPER);
        element.onLeftClick(event -> open.accept(Integer.valueOf(Math.min(pageCount - 1, page + 1))));
        element.onRightClick(event -> open.accept(Integer.valueOf(Math.max(0, page - 1))));
        return element;
    }

    private UIElement backElement(Runnable back) {
        UIElement element = RulesMenuText.element("rules-back", RulesMessages.MENU_BACK, MessageArgs.empty(), Material.ARROW);
        element.onLeftClick(event -> back.run());
        return element;
    }

    private void reprofile(RulesPortalExtension extension, UnaryOperator<TraversalProfile> change) {
        RuleDocument document = extension.document();
        extension.setDocument(document.withProfile(change.apply(document.profile())));
    }

    private void prompt(Player viewer, TextKey key, Consumer<String> accept) {
        viewer.closeInventory();
        WormholesAudience.sendMessage(viewer, Wormholes.text().component(viewer, key,
            RulesMenuText.arguments("cancel", RulesMenuText.localized(WormholesMessages.PORTAL_INPUT_CANCEL))));
        Wormholes.awaitChatInput(viewer, input -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> {
            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase(RulesMenuText.localized(WormholesMessages.PORTAL_INPUT_CANCEL))) {
                open(viewer);
                return;
            }
            accept.accept(trimmed);
        }));
    }

    private static void notice(Player viewer, TextKey key, MessageArgs arguments) {
        WormholesHud.notice(viewer, Wormholes.text().component(viewer, key, arguments));
    }
}
