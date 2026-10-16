package art.arcane.wormholes.menu.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuDriver;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class AccessMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String ROLE_CLICKS = "{LEFT=P, RIGHT=S, SHIFT_LEFT=T, MIDDLE=T} T:small/delete";

    private FakeAccessHost access;
    private MenuDriver driver;

    @BeforeEach
    void setUp() {
        access = new FakeAccessHost();
        access.managers.add(VIEWER);
        driver = new MenuDriver(VIEWER, "Ada", List.of(new AccessMenuController(access)));
    }

    @Test
    void rolesMatchTheChestWindowAndNativeTables() {
        open();
        MenuBuild build = driver.build();

        assertEquals(new ChestLayout("§0§lAlpha", 2, ChestPane.GRAY, null), build.chest());
        assertEquals(AccessRoutes.ROLES, build.screen().screenId());
        assertEquals("Access", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertNull(build.screen().paging());
        assertEquals(MenuDriver.ordered(
            "access-placard", "Label -4,0 minecraft:name_tag access [] {}",
            "access-add", "Text -1,0 minecraft:writable_book player [X] {}",
            "access-key", "Text 0,0 minecraft:tripwire_hook access [X] {}",
            "access-groups", "Text 1,0 minecraft:book access [SX] {LEFT=X, RIGHT=S} S:small/delete",
            "access-listed", "Toggle 2,0 minecraft:ender_pearl atlas [P] {}",
            "access-role-" + FakeAccessHost.ADA, "Cycle -4,1 minecraft:iron_helmet player [PST] " + ROLE_CLICKS,
            "access-role-" + FakeAccessHost.BEN, "Cycle -3,1 minecraft:lime_stained_glass_pane player [PST] " + ROLE_CLICKS,
            "access-role-" + FakeAccessHost.CY, "Cycle -2,1 minecraft:red_stained_glass_pane player [PST] " + ROLE_CLICKS),
            MenuDriver.layout(build.screen()));
        assertEquals("§b§lAccess: Alpha§r", driver.element("access-placard").label());
        assertEquals(List.of("§7Owner: §fOlga", "§7Roles: §f3", "§7Key: §falpha-key"), driver.element("access-placard").description());
        CycleElement ada = (CycleElement) driver.element("access-role-" + FakeAccessHost.ADA);
        assertEquals("§f§lAda§r", ada.label());
        assertEquals(List.of("§7Role: §fCo-owner"), ada.description());
        assertEquals(List.of("§7Left or right click to change the role.", "§7Shift-left click to remove it."), ada.common().chestHints());
        assertEquals(List.of("Owner", "Co-owner", "Trusted", "Denied"), ada.options());
        assertEquals(1, ada.index());
        assertEquals("§f§lUnknown (c0ffee00)§r", driver.element("access-role-" + FakeAccessHost.CY).label());
        assertEquals(List.of("§72 permission nodes grant access."), driver.element("access-groups").description());
        assertEquals(List.of("§7Left click to add one, right click to clear."), driver.element("access-groups").common().chestHints());
        assertEquals(List.of("§7Hidden"), driver.element("access-listed").description());
        assertFalse(((ToggleElement) driver.element("access-listed")).value());
        TextElement add = (TextElement) driver.element("access-add");
        assertEquals(List.of("§7Type a player name in chat, or cancel to stop."), add.chestPrompt());
        assertEquals(ChestReopen.ALWAYS, add.chestReopen());
        assertEquals("Player name", add.placeholder());
        TextElement key = (TextElement) driver.element("access-key");
        assertEquals("alpha-key", key.value());
        assertEquals(List.of("§7Type the new permission key in chat, or cancel to stop."), key.chestPrompt());
        assertEquals(ChestReopen.ALWAYS, key.chestReopen());
        assertEquals(ChestReopen.ALWAYS, ((TextElement) driver.element("access-groups")).chestReopen());
        MenuDriver.assertBound(build);
    }

    @Test
    void emptyRostersAreOneRowTall() {
        access.roles.clear();
        open();

        assertEquals(1, driver.build().chest().rows());
        assertEquals("§7Roles: §f0", driver.element("access-placard").description().get(1));
    }

    @Test
    void rolesCycleForwardAndBackLikeToday() {
        open();

        driver.chest("access-role-" + FakeAccessHost.ADA);
        assertEquals(PortalRole.USER, access.roles.get(FakeAccessHost.ADA));
        assertEquals("§aAda is now Trusted on Alpha.", driver.lastNotice());
        assertEquals(NoticeChannel.CHAT, driver.lastNoticeRecord().chestChannel());

        driver.chest("access-role-" + FakeAccessHost.BEN, MenuInteraction.SECONDARY);
        assertEquals(PortalRole.CO_OWNER, access.roles.get(FakeAccessHost.BEN));
        driver.chest("access-role-" + FakeAccessHost.BEN, MenuInteraction.SECONDARY);
        assertEquals(PortalRole.OWNER, access.roles.get(FakeAccessHost.BEN));
        assertEquals("§aBen is now Owner on Alpha.", driver.lastNotice());

        driver.chest("access-role-" + FakeAccessHost.CY);
        assertEquals(PortalRole.OWNER, access.roles.get(FakeAccessHost.CY));
        assertEquals(0, ((CycleElement) driver.element("access-role-" + FakeAccessHost.CY)).index());
        assertEquals("Cycle -2,1 minecraft:golden_helmet player [PST] " + ROLE_CLICKS,
            MenuDriver.describe(driver.element("access-role-" + FakeAccessHost.CY)));
    }

    @Test
    void removingARoleDropsTheEntry() {
        open();

        driver.chest("access-role-" + FakeAccessHost.ADA, MenuInteraction.TERTIARY);

        assertEquals(List.of("RemoveRole[playerId=" + FakeAccessHost.ADA + "]"), access.edits);
        assertEquals("§aAda no longer has a role on Alpha.", driver.lastNotice());
        assertTrue(driver.screen().element("access-role-" + FakeAccessHost.ADA).isEmpty());
        assertEquals("Cycle -4,1 minecraft:lime_stained_glass_pane player [PST] " + ROLE_CLICKS,
            MenuDriver.describe(driver.element("access-role-" + FakeAccessHost.BEN)));
    }

    @Test
    void addingAPlayerResolvesTheNameFirst() {
        open();

        driver.chestText("access-add", " Zed ");
        driver.chestText("access-add", "Olga");
        driver.chestText("access-add", "Ada");
        assertTrue(access.edits.isEmpty());
        driver.chestText("access-add", "Eve");

        assertEquals(List.of("Zed", "Olga", "Ada", "Eve"), access.lookups);
        assertEquals(List.of("§cNo player named Zed.", "§7The owner always has full access.", "§aAda is now Co-owner on Alpha.",
            "§aEve is now Trusted on Alpha."), driver.noticeTexts());
        assertEquals(List.of("SetRole[playerId=" + FakeAccessHost.EVE + ", role=USER]"), access.edits);
        assertEquals("Cycle -1,1 minecraft:lime_stained_glass_pane player [PST] " + ROLE_CLICKS,
            MenuDriver.describe(driver.element("access-role-" + FakeAccessHost.EVE)));
    }

    @Test
    void slowLookupsHoldTheMenuUntilTheyComplete() {
        CompletableFuture<Optional<UUID>> lookup = new CompletableFuture<>();
        access.pendingLookup = lookup;
        open();

        driver.chestText("access-add", "Eve");
        driver.chest("access-listed");
        assertTrue(access.edits.isEmpty());

        lookup.complete(Optional.of(FakeAccessHost.EVE));
        assertEquals(List.of("SetRole[playerId=" + FakeAccessHost.EVE + ", role=USER]"), access.edits);
        assertEquals("§aEve is now Trusted on Alpha.", driver.lastNotice());
        assertTrue(driver.screen().element("access-role-" + FakeAccessHost.EVE).isPresent());
    }

    @Test
    void lookupsRecheckTheEditorBeforeAdding() {
        CompletableFuture<Optional<UUID>> lookup = new CompletableFuture<>();
        access.pendingLookup = lookup;
        open();

        driver.chestText("access-add", "Eve");
        access.managers.clear();
        lookup.complete(Optional.of(FakeAccessHost.EVE));

        assertTrue(access.edits.isEmpty());
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", driver.lastNotice());
        assertFalse(driver.sessionOpen());
    }

    @Test
    void permissionKeysReportEveryOutcome() {
        open();

        access.keyOutcome = AccessKeyOutcome.INVALID;
        driver.chestText("access-key", "Bad Key");
        access.keyOutcome = AccessKeyOutcome.TAKEN;
        driver.chestText("access-key", "taken-key");
        access.keyOutcome = AccessKeyOutcome.UNCHANGED;
        driver.chestText("access-key", "alpha-key");
        access.keyOutcome = AccessKeyOutcome.SET;
        driver.chestText("access-key", " new-key ");

        assertEquals(List.of("§cPermission keys use a-z, 0-9, dot, dash, and underscore, up to 64 characters.",
            "§cPermission key taken-key is already used.", "§aPermission key for Alpha is now alpha-key.",
            "§aPermission key for Alpha is now new-key."), driver.noticeTexts());
        assertEquals("key:new-key", access.edits.get(3));
        assertEquals("new-key", ((TextElement) driver.element("access-key")).value());
    }

    @Test
    void groupsAndTheDirectoryFlag() {
        open();

        driver.chestText("access-groups", "  ");
        assertTrue(access.edits.isEmpty());
        driver.chestText("access-groups", "group.vip");
        driver.chestText("access-groups", "group.vip");
        driver.chest("access-groups", MenuInteraction.SECONDARY);
        driver.chest("access-groups", MenuInteraction.SECONDARY);
        driver.chest("access-listed");

        assertEquals(List.of("§aAlpha now grants access to group.vip.", "§aAlpha no longer grants access by group.",
            "§aAlpha is now Listed in the public directory."), driver.noticeTexts());
        assertEquals(List.of("AddGroup[node=group.vip]", "AddGroup[node=group.vip]", "ClearGroups[]", "ClearGroups[]", "Listed[listed=true]"),
            access.edits);
        assertTrue(((ToggleElement) driver.element("access-listed")).value());
        assertEquals("Toggle 2,0 minecraft:ender_eye atlas [P] {}", MenuDriver.describe(driver.element("access-listed")));
    }

    @Test
    void refusedEditsStaySilent() {
        access.editsApply = false;
        open();

        driver.chest("access-role-" + FakeAccessHost.ADA);
        driver.chest("access-role-" + FakeAccessHost.ADA, MenuInteraction.TERTIARY);
        driver.chest("access-listed");

        assertEquals(3, access.edits.size());
        assertTrue(driver.noticeTexts().isEmpty());
    }

    @Test
    void longRostersPageAtTwoHundredTwentyFive() {
        access.roles.clear();
        for (int index = 0; index < 230; index++) {
            access.roles.put(new UUID(5L, index), PortalRole.USER);
        }
        open();

        MenuScreen first = driver.screen();
        assertEquals(new MenuPaging(0, 2), first.paging());
        assertEquals(6, driver.build().chest().rows());
        assertEquals("Cycle 4,25 minecraft:lime_stained_glass_pane player [PST] " + ROLE_CLICKS,
            MenuDriver.describe(driver.element("access-role-" + new UUID(5L, 224))));
        assertEquals("Label 0,26 minecraft:paper - [] {} hidden", MenuDriver.describe(driver.element("access-page")));
        assertEquals("Button 4,26 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("access-next")));
        assertTrue(first.element("access-previous").isEmpty());
        assertEquals("§b§lPage 1/2§r", driver.element("access-page").label());
        assertEquals(List.of("§7230 entries"), driver.element("access-page").description());
        assertEquals(List.of("§7Show the next destinations."), driver.element("access-next").description());
        assertEquals(List.of("§eLeft click"), driver.element("access-next").common().chestHints());

        driver.chest("access-next");
        MenuScreen second = driver.screen();
        assertEquals(new MenuPaging(1, 2), second.paging());
        assertEquals(3, driver.build().chest().rows());
        assertEquals("Cycle -4,1 minecraft:lime_stained_glass_pane player [PST] " + ROLE_CLICKS,
            MenuDriver.describe(driver.element("access-role-" + new UUID(5L, 225))));
        assertEquals("Button -4,2 minecraft:arrow - [P] {} hidden", MenuDriver.describe(driver.element("access-previous")));
        assertEquals("Label 0,2 minecraft:paper - [] {} hidden", MenuDriver.describe(driver.element("access-page")));
        assertTrue(second.element("access-next").isEmpty());
        assertEquals("§7Roles: §f230", driver.element("access-placard").description().get(1));

        driver.chest("access-previous");
        assertEquals(new MenuPaging(0, 2), driver.screen().paging());
    }

    @Test
    void rostersBelowAPageKeepTodaysHeight() {
        access.roles.clear();
        for (int index = 0; index < 50; index++) {
            access.roles.put(new UUID(5L, index), PortalRole.DENIED);
        }
        open();

        assertNull(driver.screen().paging());
        assertEquals(6, driver.build().chest().rows());
        assertTrue(driver.screen().element("access-page").isEmpty());
        assertEquals("Cycle -4,6 minecraft:red_stained_glass_pane player [PST] " + ROLE_CLICKS,
            MenuDriver.describe(driver.element("access-role-" + new UUID(5L, 45))));
    }

    @Test
    void viewersWhoCannotManageAreRejectedOnEveryElement() {
        access.managers.clear();
        open();
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", driver.lastNotice());
        assertFalse(driver.sessionOpen());

        access.managers.add(VIEWER);
        open();
        List<MenuElement> elements = new ArrayList<>(driver.screen().elements());
        for (MenuElement element : elements) {
            for (MenuInteraction interaction : element.interactions()) {
                access.managers.add(VIEWER);
                open();
                access.managers.clear();
                MenuValue value = interaction == MenuInteraction.SET_TEXT ? MenuValue.text("Eve") : null;
                driver.chestAction(element.id(), interaction, value, false);
                assertTrue(access.edits.isEmpty(), element.id() + " " + interaction);
                assertTrue(access.lookups.isEmpty(), element.id() + " " + interaction);
                assertFalse(driver.sessionOpen(), element.id() + " " + interaction);
            }
        }
    }

    @Test
    void staleNativeActionsAreRefreshedInsteadOfApplied() {
        driver.hello();
        open();

        driver.nativeActionAt(driver.revision() - 1L, "access-listed", MenuInteraction.PRIMARY, null);

        assertTrue(access.edits.isEmpty());
        MenuServerMessage.Update update = assertInstanceOf(MenuServerMessage.Update.class, driver.lastMessage());
        assertEquals("The menu changed and was refreshed.", update.screen().feedback().text());
    }

    @Test
    void nativeRolesCycleThroughTheSameHandlers() {
        driver.hello();
        open();

        driver.nativeAction("access-role-" + FakeAccessHost.ADA, MenuInteraction.SECONDARY, null);
        assertEquals(PortalRole.OWNER, access.roles.get(FakeAccessHost.ADA));
        driver.nativeAction("access-add", MenuInteraction.SET_TEXT, MenuValue.text("Eve"));
        assertTrue(driver.nativeScreen().element("access-role-" + FakeAccessHost.EVE).isPresent());
        assertEquals(List.of("Remove"), ((CycleElement) driver.nativeScreen().element("access-role-" + FakeAccessHost.ADA).orElseThrow())
            .common().sideActions().stream().map(action -> action.label()).toList());
    }

    @Test
    void portalChangesRepaintTheRoster() {
        AccessMenuController controller = new AccessMenuController(access);
        assertEquals(0, controller.refreshIntervalTicks(AccessRoutes.roles(FakeAccessHost.PORTAL)));
        assertTrue(controller.touches(AccessRoutes.roles(FakeAccessHost.PORTAL), new MenuScope.Portal(FakeAccessHost.PORTAL)));
        assertFalse(controller.touches(AccessRoutes.roles(FakeAccessHost.PORTAL), new MenuScope.Portal(UUID.randomUUID())));

        open();
        access.roles.put(FakeAccessHost.EVE, PortalRole.USER);
        driver.service.invalidate(new MenuScope.Portal(FakeAccessHost.PORTAL));
        assertTrue(driver.screen().element("access-role-" + FakeAccessHost.EVE).isPresent());
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();

        MenuScreens.assertSettingLabels(driver.build().screen());
    }

    private void open() {
        driver.open(AccessRoutes.roles(FakeAccessHost.PORTAL));
    }
}
