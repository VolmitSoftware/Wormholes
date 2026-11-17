package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.Objects;

public final class MenuClippedText {
    private final Component text;
    private FormattedCharSequence clipped;
    private int clippedWidth = -1;
    private boolean truncated;

    public MenuClippedText(Component text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public Component text() {
        return text;
    }

    public FormattedCharSequence clip(Font font, int width) {
        if (width != clippedWidth) {
            int available = Math.max(0, width);
            truncated = font.width(text) > available;
            clipped = truncated ? ellipsize(font, available) : text.getVisualOrderText();
            clippedWidth = width;
        }
        return clipped;
    }

    public boolean truncated(Font font, int width) {
        clip(font, width);
        return truncated;
    }

    private FormattedCharSequence ellipsize(Font font, int available) {
        FormattedText head = font.substrByWidth(text, Math.max(0, available - font.width(CommonComponents.ELLIPSIS)));
        return Language.getInstance().getVisualOrder(FormattedText.composite(head, CommonComponents.ELLIPSIS));
    }
}
