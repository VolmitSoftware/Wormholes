package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TextElement(MenuElementCommon common, String value, int maxLength, String placeholder,
                          List<String> chestPrompt, ChestReopen chestReopen, MenuRoute chestCancelRoute) implements MenuElement {
    private static final Set<MenuInteraction> ALLOWED = Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY,
        MenuInteraction.TERTIARY);

    public TextElement {
        Objects.requireNonNull(common, "common").requireInteractions(ALLOWED, "text");
        if (maxLength < 0 || maxLength > MenuLimits.MAX_TEXT_INPUT_LENGTH) {
            throw new IllegalArgumentException("text max length " + maxLength + " is outside 0.." + MenuLimits.MAX_TEXT_INPUT_LENGTH);
        }
        value = MenuText.text(value, "text value");
        if (MenuText.codePoints(value) > maxLength) {
            throw new IllegalArgumentException("text value is longer than " + maxLength);
        }
        placeholder = MenuText.text(placeholder, "placeholder");
        chestPrompt = MenuText.lines(chestPrompt, MenuLimits.MAX_PROMPT_LINES, "chest prompt");
        Objects.requireNonNull(chestReopen, "chestReopen");
        if (chestCancelRoute != null && chestReopen == ChestReopen.NEVER) {
            throw new IllegalArgumentException("a chest cancel route needs chest reopen ALWAYS or ON_CANCEL");
        }
    }

    public boolean fits(String candidate) {
        return MenuText.codePoints(candidate) <= maxLength;
    }
}
