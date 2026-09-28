package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.ResolvedLines;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.google.gson.JsonElement;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class WormholesMessageRenderer {
    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private WormholesMessageRenderer() {
    }

    public static JsonElement json(ResolvedText resolved) {
        return JSON.serializeToTree(render(resolved));
    }

    public static List<JsonElement> jsonLines(ResolvedLines resolved) {
        List<JsonElement> lines = new ArrayList<>(resolved.lines().size());
        for (Component component : components(resolved)) {
            lines.add(JSON.serializeToTree(component));
        }
        return List.copyOf(lines);
    }

    public static List<Component> components(ResolvedLines resolved) {
        List<Component> components = new ArrayList<>(resolved.lines().size());
        for (String line : resolved.lines()) {
            components.add(MINI_MESSAGE.deserialize(substitute(ComponentText.normalizeMarkup(line), resolved.arguments())));
        }
        return List.copyOf(components);
    }

    public static JsonElement noticeJson(String legacy, boolean success) {
        return JSON.serializeToTree(LEGACY.deserialize(legacy).colorIfAbsent(success ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    public static String legacy(ResolvedText resolved) {
        return LEGACY.serialize(render(resolved));
    }

    public static List<String> legacyLines(ResolvedLines resolved) {
        List<Component> components = components(resolved);
        List<String> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(LEGACY.serialize(component));
        }
        return List.copyOf(lines);
    }

    public static List<String> miniMessageLines(ResolvedLines resolved) {
        List<String> lines = new ArrayList<>(resolved.lines().size());
        for (String line : resolved.lines()) {
            lines.add(substitute(ComponentText.normalizeMarkup(line), resolved.arguments()));
        }
        return List.copyOf(lines);
    }

    public static Component render(ResolvedText resolved) {
        return MINI_MESSAGE.deserialize(substitute(ComponentText.normalizeMarkup(resolved.template()), resolved.arguments()));
    }

    private static String substitute(String template, MessageArgs arguments) {
        StringBuilder rendered = new StringBuilder(template.length() + arguments.size() * 8);
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                rendered.append('{');
                index++;
                continue;
            }
            if (current == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                rendered.append('}');
                index++;
                continue;
            }
            if (current != '{') {
                rendered.append(current);
                continue;
            }
            int end = template.indexOf('}', index + 1);
            String name = template.substring(index + 1, end);
            MessageArgument argument = arguments.require(name);
            String value = String.valueOf(argument.value());
            rendered.append(argument.kind() == MessageArgumentKind.UNTRUSTED ? escapeUntrusted(value) : value);
            index = end;
        }
        return rendered.toString();
    }

    private static String escapeUntrusted(String value) {
        return MINI_MESSAGE.escapeTags(PLAIN.serialize(LEGACY.deserialize(value)));
    }
}
