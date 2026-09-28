package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class MinecraftLegacyText {
    private static final char COLOR_CHAR = '\u00A7';
    private static final Style RESET = Style.EMPTY.withBold(false).withItalic(false).withUnderlined(false)
        .withStrikethrough(false).withObfuscated(false);

    private MinecraftLegacyText() {
    }

    public static String text(ServerPlayer viewer, TextKey key) {
        return text(viewer, key, MessageArgs.empty());
    }

    public static String text(ServerPlayer viewer, TextKey key, MessageArgs arguments) {
        return WormholesMessageRenderer.legacy(snapshot(viewer).resolve(key, arguments));
    }

    public static List<String> lines(ServerPlayer viewer, LinesKey key, MessageArgs arguments) {
        return WormholesMessageRenderer.legacyLines(snapshot(viewer).resolve(key, arguments));
    }

    public static void apply(ServerPlayer viewer, MinecraftElement element, LinesKey key, MessageArgs arguments) {
        List<String> lines = lines(viewer, key, arguments);
        element.setName(lines.getFirst());
        element.getLore().clear();
        for (int index = 1; index < lines.size(); index++) {
            element.addLore(lines.get(index));
        }
    }

    public static Component component(String legacy) {
        MutableComponent root = Component.empty();
        if (legacy == null || legacy.isEmpty()) {
            return root;
        }
        Style style = Style.EMPTY;
        StringBuilder hex = null;
        int segmentStart = 0;
        int index = 0;
        int length = legacy.length();
        while (index < length) {
            char current = legacy.charAt(index);
            char code = index + 1 < length ? Character.toLowerCase(legacy.charAt(index + 1)) : 0;
            if (current != COLOR_CHAR || !isCode(code)) {
                index++;
                continue;
            }
            if (index > segmentStart) {
                root.append(Component.literal(legacy.substring(segmentStart, index)).setStyle(style));
            }
            if (code == 'x') {
                hex = new StringBuilder("#");
            } else if (hex != null) {
                hex.append(code);
                if (hex.length() == 7) {
                    TextColor color = TextColor.parseColor(hex.toString()).result().orElse(null);
                    style = RESET.withColor(color);
                    hex = null;
                }
            } else {
                style = switch (code) {
                    case 'k' -> style.withObfuscated(true);
                    case 'l' -> style.withBold(true);
                    case 'm' -> style.withStrikethrough(true);
                    case 'n' -> style.withUnderlined(true);
                    case 'o' -> style.withItalic(true);
                    default -> RESET.withColor(ChatFormatting.getByCode(code));
                };
            }
            index += 2;
            segmentStart = index;
        }
        if (segmentStart < length) {
            root.append(Component.literal(legacy.substring(segmentStart)).setStyle(style));
        }
        return root;
    }

    private static boolean isCode(char code) {
        return code >= '0' && code <= '9' || code >= 'a' && code <= 'f' || code >= 'k' && code <= 'o' || code == 'r' || code == 'x';
    }

    private static LocalizationSnapshot snapshot(ServerPlayer viewer) {
        return MinecraftLocalization.forPlayer(viewer).snapshot(viewer);
    }
}
