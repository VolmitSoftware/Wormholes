package art.arcane.wormholes.menu.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuDriver;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class DoorAccessMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String PREFIX = "§8[§6Wormholes§8] ";
    private static final String ENTRY_CLICKS = "{LEFT=P, RIGHT=S, SHIFT_LEFT=T, MIDDLE=T} P:small/confirm S:small/cancel T:small/delete";

    private FakeDoorHost doors;
    private MenuDriver driver;

    @BeforeEach
    void setUp() {
        doors = new FakeDoorHost();
        doors.managers.add(VIEWER);
        driver = new MenuDriver(VIEWER, "Olga", List.of(new DoorAccessMenuController(doors)));
    }

    @Test
    void accessListMatchesTheChestWindowAndNativeTables() {
        open();
        MenuBuild build = driver.build();

        assertEquals(new ChestLayout("Door Access: Entangled", 2, ChestPane.GRAY, null), build.chest());
        assertEquals(DoorRoutes.ACCESS, build.screen().screenId());
        assertEquals("Door Access", build.screen().title());
        assertEquals("Entangled door", build.screen().context());
        assertNull(build.screen().paging());
        assertEquals(MenuDriver.ordered(
            "door-access-placard", "Label -1,0 minecraft:oak_door - [] {}",
            "door-access-open-state", "Toggle 0,0 minecraft:lime_dye door [P] {}",
            "door-access-add", "Text 1,0 minecraft:writable_book player [X] {}",
            "door-access-projection", "Cycle 2,0 minecraft:spyglass projection [P] {}",
            "door-access-" + FakeDoorHost.ADA, "ListEntry -4,1 minecraft:black_stained_glass_pane player [PST] " + ENTRY_CLICKS,
            "door-access-" + FakeDoorHost.BEN, "ListEntry -3,1 minecraft:green_stained_glass_pane player [PST] " + ENTRY_CLICKS,
            "door-access-" + FakeDoorHost.CY, "ListEntry -2,1 minecraft:red_stained_glass_pane player [PST] " + ENTRY_CLICKS),
            MenuDriver.layout(build.screen()));
        assertEquals("§6§lEntangled Dimensional Door§r", driver.element("door-access-placard").label());
        assertEquals(List.of("§7Owner: §fOlga", "§7Whitelisted: §a1", "§7Blacklisted: §c1", "§7Listed players: §b3",
            "§8Owners and administrators edit this door."), driver.element("door-access-placard").description());
        assertEquals("§b§lOpenState: §f§lOpen", driver.element("door-access-open-state").label());
        assertEquals(List.of("", "§8Next: Closed. Click to switch."), driver.element("door-access-open-state").common().chestHints());
        assertTrue(((ToggleElement) driver.element("door-access-open-state")).value());
        CycleElement projection = (CycleElement) driver.element("door-access-projection");
        assertEquals("§b§lProjection: inherit§r", projection.label());
        assertEquals(List.of("inherit", "on", "off"), projection.options());
        assertEquals(0, projection.index());
        assertEquals("§fAda", driver.element("door-access-" + FakeDoorHost.ADA).label());
        assertEquals(List.of("§7State: §bNeutral"), driver.element("door-access-" + FakeDoorHost.ADA).description());
        assertEquals(List.of("", "§8Left click: whitelist. Right click: blacklist.", "§8Middle click or shift + left click: remove."),
            driver.element("door-access-" + FakeDoorHost.ADA).common().chestHints());
        assertEquals("§fUnknown (c0ffee00)", driver.element("door-access-" + FakeDoorHost.CY).label());
        TextElement add = (TextElement) driver.element("door-access-add");
        assertEquals(List.of("§bType the player name to list for this door (or 'cancel'):"), add.chestPrompt());
        assertEquals(ChestReopen.ALWAYS, add.chestReopen());
        MenuDriver.assertBound(build);
    }

    @Test
    void entriesWhitelistBlacklistAndRemove() {
        open();

        driver.chest("door-access-" + FakeDoorHost.ADA);
        assertEquals(DoorAccessState.WHITELIST, doors.players.get(FakeDoorHost.ADA));
        assertEquals("§aAda is now Whitelist for this door.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        driver.chest("door-access-" + FakeDoorHost.ADA);
        assertEquals(1, doors.edits.size());

        driver.chest("door-access-" + FakeDoorHost.ADA, MenuInteraction.SECONDARY);
        assertEquals("§aAda is now Blacklist for this door.", driver.lastNotice());
        assertEquals("ListEntry -4,1 minecraft:red_stained_glass_pane player [PST] " + ENTRY_CLICKS,
            MenuDriver.describe(driver.element("door-access-" + FakeDoorHost.ADA)));

        driver.chest("door-access-" + FakeDoorHost.ADA, MenuInteraction.TERTIARY);
        assertEquals("§aAda was removed from this door's list.", driver.lastNotice());
        assertTrue(driver.screen().element("door-access-" + FakeDoorHost.ADA).isEmpty());
        assertEquals(List.of("PlayerState[playerId=" + FakeDoorHost.ADA + ", state=WHITELIST]",
            "PlayerState[playerId=" + FakeDoorHost.ADA + ", state=BLACKLIST]", "RemovePlayer[playerId=" + FakeDoorHost.ADA + "]"), doors.edits);
    }

    @Test
    void openStateAndProjectionCycle() {
        open();

        driver.chest("door-access-open-state");
        assertEquals(DoorOpenState.CLOSED, doors.openState);
        assertTrue(driver.noticeTexts().isEmpty());
        assertEquals("Toggle 0,0 minecraft:gray_dye door [P] {}", MenuDriver.describe(driver.element("door-access-open-state")));
        assertFalse(((ToggleElement) driver.element("door-access-open-state")).value());

        driver.chest("door-access-projection");
        assertEquals(DoorProjectionState.ON, doors.projection);
        assertEquals(PREFIX + "§aProjection for this door: on.", driver.lastNotice());
        assertEquals(1, ((CycleElement) driver.element("door-access-projection")).index());
    }

    @Test
    void projectionNeedsTheServerSwitch() {
        doors.projectionEnabled = false;
        open();

        driver.chest("door-access-projection");

        assertTrue(doors.edits.isEmpty());
        assertEquals(PREFIX + "§cDoor projection is disabled in wormholes.toml.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertTrue(driver.sessionOpen());
    }

    @Test
    void addingAPlayerResolvesTheName() {
        open();

        driver.chestText("door-access-add", " Zed ");
        driver.chestText("door-access-add", "Olga");
        driver.chestText("door-access-add", "Ben");
        assertTrue(doors.edits.isEmpty());
        driver.chestText("door-access-add", "Eve");

        assertEquals(List.of("Zed", "Olga", "Ben", "Eve"), doors.lookups);
        assertEquals(List.of("§cNo player named Zed could be found.", "§7The door owner always has access.", "§7Ben is already listed for this door.",
            "§aEve was added to this door's list."), driver.noticeTexts());
        assertEquals(List.of("AddPlayer[playerId=" + FakeDoorHost.EVE + "]"), doors.edits);
        assertEquals("ListEntry -1,1 minecraft:black_stained_glass_pane player [PST] " + ENTRY_CLICKS,
            MenuDriver.describe(driver.element("door-access-" + FakeDoorHost.EVE)));
    }

    @Test
    void lookupsRecheckTheEditorBeforeAdding() {
        CompletableFuture<Optional<UUID>> lookup = new CompletableFuture<>();
        doors.pendingLookup = lookup;
        open();

        driver.chestText("door-access-add", "Eve");
        doors.managers.clear();
        lookup.complete(Optional.of(FakeDoorHost.EVE));

        assertTrue(doors.edits.isEmpty());
        assertEquals("§cOnly the door owner or an administrator can edit this door.", driver.lastNotice());
        assertFalse(driver.sessionOpen());
    }

    @Test
    void saveFailuresAreReportedAndLoggedThroughTheHost() {
        doors.failEdits = true;
        open();

        driver.chest("door-access-" + FakeDoorHost.BEN, MenuInteraction.SECONDARY);

        assertEquals("§cThat door access change could not be saved.", driver.lastNotice());
        assertEquals(MenuFeedbackStyle.ERROR, driver.lastNoticeRecord().feedback().style());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertTrue(driver.sessionOpen());
        assertEquals(List.of(FakeDoorHost.DOOR), doors.saveFailureDoors);
        assertEquals(1, doors.saveFailures.size());
        assertInstanceOf(IOException.class, doors.saveFailures.get(0));
    }

    @Test
    void slowSavesHoldTheMenuUntilTheyComplete() {
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        doors.pendingEdit = save;
        open();

        driver.chest("door-access-" + FakeDoorHost.ADA);
        driver.chest("door-access-open-state");
        assertEquals(1, doors.edits.size());

        save.complete(Boolean.TRUE);
        assertEquals("§aAda is now Whitelist for this door.", driver.lastNotice());
        assertEquals("ListEntry -4,1 minecraft:green_stained_glass_pane player [PST] " + ENTRY_CLICKS,
            MenuDriver.describe(driver.element("door-access-" + FakeDoorHost.ADA)));
    }

    @Test
    void openingNeedsTheRecordAndTheEditor() {
        doors.present = false;
        open();
        assertEquals("§cThis dimensional door has no access record.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());
        assertTrue(driver.host.chest.builds.isEmpty());

        doors.present = true;
        doors.managers.clear();
        open();
        assertEquals("§cOnly the door owner or an administrator can edit this door.", driver.lastNotice());
        assertTrue(driver.host.chest.builds.isEmpty());
    }

    @Test
    void viewersWhoCannotManageAreRejectedOnEveryElement() {
        open();
        List<MenuElement> elements = new ArrayList<>(driver.screen().elements());
        for (MenuElement element : elements) {
            for (MenuInteraction interaction : element.interactions()) {
                doors.managers.add(VIEWER);
                open();
                doors.managers.clear();
                MenuValue value = interaction == MenuInteraction.SET_TEXT ? MenuValue.text("Eve") : null;
                driver.chestAction(element.id(), interaction, value, false);
                assertTrue(doors.edits.isEmpty(), element.id() + " " + interaction);
                assertTrue(doors.lookups.isEmpty(), element.id() + " " + interaction);
                assertFalse(driver.sessionOpen(), element.id() + " " + interaction);
            }
        }
    }

    @Test
    void longListsPageAtTwoHundredTwentyFive() {
        doors.players.clear();
        for (int index = 0; index < 226; index++) {
            doors.players.put(new UUID(6L, index), DoorAccessState.NEUTRAL);
        }
        open();

        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
        assertEquals(6, driver.build().chest().rows());
        assertEquals("ListEntry 4,25 minecraft:black_stained_glass_pane player [PST] " + ENTRY_CLICKS,
            MenuDriver.describe(driver.element("door-access-" + new UUID(6L, 224))));
        assertEquals("Label 0,26 minecraft:paper - [] {} hidden", MenuDriver.describe(driver.element("door-access-page")));
        assertEquals("§b§lPage 1/2§r", driver.element("door-access-page").label());
        assertEquals(List.of("§7226 entries"), driver.element("door-access-page").description());
        assertEquals("Button 4,26 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("door-access-next")));
        assertEquals(List.of("§eLeft click"), driver.element("door-access-next").common().chestHints());

        driver.chest("door-access-next");
        MenuScreen second = driver.screen();
        assertEquals(new MenuPaging(1, 2), second.paging());
        assertEquals(List.of("door-access-placard", "door-access-open-state", "door-access-add", "door-access-projection",
            "door-access-" + new UUID(6L, 225), "door-access-previous", "door-access-page"), MenuDriver.ids(second));
        assertEquals("Button -4,2 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("door-access-previous")));
        assertEquals(3, driver.build().chest().rows());
    }

    @Test
    void staleNativeActionsAreRefreshedInsteadOfApplied() {
        driver.hello();
        open();

        driver.nativeActionAt(driver.revision() - 1L, "door-access-open-state", MenuInteraction.PRIMARY, null);

        assertTrue(doors.edits.isEmpty());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, driver.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
    }

    @Test
    void doorChangesRepaintTheList() {
        DoorAccessMenuController controller = new DoorAccessMenuController(doors);
        assertTrue(controller.touches(DoorRoutes.access(FakeDoorHost.DOOR), new MenuScope.Door(FakeDoorHost.DOOR)));
        assertFalse(controller.touches(DoorRoutes.access(FakeDoorHost.DOOR), new MenuScope.Door(UUID.randomUUID())));
        assertFalse(controller.touches(DoorRoutes.access(FakeDoorHost.DOOR), new MenuScope.Portal(FakeDoorHost.DOOR)));
        assertEquals(0, controller.refreshIntervalTicks(DoorRoutes.access(FakeDoorHost.DOOR)));

        open();
        doors.openState = DoorOpenState.CLOSED;
        driver.service.invalidate(new MenuScope.Door(FakeDoorHost.DOOR));
        assertFalse(((ToggleElement) driver.element("door-access-open-state")).value());
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen screen = driver.build().screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Projection", MenuText.stripColors(MenuScreens.element(screen, "door-access-projection").common().wireLabel()));
    }

    private void open() {
        driver.open(DoorRoutes.access(FakeDoorHost.DOOR));
    }
}
