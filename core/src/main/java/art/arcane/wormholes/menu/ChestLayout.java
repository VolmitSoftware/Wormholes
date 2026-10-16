package art.arcane.wormholes.menu;

import java.util.Objects;

public record ChestLayout(String title, int rows, ChestPane pane, MenuRoute escapeRoute, String windowKey) {
    public ChestLayout {
        title = MenuText.text(title, "chest title");
        if (rows < 1 || rows > MenuLimits.MAX_CHEST_ROWS) {
            throw new IllegalArgumentException("chest rows " + rows + " are outside 1.." + MenuLimits.MAX_CHEST_ROWS);
        }
        Objects.requireNonNull(pane, "pane");
        if (windowKey != null) {
            windowKey = MenuText.id(windowKey, "window key");
        }
    }

    public ChestLayout(String title, int rows, ChestPane pane, MenuRoute escapeRoute) {
        this(title, rows, pane, escapeRoute, null);
    }
}
