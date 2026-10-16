package art.arcane.wormholes.menu.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuDriver;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.nexus.NexusRoutes;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class AtlasMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String PREFIX = "§8[§6Wormholes§8] ";

    private FakeAtlasHost atlas;
    private RouteStub dial;
    private MenuDriver driver;

    @BeforeEach
    void setUp() {
        atlas = new FakeAtlasHost();
        dial = new RouteStub(NexusRoutes.DIAL_CONTROLLER);
        driver = new MenuDriver(VIEWER, "Traveler", List.of(new AtlasMenuController(atlas), dial));
        driver.viewer.permissions.add(AtlasRoutes.PERMISSION);
    }

    @Test
    void listMatchesTheChestWindowAndNativeTables() {
        open(AtlasModel.Filter.ALL);
        MenuBuild build = driver.build();

        assertEquals(new ChestLayout("§8Atlas §7- §f2 portals", 6, ChestPane.BLUE, null), build.chest());
        assertEquals(AtlasRoutes.LIST, build.screen().screenId());
        assertEquals("Atlas", build.screen().title());
        assertEquals(new MenuPaging(0, 1), build.screen().paging());
        assertEquals(MenuDriver.ordered(
            "atlas-sort", "Cycle -3,5 minecraft:comparator sort [P] {}",
            "atlas-favorites", "Toggle -2,5 minecraft:nether_star favorite [P] {}",
            "atlas-recents", "Toggle -1,5 minecraft:clock atlas [P] {}",
            "atlas-0", "ListEntry -4,0 minecraft:compass* wormhole [PST] {} S:small/favorite T:small/link",
            "atlas-1", "ListEntry -3,0 minecraft:ender_pearl portal [PST] {} S:small/favorite T:small/link",
            "atlas-page", "Label 0,5 minecraft:paper - [] {} hidden"), MenuDriver.layout(build.screen()));
        assertEquals("§6Zenith", driver.element("atlas-0").label());
        assertEquals(List.of("§7world_nether §8- §fQRST", "§bClosed"), driver.element("atlas-0").description());
        assertEquals(List.of("", "§eLeft-click to dial. §eRight-click to pin. §eShift-left-click to guide."),
            driver.element("atlas-0").common().chestHints());
        assertEquals(List.of("§7world §8- §fSpire", "§bOpen"), driver.element("atlas-1").description());
        assertTrue(((ListEntryElement) driver.element("atlas-0")).selected());
        assertFalse(((ListEntryElement) driver.element("atlas-1")).selected());
        CycleElement sort = (CycleElement) driver.element("atlas-sort");
        assertEquals("§e§lSort: §f§lSmart§r", sort.label());
        assertEquals(List.of("Smart", "Name", "World", "Distance"), sort.options());
        assertEquals(0, sort.index());
        assertEquals("§d§lPinned only: false§r", driver.element("atlas-favorites").label());
        assertEquals("§b§lRecent only: false§r", driver.element("atlas-recents").label());
        assertEquals("§b§lPage 1/1§r", driver.element("atlas-page").label());
        assertEquals(List.of("§72 destinations"), driver.element("atlas-page").description());
        MenuDriver.assertBound(build);
    }

    @Test
    void undiscoveredPortalsStayHiddenUntilDiscoveryIsOff() {
        open(AtlasModel.Filter.ALL);
        assertTrue(driver.screen().element("atlas-2").isEmpty());

        atlas.settings.discoveryRequired = false;
        open(AtlasModel.Filter.ALL);
        assertEquals("§6Cove", driver.element("atlas-2").label());
        assertEquals("§8Atlas §7- §f3 portals", driver.build().chest().title());
    }

    @Test
    void sortingAndFiltersRebuildFromTheFirstPage() {
        open(AtlasModel.Filter.ALL);

        driver.chest("atlas-sort");
        assertEquals("§6Beacon", driver.element("atlas-0").label());
        assertEquals(1, ((CycleElement) driver.element("atlas-sort")).index());
        assertEquals("§e§lSort: §f§lName§r", driver.element("atlas-sort").label());

        driver.chest("atlas-favorites");
        assertEquals(List.of("atlas-sort", "atlas-favorites", "atlas-recents", "atlas-0", "atlas-page"), MenuDriver.ids(driver.screen()));
        assertEquals("§6Zenith", driver.element("atlas-0").label());
        assertTrue(((ToggleElement) driver.element("atlas-favorites")).value());
        assertEquals("§8Atlas §7- §f1 portals", driver.build().chest().title());

        driver.chest("atlas-favorites");
        assertFalse(((ToggleElement) driver.element("atlas-favorites")).value());
        assertEquals("§8Atlas §7- §f2 portals", driver.build().chest().title());

        atlas.state.recordRecent(FakeAtlasHost.BEACON, 10);
        driver.chest("atlas-recents");
        assertEquals("§6Beacon", driver.element("atlas-0").label());
        assertTrue(driver.screen().element("atlas-1").isEmpty());
        assertTrue(((ToggleElement) driver.element("atlas-recents")).value());
    }

    @Test
    void theOpeningFilterComesFromTheRoute() {
        open(AtlasModel.Filter.FAVORITES);

        assertTrue(((ToggleElement) driver.element("atlas-favorites")).value());
        assertEquals("§6Zenith", driver.element("atlas-0").label());
        assertTrue(driver.screen().element("atlas-1").isEmpty());
    }

    @Test
    void emptyListsShowThePlaceholder() {
        atlas.candidates.clear();
        open(AtlasModel.Filter.ALL);

        assertEquals("Label 0,2 minecraft:barrier - [] {}", MenuDriver.describe(driver.element("atlas-empty")));
        assertEquals("§7No destinations are available to link.", driver.element("atlas-empty").label());
        assertEquals("§8Atlas §7- §f0 portals", driver.build().chest().title());
    }

    @Test
    void rowsPageInFortyFives() {
        atlas.candidates.clear();
        for (int index = 0; index < 50; index++) {
            atlas.candidates.add(new AtlasModel.Row(new UUID(9L, index), String.format("P%02d", Integer.valueOf(index)), "world", "", "",
                index, true, false, true));
        }
        open(AtlasModel.Filter.ALL);

        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
        assertEquals("Button 4,5 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("atlas-next")));
        assertEquals("§6P44", driver.element("atlas-44").label());

        driver.chest("atlas-next");
        assertEquals(new MenuPaging(1, 2), driver.screen().paging());
        assertEquals("ListEntry -4,0 minecraft:ender_pearl portal [PST] {} S:small/favorite T:small/link", MenuDriver.describe(driver.element("atlas-45")));
        assertEquals("Button -4,5 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("atlas-previous")));
        assertTrue(driver.screen().element("atlas-next").isEmpty());

        driver.chest("atlas-sort");
        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
    }

    @Test
    void pageAndSortChangesRepaintInPlace() {
        atlas.candidates.clear();
        for (int index = 0; index < 50; index++) {
            atlas.candidates.add(new AtlasModel.Row(new UUID(9L, index), String.format("P%02d", Integer.valueOf(index)), "world", "", "",
                index, true, false, true));
        }
        open(AtlasModel.Filter.ALL);
        ChestLayout opened = driver.build().chest();

        driver.chest("atlas-next");
        driver.chest("atlas-previous");
        driver.chest("atlas-sort");

        assertEquals(List.of("open:atlas.list@1", "update:atlas.list@2", "update:atlas.list@3", "update:atlas.list@4"), driver.host.chest.events);
        assertEquals(opened, driver.build().chest());
    }

    @Test
    void networkedRowsOpenTheDialAndOthersPrintASummary() {
        open(AtlasModel.Filter.ALL);

        driver.chest("atlas-1");
        assertEquals("§fBeacon §8| §7Spire §8| §bOpen", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertEquals(AtlasRoutes.LIST, driver.screen().screenId());

        driver.chest("atlas-0");
        assertEquals(NexusRoutes.DIAL, driver.screen().screenId());
        assertEquals(NexusRoutes.dial(FakeAtlasHost.ZENITH), dial.routes.get(dial.routes.size() - 1));
        assertTrue(driver.screen().canGoBack());
    }

    @Test
    void vanishedPortalsDoNothingWhenDialed() {
        atlas.links.clear();
        open(AtlasModel.Filter.ALL);

        driver.chest("atlas-0");

        assertTrue(driver.noticeTexts().isEmpty());
        assertEquals(AtlasRoutes.LIST, driver.screen().screenId());
    }

    @Test
    void pinningAndGuidingUseTheViewersAtlas() {
        open(AtlasModel.Filter.ALL);

        driver.chest("atlas-1", MenuInteraction.SECONDARY);
        assertEquals(PREFIX + "§aPinned Beacon.", driver.lastNotice());
        assertEquals("§6Beacon", driver.element("atlas-0").label());
        assertTrue(((ListEntryElement) driver.element("atlas-0")).selected());
        driver.chest("atlas-0", MenuInteraction.SECONDARY);
        assertEquals(PREFIX + "§aUnpinned Beacon.", driver.lastNotice());
        assertEquals("§6Beacon", driver.element("atlas-1").label());
        atlas.settings.favoritesLimit = 1;
        driver.chest("atlas-1", MenuInteraction.SECONDARY);
        assertEquals(PREFIX + "§cYou already pinned 1 portals.", driver.lastNotice());

        driver.chest("atlas-1", MenuInteraction.TERTIARY);
        assertEquals(PREFIX + "§aGuiding to Beacon.", driver.lastNotice());
        assertEquals("Button 2,5 minecraft:compass link [P] {}", MenuDriver.describe(driver.element("atlas-guide")));
        assertEquals("§a§lGuide: Beacon§r", driver.element("atlas-guide").label());

        driver.chest("atlas-guide");
        assertEquals(PREFIX + "§aGuide off.", driver.lastNotice());
        assertTrue(driver.screen().element("atlas-guide").isEmpty());
        assertEquals(List.of("favorite:Beacon", "favorite:Beacon", "favorite:Beacon", "guide:Beacon", "guide-off"), atlas.calls);
    }

    @Test
    void theAtlasNeedsThePermissionAndTheFeature() {
        driver.viewer.permissions.clear();
        open(AtlasModel.Filter.ALL);
        assertEquals(PREFIX + "§cYou do not have permission to open the atlas.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertTrue(driver.host.chest.builds.isEmpty());

        driver.viewer.permissions.add(AtlasRoutes.PERMISSION);
        atlas.settings.enabled = false;
        open(AtlasModel.Filter.ALL);
        assertEquals(PREFIX + "§cThe atlas is turned off on this server.", driver.lastNotice());
        assertTrue(driver.host.chest.builds.isEmpty());
    }

    @Test
    void revokedViewersAreRejectedOnEveryElement() {
        atlas.state.setGuideTarget(FakeAtlasHost.BEACON);
        open(AtlasModel.Filter.ALL);
        for (MenuElement element : List.copyOf(driver.screen().elements())) {
            if (element.interactions().isEmpty()) {
                continue;
            }
            for (MenuInteraction interaction : element.interactions()) {
                driver.viewer.permissions.add(AtlasRoutes.PERMISSION);
                open(AtlasModel.Filter.ALL);
                driver.viewer.permissions.clear();
                driver.chestAction(element.id(), interaction, null, false);
                assertTrue(atlas.calls.isEmpty(), element.id() + " " + interaction);
                assertFalse(driver.sessionOpen(), element.id() + " " + interaction);
            }
        }
    }

    @Test
    void staleNativeActionsAreRefreshedInsteadOfApplied() {
        driver.hello();
        open(AtlasModel.Filter.ALL);

        driver.nativeActionAt(driver.revision() - 1L, "atlas-1", MenuInteraction.SECONDARY, null);

        assertTrue(atlas.calls.isEmpty());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, driver.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
    }

    @Test
    void nativeRowsCarryTheirSideActions() {
        driver.hello();
        open(AtlasModel.Filter.ALL);

        driver.nativeAction("atlas-1", MenuInteraction.SECONDARY, null);

        MenuScreen screen = driver.nativeScreen();
        assertTrue(((ListEntryElement) screen.element("atlas-1").orElseThrow()).selected());
        assertTrue(screen.element("atlas-page").isEmpty());
        assertEquals(List.of("Favorite", "Guide"), ((ListEntryElement) screen.element("atlas-1").orElseThrow()).secondaryActions().stream()
            .map(action -> action.label()).toList());
        driver.nativeAction("atlas-sort", MenuInteraction.PRIMARY, null);
        assertEquals("§6Beacon", driver.nativeScreen().element("atlas-0").orElseThrow().label());
    }

    @Test
    void theAtlasDoesNotRefreshOnATimer() {
        AtlasMenuController controller = new AtlasMenuController(atlas);
        assertEquals(0, controller.refreshIntervalTicks(AtlasRoutes.list(AtlasModel.Filter.ALL)));
        assertFalse(controller.touches(AtlasRoutes.list(AtlasModel.Filter.ALL), new MenuScope.Viewer(VIEWER)));
        assertFalse(controller.touches(AtlasRoutes.list(AtlasModel.Filter.ALL), new MenuScope.Portal(FakeAtlasHost.BEACON)));
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open(AtlasModel.Filter.ALL);
        MenuScreen screen = driver.build().screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Sort", MenuText.stripColors(MenuScreens.element(screen, "atlas-sort").common().wireLabel()));
    }

    private void open(AtlasModel.Filter filter) {
        driver.open(AtlasRoutes.list(filter));
    }
}
