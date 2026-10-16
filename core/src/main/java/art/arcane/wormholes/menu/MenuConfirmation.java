package art.arcane.wormholes.menu;

import java.util.List;

public record MenuConfirmation(List<String> messageLines, String confirmElementId, String cancelElementId) {
    public MenuConfirmation {
        messageLines = MenuText.lines(messageLines, MenuLimits.MAX_CONFIRMATION_LINES, "confirmation message");
        confirmElementId = MenuText.id(confirmElementId, "confirm element id");
        cancelElementId = MenuText.id(cancelElementId, "cancel element id");
        if (confirmElementId.equals(cancelElementId)) {
            throw new IllegalArgumentException("confirm and cancel elements must differ");
        }
    }
}
