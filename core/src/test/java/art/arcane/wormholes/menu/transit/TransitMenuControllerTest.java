package art.arcane.wormholes.menu.transit;

import static art.arcane.wormholes.menu.MenuTestViews.assertBound;
import static art.arcane.wormholes.menu.MenuTestViews.chestLore;
import static art.arcane.wormholes.menu.MenuTestViews.element;
import static art.arcane.wormholes.menu.MenuTestViews.layout;
import static art.arcane.wormholes.menu.MenuTestViews.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakePortalAccess;
import art.arcane.wormholes.menu.FakeRouteController;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuTestViews;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;

final class TransitMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    private static final UUID PORTAL = FakePortalAccess.PORTAL;
    private static final String INVALID = "That value is not allowed.";

    private FakeMenuHost host;
    private FakePortalAccess portals;
    private FakeTransitMenuHost transit;
    private TransitMenuController controller;
    private MenuService service;
    private long now = 1_000L;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortalAccess();
        portals.managers.add(VIEWER);
        transit = new FakeTransitMenuHost();
        controller = new TransitMenuController(portals, transit);
        service = new MenuService(host, new MenuRegistry(List.of(controller, new FakeRouteController(PortalRoutes.CONTROLLER))), () -> now);
    }

    @Test
    void mainScreenMatchesTodaysBukkitWindow() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.CYAN, null), build.chest());
        assertEquals(TransitRoutes.MAIN, build.screen().screenId());
        assertEquals("Transit", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(mapOf(
            "transit-momentum", "Cycle -2,1 minecraft:slime_ball transit {LEFT=P, RIGHT=transit-momentum-factor.X}",
            "transit-momentum-factor", "Text - minecraft:slime_ball - {}",
            "transit-orientation", "Cycle -1,1 minecraft:compass rotate {}",
            "transit-membrane", "Toggle 0,1 minecraft:phantom_membrane portal {}",
            "transit-bounce", "Toggle 1,1 minecraft:slime_block transit {}",
            "transit-profile", "Button 2,1 minecraft:note_block - {LEFT=transit-threshold.X, RIGHT=transit-arrival.X, SHIFT_LEFT=transit-mask.X} hidden",
            "transit-threshold", "Text - minecraft:note_block - {}",
            "transit-arrival", "Text - minecraft:note_block - {}",
            "transit-mask", "Text - minecraft:note_block - {}",
            "transit-scale", "Cycle 3,1 minecraft:spyglass transit {LEFT=P, RIGHT=transit-scale-min.X, SHIFT_RIGHT=transit-scale-max.X}",
            "transit-scale-min", "Text - minecraft:spyglass - {}",
            "transit-scale-max", "Text - minecraft:spyglass - {}",
            "transit-back", "Button 0,2 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertTrue(build.screen().element("transit-gravity").isEmpty());
        assertBound(build);
    }

    @Test
    void chestTextMatchesToday() {
        transit.view = new TransitMenuView(new MomentumPolicy(MomentumPolicy.Mode.SCALE, 0.5D, 0.0D, null),
            new MomentumPolicy(MomentumPolicy.Mode.SCALE, 0.5D, 0.0D, null), OrientationPolicy.SNAP, OrientationPolicy.SNAP, true, false,
            new TransitionProfile("minecraft:portal", "", 40), new ScaleRule(ScaleRule.Mode.RATIO, 0.5D, 2.0D), false);
        open();
        MenuScreen screen = host.chest.screen();

        CycleElement momentum = (CycleElement) element(screen, "transit-momentum");
        assertEquals("§6§lMomentum: scale§r", momentum.label());
        assertEquals(List.of("§7Factor 0.50. How exit speed is derived from entry speed."), momentum.description());
        assertEquals(List.of("§eLeft-click to cycle the mode", "§eRight-click to set the factor"), momentum.common().chestHints());
        assertEquals(List.of("preserve", "scale", "clamp", "zero", "impulse"), momentum.options());
        assertEquals(1, momentum.index());
        assertEquals("Cycle -2,1 minecraft:slime_ball* transit {LEFT=P, RIGHT=transit-momentum-factor.X}", MenuTestViews.describe(momentum));
        CycleElement orientation = (CycleElement) element(screen, "transit-orientation");
        assertEquals("§6§lOrientation: snap§r", orientation.label());
        assertEquals(List.of("frame", "look", "snap", "mirror"), orientation.options());
        assertEquals(2, orientation.index());
        assertEquals("§6§lMembrane: On§r", element(screen, "transit-membrane").label());
        assertTrue(((ToggleElement) element(screen, "transit-membrane")).value());
        assertEquals("Toggle 0,1 minecraft:phantom_membrane* portal {}", MenuTestViews.describe(element(screen, "transit-membrane")));
        assertEquals("§6§lBounce: Off§r", element(screen, "transit-bounce").label());
        assertEquals(List.of("§7Threshold minecraft:portal", "§7Arrival default / 40t", "§eLeft-click: threshold effect, Right-click: arrival sound",
            "§eShift + Left-click: mask ticks"), chestLore(element(screen, "transit-profile")));
        CycleElement scale = (CycleElement) element(screen, "transit-scale");
        assertEquals("§6§lTraveller scale: ratio§r", scale.label());
        assertEquals(List.of("§7Size range 0.5-2. Maps travel between portals of different sizes."), scale.description());
        assertEquals(List.of("off", "motion", "ratio"), scale.options());
        assertEquals(2, scale.index());
        assertEquals("§e§lBack to Settings§r", element(screen, "transit-back").label());

        TextElement factor = (TextElement) element(screen, "transit-momentum-factor");
        assertEquals("0.50", factor.value());
        assertEquals("Momentum factor", factor.label());
        assertEquals("Factor", factor.placeholder());
        assertEquals(mapOf(
            "transit-momentum-factor", "Momentum factor / Factor",
            "transit-threshold", "Threshold effect / Threshold effect",
            "transit-arrival", "Arrival sound / Sound id",
            "transit-mask", "Mask ticks / Ticks",
            "transit-scale-min", "Minimum scale / Scale",
            "transit-scale-max", "Maximum scale / Scale"), editBoxes(screen));
        assertEquals(256, factor.maxLength());
        assertEquals(List.of("§bType the new value in chat (or 'cancel'):"), factor.chestPrompt());
        assertEquals(ChestReopen.ALWAYS, factor.chestReopen());
        assertNull(factor.chestCancelRoute());
        assertEquals("minecraft:portal", ((TextElement) element(screen, "transit-threshold")).value());
        assertEquals("", ((TextElement) element(screen, "transit-arrival")).value());
        assertEquals("40", ((TextElement) element(screen, "transit-mask")).value());
        assertEquals("0.5", ((TextElement) element(screen, "transit-scale-min")).value());
        assertEquals("2", ((TextElement) element(screen, "transit-scale-max")).value());
        for (String prompt : List.of("transit-momentum-factor", "transit-threshold", "transit-arrival", "transit-mask", "transit-scale-min",
            "transit-scale-max")) {
            assertEquals(ChestReopen.ALWAYS, ((TextElement) element(screen, prompt)).chestReopen(), prompt);
        }
    }

    @Test
    void editBoxValuesAreClippedToTheInputLimit() {
        transit.view = new TransitMenuView(transit.view.momentum(), transit.view.effectiveMomentum(), transit.view.orientation(),
            transit.view.effectiveOrientation(), transit.view.membrane(), transit.view.bounce(), new TransitionProfile("x".repeat(300), "", -1),
            transit.view.scaleRule(), transit.view.gravityRotation());
        open();

        assertEquals("x".repeat(256), ((TextElement) element(host.chest.screen(), "transit-threshold")).value());
    }

    @Test
    void gravityAppearsOnlyWhenTheHostSupportsIt() {
        transit.gravity = true;
        open();
        MenuElement gravity = element(host.chest.screen(), "transit-gravity");

        assertEquals("Toggle -3,1 minecraft:feather transit {}", MenuTestViews.describe(gravity));
        assertEquals("transit-gravity", host.chest.screen().elements().get(0).id());
        assertEquals("§6§lDirectional gravity: off§r", gravity.label());
        assertEquals(List.of("§eLeft-click to toggle"), gravity.common().chestHints());

        chest("transit-gravity");

        assertEquals(List.of(new TransitEdit.Gravity(true)), transit.edits);
        MenuElement enabled = element(host.chest.screen(), "transit-gravity");
        assertEquals("§6§lDirectional gravity: on§r", enabled.label());
        assertTrue(((ToggleElement) enabled).value());
        assertBound(host.chest.last());
    }

    @Test
    void togglesAndCyclesApplyTheNextValue() {
        open();

        chest("transit-momentum");
        chest("transit-orientation");
        chest("transit-membrane");
        chest("transit-bounce");
        chest("transit-scale");
        chest("transit-scale");

        assertEquals(List.of(
            new TransitEdit.Momentum(MomentumPolicy.of(MomentumPolicy.Mode.SCALE)),
            new TransitEdit.Orientation(OrientationPolicy.LOOK),
            new TransitEdit.Membrane(true),
            new TransitEdit.Bounce(true),
            new TransitEdit.Scale(new ScaleRule(ScaleRule.Mode.MOTION, 0.25D, 4.0D)),
            new TransitEdit.Scale(new ScaleRule(ScaleRule.Mode.RATIO, 0.25D, 4.0D))), transit.edits);
        assertEquals("§6§lTraveller scale: ratio§r", element(host.chest.screen(), "transit-scale").label());
        assertTrue(host.chest.notices.isEmpty());
        assertEquals(TransitRoutes.MAIN, host.chest.screen().screenId());
    }

    @Test
    void chestPromptsApplyTodaysParsing() {
        open();

        text("transit-momentum-factor", "2.5");
        text("transit-threshold", " Minecraft:Block.Bell.Use ");
        text("transit-arrival", "none");
        text("transit-mask", "40");
        text("transit-scale-min", "0.01");
        text("transit-scale-max", "0.1");

        assertEquals(List.of(
            new TransitEdit.Momentum(MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE).withFactor(2.5D)),
            new TransitEdit.Profile(new TransitionProfile("minecraft:block.bell.use", "", -1)),
            new TransitEdit.Profile(new TransitionProfile("minecraft:block.bell.use", "", -1)),
            new TransitEdit.Profile(new TransitionProfile("minecraft:block.bell.use", "", 40)),
            new TransitEdit.Scale(new ScaleRule(ScaleRule.Mode.OFF, ScaleRule.ATTRIBUTE_MIN, 4.0D)),
            new TransitEdit.Scale(new ScaleRule(ScaleRule.Mode.OFF, 0.0625D, 0.1D))), transit.edits);
        assertTrue(host.chest.notices.isEmpty());
        assertEquals(TransitRoutes.MAIN, host.chest.screen().screenId());
    }

    @Test
    void scaleBoundsClampThroughTheSharedSettings() {
        open();

        text("transit-scale-min", "8");
        text("transit-scale-max", "99");

        assertEquals(List.of(new TransitEdit.Scale(new ScaleRule(ScaleRule.Mode.OFF, 8.0D, 8.0D)),
            new TransitEdit.Scale(new ScaleRule(ScaleRule.Mode.OFF, 8.0D, ScaleRule.ATTRIBUTE_MAX))), transit.edits);
    }

    @Test
    void invalidPromptAnswersChangeNothingAndOnlyTellNativeViewers() {
        open();

        text("transit-momentum-factor", "11");
        text("transit-momentum-factor", "fast");
        text("transit-mask", "201");
        text("transit-mask", "-2");
        text("transit-scale-min", "0");
        text("transit-scale-max", "big");

        assertTrue(transit.edits.isEmpty());
        assertEquals(6, host.chest.notices.size());
        for (int index = 0; index < host.chest.notices.size(); index++) {
            assertEquals(NoticeChannel.NONE, host.chest.notices.get(index).chestChannel());
            assertEquals(INVALID, host.chest.notices.get(index).feedback().text());
        }
    }

    @Test
    void nativeEditBoxesUseTheSameRules() throws MenuProtocolException {
        hello();
        open();

        nativeAction("transit-mask", MenuInteraction.SET_TEXT, MenuValue.text(""));
        nativeAction("transit-momentum-factor", MenuInteraction.SET_TEXT, MenuValue.text("-3"));

        assertEquals(List.of(new TransitEdit.Profile(new TransitionProfile("", "", -1))), transit.edits);
        MenuServerMessage last = host.lastMessage();
        MenuServerMessage.Feedback feedback = assertInstanceOf(MenuServerMessage.Feedback.class, last);
        assertEquals(INVALID, feedback.feedback().text());
        MenuScreen screen = nativeScreen();
        assertTrue(screen.element("transit-profile").isEmpty());
        assertTrue(screen.element("transit-back").isEmpty());
        assertTrue(screen.element("transit-threshold").isPresent());
    }

    @Test
    void backReturnsToThePortalHome() {
        open();

        chest("transit-back");

        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
    }

    @Test
    void portalChangesRepaintInPlace() {
        open();
        transit.view = new TransitMenuView(null, MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE), null, OrientationPolicy.FRAME, false, true,
            TransitionProfile.NONE, new ScaleRule(ScaleRule.Mode.OFF, 0.25D, 4.0D), false);

        service.invalidate(new MenuScope.Portal(PORTAL));

        assertEquals("update:transit.main@2", host.chest.lastEvent());
        assertTrue(((ToggleElement) element(host.chest.screen(), "transit-bounce")).value());
        assertEquals(0, controller.refreshIntervalTicks(TransitRoutes.main(PORTAL)));
        assertTrue(controller.touches(TransitRoutes.main(PORTAL), new MenuScope.Portal(PORTAL)));
        assertFalse(controller.touches(TransitRoutes.main(PORTAL), new MenuScope.Portal(UUID.randomUUID())));
    }

    @Test
    void portalsWithoutTransitOpenNothing() {
        transit.present = false;

        open();

        assertFalse(host.chest.events.stream().anyMatch(event -> event.startsWith("open:")));
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() throws MenuProtocolException {
        transit.gravity = true;
        int checked = 0;
        for (MenuElement element : elements(false)) {
            if (element.interactions().isEmpty() || !reachableByChest(element)) {
                continue;
            }
            checked += rejected(element, false);
        }
        hello();
        for (MenuElement element : elements(true)) {
            if (element.interactions().isEmpty()) {
                continue;
            }
            checked += rejected(element, true);
        }
        assertTrue(transit.edits.isEmpty());
        assertEquals(25, checked);
    }

    @Test
    void declaredChestHintsMatchTheEnglishClickInstructions() {
        for (Map.Entry<LinesKey, Integer> entry : TransitMenuController.CHEST_HINTS.entrySet()) {
            assertEquals(MenuTestViews.trailingClickLines(entry.getKey().english()), entry.getValue().intValue(), entry.getKey().id());
        }
    }

    private int rejected(MenuElement element, boolean nativeOrigin) throws MenuProtocolException {
        portals.managers.add(VIEWER);
        open();
        portals.managers.clear();
        host.chest.events.clear();
        MenuInteraction interaction = element.interactions().iterator().next();
        MenuValue value = interaction == MenuInteraction.SET_TEXT ? MenuValue.text("1") : null;
        if (nativeOrigin) {
            nativeAction(element.id(), interaction, value);
            assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage(), element.id());
        } else {
            service.chestAction(VIEWER, new MenuChestAction(element.id(), interaction, value, false, null));
            assertTrue(host.chest.events.contains("close"), element.id());
        }
        return 1;
    }

    private List<MenuElement> elements(boolean nativeOrigin) throws MenuProtocolException {
        portals.managers.add(VIEWER);
        open();
        return List.copyOf(nativeOrigin ? nativeScreen().elements() : host.chest.screen().elements());
    }

    private boolean reachableByChest(MenuElement element) {
        if (element.chestSlot() != null) {
            return true;
        }
        for (MenuElement other : host.chest.screen().elements()) {
            if (other.chestSlot() != null && other.chestSlot().clicks().values().stream().anyMatch(target -> element.id().equals(target.elementId()))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen screen = host.chest.screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Orientation", MenuText.stripColors(MenuScreens.element(screen, "transit-orientation").common().wireLabel()));
    }

    private void open() {
        service.open(VIEWER, TransitRoutes.main(PORTAL));
    }

    private static Map<String, String> editBoxes(MenuScreen screen) {
        Map<String, String> boxes = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            if (element instanceof TextElement text) {
                boxes.put(text.id(), text.label() + " / " + text.placeholder());
            }
        }
        return boxes;
    }

    private void chest(String elementId) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, MenuInteraction.PRIMARY, null, false, null));
    }

    private void text(String elementId, String text) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, MenuInteraction.SET_TEXT, MenuValue.text(text), false, null));
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(nativeScreen().revision(), elementId, interaction, value)));
    }

    private MenuScreen nativeScreen() throws MenuProtocolException {
        List<MenuServerMessage> messages = host.serverMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof MenuServerMessage.Open opened) {
                return opened.screen();
            }
            if (messages.get(index) instanceof MenuServerMessage.Update updated) {
                return updated.screen();
            }
        }
        throw new AssertionError("no native screen was sent");
    }

    private void receive(MenuClientMessage message) {
        now += 1_000L;
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
