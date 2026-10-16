package art.arcane.wormholes.menu;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class ChestClicks {
    private static final Map<ChestClick, MenuInteraction> NUMBER = Map.of(
        ChestClick.LEFT, MenuInteraction.INCREMENT,
        ChestClick.RIGHT, MenuInteraction.DECREMENT,
        ChestClick.SHIFT_LEFT, MenuInteraction.BIG_INCREMENT,
        ChestClick.SHIFT_RIGHT, MenuInteraction.BIG_DECREMENT);
    private static final Map<ChestClick, MenuInteraction> TEXT = Map.of(
        ChestClick.LEFT, MenuInteraction.SET_TEXT,
        ChestClick.RIGHT, MenuInteraction.SECONDARY,
        ChestClick.SHIFT_LEFT, MenuInteraction.TERTIARY);
    private static final Map<ChestClick, MenuInteraction> ITEM = Map.of(
        ChestClick.LEFT, MenuInteraction.PRIMARY,
        ChestClick.RIGHT, MenuInteraction.SECONDARY);
    private static final Map<ChestClick, MenuInteraction> LOOK = Map.of(ChestClick.LEFT, MenuInteraction.PRIMARY);
    private static final Map<ChestClick, MenuInteraction> PRESS = Map.of(
        ChestClick.LEFT, MenuInteraction.PRIMARY,
        ChestClick.RIGHT, MenuInteraction.SECONDARY,
        ChestClick.SHIFT_LEFT, MenuInteraction.TERTIARY);

    private ChestClicks() {
    }

    public static Map<ChestClick, ChestTarget> resolve(MenuElement element) {
        MenuSlot slot = element.chestSlot();
        if (slot == null) {
            return Map.of();
        }
        if (!slot.clicks().isEmpty()) {
            return slot.clicks();
        }
        EnumMap<ChestClick, ChestTarget> clicks = new EnumMap<>(ChestClick.class);
        for (Map.Entry<ChestClick, MenuInteraction> entry : defaults(element).entrySet()) {
            if (element.accepts(entry.getValue())) {
                clicks.put(entry.getKey(), ChestTarget.self(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(clicks);
    }

    private static Map<ChestClick, MenuInteraction> defaults(MenuElement element) {
        return switch (element) {
            case NumberElement _ -> NUMBER;
            case TextElement _ -> TEXT;
            case ItemCaptureElement _ -> ITEM;
            case LookCaptureElement _ -> LOOK;
            case LabelElement _ -> Map.of();
            case ButtonElement _, ToggleElement _, CycleElement _, ListEntryElement _ -> PRESS;
        };
    }
}
