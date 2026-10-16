package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Objects;

public record ItemCaptureElement(MenuElementCommon common, List<String> chestPrompt) implements MenuElement {
    public ItemCaptureElement {
        Objects.requireNonNull(common, "common").requireInteractions(MenuElementRules.PRESSABLE, "item capture");
        chestPrompt = MenuText.lines(chestPrompt, MenuLimits.MAX_PROMPT_LINES, "chest prompt");
    }
}
