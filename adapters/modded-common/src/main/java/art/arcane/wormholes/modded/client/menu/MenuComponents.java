package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.modded.MinecraftLegacyText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.function.ToIntFunction;

public final class MenuComponents {
    public static final String ELLIPSIS = "...";

    private MenuComponents() {
    }

    public static Component component(String legacy) {
        return MinecraftLegacyText.component(legacy);
    }

    public static Component face(Component label) {
        return Component.literal(label.getString());
    }

    public static String plain(String legacy) {
        return component(legacy).getString();
    }

    public static Component lines(List<String> lines) {
        MutableComponent joined = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                joined.append(Component.literal("\n"));
            }
            joined.append(component(lines.get(index)));
        }
        return joined;
    }

    public static String clipFront(String text, int maxWidth, ToIntFunction<String> width) {
        if (width.applyAsInt(text) <= maxWidth) {
            return text;
        }
        int available = maxWidth - width.applyAsInt(ELLIPSIS);
        int start = text.length();
        while (start > 0 && width.applyAsInt(text.substring(start - 1)) <= available) {
            start--;
        }
        return ELLIPSIS + text.substring(start);
    }
}
