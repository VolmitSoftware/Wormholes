package art.arcane.wormholes.menu.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.menu.ChestClicks;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakeMenuViewer;
import art.arcane.wormholes.menu.FakeStrings;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuActionOrigin;
import art.arcane.wormholes.menu.MenuBinding;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLayouts;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuSession;
import art.arcane.wormholes.menu.MenuState;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.rules.Condition;
import art.arcane.wormholes.rules.Effect;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleAuthoring;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RulesMenuModel;
import art.arcane.wormholes.rules.TraversalProfile;

final class RulesMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PORTAL = FakeRulesPortals.PORTAL;
    private static final String CONSOLE_LINE = "kind=COMMAND;line=say hi;asConsole=true";

    private FakeMenuHost host;
    private FakeMenuViewer viewer;
    private FakeRulesPortals portals;
    private FakeRulesMenuHost rules;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        viewer = host.viewer(VIEWER, "Keeper");
        portals = new FakeRulesPortals();
        portals.managers.add(VIEWER);
        rules = new FakeRulesMenuHost();
        service = new MenuService(host, new MenuRegistry(List.of(new RulesMenuController(portals, rules))), () -> 1_000L);
    }

    @Test
    void profileMatchesTheChestWindowAndNativeTables() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 4, ChestPane.BROWN, null), build.chest());
        assertEquals(RulesRoutes.PROFILE, build.screen().screenId());
        assertEquals("Rules", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(MenuLayouts.mapOf(
            "rules-rules.menu.cooldown", "Number -3,0 minecraft:clock - {}",
            "rules-rules.menu.warmup", "Number -1,0 minecraft:compass - {}",
            "rules-group", "Text 1,0 minecraft:name_tag - {}",
            "rules-charges", "Number 3,0 minecraft:amethyst_shard - {LEFT=+, RIGHT=-}",
            "rules-rules.menu.pushback", "Number -2,1 minecraft:piston - {LEFT=+, RIGHT=-}",
            "rules-rules.menu.sound", "Number 0,1 minecraft:note_block - {LEFT=+, RIGHT=-}",
            "rules-default", "Toggle 2,1 minecraft:lever - {}",
            "rules-list", "Button -1,2 minecraft:book settings {}",
            "rules-templates", "Button 1,2 minecraft:writable_book atlas {}"), MenuLayouts.layout(build.screen()));
        assertTrue(MenuLayouts.bound(build));
    }

    @Test
    void effectiveChestClicksMatchTodaysHandlers() {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of()));
        rules.templateNames.add("gate");
        open();
        MenuScreen profile = host.chest.screen();
        assertEquals("{LEFT=+, RIGHT=-, SHIFT_LEFT=++, SHIFT_RIGHT=--}", clicks(profile, "rules-rules.menu.cooldown"));
        assertEquals("{LEFT=X}", clicks(profile, "rules-group"));
        assertEquals("{LEFT=+, RIGHT=-}", clicks(profile, "rules-charges"));
        assertEquals("{LEFT=+, RIGHT=-}", clicks(profile, "rules-rules.menu.sound"));
        assertEquals("{LEFT=P}", clicks(profile, "rules-default"));
        assertEquals("{LEFT=P}", clicks(profile, "rules-templates"));

        chest("rules-list");
        assertEquals("{LEFT=P, SHIFT_LEFT=T}", clicks(host.chest.screen(), "rules-rule-alpha"));
        assertEquals("{LEFT=X}", clicks(host.chest.screen(), "rules-add-rule"));
        assertEquals("{LEFT=P, RIGHT=S}", clicks(host.chest.screen(), "rules-page"));
        assertEquals("{LEFT=P}", clicks(host.chest.screen(), "rules-back"));
        chest("rules-rule-alpha");
        assertEquals("{SHIFT_LEFT=T}", clicks(host.chest.screen(), "rules-line-0"));
        service.open(VIEWER, RulesRoutes.templates(PORTAL));
        assertEquals("{LEFT=P!}", clicks(host.chest.screen(), "rules-template-gate"));
    }

    @Test
    void profileNumbersAndTextCarryTodaysRangesAndLore() {
        rules.document = RuleDocument.EMPTY.withProfile(new TraversalProfile(30_000L, "vip", 5_000L, 1.5D, 0.25D, 4, 2));
        open();
        MenuScreen screen = host.chest.screen();

        NumberElement cooldown = (NumberElement) element(screen, "rules-rules.menu.cooldown");
        assertEquals("§6Cooldown: 30s", cooldown.label());
        assertEquals(List.of("§7Left and right click to change, shift for 10s steps."), MenuLayouts.chestLore(cooldown));
        assertTrue(cooldown.description().isEmpty());
        assertEquals(List.of(30.0D, 0.0D, 3600.0D, 1.0D, 10.0D), List.of(cooldown.value(), cooldown.min(), cooldown.max(), cooldown.step(), cooldown.bigStep()));
        assertEquals(Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT,
            MenuInteraction.SET_VALUE), cooldown.interactions());
        NumberElement warmup = (NumberElement) element(screen, "rules-rules.menu.warmup");
        assertEquals(List.of(5.0D, 0.0D, 30.0D), List.of(warmup.value(), warmup.min(), warmup.max()));
        NumberElement charges = (NumberElement) element(screen, "rules-charges");
        assertEquals("§6Charges: 4", charges.label());
        assertEquals(List.of(4.0D, 0.0D, 100_000.0D, 1.0D), List.of(charges.value(), charges.min(), charges.max(), charges.step()));
        assertEquals(Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.SET_VALUE), charges.interactions());
        NumberElement pushback = (NumberElement) element(screen, "rules-rules.menu.pushback");
        assertEquals("§6Pushback: 1.50", pushback.label());
        assertEquals(List.of(1.5D, 0.0D, 10.0D, 0.25D), List.of(pushback.value(), pushback.min(), pushback.max(), pushback.step()));
        assertEquals("§6Refusal volume: 0.25", element(screen, "rules-rules.menu.sound").label());
        assertEquals("§6Rules (0)", element(screen, "rules-list").label());

        TextElement group = (TextElement) element(screen, "rules-group");
        assertEquals("§6Cooldown group: vip", group.label());
        assertEquals("vip", group.value());
        assertEquals("Group name", group.placeholder());
        assertEquals(List.of("§bType the cooldown group name (or \"cancel\"):"), group.chestPrompt());
        assertEquals(Set.of(MenuInteraction.SET_TEXT), group.interactions());
        assertEquals(ChestReopen.ALWAYS, group.chestReopen());
        assertEquals(RulesRoutes.profile(PORTAL), group.chestCancelRoute());
    }

    @Test
    void cooldownAndWarmupStepBySecondsAndFloorAtZero() {
        open();

        chest("rules-rules.menu.cooldown", MenuInteraction.INCREMENT);
        assertEquals(1_000L, rules.document.profile().cooldownMillis());
        chest("rules-rules.menu.cooldown", MenuInteraction.BIG_INCREMENT);
        assertEquals(11_000L, rules.document.profile().cooldownMillis());
        chest("rules-rules.menu.cooldown", MenuInteraction.DECREMENT);
        assertEquals(10_000L, rules.document.profile().cooldownMillis());
        chest("rules-rules.menu.cooldown", MenuInteraction.BIG_DECREMENT);
        chest("rules-rules.menu.cooldown", MenuInteraction.BIG_DECREMENT);
        assertEquals(0L, rules.document.profile().cooldownMillis());
        chest("rules-rules.menu.warmup", MenuInteraction.BIG_INCREMENT);
        assertEquals(10_000L, rules.document.profile().warmupMillis());
        assertEquals("§6Warmup: 10s", element(host.chest.screen(), "rules-rules.menu.warmup").label());
        assertEquals(RulesRoutes.PROFILE, host.chest.screen().screenId());
        assertTrue(host.chest.notices.isEmpty());
    }

    @Test
    void cooldownsPastTheConfiguredMaximumAreRefusedByTheStore() {
        rules.limits.cooldownMaxSeconds = 1;
        open();

        chest("rules-rules.menu.cooldown", MenuInteraction.INCREMENT);
        chest("rules-rules.menu.cooldown", MenuInteraction.INCREMENT);

        assertEquals(1, rules.stored.size());
        assertEquals(1_000L, rules.document.profile().cooldownMillis());
        assertTrue(host.chest.lastNotice().startsWith("That could not be applied: "), host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastNotice().chestChannel());
    }

    @Test
    void nativeSlidersSetExactValues() throws MenuProtocolException {
        hello();
        open();

        nativeAction("rules-rules.menu.cooldown", MenuInteraction.SET_VALUE, MenuValue.number(45.0D));
        nativeAction("rules-charges", MenuInteraction.SET_VALUE, MenuValue.number(12.0D));
        nativeAction("rules-rules.menu.sound", MenuInteraction.SET_VALUE, MenuValue.number(2.5D));

        TraversalProfile profile = rules.document.profile();
        assertEquals(45_000L, profile.cooldownMillis());
        assertEquals(12, profile.chargeCapacity());
        assertEquals(2.5D, profile.soundVolume());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("§6Refusal volume: 2.50", update.screen().element("rules-rules.menu.sound").orElseThrow().label());
    }

    @Test
    void chargesKeepRegenWithinCapacity() {
        rules.document = RuleDocument.EMPTY.withProfile(new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, 2, 2));
        open();

        chest("rules-charges", MenuInteraction.DECREMENT);
        assertEquals(1, rules.document.profile().chargeCapacity());
        assertEquals(1, rules.document.profile().chargeRegenPerInterval());
        chest("rules-charges", MenuInteraction.DECREMENT);
        chest("rules-charges", MenuInteraction.DECREMENT);
        assertEquals(0, rules.document.profile().chargeCapacity());
        assertEquals(0, rules.document.profile().chargeRegenPerInterval());
        chest("rules-charges", MenuInteraction.INCREMENT);
        assertEquals(1, rules.document.profile().chargeCapacity());
        assertEquals(0, rules.document.profile().chargeRegenPerInterval());
    }

    @Test
    void pushbackAndSoundStepByQuartersWithinZeroToTen() {
        rules.document = RuleDocument.EMPTY.withProfile(new TraversalProfile(0L, "", 0L, 9.9D, 0.1D, 0, 0));
        open();

        chest("rules-rules.menu.pushback", MenuInteraction.INCREMENT);
        chest("rules-rules.menu.sound", MenuInteraction.DECREMENT);
        assertEquals(10.0D, rules.document.profile().pushbackScale());
        assertEquals(0.0D, rules.document.profile().soundVolume());
        chest("rules-rules.menu.pushback", MenuInteraction.DECREMENT);
        chest("rules-rules.menu.sound", MenuInteraction.INCREMENT);
        assertEquals(9.75D, rules.document.profile().pushbackScale());
        assertEquals(0.25D, rules.document.profile().soundVolume());
    }

    @Test
    void groupTextIsTrimmedIntoTheProfile() {
        open();

        chestText("rules-group", "  vip ");

        assertEquals("vip", rules.document.profile().cooldownGroup());
        assertEquals("§6Cooldown group: vip", element(host.chest.screen(), "rules-group").label());
        assertEquals(RulesRoutes.PROFILE, host.chest.screen().screenId());
    }

    @Test
    void defaultOutcomeTogglesBetweenAllowAndTheDefaultDenial() {
        open();

        chest("rules-default");
        assertEquals(RuleOutcome.deny("rules.denied.default"), rules.document.defaultOutcome());
        ToggleElement denied = (ToggleElement) element(host.chest.screen(), "rules-default");
        assertFalse(denied.value());
        assertEquals("Toggle 2,1 minecraft:lever* - {}", MenuLayouts.describe(denied));
        assertEquals("§6Default: DENY", denied.label());

        chest("rules-default");
        assertEquals(RuleOutcome.allow(), rules.document.defaultOutcome());
        assertTrue(((ToggleElement) element(host.chest.screen(), "rules-default")).value());
    }

    @Test
    void ruleListMatchesTheChestWindowAndNativeTables() {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of()), rule("beta", List.of(), List.of()));
        open();
        chest("rules-list");
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 6, ChestPane.GRAY, null, RulesMenuController.LIST_WINDOW_KEY), build.chest());
        assertEquals("Rule List", build.screen().title());
        assertEquals(new MenuPaging(0, 1), build.screen().paging());
        assertTrue(build.screen().canGoBack());
        assertEquals(MenuLayouts.mapOf(
            "rules-rule-alpha", "ListEntry -4,0 minecraft:paper - {LEFT=P, SHIFT_LEFT=T}",
            "rules-rule-beta", "ListEntry -3,0 minecraft:paper - {LEFT=P, SHIFT_LEFT=T}",
            "rules-add-rule", "Text -2,5 minecraft:lime_dye - {}",
            "rules-page", "Button 0,5 minecraft:paper - {LEFT=P, RIGHT=S} hidden",
            "rules-back", "Button 2,5 minecraft:arrow - {} hidden"), MenuLayouts.layout(build.screen()));
        ListEntryElement alpha = (ListEntryElement) element(build.screen(), "rules-rule-alpha");
        assertEquals("§6alpha", alpha.label());
        assertEquals(List.of("§7ALLOW 1/0/0 §8- shift click removes the rule."), MenuLayouts.chestLore(alpha));
        assertEquals(List.of("ALLOW 1/0/0"), alpha.common().nativeDescription());
        assertEquals(List.of(MenuInteraction.TERTIARY), alpha.secondaryActions().stream().map(action -> action.interaction()).toList());
        assertEquals("Remove", alpha.secondaryActions().getFirst().label());
        TextElement add = (TextElement) element(build.screen(), "rules-add-rule");
        assertEquals(List.of("§bType an id for the new rule (or \"cancel\"):"), add.chestPrompt());
        assertEquals("Rule id", add.placeholder());
        assertEquals(ChestReopen.ALWAYS, add.chestReopen());
        assertEquals(RulesRoutes.profile(PORTAL), add.chestCancelRoute());
        assertEquals("§7Page 1 of 1", element(build.screen(), "rules-page").label());
        assertTrue(MenuLayouts.bound(build));
    }

    @Test
    void ruleListPagesFortyFiveAtATime() throws MenuProtocolException {
        List<Rule> many = new ArrayList<>(50);
        for (int index = 0; index < 50; index++) {
            many.add(rule("r" + index, List.of(), List.of()));
        }
        rules.document = RuleDocument.EMPTY.withRules(many);
        open();
        chest("rules-list");
        assertEquals(48, host.chest.screen().elements().size());
        assertEquals("§7Page 1 of 2", element(host.chest.screen(), "rules-page").label());

        chest("rules-page");
        MenuScreen second = host.chest.screen();
        assertEquals(new MenuPaging(1, 2), second.paging());
        assertEquals(8, second.elements().size());
        assertEquals("ListEntry -4,0 minecraft:paper - {LEFT=P, SHIFT_LEFT=T}", MenuLayouts.describe(element(second, "rules-rule-r45")));
        chest("rules-page");
        assertEquals(new MenuPaging(1, 2), host.chest.screen().paging());
        chest("rules-page", MenuInteraction.SECONDARY);
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());

        service.quit(VIEWER);
        hello();
        open();
        nativeAction("rules-list", MenuInteraction.PRIMARY, null);
        receive(new MenuClientMessage.Page(revision(), 1));
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals(new MenuPaging(1, 2), update.screen().paging());
        assertTrue(update.screen().element("rules-page").isEmpty());
        assertTrue(update.screen().element("rules-rule-r49").isPresent());
    }

    @Test
    void nativeRuleRowsKeepTheSummaryWithoutTheClickHint() throws MenuProtocolException {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of(new Effect.Message("hi"))));
        hello();
        open();

        nativeAction("rules-list", MenuInteraction.PRIMARY, null);

        MenuElement alpha = nativeScreen().element("rules-rule-alpha").orElseThrow();
        assertEquals(List.of("ALLOW 1/0/1"), alpha.description());
        assertTrue(alpha.common().chestHints().isEmpty());
        assertNull(alpha.common().nativeDescription());
    }

    @Test
    void listNavigationOpensTheFirstPageLikeTodaysMenus() {
        List<Rule> many = new ArrayList<>(50);
        for (int index = 0; index < 50; index++) {
            many.add(rule(String.format("r%02d", index), List.of(), List.of()));
        }
        rules.document = RuleDocument.EMPTY.withRules(many);
        open();
        chest("rules-list");
        chest("rules-page");
        assertEquals(new MenuPaging(1, 2), host.chest.screen().paging());

        chest("rules-rule-r45");
        chest("rules-back");
        assertEquals(RulesRoutes.LIST, host.chest.screen().screenId());
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());

        chest("rules-page");
        chest("rules-back");
        chest("rules-list");
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());
    }

    @Test
    void shiftClickRemovesARuleWithAHudNotice() {
        rules.document = document(rule("alpha", List.of(), List.of()), rule("beta", List.of(), List.of()));
        open();
        chest("rules-list");

        chest("rules-rule-alpha", MenuInteraction.TERTIARY);

        assertEquals(List.of("beta"), ruleIds());
        assertEquals("Removed rule alpha.", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastNotice().chestChannel());
        assertEquals(RulesRoutes.LIST, host.chest.screen().screenId());
        assertTrue(host.chest.screen().element("rules-rule-alpha").isEmpty());
    }

    @Test
    void addingARuleStoresItAndAnnouncesTheTypedName() {
        open();
        chest("rules-list");

        chestText("rules-add-rule", " gate ");

        assertEquals(List.of("gate"), ruleIds());
        assertEquals("Added rule gate.", host.chest.lastNotice());
        assertEquals(RulesRoutes.LIST, host.chest.screen().screenId());
        assertEquals("§6gate", element(host.chest.screen(), "rules-rule-gate").label());
    }

    @Test
    void theRuleListAndRulePagesShareOneChestWindowWhileProfileAndTemplatesKeepTheirOwn() {
        rules.document = document(rule("alpha", List.of(), List.of()));
        open();
        List<String> keys = new ArrayList<>();
        keys.add(host.chest.last().windowKey());
        for (String step : List.of("rules-list", "rules-rule-alpha", "rules-back", "rules-back", "rules-templates")) {
            chest(step);
            keys.add(host.chest.last().windowKey());
        }

        assertEquals(List.of(RulesRoutes.PROFILE, RulesMenuController.LIST_WINDOW_KEY, RulesMenuController.LIST_WINDOW_KEY,
            RulesMenuController.LIST_WINDOW_KEY, RulesRoutes.PROFILE, RulesRoutes.TEMPLATES), keys);
    }

    @Test
    void rulePageMatchesTheChestWindowAndNativeTables() {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of(new Effect.Message("hello"))));
        open();
        chest("rules-list");
        chest("rules-rule-alpha");
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 6, ChestPane.CYAN, null, RulesMenuController.LIST_WINDOW_KEY), build.chest());
        assertEquals(RulesRoutes.RULE, build.screen().screenId());
        assertEquals("Rule", build.screen().title());
        assertEquals(new MenuPaging(0, 1), build.screen().paging());
        assertEquals(MenuLayouts.mapOf(
            "rules-line-0", "ListEntry -4,0 minecraft:string - {SHIFT_LEFT=T}",
            "rules-line-1", "ListEntry -3,0 minecraft:string - {SHIFT_LEFT=T}",
            "rules-add-line", "Text -2,5 minecraft:lime_dye - {}",
            "rules-page", "Button 0,5 minecraft:paper - {LEFT=P, RIGHT=S} hidden",
            "rules-back", "Button 2,5 minecraft:arrow - {} hidden"), MenuLayouts.layout(build.screen()));
        ListEntryElement line = (ListEntryElement) element(build.screen(), "rules-line-0");
        assertEquals("§fkind=PERMISSION;node=group.vip", line.label());
        assertEquals(List.of("§7Shift click removes this line."), MenuLayouts.chestLore(line));
        assertEquals(Set.of(MenuInteraction.TERTIARY), line.interactions());
        TextElement add = (TextElement) element(build.screen(), "rules-add-line");
        assertEquals(List.of("§bType a rule line, for example kind=PERMISSION;node=group.vip (or \"cancel\"):"), add.chestPrompt());
        assertEquals("Rule line", add.placeholder());
        assertEquals(ChestReopen.ALWAYS, add.chestReopen());
        assertEquals(RulesRoutes.profile(PORTAL), add.chestCancelRoute());
        assertTrue(MenuLayouts.bound(build));
    }

    @Test
    void ruleLineLabelsShowTheWholeLineAndTrimOnlyToTheByteLimit() {
        Rule command = rule("alpha", List.of(), List.of(new Effect.Command("x".repeat(256), false, null)));
        Rule wide = rule("beta", List.of(), List.of(new Effect.Command("é".repeat(600), false, null)));
        String line = RulesMenuModel.lines(command).getFirst();
        String wideLine = "§f" + RulesMenuModel.lines(wide).getFirst();
        rules.document = document(command, wide);

        openRule("alpha");
        assertTrue(line.length() > 256 + "kind=COMMAND;line=".length());
        assertEquals("§f" + line, element(host.chest.screen(), "rules-line-0").label());

        service.open(VIEWER, RulesRoutes.rule(PORTAL, "beta"));
        String label = element(host.chest.screen(), "rules-line-0").label();
        int bytes = MenuText.encode(label).length;
        assertTrue(MenuText.encode(wideLine).length > MenuLimits.MAX_STRING_BYTES);
        assertTrue(bytes <= MenuLimits.MAX_STRING_BYTES && bytes > MenuLimits.MAX_STRING_BYTES - 2, Integer.toString(bytes));
        assertTrue(wideLine.startsWith(label));
    }

    @Test
    void ruleLineLabelsFitTheByteLimitWhenTheLocaleDropsFormattingAroundEmptyText() {
        Rule probe = rule("alpha", List.of(), List.of(new Effect.Command("x", false, null)));
        int fixed = MenuText.encode(RulesMenuModel.lines(probe).getFirst()).length - 1;
        Rule command = rule("alpha", List.of(), List.of(new Effect.Command("x".repeat(MenuLimits.MAX_STRING_BYTES - 1 - fixed), false, null)));
        String line = RulesMenuModel.lines(command).getFirst();
        rules.document = document(command);
        MenuViewer collapsing = new LocaleViewer(new CollapsingStrings());

        MenuBuild build = new RulesMenuController(portals, rules).build(new MenuContext(collapsing, RulesRoutes.rule(PORTAL, "alpha"), 0,
            new MenuState(), null, MenuActionOrigin.CHEST));

        String label = element(build.screen(), "rules-line-0").label();
        assertEquals(MenuLimits.MAX_STRING_BYTES - 1, MenuText.encode(line).length);
        assertEquals(MenuLimits.MAX_STRING_BYTES, MenuText.encode(label).length);
        assertEquals("§f" + line.substring(0, line.length() - 2), label);
    }

    @Test
    void nativeRuleDescriptionsUseTheRawSummaryWhateverTheLocaleDoes() {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of()));
        RulesMenuController controller = new RulesMenuController(portals, rules);
        MenuViewer trailing = new LocaleViewer(new RuleRowStrings(List.of("&6{name}", "&8Outcome and counts: &7{value} &8- shift click removes it.")));
        MenuViewer wrapping = new LocaleViewer(new RuleRowStrings(List.of("&6{name}", "&8Outcome and counts: &7ALLOW", "&71/0/0 &8- shift click removes it.")));

        for (MenuViewer localized : List.of(trailing, wrapping)) {
            MenuBuild build = controller.build(new MenuContext(localized, RulesRoutes.list(PORTAL), 0, new MenuState(), null, MenuActionOrigin.NATIVE));
            ListEntryElement alpha = (ListEntryElement) element(build.screen(), "rules-rule-alpha");
            assertEquals(List.of("ALLOW 1/0/0"), alpha.common().nativeDescription());
            assertEquals(List.of("ALLOW 1/0/0"), alpha.common().wireDescription());
        }
        MenuBuild trailingBuild = controller.build(new MenuContext(trailing, RulesRoutes.list(PORTAL), 0, new MenuState(), null,
            MenuActionOrigin.CHEST));
        assertEquals(List.of("§8Outcome and counts: §7ALLOW 1/0/0 §8- shift click removes it."),
            MenuLayouts.chestLore(element(trailingBuild.screen(), "rules-rule-alpha")));
    }

    @Test
    void ruleLinesAreAddedValidatedAndRemoved() {
        rules.document = document(rule("alpha", List.of(), List.of()));
        open();
        chest("rules-list");
        chest("rules-rule-alpha");

        chestText("rules-add-line", " kind=PERMISSION;node=group.vip ");
        assertEquals(List.of(new Condition.Permission("group.vip")), rules.document.rules().getFirst().conditions());
        assertEquals("§fkind=PERMISSION;node=group.vip", element(host.chest.screen(), "rules-line-0").label());
        assertTrue(host.chest.notices.isEmpty());

        chestText("rules-add-line", "kind=NOPE");
        assertEquals(1, rules.stored.size());
        assertEquals("That could not be applied: unknown kind 'NOPE'", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastNotice().chestChannel());

        chest("rules-line-0", MenuInteraction.TERTIARY);
        assertTrue(rules.document.rules().getFirst().conditions().isEmpty());
        assertEquals(RulesRoutes.RULE, host.chest.screen().screenId());
    }

    @Test
    void consoleEffectsFromNonAdministratorsAreRefusedWithTodaysNotice() {
        rules.document = document(rule("alpha", List.of(), List.of()));
        openRule("alpha");

        chestText("rules-add-line", CONSOLE_LINE);

        assertTrue(rules.stored.isEmpty());
        assertEquals("That could not be applied: " + RuleAuthoring.CONSOLE_EFFECT_REFUSED, host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastNotice().chestChannel());
        assertTrue(rules.document.rules().getFirst().effects().isEmpty());
    }

    @Test
    void consoleEffectsNeedTheConfigEvenForAdministrators() {
        viewer.administrator = true;
        rules.limits.commandEffectsConsoleAllowed = false;
        rules.document = document(rule("alpha", List.of(), List.of()));
        openRule("alpha");

        chestText("rules-add-line", CONSOLE_LINE);
        assertTrue(rules.stored.isEmpty());
        assertEquals("That could not be applied: " + RuleAuthoring.CONSOLE_EFFECT_REFUSED, host.chest.lastNotice());

        rules.limits.commandEffectsConsoleAllowed = true;
        chestText("rules-add-line", CONSOLE_LINE);
        assertEquals(List.of(new Effect.Command("say hi", true, VIEWER)), rules.document.rules().getFirst().effects());
    }

    @Test
    void editsThatAddNoLineKeepStoredCommandEffectsAndTheirAuthors() {
        UUID author = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
        Effect console = new Effect.Command("say hi", true, author);
        Effect player = new Effect.Command("spawn", false, author);
        rules.document = document(rule("alpha", List.of(), List.of(console, player)), rule("beta", List.of(new Condition.Permission("group.vip")),
            List.of()));
        open();

        chest("rules-rules.menu.cooldown", MenuInteraction.INCREMENT);
        chest("rules-list");
        chestText("rules-add-rule", "gamma");
        chest("rules-rule-beta");
        chest("rules-line-0", MenuInteraction.TERTIARY);

        assertEquals(3, rules.stored.size());
        assertEquals(1_000L, rules.document.profile().cooldownMillis());
        assertEquals(List.of("alpha", "beta", "gamma"), ruleIds());
        assertTrue(rules.document.rules().get(1).conditions().isEmpty());
        assertEquals(List.of(console, player), rules.document.rules().getFirst().effects());
    }

    @Test
    void addingAnyLineAuthorsTheWholeDocumentLikeTodaysModdedMenu() {
        UUID author = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
        rules.document = document(rule("alpha", List.of(), List.of(new Effect.Command("say hi", true, author),
            new Effect.Command("spawn", false, author))), rule("beta", List.of(), List.of()));
        openRule("beta");

        chestText("rules-add-line", "kind=PERMISSION;node=group.vip");
        assertTrue(rules.stored.isEmpty());
        assertEquals("That could not be applied: " + RuleAuthoring.CONSOLE_EFFECT_REFUSED, host.chest.lastNotice());

        viewer.administrator = true;
        rules.limits.commandEffectsConsoleAllowed = true;
        chestText("rules-add-line", "kind=PERMISSION;node=group.vip");
        assertEquals(List.of(new Effect.Command("say hi", true, VIEWER), new Effect.Command("spawn", false, VIEWER)),
            rules.document.rules().getFirst().effects());
        assertEquals(List.of(new Condition.Permission("group.vip")), rules.document.rules().get(1).conditions());
    }

    @Test
    void templatesWithConsoleEffectsNeedAnAdministratorAndTheConfig() {
        UUID author = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
        rules.templateNames.add("gate");
        rules.templates.put("gate", document(rule("members", List.of(), List.of(new Effect.Command("say hi", true, author)))));
        rules.limits.commandEffectsConsoleAllowed = false;
        open();
        chest("rules-templates");

        service.chestAction(VIEWER, new MenuChestAction("rules-template-gate", MenuInteraction.PRIMARY, null, true, null));
        assertTrue(rules.stored.isEmpty());
        assertEquals("That could not be applied: " + RuleAuthoring.CONSOLE_EFFECT_REFUSED, host.chest.lastNotice());

        viewer.administrator = true;
        service.chestAction(VIEWER, new MenuChestAction("rules-template-gate", MenuInteraction.PRIMARY, null, true, null));
        assertTrue(rules.stored.isEmpty());

        rules.limits.commandEffectsConsoleAllowed = true;
        service.chestAction(VIEWER, new MenuChestAction("rules-template-gate", MenuInteraction.PRIMARY, null, true, null));
        assertEquals(List.of(new Effect.Command("say hi", true, VIEWER)), rules.document.rules().getFirst().effects());
        assertEquals("Applied template gate.", host.chest.lastNotice());
    }

    @Test
    void templatesMatchTheChestWindowAndApplyPreconfirmedFromTheChest() {
        rules.templateNames.addAll(List.of("gate", "vip"));
        RuleDocument template = document(rule("members", List.of(new Condition.Permission("group.member")), List.of()));
        rules.templates.put("gate", template);
        open();
        chest("rules-templates");
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 6, ChestPane.LIGHT_BLUE, null), build.chest());
        assertEquals("Rule Templates", build.screen().title());
        assertEquals(MenuLayouts.mapOf(
            "rules-template-gate", "ListEntry -4,0 minecraft:written_book - {LEFT=P!}",
            "rules-template-vip", "ListEntry -3,0 minecraft:written_book - {LEFT=P!}",
            "rules-page", "Button 0,5 minecraft:paper - {LEFT=P, RIGHT=S} hidden",
            "rules-back", "Button 2,5 minecraft:arrow - {} hidden"), MenuLayouts.layout(build.screen()));
        assertEquals("§fgate", element(build.screen(), "rules-template-gate").label());
        assertTrue(MenuLayouts.bound(build));

        service.chestAction(VIEWER, new MenuChestAction("rules-template-gate", MenuInteraction.PRIMARY, null, true, null));
        assertEquals(List.of("members"), ruleIds());
        assertEquals("Applied template gate.", host.chest.lastNotice());
        assertEquals(RulesRoutes.TEMPLATES, host.chest.screen().screenId());

        service.chestAction(VIEWER, new MenuChestAction("rules-template-vip", MenuInteraction.PRIMARY, null, true, null));
        assertEquals(1, rules.stored.size());
        assertEquals("There is no rule template named vip.", host.chest.lastNotice());
    }

    @Test
    void nativeTemplatesAskBeforeReplacingTheRules() throws MenuProtocolException {
        rules.templateNames.add("gate");
        rules.templates.put("gate", document(rule("members", List.of(), List.of())));
        hello();
        open();
        nativeAction("rules-templates", MenuInteraction.PRIMARY, null);

        nativeAction("rules-template-gate", MenuInteraction.PRIMARY, null);
        MenuServerMessage.Open confirm = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertTrue(rules.stored.isEmpty());
        assertEquals("Load gate?", confirm.screen().element(MenuSession.CONFIRM_HEADER).orElseThrow().label());
        assertEquals(List.of("The template replaces every rule on this portal."), confirm.screen().confirmation().messageLines());
        assertEquals("Load", confirm.screen().element(MenuSession.CONFIRM_ACCEPT).orElseThrow().label());

        receive(new MenuClientMessage.Confirm(revision(), true));
        assertEquals(List.of("members"), ruleIds());
        MenuServerMessage.Feedback feedback = assertInstanceOf(MenuServerMessage.Feedback.class, host.lastMessage());
        assertEquals("Applied template gate.", feedback.feedback().text());
    }

    @Test
    void chestBackElementsPopToTheirParentScreens() {
        rules.document = document(rule("alpha", List.of(), List.of()));
        open();
        chest("rules-list");
        chest("rules-rule-alpha");

        chest("rules-back");
        assertEquals(RulesRoutes.LIST, host.chest.screen().screenId());
        chest("rules-back");
        assertEquals(RulesRoutes.PROFILE, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
        chest("rules-templates");
        chest("rules-back");
        assertEquals(RulesRoutes.PROFILE, host.chest.screen().screenId());

        service.open(VIEWER, RulesRoutes.rule(PORTAL, "alpha"));
        chest("rules-back");
        assertEquals(RulesRoutes.LIST, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
    }

    @Test
    void everyMutatingElementCarriesTheManageGuard() {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of()));
        rules.templateNames.add("gate");
        FakeMenuViewer stranger = host.viewer(UUID.fromString("00000000-0000-0000-0000-0000000000d2"), "Stranger");
        for (MenuRoute route : List.of(RulesRoutes.profile(PORTAL), RulesRoutes.list(PORTAL), RulesRoutes.rule(PORTAL, "alpha"),
            RulesRoutes.templates(PORTAL))) {
            service.open(VIEWER, route);
            MenuBuild build = host.chest.last();
            for (MenuElement element : build.screen().elements()) {
                for (MenuInteraction interaction : element.interactions()) {
                    MenuBinding binding = build.bindings().find(element.id()).orElseThrow();
                    MenuContext context = new MenuContext(stranger, route, 0, new MenuState(), null, MenuActionOrigin.CHEST);
                    MenuDecision decision = binding.guard().check(context, new MenuAction(1L, element.id(), interaction, null));
                    MenuDecision.Deny deny = assertInstanceOf(MenuDecision.Deny.class, decision, route.screen() + "/" + element.id());
                    assertTrue(deny.close());
                    assertEquals("§cOnly the portal owner or an administrator can edit this portal.", deny.notice().feedback().text());
                }
            }
        }
    }

    @Test
    void viewersWhoLoseManageRightsAreRejectedWithoutTouchingTheRules() {
        rules.document = document(rule("alpha", List.of(new Condition.Permission("group.vip")), List.of()));
        rules.templateNames.add("gate");
        rules.templates.put("gate", RuleDocument.EMPTY);
        for (MenuRoute route : List.of(RulesRoutes.profile(PORTAL), RulesRoutes.list(PORTAL), RulesRoutes.rule(PORTAL, "alpha"),
            RulesRoutes.templates(PORTAL))) {
            for (String elementId : interactiveElements(route)) {
                portals.managers.add(VIEWER);
                service.open(VIEWER, route);
                portals.managers.clear();
                MenuElement element = element(host.chest.screen(), elementId);
                for (MenuInteraction interaction : element.interactions()) {
                    MenuValue value = switch (interaction) {
                        case SET_TEXT -> MenuValue.text("x");
                        case SET_VALUE -> MenuValue.number(((NumberElement) element).min());
                        default -> null;
                    };
                    service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, value, true, null));
                }
                assertTrue(rules.stored.isEmpty(), route.screen() + "/" + elementId);
                assertEquals(List.of("close", "notice:HUD"), lastEvents(2), route.screen() + "/" + elementId);
            }
        }
        portals.managers.clear();
        service.open(VIEWER, RulesRoutes.profile(PORTAL));
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
    }

    @Test
    void staleNativeRevisionsNeverReachTheStore() throws MenuProtocolException {
        hello();
        open();
        long stale = revision();
        nativeAction("rules-rules.menu.cooldown", MenuInteraction.INCREMENT, null);
        assertEquals(1, rules.stored.size());

        receive(new MenuClientMessage.Action(new MenuAction(stale, "rules-rules.menu.cooldown", MenuInteraction.INCREMENT, null)));

        assertEquals(1, rules.stored.size());
        MenuServerMessage.Update resent = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", resent.screen().feedback().text());
        assertEquals("§6Cooldown: 1s", resent.screen().element("rules-rules.menu.cooldown").orElseThrow().label());
    }

    @Test
    void missingRulesOrVanishedRulesCloseTheMenu() {
        rules.present = false;
        service.open(VIEWER, RulesRoutes.profile(PORTAL));
        assertTrue(host.chest.builds.isEmpty());
        assertEquals("That option is not available right now.", host.chest.lastNotice());

        rules.present = true;
        rules.document = document(rule("alpha", List.of(), List.of()));
        openRule("alpha");
        rules.document = RuleDocument.EMPTY;
        service.invalidate(new MenuScope.Portal(PORTAL));

        assertEquals(List.of("close", "notice:NONE"), lastEvents(2));
        assertTrue(rules.stored.isEmpty());
    }

    @Test
    void portalInvalidationRepaintsOnlyThatPortal() {
        RulesMenuController controller = new RulesMenuController(portals, rules);
        MenuRoute profile = RulesRoutes.profile(PORTAL);

        assertEquals(0, controller.refreshIntervalTicks(profile));
        assertTrue(controller.touches(profile, new MenuScope.Portal(PORTAL)));
        assertFalse(controller.touches(profile, new MenuScope.Portal(UUID.randomUUID())));
        assertFalse(controller.touches(profile, new MenuScope.Viewer(VIEWER)));

        open();
        rules.document = RuleDocument.EMPTY.withProfile(new TraversalProfile(7_000L, "", 0L, 1.0D, 1.0D, 0, 0));
        service.invalidate(new MenuScope.Portal(PORTAL));
        assertEquals("§6Cooldown: 7s", element(host.chest.screen(), "rules-rules.menu.cooldown").label());
    }

    private List<String> interactiveElements(MenuRoute route) {
        portals.managers.add(VIEWER);
        service.open(VIEWER, route);
        List<String> ids = new ArrayList<>();
        for (MenuElement element : host.chest.screen().elements()) {
            if (!element.interactions().isEmpty()) {
                ids.add(element.id());
            }
        }
        service.quit(VIEWER);
        return ids;
    }

    private static String clicks(MenuScreen screen, String elementId) {
        return MenuLayouts.clicks(ChestClicks.resolve(element(screen, elementId)));
    }

    private List<String> lastEvents(int count) {
        return host.chest.events.subList(host.chest.events.size() - count, host.chest.events.size());
    }

    private List<String> ruleIds() {
        return rules.document.rules().stream().map(Rule::id).toList();
    }

    private MenuNotice lastNotice() {
        return host.chest.notices.get(host.chest.notices.size() - 1);
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen screen = host.chest.screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Default", MenuText.stripColors(MenuScreens.element(screen, "rules-default").common().wireLabel()));
    }

    private void open() {
        service.open(VIEWER, RulesRoutes.profile(PORTAL));
    }

    private void openRule(String ruleId) {
        open();
        chest("rules-list");
        chest("rules-rule-" + ruleId);
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void chest(String elementId) {
        chest(elementId, MenuInteraction.PRIMARY);
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private void chestText(String elementId, String text) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, MenuInteraction.SET_TEXT, MenuValue.text(text), false, null));
    }

    private void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(revision(), elementId, interaction, value)));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MenuScreen nativeScreen() throws MenuProtocolException {
        List<MenuServerMessage> messages = host.serverMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            MenuServerMessage message = messages.get(index);
            if (message instanceof MenuServerMessage.Open opened) {
                return opened.screen();
            }
            if (message instanceof MenuServerMessage.Update update) {
                return update.screen();
            }
        }
        throw new IllegalStateException("no native screen was sent");
    }

    private long revision() throws MenuProtocolException {
        List<MenuServerMessage> messages = host.serverMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            MenuServerMessage message = messages.get(index);
            if (message instanceof MenuServerMessage.Open opened) {
                return opened.screen().revision();
            }
            if (message instanceof MenuServerMessage.Update update) {
                return update.screen().revision();
            }
        }
        throw new IllegalStateException("no native screen was sent");
    }

    private static RuleDocument document(Rule... rules) {
        return RuleDocument.EMPTY.withRules(List.of(rules));
    }

    private static Rule rule(String id, List<Condition> conditions, List<Effect> effects) {
        return new Rule(id, conditions, RuleOutcome.allow(), List.of(), effects);
    }

    private static MenuElement element(MenuScreen screen, String id) {
        return screen.element(id).orElseThrow();
    }

    private record LocaleViewer(MenuStrings strings) implements MenuViewer {
        @Override
        public UUID id() {
            return VIEWER;
        }

        @Override
        public String name() {
            return "Keeper";
        }

        @Override
        public boolean hasPermission(String node) {
            return false;
        }

        @Override
        public boolean administrator() {
            return false;
        }
    }

    private static final class RuleRowStrings implements MenuStrings {
        private final FakeStrings fallback = new FakeStrings();
        private final List<String> ruleRow;

        private RuleRowStrings(List<String> ruleRow) {
            this.ruleRow = ruleRow;
        }

        @Override
        public String text(TextKey key, MessageArgs arguments) {
            return fallback.text(key, arguments);
        }

        @Override
        public MenuLines lines(LinesKey key, MessageArgs arguments, int chestHintLines) {
            if (!key.equals(RulesMessages.MENU_RULE)) {
                return fallback.lines(key, arguments, chestHintLines);
            }
            List<String> resolved = new ArrayList<>(ruleRow.size());
            for (String line : ruleRow) {
                resolved.add(FakeStrings.render(line, arguments));
            }
            return MenuLines.split(resolved, key, chestHintLines);
        }
    }

    private static final class CollapsingStrings implements MenuStrings {
        private final FakeStrings fallback = new FakeStrings();

        @Override
        public String text(TextKey key, MessageArgs arguments) {
            return collapsed(fallback.text(key, arguments));
        }

        @Override
        public MenuLines lines(LinesKey key, MessageArgs arguments, int chestHintLines) {
            List<String> resolved = new ArrayList<>(key.english().size());
            for (String line : key.english()) {
                resolved.add(collapsed(FakeStrings.render(line, arguments)));
            }
            return MenuLines.split(resolved, key, chestHintLines);
        }

        private static String collapsed(String rendered) {
            return MenuText.stripColors(rendered).isEmpty() ? "" : rendered;
        }
    }
}
