package art.arcane.wormholes.menu;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record MenuSlot(int position, int row, Map<ChestClick, ChestTarget> clicks) {
    public static final int MIN_POSITION = -4;
    public static final int MAX_POSITION = 4;

    public MenuSlot {
        if (position < MIN_POSITION || position > MAX_POSITION) {
            throw new IllegalArgumentException("chest position " + position + " is outside " + MIN_POSITION + ".." + MAX_POSITION);
        }
        if (row < 0 || row > MenuLimits.MAX_CHEST_ROW) {
            throw new IllegalArgumentException("chest row " + row + " is outside 0.." + MenuLimits.MAX_CHEST_ROW);
        }
        EnumMap<ChestClick, ChestTarget> copy = new EnumMap<>(ChestClick.class);
        for (Map.Entry<ChestClick, ChestTarget> entry : Objects.requireNonNull(clicks, "clicks").entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "click"), Objects.requireNonNull(entry.getValue(), "target"));
        }
        clicks = Collections.unmodifiableMap(copy);
    }

    public MenuSlot(int position, int row) {
        this(position, row, Map.of());
    }

    public MenuSlot withClick(ChestClick click, ChestTarget target) {
        EnumMap<ChestClick, ChestTarget> next = new EnumMap<>(ChestClick.class);
        next.putAll(clicks);
        next.put(click, target);
        return new MenuSlot(position, row, next);
    }

    public int slot() {
        return row * 9 + 4 + position;
    }
}
