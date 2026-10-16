package art.arcane.wormholes.menu.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuText;

final class LanguageMenuText {
    static final int CATEGORY_PAGE_SIZE = 16;
    static final int CLIP_LENGTH = 512;

    private static final String COLOR_CODES = "0123456789abcdefklmnor";
    private static final String ELLIPSIS = "...";
    private static final int ROW_WIDTH = 9;
    private static final int CONTENT_ROWS = 5;
    private static final int CATEGORY_ROW_WIDTH = 7;

    private LanguageMenuText() {
    }

    static List<Integer> categorySlots(int count) {
        int bounded = Math.max(0, Math.min(count, CATEGORY_PAGE_SIZE));
        if (bounded == 0) {
            return List.of();
        }
        int rows = (bounded + CATEGORY_ROW_WIDTH - 1) / CATEGORY_ROW_WIDTH;
        int firstRow = (CONTENT_ROWS - rows) / 2;
        List<Integer> slots = new ArrayList<>(bounded);
        int remaining = bounded;
        for (int row = 0; row < rows; row++) {
            int rowSize = (remaining + rows - row - 1) / (rows - row);
            int firstColumn = (ROW_WIDTH - rowSize) / 2;
            for (int column = firstColumn; column < firstColumn + rowSize; column++) {
                slots.add((firstRow + row) * ROW_WIDTH + column);
            }
            remaining -= rowSize;
        }
        return slots;
    }

    static String groupName(String group) {
        if (group.equalsIgnoreCase("gui") || group.equalsIgnoreCase("hud") || group.equalsIgnoreCase("api")) {
            return group.toUpperCase(Locale.ROOT);
        }
        String normalized = group.replace('_', ' ').replace('-', ' ').trim();
        return normalized.isEmpty() ? group : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    static String groupItem(String group) {
        return switch (group.toLowerCase(Locale.ROOT)) {
            case "command" -> "minecraft:command_block";
            case "config", "configuration" -> "minecraft:comparator";
            case "debug", "diagnostics" -> "minecraft:spyglass";
            case "director", "help" -> "minecraft:writable_book";
            case "gui", "menu" -> "minecraft:chest";
            case "hud", "presentation" -> "minecraft:name_tag";
            case "integration" -> "minecraft:ender_chest";
            case "portal" -> "minecraft:obsidian";
            case "runtime", "system" -> "minecraft:redstone_torch";
            default -> "minecraft:paper";
        };
    }

    static String decodeInput(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\' && index + 1 < input.length()) {
                char next = input.charAt(index + 1);
                if (next == 'n' || next == '\\') {
                    result.append(next == 'n' ? '\n' : '\\');
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    static String encodeInput(String value) {
        return value.replace("\n", "\\n");
    }

    static String clip(String value) {
        return value.length() <= CLIP_LENGTH ? value : value.substring(0, CLIP_LENGTH) + ELLIPSIS;
    }

    static String clipCodePoints(String value, int codePoints) {
        if (MenuText.codePoints(value) <= codePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    static String fit(String line) {
        if (MenuText.encode(line).length <= MenuLimits.MAX_STRING_BYTES) {
            return line;
        }
        String clipped = line;
        while (!clipped.isEmpty() && MenuText.encode(clipped).length + ELLIPSIS.length() > MenuLimits.MAX_STRING_BYTES) {
            clipped = clipped.substring(0, clipped.offsetByCodePoints(0, MenuText.codePoints(clipped) - 1));
        }
        return clipped + ELLIPSIS;
    }

    static List<String> preview(String text, int width, int maximumLines, String empty) {
        String rendered = ComponentText.markup(text).legacy();
        if (rendered.isEmpty()) {
            return List.of(empty);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int visible = 0;
        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            if (character == '§' && index + 1 < rendered.length()) {
                current.append(character).append(rendered.charAt(++index));
                continue;
            }
            if (character == '\r') {
                continue;
            }
            if (character == '\n' || visible == width) {
                lines.add(current.toString());
                if (lines.size() == maximumLines) {
                    lines.set(maximumLines - 1, lines.get(maximumLines - 1) + "§8" + ELLIPSIS);
                    return fitted(lines);
                }
                current = new StringBuilder(lastColors(current.toString()));
                visible = 0;
                if (character == '\n') {
                    continue;
                }
            }
            current.append(character);
            visible++;
        }
        if (visible > 0 || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return fitted(lines);
    }

    static String lastColors(String input) {
        String result = "";
        int length = input.length();
        for (int index = length - 1; index > -1; index--) {
            if (input.charAt(index) != '§' || index >= length - 1) {
                continue;
            }
            if (index > 11 && input.charAt(index - 12) == '§' && (input.charAt(index - 11) == 'x' || input.charAt(index - 11) == 'X')) {
                String color = input.substring(index - 12, index + 2);
                if (hexColor(color)) {
                    return color + result;
                }
            }
            char code = input.charAt(index + 1);
            if (COLOR_CODES.indexOf(code) < 0) {
                continue;
            }
            result = "§" + code + result;
            if (code <= 'f' || code == 'r') {
                return result;
            }
        }
        return result;
    }

    private static List<String> fitted(List<String> lines) {
        List<String> fitted = new ArrayList<>(lines.size());
        for (String line : lines) {
            fitted.add(fit(line));
        }
        return fitted;
    }

    private static boolean hexColor(String color) {
        if (color.length() != 14) {
            return false;
        }
        for (int index = 2; index < 14; index += 2) {
            if (color.charAt(index) != '§' || Character.digit(color.charAt(index + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
