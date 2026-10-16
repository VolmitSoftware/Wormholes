package art.arcane.wormholes.menu.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuDriver;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.PortalNetwork;

final class DialMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String PREFIX = "§8[§6Wormholes§8] ";

    private FakeNexusHost nexus;
    private MenuDriver driver;

    @BeforeEach
    void setUp() {
        nexus = new FakeNexusHost();
        nexus.dialers.add(VIEWER);
        driver = new MenuDriver(VIEWER, "Traveler", List.of(new DialMenuController(nexus)));
    }

    @Test
    void entriesMatchTheChestWindowAndHighlightTheCurrentAddress() {
        nexus.join(FakeNexusHost.lobby(3));
        dialing("BAB");
        nexus.worlds.put(new UUID(7L, 0), "world_nether");
        open();
        MenuBuild build = driver.build();

        assertEquals(new ChestLayout("§0§lAlpha", 6, ChestPane.CYAN, null), build.chest());
        assertEquals(NexusRoutes.DIAL, build.screen().screenId());
        assertEquals("Dial", build.screen().title());
        assertEquals("Lobby", build.screen().context());
        assertEquals(new MenuPaging(0, 1), build.screen().paging());
        assertEquals(MenuDriver.ordered(
            "dial-0", "ListEntry -4,0 minecraft:ender_pearl portal [P] {}",
            "dial-1", "ListEntry -3,0 minecraft:end_crystal* gateway [P] {}",
            "dial-2", "ListEntry -2,0 minecraft:ender_pearl portal [P] {}",
            "dial-page", "Label 0,5 minecraft:paper - [] {} hidden"), MenuDriver.layout(build.screen()));
        assertEquals("§eBAA §fMember 0", driver.element("dial-0").label());
        assertEquals(List.of("§7world_nether", "§bOpen"), driver.element("dial-0").description());
        assertEquals(List.of("§7east", "§bOpen"), driver.element("dial-1").description());
        assertTrue(((ListEntryElement) driver.element("dial-1")).selected());
        assertFalse(((ListEntryElement) driver.element("dial-0")).selected());
        assertEquals("§b§lPage 1/1§r", driver.element("dial-page").label());
        assertEquals(List.of("§73 destinations"), driver.element("dial-page").description());
        MenuDriver.assertBound(build);
    }

    @Test
    void emptyNetworksShowThePlaceholder() {
        nexus.join(FakeNexusHost.lobby(0));
        open();

        assertEquals(MenuDriver.ordered(
            "dial-empty", "Label 0,2 minecraft:barrier - [] {}",
            "dial-page", "Label 0,5 minecraft:paper - [] {} hidden"), MenuDriver.layout(driver.screen()));
        assertEquals("§7No destinations are available to link.", driver.element("dial-empty").label());
    }

    @Test
    void entriesPageInFortyFives() {
        nexus.join(FakeNexusHost.lobby(50));
        open();

        MenuScreen first = driver.screen();
        assertEquals(new MenuPaging(0, 2), first.paging());
        assertEquals(47, first.elements().size());
        assertEquals("ListEntry 4,4 minecraft:ender_pearl portal [P] {}", MenuDriver.describe(driver.element("dial-44")));
        assertEquals("Button 4,5 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("dial-next")));
        assertTrue(first.element("dial-previous").isEmpty());
        assertEquals(List.of("§eLeft click"), driver.element("dial-next").common().chestHints());

        driver.chest("dial-next");
        MenuScreen second = driver.screen();
        assertEquals(new MenuPaging(1, 2), second.paging());
        assertEquals(MenuDriver.ordered(
            "dial-45", "ListEntry -4,0 minecraft:end_crystal gateway [P] {}",
            "dial-46", "ListEntry -3,0 minecraft:ender_pearl portal [P] {}",
            "dial-47", "ListEntry -2,0 minecraft:end_crystal gateway [P] {}",
            "dial-48", "ListEntry -1,0 minecraft:ender_pearl portal [P] {}",
            "dial-49", "ListEntry 0,0 minecraft:end_crystal gateway [P] {}",
            "dial-previous", "Button -4,5 minecraft:arrow - [P] {} hidden",
            "dial-page", "Label 0,5 minecraft:paper - [] {} hidden"), MenuDriver.layout(second));
        assertEquals("§b§lPage 2/2§r", driver.element("dial-page").label());

        driver.chest("dial-previous");
        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
    }

    @Test
    void nativePagingUsesTheFooter() {
        nexus.join(FakeNexusHost.lobby(50));
        driver.hello();
        open();

        driver.nativePage(1);

        MenuScreen screen = driver.nativeScreen();
        assertEquals(new MenuPaging(1, 2), screen.paging());
        assertTrue(screen.element("dial-45").isPresent());
        assertTrue(screen.element("dial-previous").isEmpty());
        assertEquals("Page 2 / 2", screen.chrome().pageLabel());
    }

    @Test
    void opensOnThePageHoldingTheCurrentAddress() {
        nexus.join(FakeNexusHost.lobby(50));
        dialing(nexus.networks.get(FakeNexusHost.NETWORK).member(new UUID(7L, 47)).address());
        open();

        assertEquals(new MenuPaging(1, 2), driver.screen().paging());
        assertTrue(((ListEntryElement) driver.element("dial-47")).selected());

        driver.chest("dial-previous");
        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
        driver.tick(40);
        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
    }

    @Test
    void dialingClosesAndReportsEveryOutcome() {
        nexus.join(FakeNexusHost.lobby(3));
        Map<DialOutcome, String> expected = Map.of(
            DialOutcome.DIALED, PREFIX + "§aAlpha dialed to BAB: §fMember 1",
            DialOutcome.DEBOUNCED, PREFIX + "§7Dialing too fast; wait a moment.",
            DialOutcome.UNKNOWN_ADDRESS, PREFIX + "§cNo portal at address BAB.",
            DialOutcome.NOT_ON_NETWORK, PREFIX + "§cAlpha is not on a network.",
            DialOutcome.MANAGED_PORTAL, PREFIX + "§cAlpha is a managed portal and cannot be dialed.");
        for (DialOutcome outcome : DialOutcome.values()) {
            nexus.outcome = outcome;
            open();

            driver.chest("dial-1");

            assertEquals("BAB", nexus.dials.get(nexus.dials.size() - 1));
            assertEquals(expected.get(outcome), driver.lastNotice(), outcome.name());
            assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
            assertFalse(driver.sessionOpen(), outcome.name());
        }
    }

    @Test
    void openingNeedsANetworkAndDialAdmission() {
        open();
        assertEquals(PREFIX + "§cAlpha is not on a network.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertTrue(driver.host.chest.builds.isEmpty());

        nexus.join(FakeNexusHost.lobby(3));
        nexus.dialers.clear();
        open();
        assertEquals("§cPortal access denied", driver.lastNotice());
        assertTrue(driver.host.chest.builds.isEmpty());
        assertFalse(driver.sessionOpen());
    }

    @Test
    void dialAdmissionIsCheckedAgainWhenDialing() {
        nexus.join(FakeNexusHost.lobby(3));
        open();
        nexus.dialers.clear();

        driver.chest("dial-0");

        assertTrue(nexus.dials.isEmpty());
        assertEquals("§cPortal access denied", driver.lastNotice());
        assertFalse(driver.sessionOpen());
    }

    @Test
    void entriesRefreshEveryFortyTicks() {
        nexus.join(FakeNexusHost.lobby(3));
        DialMenuController controller = new DialMenuController(nexus);
        MenuRoute route = NexusRoutes.dial(FakeNexusHost.PORTAL);
        assertEquals(DialMenuController.REFRESH_TICKS, controller.refreshIntervalTicks(route));
        assertEquals(40, DialMenuController.REFRESH_TICKS);
        int lookups = nexus.portalLookups;
        assertTrue(controller.touches(route, new MenuScope.Portal(FakeNexusHost.PORTAL)));
        assertFalse(controller.touches(route, new MenuScope.Portal(UUID.randomUUID())));
        assertTrue(controller.touches(route, new MenuScope.Network(FakeNexusHost.NETWORK)));
        assertTrue(controller.touches(route, new MenuScope.Network(UUID.randomUUID())));
        assertEquals(lookups, nexus.portalLookups);

        open();
        int shown = driver.host.chest.builds.size();
        driver.service.invalidate(new MenuScope.Network(UUID.randomUUID()));
        assertEquals(shown, driver.host.chest.builds.size());
        driver.tick(40);
        assertEquals(shown, driver.host.chest.builds.size());

        UUID added = UUID.randomUUID();
        PortalNetwork network = nexus.networks.get(FakeNexusHost.NETWORK);
        nexus.networks.put(network.id(), network.withMember(added, new NetworkMember(added, "ZZZ", "Late", 3L, null)));
        driver.tick(39);
        assertEquals(shown, driver.host.chest.builds.size());
        driver.tick(1);
        assertEquals("update:nexus.dial@2", driver.lastEvent());
        assertEquals("§eZZZ §fLate", driver.element("dial-3").label());
    }

    @Test
    void staleNativeDialsAreRefreshedInsteadOfDialed() {
        nexus.join(FakeNexusHost.lobby(3));
        driver.hello();
        open();

        driver.nativeActionAt(driver.revision() - 1L, "dial-0", MenuInteraction.PRIMARY, null);

        assertTrue(nexus.dials.isEmpty());
        assertInstanceOf(MenuServerMessage.Update.class, driver.lastMessage());
        assertTrue(driver.sessionOpen());
    }

    private void dialing(String address) {
        NexusPortalView current = nexus.portal;
        nexus.portal = new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(), current.routerTitle(),
            current.networkId(), current.address(), current.reciprocal(), current.policy(), current.frameIo(),
            new DialState(address, 1L, VIEWER, false));
    }

    private void open() {
        driver.open(NexusRoutes.dial(FakeNexusHost.PORTAL));
    }
}
