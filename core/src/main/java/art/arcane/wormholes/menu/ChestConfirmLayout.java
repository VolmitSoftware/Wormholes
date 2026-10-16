package art.arcane.wormholes.menu;

import java.util.Objects;

public record ChestConfirmLayout(
    String title,
    int rows,
    ChestPane pane,
    MenuSlot header,
    String headerItemId,
    MenuSlot confirm,
    String confirmItemId,
    MenuSlot cancel,
    String cancelItemId
) {
    public ChestConfirmLayout {
        title = MenuText.text(title, "chest title");
        if (rows < 1 || rows > MenuLimits.MAX_CHEST_ROWS) {
            throw new IllegalArgumentException("chest rows " + rows + " are outside 1.." + MenuLimits.MAX_CHEST_ROWS);
        }
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(confirm, "confirm");
        Objects.requireNonNull(cancel, "cancel");
        MenuIcon.item(headerItemId);
        MenuIcon.item(confirmItemId);
        MenuIcon.item(cancelItemId);
        if (header.slot() == confirm.slot() || header.slot() == cancel.slot() || confirm.slot() == cancel.slot()) {
            throw new IllegalArgumentException("confirmation slots must differ");
        }
    }

    public static ChestConfirmLayout standard(String title) {
        return new ChestConfirmLayout(title, 3, ChestPane.GRAY, new MenuSlot(0, 0), "minecraft:paper",
            new MenuSlot(-2, 1), "minecraft:lime_dye", new MenuSlot(2, 1), "minecraft:red_dye");
    }
}
