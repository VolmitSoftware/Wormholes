package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RuleValidationException;
import art.arcane.wormholes.rules.RulesMenuModel;
import art.arcane.wormholes.rules.TraversalProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public final class MinecraftRulesMenus {
    private static final int ROW_WIDTH = 9;
    private static final long SECOND = 1000L;
    private static final long SHIFT_STEP = 10L;

    private final WormholesModRuntime runtime;

    public MinecraftRulesMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void open(ServerPlayer viewer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal != null) {
            new PortalRules(portal).open(viewer);
        }
    }

    private final class PortalRules {
        private final MinecraftPortal portal;

        private PortalRules(MinecraftPortal portal) {
            this.portal = portal;
        }

        private void open(ServerPlayer viewer) {
            MinecraftWindow window = window(viewer, Items.STAINED_GLASS_PANE.brown(), 4);
            populateProfile(window, viewer);
            window.setVisible(true);
        }

        private MinecraftWindow window(ServerPlayer viewer, Item pane, int viewportHeight) {
            MinecraftWindow window = new MinecraftWindow(runtime, viewer);
            window.setTitle(MinecraftPortalText.router(runtime, portal, true));
            window.setDecorator(pane);
            window.setViewportHeight(viewportHeight);
            return window;
        }

        private RuleDocument document() {
            return runtime.rules().document(portal);
        }

        private void populateProfile(MinecraftWindow window, ServerPlayer viewer) {
            TraversalProfile profile = document().profile();
            window.setElement(-3, 0, seconds(window, viewer, RulesMessages.MENU_COOLDOWN, Items.CLOCK,
                profile.cooldownMillis(), millis -> reprofile(viewer, current -> new TraversalProfile(millis,
                    current.cooldownGroup(), current.warmupMillis(), current.pushbackScale(), current.soundVolume(),
                    current.chargeCapacity(), current.chargeRegenPerInterval()))));
            window.setElement(-1, 0, seconds(window, viewer, RulesMessages.MENU_WARMUP, Items.COMPASS,
                profile.warmupMillis(), millis -> reprofile(viewer, current -> new TraversalProfile(current.cooldownMillis(),
                    current.cooldownGroup(), millis, current.pushbackScale(), current.soundVolume(),
                    current.chargeCapacity(), current.chargeRegenPerInterval()))));
            window.setElement(1, 0, groupElement(viewer, profile));
            window.setElement(3, 0, chargesElement(window, viewer, profile));
            window.setElement(-2, 1, scaleElement(window, viewer, RulesMessages.MENU_PUSHBACK, Items.PISTON,
                profile.pushbackScale(), value -> reprofile(viewer, current -> new TraversalProfile(current.cooldownMillis(),
                    current.cooldownGroup(), current.warmupMillis(), value, current.soundVolume(),
                    current.chargeCapacity(), current.chargeRegenPerInterval()))));
            window.setElement(0, 1, scaleElement(window, viewer, RulesMessages.MENU_SOUND, Items.NOTE_BLOCK,
                profile.soundVolume(), value -> reprofile(viewer, current -> new TraversalProfile(current.cooldownMillis(),
                    current.cooldownGroup(), current.warmupMillis(), current.pushbackScale(), value,
                    current.chargeCapacity(), current.chargeRegenPerInterval()))));
            window.setElement(2, 1, defaultOutcomeElement(window, viewer));
            window.setElement(-1, 2, rulesElement(viewer));
            window.setElement(1, 2, templatesElement(viewer));
        }

        private MinecraftElement seconds(MinecraftWindow window, ServerPlayer viewer, LinesKey key, Item material, long currentMillis,
                                         Consumer<Long> apply) {
            MinecraftElement element = element(viewer, "rules-" + key.id(), key,
                MinecraftPortalText.arguments("seconds", currentMillis / SECOND), material);
            element.onLeftClick(event -> step(window, viewer, currentMillis, SECOND, apply));
            element.onRightClick(event -> step(window, viewer, currentMillis, -SECOND, apply));
            element.onShiftLeftClick(event -> step(window, viewer, currentMillis, SECOND * SHIFT_STEP, apply));
            element.onShiftRightClick(event -> step(window, viewer, currentMillis, -SECOND * SHIFT_STEP, apply));
            return element;
        }

        private void step(MinecraftWindow window, ServerPlayer viewer, long currentMillis, long deltaMillis, Consumer<Long> apply) {
            apply.accept(Math.max(0L, currentMillis + deltaMillis));
            refresh(window, viewer);
        }

        private MinecraftElement scaleElement(MinecraftWindow window, ServerPlayer viewer, LinesKey key, Item material, double current,
                                              Consumer<Double> apply) {
            MinecraftElement element = element(viewer, "rules-" + key.id(), key,
                MinecraftPortalText.arguments("value", String.format(Locale.ROOT, "%.2f", current)), material);
            element.onLeftClick(event -> {
                apply.accept(Math.min(10.0D, current + 0.25D));
                refresh(window, viewer);
            });
            element.onRightClick(event -> {
                apply.accept(Math.max(0.0D, current - 0.25D));
                refresh(window, viewer);
            });
            return element;
        }

        private MinecraftElement chargesElement(MinecraftWindow window, ServerPlayer viewer, TraversalProfile profile) {
            MinecraftElement element = element(viewer, "rules-charges", RulesMessages.MENU_CHARGES,
                MinecraftPortalText.arguments("count", profile.chargeCapacity()), Items.AMETHYST_SHARD);
            element.onLeftClick(event -> {
                reprofile(viewer, current -> withCharges(current, current.chargeCapacity() + 1));
                refresh(window, viewer);
            });
            element.onRightClick(event -> {
                reprofile(viewer, current -> withCharges(current, Math.max(0, current.chargeCapacity() - 1)));
                refresh(window, viewer);
            });
            return element;
        }

        private MinecraftElement defaultOutcomeElement(MinecraftWindow window, ServerPlayer viewer) {
            RuleOutcome outcome = document().defaultOutcome();
            MinecraftElement element = element(viewer, "rules-default", RulesMessages.MENU_DEFAULT_OUTCOME,
                MinecraftPortalText.arguments("mode", outcome.kind().name()), Items.LEVER);
            element.setEnchanted(!outcome.allowed());
            element.onLeftClick(event -> {
                store(viewer, document().withDefaultOutcome(outcome.allowed()
                    ? RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id())
                    : RuleOutcome.allow()));
                refresh(window, viewer);
            });
            return element;
        }

        private MinecraftElement groupElement(ServerPlayer viewer, TraversalProfile profile) {
            MinecraftElement element = element(viewer, "rules-group", RulesMessages.MENU_GROUP,
                MinecraftPortalText.arguments("value", profile.cooldownGroup().isEmpty() ? "-" : profile.cooldownGroup()),
                Items.NAME_TAG);
            element.onLeftClick(event -> prompt(viewer, RulesMessages.PROMPT_GROUP, input -> {
                reprofile(viewer, current -> new TraversalProfile(current.cooldownMillis(), input, current.warmupMillis(),
                    current.pushbackScale(), current.soundVolume(), current.chargeCapacity(), current.chargeRegenPerInterval()));
                open(viewer);
            }));
            return element;
        }

        private MinecraftElement rulesElement(ServerPlayer viewer) {
            MinecraftElement element = element(viewer, "rules-list", RulesMessages.MENU_RULES,
                MinecraftPortalText.arguments("count", document().rules().size()), Items.BOOK);
            element.onLeftClick(event -> openRules(viewer, 0));
            return element;
        }

        private MinecraftElement templatesElement(ServerPlayer viewer) {
            MinecraftElement element = element(viewer, "rules-templates", RulesMessages.MENU_TEMPLATES,
                MessageArgs.empty(), Items.WRITABLE_BOOK);
            element.onLeftClick(event -> openTemplates(viewer, 0));
            return element;
        }

        private void refresh(MinecraftWindow window, ServerPlayer viewer) {
            window.batch(() -> {
                window.clearElements();
                populateProfile(window, viewer);
            });
            window.updateInventory();
        }

        private void openRules(ServerPlayer viewer, int page) {
            MinecraftWindow window = window(viewer, Items.STAINED_GLASS_PANE.gray(), 6);
            populateRules(window, viewer, page);
            window.setVisible(true);
        }

        private void populateRules(MinecraftWindow window, ServerPlayer viewer, int requestedPage) {
            List<Rule> rules = document().rules();
            int page = RulesMenuModel.clampPage(requestedPage, rules.size());
            List<Rule> visible = RulesMenuModel.page(rules, page);
            for (int slot = 0; slot < visible.size(); slot++) {
                Rule rule = visible.get(slot);
                MinecraftElement element = element(viewer, "rules-rule-" + rule.id(), RulesMessages.MENU_RULE,
                    MinecraftPortalText.arguments("name", rule.id(), "value", RulesMenuModel.summary(rule)), Items.PAPER);
                element.onLeftClick(event -> openRule(viewer, rule.id(), 0));
                element.onShiftLeftClick(event -> {
                    store(viewer, RulesMenuModel.removeRule(document(), rule.id()));
                    notice(viewer, RulesMessages.NOTICE_RULE_REMOVED, MinecraftPortalText.arguments("name", rule.id()));
                    reopenRules(window, viewer, page);
                });
                window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, element);
            }
            MinecraftElement add = element(viewer, "rules-add-rule", RulesMessages.MENU_ADD_RULE, MessageArgs.empty(), Items.DYE.lime());
            add.onLeftClick(event -> prompt(viewer, RulesMessages.PROMPT_RULE_ID, input -> {
                store(viewer, RulesMenuModel.addRule(document(), input));
                notice(viewer, RulesMessages.NOTICE_RULE_ADDED, MinecraftPortalText.arguments("name", input));
                openRules(viewer, page);
            }));
            window.setElement(-2, 5, add);
            window.setElement(0, 5, pageElement(viewer, page, RulesMenuModel.pageCount(rules.size()),
                next -> reopenRules(window, viewer, next)));
            window.setElement(2, 5, backElement(viewer, () -> open(viewer)));
        }

        private void reopenRules(MinecraftWindow window, ServerPlayer viewer, int page) {
            window.batch(() -> {
                window.clearElements();
                populateRules(window, viewer, page);
            });
            window.updateInventory();
        }

        private void openRule(ServerPlayer viewer, String ruleId, int page) {
            if (RulesMenuModel.indexOf(document(), ruleId) < 0) {
                return;
            }
            MinecraftWindow window = window(viewer, Items.STAINED_GLASS_PANE.cyan(), 6);
            populateRule(window, viewer, ruleId, page);
            window.setVisible(true);
        }

        private void populateRule(MinecraftWindow window, ServerPlayer viewer, String ruleId, int requestedPage) {
            int index = RulesMenuModel.indexOf(document(), ruleId);
            if (index < 0) {
                return;
            }
            Rule rule = document().rules().get(index);
            List<String> lines = RulesMenuModel.lines(rule);
            int page = RulesMenuModel.clampPage(requestedPage, lines.size());
            List<String> visible = RulesMenuModel.page(lines, page);
            for (int slot = 0; slot < visible.size(); slot++) {
                int lineIndex = page * RulesMenuModel.ENTRIES_PER_PAGE + slot;
                MinecraftElement element = element(viewer, "rules-line-" + lineIndex, RulesMessages.MENU_LINE,
                    MinecraftPortalText.arguments("value", visible.get(slot)), Items.STRING);
                element.onShiftLeftClick(event -> {
                    store(viewer, RulesMenuModel.replaceRule(document(), RulesMenuModel.removeLine(rule, lineIndex)));
                    reopenRule(window, viewer, ruleId, page);
                });
                window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, element);
            }
            MinecraftElement add = element(viewer, "rules-add-line", RulesMessages.MENU_ADD_LINE, MessageArgs.empty(), Items.DYE.lime());
            add.onLeftClick(event -> prompt(viewer, RulesMessages.PROMPT_LINE, input -> {
                try {
                    store(viewer, runtime.rules().authorDocument(viewer, RulesMenuModel.replaceRule(document(),
                        RulesMenuModel.addLine(rule, input, runtime.configuration().settings().getRules()))));
                } catch (RuleValidationException invalid) {
                    invalid(viewer, invalid);
                } catch (IllegalArgumentException invalid) {
                    invalid(viewer, invalid);
                }
                openRule(viewer, ruleId, page);
            }));
            window.setElement(-2, 5, add);
            window.setElement(0, 5, pageElement(viewer, page, RulesMenuModel.pageCount(lines.size()),
                next -> reopenRule(window, viewer, ruleId, next)));
            window.setElement(2, 5, backElement(viewer, () -> openRules(viewer, 0)));
        }

        private void reopenRule(MinecraftWindow window, ServerPlayer viewer, String ruleId, int page) {
            window.batch(() -> {
                window.clearElements();
                populateRule(window, viewer, ruleId, page);
            });
            window.updateInventory();
        }

        private void openTemplates(ServerPlayer viewer, int page) {
            MinecraftWindow window = window(viewer, Items.STAINED_GLASS_PANE.lightBlue(), 6);
            populateTemplates(window, viewer, page);
            window.setVisible(true);
        }

        private void populateTemplates(MinecraftWindow window, ServerPlayer viewer, int requestedPage) {
            RuleTemplates templates = new RuleTemplates(runtime.server().getServerDirectory().resolve("config/wormholes"),
                runtime.configuration().settings().getRules());
            List<String> names = templates.list();
            int page = RulesMenuModel.clampPage(requestedPage, names.size());
            List<String> visible = RulesMenuModel.page(names, page);
            for (int slot = 0; slot < visible.size(); slot++) {
                String name = visible.get(slot);
                MinecraftElement element = element(viewer, "rules-template-" + name, RulesMessages.MENU_TEMPLATE,
                    MinecraftPortalText.arguments("name", name), Items.WRITTEN_BOOK);
                element.onLeftClick(event -> applyTemplate(viewer, templates, name));
                window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, element);
            }
            window.setElement(0, 5, pageElement(viewer, page, RulesMenuModel.pageCount(names.size()), next -> {
                window.batch(() -> {
                    window.clearElements();
                    populateTemplates(window, viewer, next);
                });
                window.updateInventory();
            }));
            window.setElement(2, 5, backElement(viewer, () -> open(viewer)));
        }

        private void applyTemplate(ServerPlayer viewer, RuleTemplates templates, String name) {
            try {
                RuleDocument document = templates.load(name);
                if (document == null) {
                    notice(viewer, RulesMessages.COMMAND_TEMPLATE_MISSING, MinecraftPortalText.arguments("name", name));
                    return;
                }
                if (store(viewer, runtime.rules().authorDocument(viewer, document))) {
                    notice(viewer, RulesMessages.NOTICE_APPLIED, MinecraftPortalText.arguments("name", name));
                }
            } catch (RuleValidationException invalid) {
                invalid(viewer, invalid);
            } catch (IllegalArgumentException invalid) {
                invalid(viewer, invalid);
            }
        }

        private MinecraftElement pageElement(ServerPlayer viewer, int page, int pageCount, Consumer<Integer> open) {
            MinecraftElement element = element(viewer, "rules-page", RulesMessages.MENU_PAGE,
                MinecraftPortalText.arguments("page", page + 1, "pages", pageCount), Items.PAPER);
            element.onLeftClick(event -> open.accept(Math.min(pageCount - 1, page + 1)));
            element.onRightClick(event -> open.accept(Math.max(0, page - 1)));
            return element;
        }

        private MinecraftElement backElement(ServerPlayer viewer, Runnable back) {
            MinecraftElement element = element(viewer, "rules-back", RulesMessages.MENU_BACK, MessageArgs.empty(), Items.ARROW);
            element.onLeftClick(event -> back.run());
            return element;
        }

        private void reprofile(ServerPlayer viewer, UnaryOperator<TraversalProfile> change) {
            RuleDocument document = document();
            store(viewer, document.withProfile(change.apply(document.profile())));
        }

        private boolean store(ServerPlayer viewer, RuleDocument document) {
            try {
                runtime.rules().setDocument(portal, document);
                return true;
            } catch (RuleValidationException invalid) {
                invalid(viewer, invalid);
                return false;
            }
        }

        private void prompt(ServerPlayer viewer, TextKey key, Consumer<String> accept) {
            viewer.closeContainer();
            viewer.sendSystemMessage(MinecraftMenuText.text(viewer, key,
                MinecraftPortalText.arguments("cancel", localized(viewer, WormholesMessages.PORTAL_INPUT_CANCEL))));
            runtime.chatInput().await(viewer, input -> runtime.schedule(() -> {
                String trimmed = input.trim();
                if (trimmed.equalsIgnoreCase(localized(viewer, WormholesMessages.PORTAL_INPUT_CANCEL))) {
                    open(viewer);
                    return;
                }
                accept.accept(trimmed);
            }, 1L));
        }
    }

    private static TraversalProfile withCharges(TraversalProfile current, int capacity) {
        return new TraversalProfile(current.cooldownMillis(), current.cooldownGroup(), current.warmupMillis(),
            current.pushbackScale(), current.soundVolume(), capacity,
            Math.min(current.chargeRegenPerInterval(), Math.max(0, capacity)));
    }

    private static void invalid(ServerPlayer viewer, RuleValidationException invalid) {
        notice(viewer, RulesMessages.NOTICE_INVALID, MinecraftPortalText.arguments("reason", String.join("; ", invalid.problems())));
    }

    private static void invalid(ServerPlayer viewer, IllegalArgumentException invalid) {
        notice(viewer, RulesMessages.NOTICE_INVALID, MinecraftPortalText.arguments("reason", Objects.toString(invalid.getMessage(), "")));
    }

    private static String localized(ServerPlayer viewer, TextKey key) {
        return MinecraftMenuText.text(viewer, key, MessageArgs.empty()).getString();
    }

    private static MinecraftElement element(ServerPlayer viewer, String id, LinesKey key, MessageArgs arguments, Item material) {
        MinecraftElement element = new MinecraftElement(id);
        element.setMaterial(material);
        MinecraftLegacyText.apply(viewer, element, key, arguments);
        return element;
    }

    private static void notice(ServerPlayer viewer, TextKey key, MessageArgs arguments) {
        MinecraftMenuText.notice(viewer, MinecraftMenuText.text(viewer, key, arguments));
    }
}
