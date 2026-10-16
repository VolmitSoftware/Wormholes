package art.arcane.wormholes.menu.cosmetics;

import static art.arcane.wormholes.menu.settings.MenuLayouts.assertBound;
import static art.arcane.wormholes.menu.settings.MenuLayouts.assertClosedWithNotice;
import static art.arcane.wormholes.menu.settings.MenuLayouts.boundIds;
import static art.arcane.wormholes.menu.settings.MenuLayouts.chestLore;
import static art.arcane.wormholes.menu.settings.MenuLayouts.element;
import static art.arcane.wormholes.menu.settings.MenuLayouts.layout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.portal.StubController;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.menu.settings.FakePortalHosts;
import art.arcane.wormholes.menu.settings.SettingsMenuController;
import art.arcane.wormholes.menu.settings.SettingsRoutes;
import art.arcane.wormholes.portal.BlackoutColor;

final class CosmeticsMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID PORTAL = FakePortalHosts.PORTAL;
    private static final String DENIED = "§cOnly the portal owner or an administrator can edit this portal.";

    private FakeMenuHost host;
    private FakePortalHosts portals;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortalHosts();
        portals.managers.add(VIEWER);
        service = new MenuService(host, new MenuRegistry(List.of(new CosmeticsMenuController(portals, portals),
            new SettingsMenuController(portals, portals), new StubController(PortalRoutes.CONTROLLER),
            new StubController(PortalRoutes.COST_CONTROLLER), new StubController(PortalRoutes.EXTENSIONS_CONTROLLER))), () -> 1_000L);
    }

    @Test
    void blackoutColorsMatchTheChestWindow() {
        service.open(VIEWER, CosmeticsRoutes.blackout(PORTAL));
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 4, ChestPane.BLACK, null), build.chest());
        assertEquals("Blackout Color", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("blackout-color-placard", "Label 0,0 minecraft:black_concrete - {}");
        BlackoutColor[] colors = BlackoutColor.values();
        for (int index = 0; index < colors.length; index++) {
            String name = colors[index].name().toLowerCase(Locale.ROOT);
            expected.put("blackout-color-" + name, "ListEntry " + (-4 + index % 8) + "," + (1 + index / 8) + " minecraft:" + name + "_concrete"
                + (colors[index] == BlackoutColor.BLACK ? "*" : "") + " - {}");
        }
        expected.put("back-to-settings", "Button 0,3 minecraft:arrow - {} hidden");
        assertEquals(expected, layout(build.screen()));
        assertTrue(((ListEntryElement) element(build.screen(), "blackout-color-black")).selected());
        assertFalse(((ListEntryElement) element(build.screen(), "blackout-color-white")).selected());
        assertEquals(List.of("§7Concrete color used for the blackout shell.", "", "§7Currently: §fBlack Concrete"),
            element(build.screen(), "blackout-color-placard").description());
        assertEquals(List.of("", "§8Click to select."), element(build.screen(), "blackout-color-red").common().chestHints());
        assertBound(build);
    }

    @Test
    void choosingABlackoutColorAppliesItAndNotifies() {
        service.open(VIEWER, CosmeticsRoutes.blackout(PORTAL));

        chest("blackout-color-red", MenuInteraction.PRIMARY);

        assertEquals(List.of("BlackoutColorChoice[color=RED]"), portals.edits);
        assertEquals("§aAlpha: §rBlackout Color Red Concrete", host.chest.lastNotice());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, host.chest.notices.get(0).chestChannel());
        assertEquals(CosmeticsRoutes.BLACKOUT, host.chest.screen().screenId());
        assertTrue(((ListEntryElement) element(host.chest.screen(), "blackout-color-red")).selected());
        assertEquals(MenuIcon.item("minecraft:red_concrete"), element(host.chest.screen(), "blackout-color-placard").icon());
    }

    @Test
    void ambientColorMatchesTheChestWindow() {
        service.open(VIEWER, CosmeticsRoutes.ambient(PORTAL));
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 5, ChestPane.BLACK, null), build.chest());
        assertEquals("Ambient Color", build.screen().title());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ambient-color-placard", "Label 0,0 minecraft:firework_star - {}");
        expected.put("ambient-red", "Number -2,1 minecraft:red_dye - {}");
        expected.put("ambient-green", "Number 0,1 minecraft:green_dye - {}");
        expected.put("ambient-blue", "Number 2,1 minecraft:blue_dye - {}");
        for (int index = 0; index < FakePortalHosts.PALETTE.size(); index++) {
            DyeSwatch swatch = FakePortalHosts.PALETTE.get(index);
            expected.put("ambient-color-" + swatch.name(), "ListEntry " + (-4 + index % 8) + "," + (2 + index / 8) + " " + swatch.itemId()
                + (swatch.name().equals("magenta") ? "*" : "") + " - {}");
        }
        expected.put("back-to-settings", "Button 0,4 minecraft:arrow - {} hidden");
        assertEquals(expected, layout(build.screen()));
        assertEquals("§7Currently: §f#B969FF", element(build.screen(), "ambient-color-placard").description().get(2));
        NumberElement red = (NumberElement) element(build.screen(), "ambient-red");
        assertEquals(List.of(185.0D, 0.0D, 255.0D, 8.0D, 32.0D), List.of(red.value(), red.min(), red.max(), red.step(), red.bigStep()));
        assertEquals("§b§lRed §f§l185§r", red.label());
        assertEquals(List.of("", "§8Left click: +8", "§8Right click: -8", "§8Shift-left: +32", "§8Shift-right: -32"), chestLore(red));
        assertEquals("§b§lLight Blue§r", element(build.screen(), "ambient-color-light_blue").label());
        assertBound(build);
    }

    @Test
    void ambientChannelsClampAndAnnounceRealChanges() {
        service.open(VIEWER, CosmeticsRoutes.ambient(PORTAL));

        chest("ambient-blue", MenuInteraction.INCREMENT);
        chest("ambient-red", MenuInteraction.INCREMENT);
        chest("ambient-green", MenuInteraction.BIG_DECREMENT);

        assertEquals(List.of("AmbientColor[rgb=" + 0xB969FF + "]", "AmbientColor[rgb=" + 0xC169FF + "]", "AmbientColor[rgb=" + 0xC149FF + "]"),
            portals.edits);
        assertEquals(List.of("§aAlpha: §rRed 193", "§aAlpha: §rGreen 73"), noticeTexts());
        assertEquals(193.0D, ((NumberElement) element(host.chest.screen(), "ambient-red")).value());
    }

    @Test
    void dyePresetsSetTheirColor() throws MenuProtocolException {
        receive(new MenuClientMessage.Hello(1));
        service.open(VIEWER, CosmeticsRoutes.ambient(PORTAL));

        receive(new MenuClientMessage.Action(new MenuAction(lastScreen().revision(), "ambient-color-light_blue", MenuInteraction.PRIMARY, null)));
        receive(new MenuClientMessage.Action(new MenuAction(lastScreen().revision(), "ambient-green", MenuInteraction.SET_VALUE,
            MenuValue.number(0.0D))));

        assertEquals(List.of("AmbientColor[rgb=" + 0x3AB3DA + "]", "AmbientColor[rgb=" + 0x3A00DA + "]"), portals.edits);
        MenuScreen screen = lastScreen();
        assertTrue(screen.element("back-to-settings").isEmpty());
        assertEquals(0.0D, ((NumberElement) screen.element("ambient-green").orElseThrow()).value());
        assertEquals("§aAlpha: §rGreen 0", assertInstanceOf(MenuServerMessage.Feedback.class, host.lastMessage()).feedback().text());
    }

    @Test
    void dyeSelectionAnnouncesTheDyeName() {
        service.open(VIEWER, CosmeticsRoutes.ambient(PORTAL));

        chest("ambient-color-light_blue", MenuInteraction.PRIMARY);

        assertEquals("§aAlpha: §rAmbient Color Light Blue", host.chest.lastNotice());
        assertTrue(((ListEntryElement) element(host.chest.screen(), "ambient-color-light_blue")).selected());
    }

    @Test
    void surfaceSkinPickerMatchesTheChestWindow() {
        service.open(VIEWER, CosmeticsRoutes.skin(PORTAL));
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.CYAN, null), build.chest());
        assertEquals("Surface Skin", build.screen().title());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("surface-skin-placard", "Label 0,0 minecraft:glass - {}");
        expected.put("surface-skin-glass", "Button -1,1 minecraft:glass - {}");
        expected.put("surface-skin-clear", "Button 1,1 minecraft:barrier delete {}");
        expected.put("back-to-settings", "Button 0,2 minecraft:arrow - {} hidden");
        assertEquals(expected, layout(build.screen()));
        assertEquals("§7Skin: §fNone", element(build.screen(), "surface-skin-placard").description().get(2));
        assertBound(build);
    }

    @Test
    void surfaceSkinChoicesNeedTheAdministratorPermission() {
        service.open(VIEWER, CosmeticsRoutes.skin(PORTAL));

        chest("surface-skin-glass", MenuInteraction.PRIMARY);
        assertTrue(portals.edits.isEmpty());
        assertEquals(DENIED, host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, host.chest.notices.get(0).chestChannel());
        assertEquals(CosmeticsRoutes.SKIN, host.chest.screen().screenId());
        assertFalse("close".equals(host.chest.lastEvent()));

        chest("surface-skin-clear", MenuInteraction.PRIMARY);
        assertTrue(portals.edits.isEmpty());
        assertEquals(1, host.chest.notices.size());

        portals.skinEditors.add(VIEWER);
        chest("surface-skin-glass", MenuInteraction.PRIMARY);
        assertEquals(List.of("SurfaceSkin[skin=minecraft:glass]"), portals.edits);
        assertEquals("§aAlpha: §rSurface Skin minecraft:glass", host.chest.lastNotice());
        assertEquals(MenuIcon.item("minecraft:glass", 1, true), element(host.chest.screen(), "surface-skin-glass").icon());

        portals.skinEditors.clear();
        chest("surface-skin-clear", MenuInteraction.PRIMARY);
        assertEquals(1, portals.edits.size());
        assertEquals(DENIED, host.chest.lastNotice());

        portals.skinEditors.add(VIEWER);
        chest("surface-skin-clear", MenuInteraction.PRIMARY);
        assertEquals(List.of("SurfaceSkin[skin=minecraft:glass]", "SurfaceSkin[skin=]"), portals.edits);
        assertEquals("§aAlpha: §rSurface Skin None", host.chest.lastNotice());
    }

    @Test
    void backReturnsToSettings() {
        for (MenuRoute route : List.of(CosmeticsRoutes.blackout(PORTAL), CosmeticsRoutes.ambient(PORTAL), CosmeticsRoutes.skin(PORTAL))) {
            service.open(VIEWER, SettingsRoutes.advanced(PORTAL));
            service.open(VIEWER, route);
            chest("back-to-settings", MenuInteraction.PRIMARY);
            assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId(), route.screen());
        }
        service.open(VIEWER, PortalRoutes.settings(PORTAL));
        chest("blackout-background", MenuInteraction.SECONDARY);
        chest("back-to-settings", MenuInteraction.PRIMARY);
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertTrue(portals.edits.isEmpty());
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() {
        portals.skinEditors.add(VIEWER);
        portals.view = FakePortalHosts.base().surfaceSkin("minecraft:stone").build();
        for (MenuRoute route : List.of(CosmeticsRoutes.blackout(PORTAL), CosmeticsRoutes.ambient(PORTAL), CosmeticsRoutes.skin(PORTAL))) {
            portals.managers.clear();
            int denied = host.chest.notices.size();
            service.open(VIEWER, route);
            assertEquals(denied + 1, host.chest.notices.size());
            assertEquals(DENIED, host.chest.lastNotice());
            portals.managers.add(VIEWER);
            service.open(VIEWER, route);
            for (String id : boundIds(host.chest.screen())) {
                for (MenuInteraction interaction : element(host.chest.screen(), id).interactions()) {
                    portals.managers.add(VIEWER);
                    service.open(VIEWER, route);
                    portals.managers.clear();
                    int notices = host.chest.notices.size();
                    MenuValue value = interaction == MenuInteraction.SET_VALUE ? MenuValue.number(16.0D) : null;
                    service.chestAction(VIEWER, new MenuChestAction(id, interaction, value, false, null));
                    assertTrue(portals.edits.isEmpty(), route.screen() + "/" + id + "/" + interaction);
                    assertEquals(notices + 1, host.chest.notices.size(), route.screen() + "/" + id);
                    assertEquals(DENIED, host.chest.lastNotice());
                    assertClosedWithNotice(host.chest, route.screen() + "/" + id);
                }
            }
        }
    }

    @Test
    void refreshAndInvalidationScopes() {
        CosmeticsMenuController controller = new CosmeticsMenuController(portals, portals);

        assertEquals(CosmeticsRoutes.CONTROLLER, controller.id());
        assertEquals(0, controller.refreshIntervalTicks(CosmeticsRoutes.ambient(PORTAL)));
        assertTrue(controller.touches(CosmeticsRoutes.skin(PORTAL), new MenuScope.Portal(PORTAL)));
        assertFalse(controller.touches(CosmeticsRoutes.skin(PORTAL), new MenuScope.Portal(UUID.randomUUID())));

        service.open(VIEWER, CosmeticsRoutes.blackout(PORTAL));
        portals.view = portals.view.toBuilder().blackoutColor(BlackoutColor.CYAN).build();
        service.invalidate(new MenuScope.Portal(PORTAL));
        assertEquals("update:cosmetics.blackout@2", host.chest.lastEvent());
        assertTrue(((ListEntryElement) element(host.chest.screen(), "blackout-color-cyan")).selected());
    }

    @Test
    void staleNativeRevisionsRefreshWithoutTouchingTheHost() throws MenuProtocolException {
        receive(new MenuClientMessage.Hello(1));
        service.open(VIEWER, CosmeticsRoutes.blackout(PORTAL));
        long stale = lastScreen().revision();
        receive(new MenuClientMessage.Action(new MenuAction(stale, "blackout-color-red", MenuInteraction.PRIMARY, null)));

        receive(new MenuClientMessage.Action(new MenuAction(stale, "blackout-color-blue", MenuInteraction.PRIMARY, null)));

        assertEquals(List.of("BlackoutColorChoice[color=RED]"), portals.edits);
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MenuScreen lastScreen() throws MenuProtocolException {
        MenuScreen screen = null;
        for (MenuServerMessage message : host.serverMessages()) {
            if (message instanceof MenuServerMessage.Open opened) {
                screen = opened.screen();
            } else if (message instanceof MenuServerMessage.Update updated) {
                screen = updated.screen();
            }
        }
        return screen;
    }

    private List<String> noticeTexts() {
        List<String> texts = new ArrayList<>();
        host.chest.notices.forEach(notice -> texts.add(notice.feedback().text()));
        return texts;
    }
}
