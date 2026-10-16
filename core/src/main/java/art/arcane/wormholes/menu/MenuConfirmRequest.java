package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Objects;

public record MenuConfirmRequest(String title, List<String> messageLines, String confirmLabel, String cancelLabel,
                                 MenuHandler onConfirm, ChestConfirmLayout chest) {
    public MenuConfirmRequest {
        title = MenuText.text(title, "confirmation title");
        messageLines = MenuText.lines(messageLines, MenuLimits.MAX_CONFIRMATION_LINES, "confirmation message");
        confirmLabel = MenuText.text(confirmLabel, "confirm label");
        cancelLabel = MenuText.text(cancelLabel, "cancel label");
        Objects.requireNonNull(onConfirm, "onConfirm");
        Objects.requireNonNull(chest, "chest");
    }
}
