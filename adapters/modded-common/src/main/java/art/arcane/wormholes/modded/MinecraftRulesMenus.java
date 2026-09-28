package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleValidationException;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RulesMenuModel;
import art.arcane.wormholes.rules.TraversalProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

public final class MinecraftRulesMenus implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    public MinecraftRulesMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public void open(ServerPlayer player, UUID portalId) {
        new Session(player, portalId, Page.PROFILE, "").open();
    }

    public void tick() {
        prompts.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.nanoTime());
        for (Session session : List.copyOf(sessions.values())) {
            if (session.viewer.containerMenu != session.menu) {
                sessions.remove(session.viewer.getUUID());
            } else if (!session.valid()) {
                sessions.remove(session.viewer.getUUID());
                session.viewer.closeContainer();
            }
        }
    }

    public void disconnected(ServerPlayer player) {
        sessions.remove(player.getUUID());
        prompts.remove(player.getUUID());
    }

    @Override
    public void close() {
        for (Session session : sessions.values()) {
            if (session.viewer.containerMenu == session.menu) {
                session.viewer.closeContainer();
            }
        }
        sessions.clear();
        prompts.clear();
    }

    public boolean acceptChat(ServerPlayer player, String message) {
        Prompt prompt = prompts.remove(player.getUUID());
        if (prompt == null || prompt.expiresAt < System.nanoTime()) {
            return false;
        }
        Session session = prompt.session;
        if (!session.valid()) {
            return true;
        }
        String value = message.trim();
        String cancel = MinecraftMenuText.text(player, WormholesMessages.PORTAL_INPUT_CANCEL, Map.of()).getString();
        if (!value.equalsIgnoreCase(cancel)) {
            try {
                RuleDocument document = runtime.rules().document(session.portal());
                RuleDocument changed = switch (prompt.kind) {
                    case GROUP -> document.withProfile(new TraversalProfile(document.profile().cooldownMillis(), value,
                        document.profile().warmupMillis(), document.profile().pushbackScale(), document.profile().soundVolume(),
                        document.profile().chargeCapacity(), document.profile().chargeRegenPerInterval()));
                    case RULE -> RulesMenuModel.addRule(document, value);
                    case LINE -> {
                        int index = RulesMenuModel.indexOf(document, session.ruleId);
                        yield index < 0 ? document : RulesMenuModel.replaceRule(document,
                            RulesMenuModel.addLine(document.rules().get(index), value, runtime.configuration().settings().getRules()));
                    }
                };
                runtime.rules().setDocument(session.portal(), runtime.rules().authorDocument(player, changed));
            } catch (IllegalArgumentException | RuleValidationException invalid) {
                notice(player, RulesMessages.NOTICE_INVALID, Map.of("reason", invalid.getMessage()));
            }
        }
        session.open();
        return true;
    }

    private RuleTemplates templates() {
        return new RuleTemplates(runtime.server().getServerDirectory().resolve("config/wormholes"),
            runtime.configuration().settings().getRules());
    }

    private static void notice(ServerPlayer player, TextKey key, Map<String, ?> values) {
        player.sendSystemMessage(MinecraftMenuText.text(player, key, values));
    }

    private enum Page { PROFILE, RULES, LINES, TEMPLATES }
    private enum Input { GROUP, RULE, LINE }
    private record Prompt(Session session, Input kind, long expiresAt) { }

    private final class Session {
        private final ServerPlayer viewer;
        private final UUID portalId;
        private final Page page;
        private final String ruleId;
        private MinecraftInventoryMenu menu;
        private int index;
        private List<String> templateNames = List.of();

        private Session(ServerPlayer viewer, UUID portalId, Page page, String ruleId) {
            this.viewer = viewer;
            this.portalId = portalId;
            this.page = page;
            this.ruleId = ruleId;
        }

        private MinecraftPortal portal() { return runtime.portals().get(portalId); }

        private boolean valid() {
            return runtime.running() && !viewer.hasDisconnected() && runtime.portals().canManage(viewer, portal());
        }

        private void open() {
            if (!valid()) {
                return;
            }
            prompts.remove(viewer.getUUID());
            sessions.put(viewer.getUUID(), this);
            MinecraftInventoryMenu.open(viewer, Component.literal(portal().getName()), new MinecraftInventoryMenu.Actions(this::valid, this::render, this::click));
            if (page == Page.TEMPLATES) {
                loadTemplates();
            }
        }

        private void render(MinecraftInventoryMenu target) {
            menu = target;
            RuleDocument document = runtime.rules().document(portal());
            if (page == Page.PROFILE) {
                profile(document);
                return;
            }
            int count = switch (page) {
                case RULES -> document.rules().size();
                case LINES -> lines(document).size();
                case TEMPLATES -> templateNames.size();
                default -> 0;
            };
            index = RulesMenuModel.clampPage(index, count);
            int first = index * RulesMenuModel.ENTRIES_PER_PAGE;
            for (int slot = 0; slot < Math.min(RulesMenuModel.ENTRIES_PER_PAGE, count - first); slot++) {
                int selected = first + slot;
                switch (page) {
                    case RULES -> {
                        Rule rule = document.rules().get(selected);
                        put(slot, Items.PAPER, RulesMessages.MENU_RULE, Map.of("name", rule.id(), "value", RulesMenuModel.summary(rule)));
                    }
                    case LINES -> put(slot, Items.STRING, RulesMessages.MENU_LINE, Map.of("value", lines(document).get(selected)));
                    case TEMPLATES -> put(slot, Items.WRITTEN_BOOK, RulesMessages.MENU_TEMPLATE, Map.of("name", templateNames.get(selected)));
                    default -> { }
                }
            }
            if (page != Page.TEMPLATES) {
                put(47, Items.DYE.lime(), page == Page.RULES ? RulesMessages.MENU_ADD_RULE : RulesMessages.MENU_ADD_LINE, Map.of());
            }
            if (page == Page.LINES && rule(document) != null) {
                put(45, Items.LEVER, RulesMessages.MENU_DEFAULT_OUTCOME, Map.of("mode", rule(document).outcome().kind().name()));
            }
            put(49, Items.PAPER, RulesMessages.MENU_PAGE, Map.of("page", index + 1, "pages", RulesMenuModel.pageCount(count)));
            put(51, Items.ARROW, RulesMessages.MENU_BACK, Map.of());
        }

        private void profile(RuleDocument document) {
            TraversalProfile profile = document.profile();
            put(10, Items.CLOCK, RulesMessages.MENU_COOLDOWN, Map.of("seconds", profile.cooldownMillis() / 1000L));
            put(12, Items.COMPASS, RulesMessages.MENU_WARMUP, Map.of("seconds", profile.warmupMillis() / 1000L));
            put(14, Items.NAME_TAG, RulesMessages.MENU_GROUP, Map.of("value", profile.cooldownGroup().isEmpty() ? "-" : profile.cooldownGroup()));
            put(16, Items.AMETHYST_SHARD, RulesMessages.MENU_CHARGES, Map.of("count", profile.chargeCapacity()));
            put(20, Items.PISTON, RulesMessages.MENU_PUSHBACK, Map.of("value", profile.pushbackScale()));
            put(22, Items.NOTE_BLOCK, RulesMessages.MENU_SOUND, Map.of("value", profile.soundVolume()));
            put(24, Items.LEVER, RulesMessages.MENU_DEFAULT_OUTCOME, Map.of("mode", document.defaultOutcome().kind().name()));
            put(30, Items.BOOK, RulesMessages.MENU_RULES, Map.of("count", document.rules().size()));
            put(32, Items.WRITABLE_BOOK, RulesMessages.MENU_TEMPLATES, Map.of());
            put(49, Items.ARROW, RulesMessages.MENU_BACK, Map.of());
        }

        private List<String> lines(RuleDocument document) {
            Rule rule = rule(document);
            return rule == null ? List.of() : RulesMenuModel.lines(rule);
        }

        private Rule rule(RuleDocument document) {
            int selected = RulesMenuModel.indexOf(document, ruleId);
            return selected < 0 ? null : document.rules().get(selected);
        }

        private void click(MinecraftInventoryMenu.Click click) {
            if (!valid() || viewer.containerMenu != menu || click.menu() != menu) {
                return;
            }
            try {
                if (page == Page.PROFILE) {
                    profileClick(click);
                } else {
                    listClick(click);
                }
            } catch (IllegalArgumentException | RuleValidationException invalid) {
                notice(viewer, RulesMessages.NOTICE_INVALID, Map.of("reason", invalid.getMessage()));
            }
        }

        private void profileClick(MinecraftInventoryMenu.Click click) {
            RuleDocument document = runtime.rules().document(portal());
            TraversalProfile profile = document.profile();
            int step = (click.right() ? -1 : 1) * (click.shift() ? 10 : 1);
            long cooldown = profile.cooldownMillis();
            long warmup = profile.warmupMillis();
            double pushback = profile.pushbackScale();
            double volume = profile.soundVolume();
            int charges = profile.chargeCapacity();
            switch (click.slot()) {
                case 10 -> cooldown = Math.clamp(cooldown + step * 1000L, 0L, runtime.configuration().settings().getRules().cooldownMaxSeconds * 1000L);
                case 12 -> warmup = Math.clamp(warmup + step * 1000L, 0L, runtime.configuration().settings().getRules().warmupMaxSeconds * 1000L);
                case 14 -> { prompt(Input.GROUP); return; }
                case 16 -> charges = Math.max(0, charges + step);
                case 20 -> pushback = Math.clamp(pushback + (click.right() ? -0.25D : 0.25D), 0D, 10D);
                case 22 -> volume = Math.clamp(volume + (click.right() ? -0.25D : 0.25D), 0D, 10D);
                case 24 -> { change(current -> current.withDefaultOutcome(toggled(current.defaultOutcome()))); return; }
                case 30 -> { new Session(viewer, portalId, Page.RULES, "").open(); return; }
                case 32 -> { new Session(viewer, portalId, Page.TEMPLATES, "").open(); return; }
                case 49 -> { runtime.menus().open(viewer, portalId); return; }
                default -> { return; }
            }
            runtime.rules().setDocument(portal(), document.withProfile(new TraversalProfile(cooldown, profile.cooldownGroup(),
                warmup, pushback, volume, charges, Math.min(charges, profile.chargeRegenPerInterval()))));
            menu.refresh();
        }

        private void listClick(MinecraftInventoryMenu.Click click) {
            RuleDocument document = runtime.rules().document(portal());
            int selected = index * RulesMenuModel.ENTRIES_PER_PAGE + click.slot();
            if (click.slot() < RulesMenuModel.ENTRIES_PER_PAGE) {
                if (page == Page.RULES && selected < document.rules().size()) {
                    Rule chosen = document.rules().get(selected);
                    if (click.shift() && !click.right()) {
                        change(current -> RulesMenuModel.removeRule(current, chosen.id()));
                    } else {
                        new Session(viewer, portalId, Page.LINES, chosen.id()).open();
                    }
                } else if (page == Page.LINES && selected < lines(document).size() && click.shift() && !click.right()) {
                    change(current -> RulesMenuModel.replaceRule(current, RulesMenuModel.removeLine(rule(current), selected)));
                } else if (page == Page.TEMPLATES && selected < templateNames.size()) {
                    applyTemplate(templateNames.get(selected));
                }
                return;
            }
            switch (click.slot()) {
                case 45 -> {
                    if (page == Page.LINES && rule(document) != null) {
                        Rule current = rule(document);
                        change(existing -> RulesMenuModel.replaceRule(existing, new Rule(current.id(), current.conditions(),
                            toggled(current.outcome()), current.costs(), current.effects())));
                    }
                }
                case 47 -> { if (page != Page.TEMPLATES) { prompt(page == Page.RULES ? Input.RULE : Input.LINE); } }
                case 49 -> { index = Math.max(0, index + (click.right() ? -1 : 1)); menu.refresh(); }
                case 51 -> new Session(viewer, portalId, page == Page.LINES ? Page.RULES : Page.PROFILE, "").open();
                default -> { }
            }
        }

        private void change(UnaryOperator<RuleDocument> edit) {
            runtime.rules().setDocument(portal(), edit.apply(runtime.rules().document(portal())));
            menu.refresh();
        }

        private void prompt(Input input) {
            viewer.closeContainer();
            sessions.remove(viewer.getUUID());
            prompts.put(viewer.getUUID(), new Prompt(this, input, System.nanoTime() + 60_000_000_000L));
            TextKey key = switch (input) {
                case GROUP -> RulesMessages.PROMPT_GROUP;
                case RULE -> RulesMessages.PROMPT_RULE_ID;
                case LINE -> RulesMessages.PROMPT_LINE;
            };
            notice(viewer, key, Map.of("cancel", MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_INPUT_CANCEL, Map.of()).getString()));
        }

        private void loadTemplates() {
            RuleTemplates templates = templates();
            CompletableFuture.supplyAsync(templates::list).whenCompleteAsync((names, failure) -> {
                if (!valid() || viewer.containerMenu != menu) {
                    return;
                }
                if (failure != null) {
                    failed(failure);
                    return;
                }
                templateNames = names;
                menu.refresh();
            }, runtime.server());
        }

        private void applyTemplate(String name) {
            RuleTemplates templates = templates();
            CompletableFuture.supplyAsync(() -> templates.load(name)).whenCompleteAsync((document, failure) -> {
                if (!valid() || viewer.containerMenu != menu) {
                    return;
                }
                if (failure != null) {
                    failed(failure);
                    return;
                }
                if (document == null) {
                    notice(viewer, RulesMessages.COMMAND_TEMPLATE_MISSING, Map.of("name", name));
                    return;
                }
                try {
                    runtime.rules().setDocument(portal(), runtime.rules().authorDocument(viewer, document));
                    notice(viewer, RulesMessages.NOTICE_APPLIED, Map.of("name", name));
                    new Session(viewer, portalId, Page.PROFILE, "").open();
                } catch (IllegalArgumentException | RuleValidationException invalid) {
                    notice(viewer, RulesMessages.NOTICE_INVALID, Map.of("reason", invalid.getMessage()));
                }
            }, runtime.server());
        }

        private void failed(Throwable failure) {
            LOGGER.error("Could not read traversal rule templates for portal {}", portalId, failure);
            notice(viewer, RulesMessages.COMMAND_FAILED, Map.of("reason", failure.getMessage()));
        }

        private void put(int slot, Item item, LinesKey key, Map<String, ?> values) {
            menu.set(slot, MinecraftMenuText.item(viewer, item, key, values));
        }
    }

    private static RuleOutcome toggled(RuleOutcome current) {
        return current.allowed() ? RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()) : RuleOutcome.allow();
    }
}
