package art.arcane.wormholes.menu.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuDriver;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuSession;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.nexus.DestinationMode;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.Topology;
import art.arcane.wormholes.nexus.Visibility;

final class NetworkMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String PREFIX = "§8[§6Wormholes§8] ";

    private FakeNexusHost nexus;
    private MenuDriver driver;

    @BeforeEach
    void setUp() {
        nexus = new FakeNexusHost();
        nexus.managers.add(VIEWER);
        nexus.dialers.add(VIEWER);
        driver = new MenuDriver(VIEWER, "Owner", List.of(new NetworkMenuController(nexus, new Random(7L)), new DialMenuController(nexus)));
    }

    @Test
    void unjoinedPortalShowsJoinAndCreate() {
        open();
        MenuBuild build = driver.build();

        assertEquals(new ChestLayout("§0§lAlpha", 4, ChestPane.CYAN, null), build.chest());
        assertEquals(NexusRoutes.NETWORK, build.screen().screenId());
        assertEquals("Network", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(MenuDriver.ordered(
            "nexus-placard", "Label 0,0 minecraft:compass network [] {}",
            "nexus-join", "Text -1,1 minecraft:ender_eye network [X] {}",
            "nexus-create", "Text 1,1 minecraft:nether_star network [X] {}"), MenuDriver.layout(build.screen()));
        assertEquals("§b§lNetwork: §r", driver.element("nexus-placard").label());
        assertEquals(List.of("§7Address: §f", "§7Members: §f0"), driver.element("nexus-placard").description());
        assertEquals("§a§lJoin a network§r", driver.element("nexus-join").label());
        assertEquals(List.of("§7Add this portal to a network and give it an address."), driver.element("nexus-join").description());
        assertEquals(List.of("§eLeft-click"), driver.element("nexus-join").common().chestHints());
        TextElement join = (TextElement) driver.element("nexus-join");
        assertEquals(List.of("§bType the network name in chat (or 'cancel'):"), join.chestPrompt());
        assertEquals("Network name", join.placeholder());
        assertEquals("", join.value());
        assertEquals(ChestReopen.NEVER, join.chestReopen());
        assertEquals(ChestReopen.NEVER, ((TextElement) driver.element("nexus-create")).chestReopen());
        MenuDriver.assertBound(build);
    }

    @Test
    void joinedPortalMatchesTheChestWindowAndNativeTables() {
        nexus.join(FakeNexusHost.lobby(2));
        open();
        MenuBuild build = driver.build();

        assertEquals(new ChestLayout("§0§lAlpha", 4, ChestPane.CYAN, null), build.chest());
        assertEquals(NexusRoutes.NETWORK, build.screen().screenId());
        assertEquals("Lobby", build.screen().context());
        assertEquals(MenuDriver.ordered(
            "nexus-placard", "Label 0,0 minecraft:compass network [] {}",
            "nexus-address", "Text -3,1 minecraft:name_tag rename [SX] {LEFT=X, RIGHT=S} S:small/reset",
            "nexus-visibility", "Cycle -1,1 minecraft:item_frame network [P] {}",
            "nexus-topology", "Cycle 1,1 minecraft:iron_bars network [P] {}",
            "nexus-dial", "Button 3,1 minecraft:lever wormhole [P] {}",
            "nexus-reciprocal", "Toggle -3,2 minecraft:ender_chest link [P] {}",
            "nexus-policy", "Cycle -1,2 minecraft:target network [PS] {LEFT=P, RIGHT=S} S:small/delete",
            "nexus-redstone", "Cycle 1,2 minecraft:redstone_torch settings [P] {LEFT=P, RIGHT=nexus-redstone-comparator.P}",
            "nexus-redstone-comparator", "Cycle - minecraft:comparator settings [P] {}",
            "nexus-leave", "Button 3,2 minecraft:barrier delete [P] {LEFT=P!}"), MenuDriver.layout(build.screen()));
        assertEquals(MenuStyle.DESTRUCTIVE, driver.element("nexus-leave").style());
        assertEquals("§b§lNetwork: Lobby§r", driver.element("nexus-placard").label());
        assertEquals(List.of("§7Address: §fAAAA", "§7Members: §f3"), driver.element("nexus-placard").description());
        assertEquals("§e§lAddress: AAAA§r", driver.element("nexus-address").label());
        assertEquals(List.of("§eLeft-click to retype. §eRight-click to reroll."), driver.element("nexus-address").common().chestHints());
        assertEquals("AAAA", ((TextElement) driver.element("nexus-address")).value());
        assertEquals(List.of("§bType the new address in chat (or 'cancel'):"), ((TextElement) driver.element("nexus-address")).chestPrompt());
        assertEquals(ChestReopen.NEVER, ((TextElement) driver.element("nexus-address")).chestReopen());
        CycleElement visibility = (CycleElement) driver.element("nexus-visibility");
        assertEquals("§e§lVisibility: MEMBERS§r", visibility.label());
        assertEquals(List.of("PUBLIC", "MEMBERS", "HIDDEN"), visibility.options());
        assertEquals(Visibility.MEMBERS.ordinal(), visibility.index());
        assertEquals(List.of("MESH", "HUB", "CHAIN", "RING"), ((CycleElement) driver.element("nexus-topology")).options());
        assertEquals("§e§lReturn link: false§r", driver.element("nexus-reciprocal").label());
        assertEquals("§e§lDestinations: SINGLE§r", driver.element("nexus-policy").label());
        assertEquals(List.of("§70 entries. Ordered, weighted, scheduled, per-player, or return."), driver.element("nexus-policy").description());
        assertEquals("§e§lRedstone: NONE/NONE§r", driver.element("nexus-redstone").label());
        CycleElement comparator = (CycleElement) driver.element("nexus-redstone-comparator");
        assertEquals("Comparator output", comparator.label());
        assertEquals(List.of(), comparator.description());
        assertEquals(List.of("NONE", "STATE", "TRAVERSALS"), comparator.options());
        assertEquals("§c§lLeave Lobby§r", driver.element("nexus-leave").label());
        MenuDriver.assertBound(build);
    }

    @Test
    void joiningAddsThePortalUnderAFreshAddress() {
        PortalNetwork lobby = PortalNetwork.create(FakeNexusHost.NETWORK, "Lobby", UUID.randomUUID());
        nexus.networks.put(lobby.id(), lobby);
        open();

        driver.chestText("nexus-join", "  lobby ");

        assertEquals(1, nexus.saved.size());
        NetworkMember member = nexus.saved.get(0).member(FakeNexusHost.PORTAL);
        assertEquals("Alpha", member.label());
        assertEquals(4, member.address().length());
        assertEquals(List.of("Join[networkId=" + FakeNexusHost.NETWORK + ", address=" + member.address() + ", label=Alpha]"), nexus.edits);
        assertEquals(PREFIX + "§aAlpha joined Lobby at address " + member.address() + ".", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertEquals(MenuFeedbackStyle.SUCCESS, driver.lastNoticeRecord().feedback().style());
        assertTrue(driver.screen().element("nexus-leave").isPresent());
    }

    @Test
    void joiningRefusesUnknownAndFullNetworks() {
        PortalNetwork lobby = FakeNexusHost.lobby(2).withoutMember(FakeNexusHost.PORTAL);
        nexus.networks.put(lobby.id(), lobby);
        nexus.config.maxMembersPerNetwork = 2;
        open();

        driver.chestText("nexus-join", "Nowhere");
        driver.chestText("nexus-join", "Lobby");

        assertEquals(List.of(PREFIX + "§cNo network named Nowhere.", PREFIX + "§cLobby already holds 2 portals."), driver.noticeTexts());
        assertTrue(nexus.saved.isEmpty());
        assertTrue(nexus.edits.isEmpty());
        assertTrue(driver.screen().element("nexus-join").isPresent());
    }

    @Test
    void creatingChecksTheNameAndTheOwnershipLimit() {
        PortalNetwork lobby = FakeNexusHost.lobby(0).withoutMember(FakeNexusHost.PORTAL);
        nexus.networks.put(lobby.id(), lobby);
        nexus.owned.put(VIEWER, 4);
        open();

        driver.chestText("nexus-create", "lobby");
        driver.chestText("nexus-create", "Spire");
        assertEquals(List.of(PREFIX + "§cA network named lobby already exists.", PREFIX + "§cYou already own 4 networks."), driver.noticeTexts());
        assertTrue(nexus.saved.isEmpty());

        driver.viewer.permissions.add(NetworkMenuController.ADMIN_NEXUS_PERMISSION);
        driver.chestText("nexus-create", "Spire");

        PortalNetwork created = nexus.saved.get(0);
        assertEquals("Spire", created.name());
        assertEquals(VIEWER, created.ownerId());
        String address = created.member(FakeNexusHost.PORTAL).address();
        assertEquals(List.of(PREFIX + "§aAlpha joined Spire at address " + address + "."), nexus.told);
        assertEquals(PREFIX + "§aCreated network Spire.", driver.lastNotice());
        assertTrue(driver.screen().element("nexus-leave").isPresent());
    }

    @Test
    void creatingReportsTheCreationEvenWhenTheJoinIsRefused() {
        nexus.config.maxMembersPerNetwork = 0;
        open();

        driver.chestText("nexus-create", "Spire");

        assertTrue(nexus.saved.isEmpty());
        assertTrue(nexus.edits.isEmpty());
        assertEquals(List.of(PREFIX + "§cSpire already holds 0 portals."), nexus.told);
        assertEquals(PREFIX + "§aCreated network Spire.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());

        nexus.config.maxMembersPerNetwork = 64;
        nexus.saveFails = true;
        driver.chestText("nexus-create", "Spire");

        assertEquals(List.of(PREFIX + "§cSpire already holds 0 portals.", PREFIX + "§aCreated network Spire."), nexus.told);
        assertEquals("The change could not be saved.", driver.lastNotice());
        assertEquals(NoticeChannel.NONE, driver.lastNoticeRecord().chestChannel());
        assertTrue(driver.screen().element("nexus-join").isPresent());
    }

    @Test
    void addressesMustUseTheAlphabetAndStayUnique() {
        nexus.join(FakeNexusHost.lobby(2));
        open();

        driver.chestText("nexus-address", "zz!");
        driver.chestText("nexus-address", "baa");
        assertEquals(List.of(PREFIX + "§cAddresses use only these characters: ABCDEFGHJKLMNPQRSTUVWXYZ23456789",
            PREFIX + "§cAddress BAA is already used in Lobby."), driver.noticeTexts());
        assertTrue(nexus.saved.isEmpty());

        driver.chestText("nexus-address", "zzzz");
        assertEquals("ZZZZ", nexus.saved.get(0).member(FakeNexusHost.PORTAL).address());
        assertEquals(List.of("Address[address=ZZZZ]"), nexus.edits);
        assertEquals(PREFIX + "§aAlpha answers to ZZZZ.", driver.lastNotice());
        assertEquals("§e§lAddress: ZZZZ§r", driver.element("nexus-address").label());

        driver.chest("nexus-address", MenuInteraction.SECONDARY);
        String rerolled = nexus.saved.get(1).member(FakeNexusHost.PORTAL).address();
        assertNotEquals("ZZZZ", rerolled);
        assertEquals(PREFIX + "§aAlpha answers to " + rerolled + ".", driver.lastNotice());
    }

    @Test
    void networkSettingsSaveTheNetworkAndReportSaveFailures() {
        nexus.join(FakeNexusHost.lobby(2));
        open();

        driver.chest("nexus-visibility");
        driver.chest("nexus-topology");
        assertEquals(Visibility.HIDDEN, nexus.networks.get(FakeNexusHost.NETWORK).visibility());
        assertEquals(Topology.HUB, nexus.networks.get(FakeNexusHost.NETWORK).topology());
        assertEquals("§e§lTopology: HUB§r", driver.element("nexus-topology").label());
        assertTrue(driver.noticeTexts().isEmpty());

        nexus.saveFails = true;
        driver.chest("nexus-visibility");
        assertEquals("The change could not be saved.", driver.lastNotice());
        assertEquals(NoticeChannel.NONE, driver.lastNoticeRecord().chestChannel());
        assertEquals(Visibility.HIDDEN, nexus.networks.get(FakeNexusHost.NETWORK).visibility());
    }

    @Test
    void portalSettingsEditTheNexusState() {
        nexus.join(FakeNexusHost.lobby(2));
        open();

        driver.chest("nexus-reciprocal");
        assertTrue(((ToggleElement) driver.element("nexus-reciprocal")).value());
        driver.chest("nexus-policy");
        assertEquals(DestinationMode.ORDERED, nexus.portal.policy().mode());
        driver.chest("nexus-policy", MenuInteraction.SECONDARY);
        assertEquals(DestinationPolicy.empty(), nexus.portal.policy());
        driver.chest("nexus-redstone");
        assertEquals(FrameIo.RedstoneAction.OPEN, nexus.portal.frameIo().action());
        driver.chest("nexus-redstone-comparator");
        assertEquals(FrameIo.ComparatorOutput.STATE, nexus.portal.frameIo().comparator());

        assertEquals(List.of(PREFIX + "§aAlpha redstone: OPEN.", PREFIX + "§aAlpha redstone: STATE."), driver.noticeTexts());
        assertEquals("§e§lRedstone: OPEN/STATE§r", driver.element("nexus-redstone").label());
        assertEquals(5, nexus.edits.size());
        assertTrue(nexus.saved.isEmpty());
    }

    @Test
    void chestLeaveIsPreconfirmedAndShowsTheJoinScreenAgain() {
        nexus.join(FakeNexusHost.lobby(2));
        open();

        driver.chestPreconfirmed("nexus-leave");

        assertEquals(null, nexus.saved.get(0).member(FakeNexusHost.PORTAL));
        assertEquals(List.of("Leave[]"), nexus.edits);
        assertEquals(PREFIX + "§aAlpha left Lobby.", driver.lastNotice());
        assertTrue(driver.screen().element("nexus-join").isPresent());
        assertTrue(driver.sessionOpen());
    }

    @Test
    void joiningAndLeavingRepaintTheNetworkWindowInPlace() {
        PortalNetwork lobby = PortalNetwork.create(FakeNexusHost.NETWORK, "Lobby", UUID.randomUUID());
        nexus.networks.put(lobby.id(), lobby);
        open();

        driver.chestText("nexus-join", "Lobby");
        driver.chestPreconfirmed("nexus-leave");

        assertEquals(List.of("open:nexus.network@1", "update:nexus.network@2", "notice:CHAT", "update:nexus.network@3", "notice:CHAT"),
            driver.host.chest.events);
        assertEquals(driver.host.chest.builds.get(0).chest(), driver.build().chest());
    }

    @Test
    void nativeLeaveAsksFirst() {
        nexus.join(FakeNexusHost.lobby(2));
        driver.hello();
        open();

        driver.nativeAction("nexus-leave", MenuInteraction.PRIMARY, null);
        MenuServerMessage.Open confirm = assertInstanceOf(MenuServerMessage.Open.class, driver.lastMessage());
        assertTrue(nexus.edits.isEmpty());
        assertEquals("Leave Lobby?", confirm.screen().element(MenuSession.CONFIRM_HEADER).orElseThrow().label());
        assertEquals(List.of("This portal stops dialing and receiving through the network."), confirm.screen().confirmation().messageLines());
        assertEquals("Leave", confirm.screen().element(MenuSession.CONFIRM_ACCEPT).orElseThrow().label());
        assertEquals(MenuStyle.DESTRUCTIVE, confirm.screen().element(MenuSession.CONFIRM_ACCEPT).orElseThrow().style());

        driver.nativeConfirm(true);
        assertEquals(List.of("Leave[]"), nexus.edits);
        assertTrue(driver.nativeScreen().element("nexus-join").isPresent());
        assertEquals(NexusRoutes.NETWORK, driver.nativeScreen().screenId());
    }

    @Test
    void dialOpensTheDialScreenOnlyForAdmittedViewers() {
        nexus.join(FakeNexusHost.lobby(2));
        open();
        driver.chest("nexus-dial");
        assertEquals(NexusRoutes.DIAL, driver.screen().screenId());

        nexus.dialers.clear();
        open();
        driver.chest("nexus-dial");
        assertEquals("§cPortal access denied", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertEquals(NexusRoutes.NETWORK, driver.screen().screenId());
        assertFalse(driver.sessionOpen());
    }

    @Test
    void viewersWhoCannotManageAreRejectedOnEveryElement() {
        nexus.managers.clear();
        open();
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", driver.lastNotice());
        assertFalse(driver.sessionOpen());

        for (boolean joined : List.of(false, true)) {
            nexus.networks.clear();
            nexus.portal = FakeNexusHost.view(null, "");
            if (joined) {
                nexus.join(FakeNexusHost.lobby(2));
            }
            for (MenuElement element : boundElements()) {
                for (MenuInteraction interaction : element.interactions()) {
                    nexus.managers.add(VIEWER);
                    open();
                    nexus.managers.clear();
                    MenuValue value = interaction == MenuInteraction.SET_TEXT ? MenuValue.text("Lobby") : null;
                    driver.chestAction(element.id(), interaction, value, true);
                    assertTrue(nexus.edits.isEmpty(), element.id() + " " + interaction);
                    assertTrue(nexus.saved.isEmpty(), element.id() + " " + interaction);
                    assertTrue(nexus.told.isEmpty(), element.id() + " " + interaction);
                    assertFalse(driver.sessionOpen(), element.id() + " " + interaction);
                }
            }
        }
    }

    @Test
    void staleNativeActionsAreRefreshedInsteadOfApplied() {
        nexus.join(FakeNexusHost.lobby(2));
        driver.hello();
        open();
        long stale = driver.revision() - 1L;

        driver.nativeActionAt(stale, "nexus-reciprocal", MenuInteraction.PRIMARY, null);

        assertTrue(nexus.edits.isEmpty());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, driver.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
    }

    @Test
    void invalidationScopesAndNoPeriodicRefresh() {
        nexus.join(FakeNexusHost.lobby(2));
        NetworkMenuController controller = new NetworkMenuController(nexus, new Random(1L));
        MenuRoute route = NexusRoutes.network(FakeNexusHost.PORTAL);

        assertEquals(0, controller.refreshIntervalTicks(route));
        int lookups = nexus.portalLookups;
        assertTrue(controller.touches(route, new MenuScope.Portal(FakeNexusHost.PORTAL)));
        assertFalse(controller.touches(route, new MenuScope.Portal(UUID.randomUUID())));
        assertTrue(controller.touches(route, new MenuScope.Network(FakeNexusHost.NETWORK)));
        assertTrue(controller.touches(route, new MenuScope.Network(UUID.randomUUID())));
        assertFalse(controller.touches(route, new MenuScope.Viewer(VIEWER)));
        assertEquals(lookups, nexus.portalLookups);

        open();
        int shown = driver.host.chest.events.size();
        driver.service.invalidate(new MenuScope.Network(UUID.randomUUID()));
        assertEquals(shown, driver.host.chest.events.size());

        nexus.networks.put(FakeNexusHost.NETWORK, nexus.networks.get(FakeNexusHost.NETWORK).withVisibility(Visibility.PUBLIC));
        driver.service.invalidate(new MenuScope.Network(FakeNexusHost.NETWORK));
        assertEquals("update:nexus.network@2", driver.lastEvent());
        assertEquals("§e§lVisibility: PUBLIC§r", driver.element("nexus-visibility").label());
    }

    private List<MenuElement> boundElements() {
        nexus.managers.add(VIEWER);
        open();
        nexus.managers.clear();
        List<MenuElement> elements = new ArrayList<>();
        for (MenuElement element : driver.screen().elements()) {
            if (!element.interactions().isEmpty()) {
                elements.add(element);
            }
        }
        driver.service.quit(VIEWER);
        return elements;
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        nexus.join(FakeNexusHost.lobby(2));
        open();
        MenuScreen screen = driver.build().screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Visibility", MenuText.stripColors(MenuScreens.element(screen, "nexus-visibility").common().wireLabel()));
        assertEquals("Return link", MenuText.stripColors(MenuScreens.element(screen, "nexus-reciprocal").common().wireLabel()));
    }

    private void open() {
        driver.open(NexusRoutes.network(FakeNexusHost.PORTAL));
    }
}
