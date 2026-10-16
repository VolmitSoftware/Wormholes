package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class MenuModelTest {
    @Test
    void combinedGuardsStopAtTheFirstDecisionThatIsNotAllow() {
        MenuDecision redirect = MenuDecision.redirect(MenuRoute.of("test", "test.main"), MenuNotices.text("moved", MenuFeedbackStyle.WARNING,
            NoticeChannel.CHAT));
        MenuDecision deny = MenuDecision.deny(MenuNotices.text("denied", MenuFeedbackStyle.ERROR, NoticeChannel.HUD));
        MenuGuard second = (context, action) -> {
            throw new AssertionError("second guard ran");
        };

        assertSame(redirect, ((MenuGuard) (context, action) -> redirect).and(second).check(null, null));
        assertSame(deny, ((MenuGuard) (context, action) -> deny).and(second).check(null, null));
        assertSame(deny, MenuGuards.none().and((context, action) -> deny).check(null, null));
    }

    @Test
    void elementCollectionsAreDefensivelyCopied() {
        List<String> description = new ArrayList<>(List.of("first", "second"));
        List<String> hints = new ArrayList<>(List.of("Click"));
        Set<MenuInteraction> interactions = EnumSet.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY);
        List<MenuSideAction> sideActions = new ArrayList<>(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "Rotate", MenuIcon.sprite("small/rotate"))));
        MenuElementCommon common = MenuElementCommon.builder("rename")
            .label("Rename")
            .description(description)
            .chestHints(hints)
            .interactions(interactions)
            .sideActions(sideActions)
            .build();
        description.add("third");
        hints.clear();
        interactions.add(MenuInteraction.TERTIARY);
        sideActions.clear();

        assertEquals(List.of("first", "second"), common.description());
        assertEquals(List.of("Click"), common.chestHints());
        assertEquals(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY), common.interactions());
        assertEquals(1, common.sideActions().size());
        assertThrows(UnsupportedOperationException.class, () -> common.description().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> common.interactions().add(MenuInteraction.TERTIARY));
        assertThrows(UnsupportedOperationException.class, () -> common.sideActions().clear());
    }

    @Test
    void cycleOptionsScreenElementsAndChestClicksAreCopied() {
        List<String> options = new ArrayList<>(List.of("On", "Off"));
        CycleElement cycle = new CycleElement(common("cycle", MenuInteraction.SELECT), options, 1);
        options.clear();
        List<MenuElement> elements = new ArrayList<>(List.of(cycle));
        MenuScreen screen = MenuScreen.builder("s").elements(elements).build();
        elements.clear();
        Map<ChestClick, ChestTarget> clicks = new EnumMap<>(ChestClick.class);
        clicks.put(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY));
        MenuSlot slot = new MenuSlot(0, 0, clicks);
        clicks.put(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY));

        assertEquals(List.of("On", "Off"), cycle.options());
        assertEquals(1, screen.elements().size());
        assertEquals(1, slot.clicks().size());
        assertThrows(UnsupportedOperationException.class, () -> screen.elements().clear());
    }

    @Test
    void elementsAndScreensCompareByContent() {
        MenuScreen first = screen(7L, new ToggleElement(common("toggle", MenuInteraction.PRIMARY), true));
        MenuScreen second = screen(7L, new ToggleElement(common("toggle", MenuInteraction.PRIMARY), true));
        MenuScreen flipped = screen(7L, new ToggleElement(common("toggle", MenuInteraction.PRIMARY), false));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, flipped);
    }

    @Test
    void sameContentIgnoresRevisionAndFeedbackOnly() {
        MenuScreen first = screen(1L, new ButtonElement(common("button", MenuInteraction.PRIMARY)));
        MenuScreen later = screen(9L, new ButtonElement(common("button", MenuInteraction.PRIMARY))).withFeedback(MenuFeedback.info("Saved"));
        MenuScreen changed = screen(9L, new ButtonElement(common("button", MenuInteraction.SECONDARY)));

        assertNotEquals(first, later);
        assertTrue(first.sameContent(later));
        assertFalse(first.sameContent(changed));
        assertFalse(first.sameContent(first.toBuilder().title("Other").build()));
    }

    @Test
    void elementsDelegateTheirCommonFields() {
        ButtonElement button = new ButtonElement(MenuElementCommon.builder("go")
            .label("Go")
            .style(MenuStyle.DESTRUCTIVE)
            .enabled(false)
            .chestSlot(new MenuSlot(-2, 1))
            .icon(MenuIcon.item("minecraft:gunpowder"))
            .nativeIcon(MenuIcon.sprite("delete"))
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .build());

        assertEquals("go", button.id());
        assertEquals("Go", button.label());
        assertEquals(MenuStyle.DESTRUCTIVE, button.style());
        assertFalse(button.enabled());
        assertEquals(11, button.chestSlot().slot());
        assertEquals(MenuIcon.sprite("delete"), button.common().wireIcon());
        assertTrue(button.accepts(MenuInteraction.PRIMARY));
        assertFalse(button.accepts(MenuInteraction.SECONDARY));
    }

    @Test
    void interactionsAreLimitedPerKind() {
        assertThrows(IllegalArgumentException.class, () -> new ButtonElement(common("b", MenuInteraction.SET_TEXT)));
        assertThrows(IllegalArgumentException.class, () -> new LabelElement(common("l", MenuInteraction.PRIMARY)));
        assertThrows(IllegalArgumentException.class, () -> new ToggleElement(common("t", MenuInteraction.SELECT), true));
        assertThrows(IllegalArgumentException.class, () -> new NumberElement(common("n", MenuInteraction.PRIMARY), 1.0D, 0.0D, 2.0D, 1.0D, 1.0D, "1"));
        assertThrows(IllegalArgumentException.class, () -> new TextElement(common("x", MenuInteraction.PRIMARY), "", 4, "", List.of(), ChestReopen.ALWAYS, null));
        assertThrows(IllegalArgumentException.class, () -> new CycleElement(common("c", MenuInteraction.SET_VALUE), List.of("a"), 0));
        assertThrows(IllegalArgumentException.class, () -> new ListEntryElement(common("r", MenuInteraction.INCREMENT), false));
        assertThrows(IllegalArgumentException.class, () -> new ItemCaptureElement(common("i", MenuInteraction.SET_TEXT), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LookCaptureElement(common("k", MenuInteraction.SELECT)));
        assertDoesNotThrow(() -> new CycleElement(common("c", MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.SELECT), List.of("a"), 0));
        assertDoesNotThrow(() -> new TextElement(common("x", MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY), "", 0, "", List.of(),
            ChestReopen.ALWAYS, null));
        assertDoesNotThrow(() -> new NumberElement(common("n", MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.BIG_INCREMENT,
            MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE), 1.0D, 0.0D, 2.0D, 1.0D, 1.0D, "1"));
    }

    @Test
    void sideActionsFollowTheirRules() {
        MenuIcon icon = MenuIcon.sprite("small/rotate");
        assertThrows(IllegalArgumentException.class, () -> new MenuSideAction(MenuInteraction.SET_TEXT, "x", icon));
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("a")
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "x", icon)))
            .build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("a")
            .interactions(Set.of(MenuInteraction.SECONDARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "x", icon), new MenuSideAction(MenuInteraction.SECONDARY, "y", icon)))
            .build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("a")
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY))
            .sideActions(List.of(new MenuSideAction(MenuInteraction.PRIMARY, "a", icon), new MenuSideAction(MenuInteraction.SECONDARY, "b", icon),
                new MenuSideAction(MenuInteraction.TERTIARY, "c", icon), new MenuSideAction(MenuInteraction.TERTIARY, "d", icon)))
            .build());
    }

    @Test
    void valuesOutsideTheirBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CycleElement(common("c"), List.of("a"), 1));
        assertThrows(IllegalArgumentException.class, () -> new CycleElement(common("c"), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new NumberElement(common("n"), 5.0D, 0.0D, 4.0D, 1.0D, 2.0D, "5"));
        assertThrows(IllegalArgumentException.class, () -> new NumberElement(common("n"), 1.0D, 0.0D, 4.0D, 0.0D, 2.0D, "1"));
        assertThrows(IllegalArgumentException.class, () -> new NumberElement(common("n"), Double.NaN, 0.0D, 4.0D, 1.0D, 2.0D, "1"));
        assertThrows(IllegalArgumentException.class, () -> new TextElement(common("t"), "toolong", 3, "", List.of(), ChestReopen.ALWAYS, null));
        assertThrows(IllegalArgumentException.class, () -> new TextElement(common("t"), "", MenuLimits.MAX_TEXT_INPUT_LENGTH + 1, "", List.of(), ChestReopen.ALWAYS,
            null));
        assertThrows(IllegalArgumentException.class, () -> new MenuSlot(5, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuSlot(0, MenuLimits.MAX_CHEST_ROW + 1));
        assertDoesNotThrow(() -> new MenuSlot(0, MenuLimits.MAX_CHEST_ROW));
        assertThrows(IllegalArgumentException.class, () -> new MenuPaging(2, 2));
        assertThrows(IllegalArgumentException.class, () -> MenuIcon.item("Not An Id"));
        assertThrows(IllegalArgumentException.class, () -> MenuIcon.item("minecraft:stone", 0, false));
        assertThrows(IllegalArgumentException.class, () -> MenuIcon.item("minecraft:stone", 100, false));
        assertThrows(IllegalArgumentException.class, () -> MenuIcon.sprite(""));
        assertThrows(IllegalArgumentException.class, () -> MenuIcon.sprite("wormholes:icon/portal"));
        assertThrows(IllegalArgumentException.class, () -> new ChestLayout("t", 7, ChestPane.GRAY, null));
        assertThrows(IllegalArgumentException.class, () -> new MenuCapability(0));
    }

    @Test
    void chestWindowKeysDefaultToTheScreenId() {
        MenuScreen screen = MenuScreen.builder("rtp.destination").build();
        MenuBindings bindings = MenuBindings.builder().build();

        assertEquals("rtp.destination", new MenuBuild(screen, new ChestLayout("t", 6, ChestPane.BLACK, null), bindings).windowKey());
        assertEquals("rtp", new MenuBuild(screen, new ChestLayout("t", 6, ChestPane.BLACK, null, "rtp"), bindings).windowKey());
        assertEquals("rtp", new MenuBuild(screen, new ChestLayout("t", 6, ChestPane.BLACK, null, "rtp"), bindings)
            .withScreen(MenuScreen.builder("rtp.biome").build()).windowKey());
        assertThrows(IllegalArgumentException.class, () -> new ChestLayout("t", 6, ChestPane.BLACK, null, " "));
    }

    @Test
    void itemCaptureChestPromptsAreCopiedAndLimited() {
        List<String> prompt = new ArrayList<>(List.of("Hold the item, then press Drop."));
        ItemCaptureElement capture = new ItemCaptureElement(common("i", MenuInteraction.PRIMARY), prompt);
        prompt.clear();
        List<String> tooManyLines = new ArrayList<>();
        for (int index = 0; index <= MenuLimits.MAX_PROMPT_LINES; index++) {
            tooManyLines.add("line");
        }

        assertEquals(List.of("Hold the item, then press Drop."), capture.chestPrompt());
        assertThrows(UnsupportedOperationException.class, () -> capture.chestPrompt().add("x"));
        assertThrows(NullPointerException.class, () -> new ItemCaptureElement(common("i", MenuInteraction.PRIMARY), null));
        assertThrows(IllegalArgumentException.class, () -> new ItemCaptureElement(common("i", MenuInteraction.PRIMARY), tooManyLines));
        assertThrows(IllegalArgumentException.class, () -> new ItemCaptureElement(common("i", MenuInteraction.PRIMARY),
            List.of("x".repeat(MenuLimits.MAX_STRING_BYTES + 1))));
        assertDoesNotThrow(() -> new ItemCaptureElement(common("i", MenuInteraction.PRIMARY), tooManyLines.subList(1, tooManyLines.size())));
    }

    @Test
    void textChestReopenHintsAreValidated() {
        MenuRoute profile = MenuRoute.of("rules", "rules.profile");

        assertThrows(NullPointerException.class, () -> new TextElement(common("t", MenuInteraction.SET_TEXT), "", 4, "", List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new TextElement(common("t", MenuInteraction.SET_TEXT), "", 4, "", List.of(),
            ChestReopen.NEVER, profile));
        assertEquals(profile, new TextElement(common("t", MenuInteraction.SET_TEXT), "", 4, "", List.of(), ChestReopen.ALWAYS, profile)
            .chestCancelRoute());
        assertEquals(ChestReopen.ON_CANCEL, new TextElement(common("t", MenuInteraction.SET_TEXT), "", 4, "", List.of(), ChestReopen.ON_CANCEL,
            profile).chestReopen());
        assertDoesNotThrow(() -> new TextElement(common("t", MenuInteraction.SET_TEXT), "", 4, "", List.of(), ChestReopen.NEVER, null));
    }

    @Test
    void textLimitsAreEnforced() {
        String oversized = "x".repeat(MenuLimits.MAX_STRING_BYTES + 1);
        List<String> tooManyLines = new ArrayList<>();
        for (int index = 0; index <= MenuLimits.MAX_DESCRIPTION_LINES; index++) {
            tooManyLines.add("line");
        }

        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("x").label(oversized).build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("x").description(tooManyLines).build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("x").chestHints(tooManyLines).build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("").build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("x").label("\uD800").build());
        assertEquals(1024, MenuElementCommon.builder("x").label("é".repeat(512)).build().label().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void nativeDescriptionsSurviveCopiesAndFollowTheDescriptionLimits() {
        List<String> full = new ArrayList<>(MenuLimits.MAX_DESCRIPTION_LINES);
        for (int index = 0; index < MenuLimits.MAX_DESCRIPTION_LINES; index++) {
            full.add("line " + index);
        }
        List<String> tooMany = new ArrayList<>(full);
        tooMany.add("one more");
        MenuElementCommon common = MenuElementCommon.builder("rule").label("Rule").description(List.of("chest lore"))
            .nativeDescription(List.of("summary")).build();

        assertEquals(List.of("summary"), common.toBuilder().build().nativeDescription());
        assertEquals(common, common.toBuilder().build());
        assertEquals(List.of("summary"), common.wireDescription());
        assertEquals(List.of("chest lore"), common.toBuilder().nativeDescription(null).build().wireDescription());
        assertEquals("Rule", common.wireLabel());
        MenuElementCommon relabelled = common.toBuilder().label("§x§8§c§7§a§4§5Rule").nativeLabel("Rule").build();
        assertEquals("Rule", relabelled.wireLabel());
        assertEquals("§x§8§c§7§a§4§5Rule", relabelled.label());
        assertEquals(relabelled, relabelled.toBuilder().build());
        assertEquals(full, MenuElementCommon.builder("x").nativeDescription(full).build().nativeDescription());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("x").nativeDescription(tooMany).build());
        assertThrows(IllegalArgumentException.class,
            () -> MenuElementCommon.builder("x").nativeDescription(List.of("x".repeat(MenuLimits.MAX_STRING_BYTES + 1))).build());
    }

    @Test
    void settingNamesDropTheMarkedValueWithItsSeparatorAndTrailingColours() {
        String mark = MenuText.VALUE_MARK;

        assertEquals("§e§lVisibility", MenuText.settingName("§e§lVisibility: " + mark + "§r"));
        assertEquals("§b§lRender Mode", MenuText.settingName("§b§lRender Mode §f§l" + mark + "§r"));
        assertEquals("§6§lAccess", MenuText.settingName("§6§lAccess " + mark + "§r"));
        assertEquals("§bAcoustics", MenuText.settingName("§bAcoustics：§f" + mark));
        assertEquals("§b§lProjection§r", MenuText.settingName("§b§l" + mark + " : Projection§r"));
        assertEquals("§b§lPublic directory§r", MenuText.settingName("§b§lPublic directory§r"));
    }

    @Test
    void screensRejectDuplicateIdsSlotsHiddenOrphansAndDanglingConfirmations() {
        ButtonElement first = new ButtonElement(MenuElementCommon.builder("a").chestSlot(new MenuSlot(0, 0)).build());
        ButtonElement sameId = new ButtonElement(MenuElementCommon.builder("a").build());
        ButtonElement sameSlot = new ButtonElement(MenuElementCommon.builder("b").chestSlot(new MenuSlot(0, 0)).build());

        assertThrows(IllegalArgumentException.class, () -> MenuScreen.builder("s").elements(List.of(first, sameId)).build());
        assertThrows(IllegalArgumentException.class, () -> MenuScreen.builder("s").elements(List.of(first, sameSlot)).build());
        assertThrows(IllegalArgumentException.class, () -> MenuScreen.builder("s").elements(List.of(first))
            .confirmation(new MenuConfirmation(List.of("Sure?"), "a", "missing")).build());
        assertThrows(IllegalArgumentException.class, () -> MenuElementCommon.builder("hidden").nativeHidden(true).build());
        List<MenuElement> tooMany = new ArrayList<>();
        for (int index = 0; index <= MenuLimits.MAX_ELEMENTS; index++) {
            tooMany.add(new ButtonElement(common("e" + index)));
        }
        assertThrows(IllegalArgumentException.class, () -> MenuScreen.builder("s").elements(tooMany).build());
    }

    @Test
    void screensLookUpElementsById() {
        ButtonElement button = new ButtonElement(common("button"));
        MenuScreen screen = screen(1L, button);

        assertEquals(button, screen.element("button").orElseThrow());
        assertTrue(screen.element("missing").isEmpty());
    }

    @Test
    void routesCarryParametersAndCompareScreens() {
        MenuRoute route = MenuRoute.of("portal", "portal.home").with("portal", "00000000-0000-0000-0000-000000000009");

        assertEquals("00000000-0000-0000-0000-000000000009", route.uuid("portal").toString());
        assertTrue(route.sameScreen(MenuRoute.of("portal", "portal.home").with("portal", "00000000-0000-0000-0000-000000000009")));
        assertFalse(route.sameScreen(MenuRoute.of("portal", "portal.mode").with("portal", "00000000-0000-0000-0000-000000000009")));
        assertThrows(NullPointerException.class, () -> MenuRoute.of("portal", "portal.home").uuid("portal"));
    }

    @Test
    void menuStateIsTyped() {
        MenuState state = new MenuState();
        MenuStateKey<Integer> sort = new MenuStateKey<>("sort", Integer.class);

        assertEquals(3, state.getOrDefault(sort, 3));
        state.put(sort, 5);
        assertEquals(5, state.get(sort).orElseThrow());
        state.remove(sort);
        assertTrue(state.get(sort).isEmpty());
    }

    private static MenuElementCommon common(String id, MenuInteraction... interactions) {
        return MenuElementCommon.builder(id).label(id).interactions(Set.of(interactions)).build();
    }

    private static MenuScreen screen(long revision, MenuElement element) {
        return MenuScreen.builder("home")
            .title("Home")
            .revision(revision)
            .elements(List.of(element))
            .build();
    }
}
