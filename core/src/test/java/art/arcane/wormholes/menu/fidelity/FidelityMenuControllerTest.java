package art.arcane.wormholes.menu.fidelity;

import static art.arcane.wormholes.menu.MenuTestViews.assertBound;
import static art.arcane.wormholes.menu.MenuTestViews.chestLore;
import static art.arcane.wormholes.menu.MenuTestViews.element;
import static art.arcane.wormholes.menu.MenuTestViews.layout;
import static art.arcane.wormholes.menu.MenuTestViews.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakePortalAccess;
import art.arcane.wormholes.menu.FakeRouteController;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuTestViews;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class FidelityMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000e0");
    private static final UUID PORTAL = FakePortalAccess.PORTAL;
    private static final List<String> HINT = List.of("§8Shift-click to use the server default.");

    private FakeMenuHost host;
    private FakePortalAccess portals;
    private FakeFidelityMenuHost fidelity;
    private FidelityMenuController controller;
    private MenuService service;
    private long now = 1_000L;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortalAccess();
        portals.managers.add(VIEWER);
        fidelity = new FakeFidelityMenuHost();
        controller = new FidelityMenuController(portals, fidelity);
        service = new MenuService(host, new MenuRegistry(List.of(controller, new FakeRouteController(PortalRoutes.CONTROLLER))), () -> now);
    }

    @Test
    void mainScreenMatchesTodaysWindow() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.CYAN, null), build.chest());
        assertEquals(FidelityRoutes.MAIN, build.screen().screenId());
        assertEquals("Fidelity", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(mapOf(
            "fidelity-placard", "Label 0,0 minecraft:comparator - {}",
            "fidelity-atmosphere", "Cycle -3,1 minecraft:light_blue_stained_glass - {LEFT=P, SHIFT_LEFT=T}",
            "fidelity-acoustics", "Cycle -1,1 minecraft:note_block - {LEFT=P, SHIFT_LEFT=T}",
            "fidelity-lod", "Cycle 1,1 minecraft:spyglass - {LEFT=P, SHIFT_LEFT=T}",
            "fidelity-block-entities", "Cycle 3,1 minecraft:oak_sign - {LEFT=P, SHIFT_LEFT=T}",
            "fidelity-back", "Button 0,2 minecraft:arrow - {} hidden"), layout(build.screen()));
        MenuElement placard = element(build.screen(), "fidelity-placard");
        assertEquals("§b§lProjection fidelity§r", placard.label());
        assertEquals(List.of("§7Per-portal channels layered on the projection."), placard.description());
        CycleElement atmosphere = (CycleElement) element(build.screen(), "fidelity-atmosphere");
        assertEquals("§bAtmosphere: §fdefault", atmosphere.label());
        assertTrue(atmosphere.description().isEmpty());
        assertEquals(HINT, atmosphere.common().chestHints());
        assertEquals(List.of("default", "off", "tint", "tint_light", "full"), atmosphere.options());
        assertEquals(0, atmosphere.index());
        assertEquals(List.of(new MenuSideAction(MenuInteraction.TERTIARY, "Use server default", MenuIcon.sprite("small/reset"))),
            atmosphere.common().sideActions());
        assertEquals("§bAcoustics: §fdefault", element(build.screen(), "fidelity-acoustics").label());
        assertEquals(List.of("default", "near", "balanced", "far"), ((CycleElement) element(build.screen(), "fidelity-lod")).options());
        assertEquals(List.of("default", "On", "Off"), ((CycleElement) element(build.screen(), "fidelity-block-entities")).options());
        assertEquals("§bBlock entities: §fdefault", element(build.screen(), "fidelity-block-entities").label());
        assertEquals("§e§lBack§r", element(build.screen(), "fidelity-back").label());
        assertEquals(List.of("§7Return to the portal menu."), chestLore(element(build.screen(), "fidelity-back")));
        assertBound(build);
    }

    @Test
    void overridesShowTheirValueWithAGlint() {
        fidelity.atmosphere = AtmosphereMode.TINT;
        fidelity.acoustics = AcousticsProfile.AMBIENT_EVENTS;
        fidelity.lod = LodProfile.FAR;
        fidelity.blockEntities = Boolean.FALSE;
        fidelity.bedrockViewers = 3;
        open();
        MenuScreen screen = host.chest.screen();

        assertEquals("Cycle -3,1 minecraft:light_blue_stained_glass* - {LEFT=P, SHIFT_LEFT=T}",
            MenuTestViews.describe(element(screen, "fidelity-atmosphere")));
        assertEquals("§bAtmosphere: §ftint", element(screen, "fidelity-atmosphere").label());
        assertEquals(2, ((CycleElement) element(screen, "fidelity-atmosphere")).index());
        assertEquals("§bAcoustics: §fambient_events", element(screen, "fidelity-acoustics").label());
        assertEquals("§bDetail: §ffar", element(screen, "fidelity-lod").label());
        assertEquals("§bBlock entities: §ffalse", element(screen, "fidelity-block-entities").label());
        assertEquals(2, ((CycleElement) element(screen, "fidelity-block-entities")).index());
        assertEquals(List.of("§7Per-portal channels layered on the projection.", "Bedrock profile active for 3 viewers"),
            element(screen, "fidelity-placard").description());
    }

    @Test
    void cyclingAdvancesTheEffectiveValueAndPersists() {
        open();

        chest("fidelity-atmosphere", MenuInteraction.PRIMARY);
        chest("fidelity-acoustics", MenuInteraction.PRIMARY);
        chest("fidelity-lod", MenuInteraction.PRIMARY);
        chest("fidelity-block-entities", MenuInteraction.PRIMARY);
        chest("fidelity-atmosphere", MenuInteraction.PRIMARY);

        assertEquals(List.of(new FidelityEdit.Atmosphere(AtmosphereMode.TINT), new FidelityEdit.Acoustics(AcousticsProfile.AMBIENT_EVENTS),
            new FidelityEdit.Lod(LodProfile.FAR), new FidelityEdit.BlockEntities(Boolean.FALSE), new FidelityEdit.Atmosphere(AtmosphereMode.TINT_LIGHT)),
            fidelity.edits);
        assertEquals(List.of("apply", "apply", "apply", "apply", "apply"), fidelity.calls);
        assertEquals("§bAtmosphere: §ftint_light", element(host.chest.screen(), "fidelity-atmosphere").label());
    }

    @Test
    void shiftClickRestoresTheServerDefault() {
        fidelity.atmosphere = AtmosphereMode.FULL;
        fidelity.blockEntities = Boolean.TRUE;
        open();

        chest("fidelity-atmosphere", MenuInteraction.TERTIARY);
        chest("fidelity-block-entities", MenuInteraction.TERTIARY);
        chest("fidelity-acoustics", MenuInteraction.TERTIARY);
        chest("fidelity-lod", MenuInteraction.TERTIARY);

        assertEquals(List.of(new FidelityEdit.Atmosphere(null), new FidelityEdit.BlockEntities(null), new FidelityEdit.Acoustics(null),
            new FidelityEdit.Lod(null)), fidelity.edits);
        assertEquals(List.of("apply", "apply", "apply", "apply"), fidelity.calls);
        assertEquals("§bAtmosphere: §fdefault", element(host.chest.screen(), "fidelity-atmosphere").label());
    }

    @Test
    void changesRepaintInPlace() {
        open();

        chest("fidelity-lod", MenuInteraction.PRIMARY);

        assertEquals(List.of("open:fidelity.main@1", "update:fidelity.main@2"), host.chest.events);
        assertTrue(host.chest.notices.isEmpty());
    }

    @Test
    void nativeSideActionResetsToTheDefault() throws MenuProtocolException {
        fidelity.lod = LodProfile.NEAR;
        hello();
        open();

        nativeAction("fidelity-lod", MenuInteraction.TERTIARY);

        assertEquals(List.of(new FidelityEdit.Lod(null)), fidelity.edits);
        assertEquals(List.of("apply"), fidelity.calls);
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        CycleElement lod = assertInstanceOf(CycleElement.class, update.screen().element("fidelity-lod").orElseThrow());
        assertEquals("§bDetail", lod.label());
        assertEquals(0, lod.index());
        assertTrue(update.screen().element("fidelity-back").isEmpty());
    }

    @Test
    void backReturnsToThePortalHome() {
        open();

        chest("fidelity-back", MenuInteraction.PRIMARY);

        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertTrue(fidelity.calls.isEmpty());
    }

    @Test
    void portalChangesRepaintTheOpenMenu() {
        open();
        fidelity.acoustics = AcousticsProfile.FULL;

        service.invalidate(new MenuScope.Portal(PORTAL));

        assertEquals("update:fidelity.main@2", host.chest.lastEvent());
        assertEquals(0, controller.refreshIntervalTicks(FidelityRoutes.main(PORTAL)));
        assertTrue(controller.touches(FidelityRoutes.main(PORTAL), new MenuScope.Portal(PORTAL)));
        assertFalse(controller.touches(FidelityRoutes.main(PORTAL), new MenuScope.Door(PORTAL)));
    }

    @Test
    void portalsWithoutFidelityOpenNothing() {
        fidelity.present = false;

        open();

        assertFalse(host.chest.events.stream().anyMatch(event -> event.startsWith("open:")));
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() throws MenuProtocolException {
        int checked = 0;
        for (MenuElement element : elements(false)) {
            for (MenuInteraction interaction : element.interactions()) {
                checked += rejected(element, interaction, false);
            }
        }
        hello();
        for (MenuElement element : elements(true)) {
            for (MenuInteraction interaction : element.interactions()) {
                checked += rejected(element, interaction, true);
            }
        }
        assertTrue(fidelity.calls.isEmpty());
        assertEquals(17, checked);
    }

    @Test
    void declaredChestHintsMatchTheEnglishClickInstructions() {
        for (Map.Entry<LinesKey, Integer> entry : FidelityMenuController.CHEST_HINTS.entrySet()) {
            assertEquals(MenuTestViews.trailingClickLines(entry.getKey().english()), entry.getValue().intValue(), entry.getKey().id());
        }
    }

    private int rejected(MenuElement element, MenuInteraction interaction, boolean nativeOrigin) throws MenuProtocolException {
        portals.managers.add(VIEWER);
        open();
        portals.managers.clear();
        host.chest.events.clear();
        if (nativeOrigin) {
            nativeAction(element.id(), interaction);
            assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage(), element.id());
        } else {
            chest(element.id(), interaction);
            assertTrue(host.chest.events.contains("close"), element.id());
        }
        return 1;
    }

    private List<MenuElement> elements(boolean nativeOrigin) throws MenuProtocolException {
        portals.managers.add(VIEWER);
        open();
        return List.copyOf(nativeOrigin ? nativeScreen().elements() : host.chest.screen().elements());
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen screen = host.chest.screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Acoustics", MenuText.stripColors(MenuScreens.element(screen, "fidelity-acoustics").common().wireLabel()));
    }

    private void open() {
        service.open(VIEWER, FidelityRoutes.main(PORTAL));
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void nativeAction(String elementId, MenuInteraction interaction) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(nativeScreen().revision(), elementId, interaction, null)));
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
