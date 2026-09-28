package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class MinecraftDirectorMiniMenu {
    static final int MENU_LINE_COUNT = 19;

    private static final int FOOTER_WIDTH = 75;
    private static final int FOOTER_BUTTON_WIDTH = 10;
    private static final int FONT_SPACE_WIDTH = 4;
    private static final int HEADER_ORNAMENT_WIDTH = 28;
    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();

    private MinecraftDirectorMiniMenu() {
    }

    static void deliverContent(CommandSourceStack source, ContentMenu menu, Theme theme, Function<TextKey, String> resolver) {
        if (source.getPlayer() != null) {
            deliver(source, renderContent(menu, theme, resolver));
            return;
        }
        for (String line : renderContentConsole(menu)) {
            if (!line.trim().isEmpty()) {
                source.sendSystemMessage(Component.literal(line));
            }
        }
    }

    static void deliver(CommandSourceStack source, List<String> lines) {
        boolean renderable = false;
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                renderable = true;
                break;
            }
        }
        if (renderable && source.getPlayer() != null) {
            send(source, ComponentText.literal("\n".repeat(MENU_LINE_COUNT)));
        }
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                send(source, ComponentText.component(WormholesMessageRenderer.markup(line)));
            }
        }
    }

    static void send(CommandSourceStack source, ComponentText message) {
        source.sendSystemMessage(component(message));
    }

    static Component component(ComponentText message) {
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE,
            JSON.serializeToTree(WormholesMessageRenderer.markup(message.miniMessage()))).getOrThrow();
    }

    static List<String> renderContent(ContentMenu menu, Theme theme, Function<TextKey, String> resolver) {
        ContentPage page = menu.page();
        ArrayList<String> lines = new ArrayList<>();
        lines.add(banner(menu.title(), theme));
        if (!menu.parentCommand().isBlank()) {
            lines.add(backLink(menu.parentCommand(), theme, resolver));
        }
        if (menu.entries().isEmpty()) {
            if (!menu.emptyLine().isBlank()) {
                lines.add(menu.emptyLine());
            }
        } else {
            lines.addAll(menu.entries().subList(page.startIndex(), page.endIndex()));
        }
        lines.add(paginationBar(page, menu.command(), theme, resolver));
        return List.copyOf(lines);
    }

    static List<String> renderContentConsole(ContentMenu menu) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("--- " + stripMiniMessage(menu.title()) + " ---");
        if (menu.entries().isEmpty()) {
            if (!menu.emptyLine().isBlank()) {
                lines.add(stripMiniMessage(menu.emptyLine()));
            }
            return List.copyOf(lines);
        }
        for (String entry : menu.entries()) {
            lines.add(stripMiniMessage(entry));
        }
        return List.copyOf(lines);
    }

    static String banner(String title, Theme theme) {
        String activeTitle = title == null ? "" : title;
        int remainingWidth = (FOOTER_WIDTH * FONT_SPACE_WIDTH) - textWidth(activeTitle) - HEADER_ORNAMENT_WIDTH;
        int padding = Math.max(1, (remainingWidth + FONT_SPACE_WIDTH) / (FONT_SPACE_WIDTH * 2));
        return "<font:minecraft:uniform><strikethrough><gradient:" + theme.borderLeft() + ":" + theme.borderRight() + ">["
            + spaces(padding) + "(((</gradient></strikethrough></font>"
            + " <gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + escapeText(activeTitle) + "</gradient> "
            + "<font:minecraft:uniform><strikethrough><gradient:" + theme.borderRight() + ":" + theme.borderLeft() + ">)))"
            + spaces(padding) + "]</gradient></strikethrough></font>";
    }

    static String backLink(String parentCommand, Theme theme, Function<TextKey, String> resolver) {
        String command = Objects.requireNonNull(parentCommand, "parentCommand");
        return "<hover:show_text:'" + escapeAttr(escapeText(resolver.apply(DirectorHelpMessages.PARENT_HOVER)))
            + "'><click:run_command:" + command + "><font:minecraft:uniform><" + theme.primaryRight() + ">〈 "
            + escapeText(resolver.apply(DirectorHelpMessages.BACK)) + "</" + theme.primaryRight()
            + "></font></click></hover>";
    }

    static String paginationBar(ContentPage page, String command, Theme theme, Function<TextKey, String> resolver) {
        StringBuilder line = new StringBuilder();
        int fill = FOOTER_WIDTH;
        if (page.hasPrevious()) {
            fill -= FOOTER_BUTTON_WIDTH;
            line.append("<hover:show_text:'")
                .append(escapeAttr(escapeText(resolver.apply(DirectorHelpMessages.PREVIOUS_PAGE))))
                .append("'><click:run_command:")
                .append(page.previousCommand(command))
                .append("><").append(theme.primaryLeft()).append(">〈 ")
                .append(escapeText(resolver.apply(DirectorHelpMessages.PAGE))).append(" ")
                .append(page.page() - 1)
                .append("</").append(theme.primaryLeft()).append("></click></hover> ");
        }
        if (page.hasNext()) {
            fill -= FOOTER_BUTTON_WIDTH;
        }
        line.append("<font:minecraft:uniform><strikethrough><gradient:")
            .append(theme.borderRight()).append(":").append(theme.borderLeft()).append(">")
            .append(spaces(fill))
            .append("</gradient></strikethrough></font>");
        if (page.hasNext()) {
            line.append(" <hover:show_text:'")
                .append(escapeAttr(escapeText(resolver.apply(DirectorHelpMessages.NEXT_PAGE))))
                .append("'><click:run_command:")
                .append(page.nextCommand(command))
                .append("><").append(theme.primaryRight()).append(">")
                .append(escapeText(resolver.apply(DirectorHelpMessages.PAGE))).append(" ")
                .append(page.page() + 1)
                .append(" ❭</").append(theme.primaryRight()).append("></click></hover>");
        }
        return line.toString();
    }

    static ContentPage paginate(int itemCount, int requestedPage, int pageSize) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("item count must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("page size must be positive");
        }
        int totalPages = itemCount == 0 ? 1 : ((itemCount - 1) / pageSize) + 1;
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, itemCount);
        return new ContentPage(page, totalPages, startIndex, endIndex, itemCount);
    }

    static String escapeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }

    static String stripMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        int index = 0;
        int length = input.length();
        while (index < length) {
            char current = input.charAt(index);
            if (current == '\\' && index + 1 < length) {
                char escaped = input.charAt(index + 1);
                if (escaped == '<' || escaped == '>' || escaped == '\\') {
                    out.append(escaped);
                    index += 2;
                    continue;
                }
                out.append(current);
                index++;
                continue;
            }
            if (current == '<') {
                int scan = tagEnd(input, index + 1);
                if (scan < length) {
                    index = scan + 1;
                    continue;
                }
                out.append(current);
                index++;
                continue;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static int tagEnd(String input, int start) {
        int scan = start;
        boolean quoted = false;
        int length = input.length();
        while (scan < length) {
            char inside = input.charAt(scan);
            if (inside == '\\' && scan + 1 < length) {
                scan += 2;
                continue;
            }
            if (inside == '\'') {
                quoted = !quoted;
                scan++;
                continue;
            }
            if (inside == '>' && !quoted) {
                return scan;
            }
            scan++;
        }
        return scan;
    }

    private static String escapeAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String spaces(int length) {
        return length <= 0 ? "" : " ".repeat(length);
    }

    private static int textWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length(); index++) {
            width += characterWidth(value.charAt(index));
        }
        return width;
    }

    private static int characterWidth(char character) {
        return switch (character) {
            case ' ', '[', ']', 'I', 't' -> 4;
            case '!', ',', '.', ':', ';', 'i', '|', 'l' -> 2;
            case '\'', '`' -> 3;
            case '"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    record Theme(String primaryLeft, String primaryRight, String borderLeft, String borderRight, String description,
                 String required, String optional, String muted) {
        static final Theme WORMHOLES = new Theme("#d4af37", "#8c7a45", "#d4af37", "#8c7a45", "#8c7a45", "#f4d35e",
            "#8c7a45", "#8c7a45");
    }

    record ContentMenu(String title, String command, String parentCommand, List<String> entries, String emptyLine,
                       int requestedPage, int pageSize) {
        ContentMenu {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("content menu title must not be blank");
            }
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("content menu command must not be blank");
            }
            parentCommand = parentCommand == null ? "" : parentCommand;
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            emptyLine = emptyLine == null ? "" : emptyLine;
            if (pageSize < 1) {
                throw new IllegalArgumentException("content menu page size must be positive");
            }
        }

        ContentPage page() {
            return paginate(entries.size(), requestedPage, pageSize);
        }
    }

    record ContentPage(int page, int pages, int startIndex, int endIndex, int total) {
        boolean hasPrevious() {
            return page > 1;
        }

        boolean hasNext() {
            return page < pages;
        }

        String previousCommand(String command) {
            return command + " page=" + (page - 1);
        }

        String nextCommand(String command) {
            return command + " page=" + (page + 1);
        }
    }
}
