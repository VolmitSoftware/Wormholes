package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class ChestClicksTest {
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);
    private static final Map<ChestClick, ChestTarget> PRESS_CLICKS = Map.of(
        ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY),
        ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY),
        ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY));

    @Test
    void pressableKindsMapLeftRightAndShiftLeft() {
        assertEquals(PRESS_CLICKS, ChestClicks.resolve(new ButtonElement(slotted("button", PRESS))));
        assertEquals(PRESS_CLICKS, ChestClicks.resolve(new ToggleElement(slotted("toggle", PRESS), true)));
        assertEquals(PRESS_CLICKS, ChestClicks.resolve(new CycleElement(slotted("cycle", Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY,
            MenuInteraction.TERTIARY, MenuInteraction.SELECT)), List.of("a", "b"), 0)));
        assertEquals(PRESS_CLICKS, ChestClicks.resolve(new ListEntryElement(slotted("entry", PRESS), false)));
    }

    @Test
    void numbersStepAndBigStepOnEveryClick() {
        NumberElement number = new NumberElement(slotted("number", Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
            MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE)), 1.0D, 0.0D, 10.0D, 1.0D, 5.0D, "1");

        assertEquals(Map.of(
            ChestClick.LEFT, ChestTarget.self(MenuInteraction.INCREMENT),
            ChestClick.RIGHT, ChestTarget.self(MenuInteraction.DECREMENT),
            ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.BIG_INCREMENT),
            ChestClick.SHIFT_RIGHT, ChestTarget.self(MenuInteraction.BIG_DECREMENT)), ChestClicks.resolve(number));
    }

    @Test
    void textPromptsOnLeftAndKeepsItsSideActions() {
        TextElement text = new TextElement(slotted("text", Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY)),
            "", 8, "", List.of(), ChestReopen.ALWAYS, null);

        assertEquals(Map.of(
            ChestClick.LEFT, ChestTarget.self(MenuInteraction.SET_TEXT),
            ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY),
            ChestClick.SHIFT_LEFT, ChestTarget.self(MenuInteraction.TERTIARY)), ChestClicks.resolve(text));
    }

    @Test
    void capturesAndLabelsUseTheirNarrowDefaults() {
        assertEquals(Map.of(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY), ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY)),
            ChestClicks.resolve(new ItemCaptureElement(slotted("item", PRESS), List.of())));
        assertEquals(Map.of(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY)),
            ChestClicks.resolve(new LookCaptureElement(slotted("look", PRESS))));
        assertTrue(ChestClicks.resolve(new LabelElement(slotted("label", Set.of()))).isEmpty());
    }

    @Test
    void defaultsKeepOnlyDeclaredInteractions() {
        assertEquals(Map.of(ChestClick.LEFT, ChestTarget.self(MenuInteraction.PRIMARY)),
            ChestClicks.resolve(new ButtonElement(slotted("button", Set.of(MenuInteraction.PRIMARY)))));
        assertEquals(Map.of(ChestClick.LEFT, ChestTarget.self(MenuInteraction.SET_TEXT)),
            ChestClicks.resolve(new TextElement(slotted("text", Set.of(MenuInteraction.SET_TEXT)), "", 8, "", List.of(), ChestReopen.NEVER, null)));
    }

    @Test
    void listedClicksReplaceTheDefault() {
        ButtonElement destroy = new ButtonElement(MenuElementCommon.builder("destroy").interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(0, 3).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY))).build());
        ButtonElement delegating = new ButtonElement(MenuElementCommon.builder("tile").interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(1, 1).withClick(ChestClick.LEFT, ChestTarget.delegate("other", MenuInteraction.PRIMARY))).build());

        assertEquals(Map.of(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)), ChestClicks.resolve(destroy));
        assertEquals(Map.of(ChestClick.LEFT, ChestTarget.delegate("other", MenuInteraction.PRIMARY)), ChestClicks.resolve(delegating));
    }

    @Test
    void elementsWithoutAChestSlotHaveNoClicks() {
        assertTrue(ChestClicks.resolve(new ButtonElement(MenuElementCommon.builder("native").interactions(PRESS).build())).isEmpty());
    }

    private static MenuElementCommon slotted(String id, Set<MenuInteraction> interactions) {
        return MenuElementCommon.builder(id).interactions(interactions).chestSlot(new MenuSlot(0, 1)).build();
    }
}
