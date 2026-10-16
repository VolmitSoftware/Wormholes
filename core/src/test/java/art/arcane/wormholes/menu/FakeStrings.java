package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

public final class FakeStrings implements MenuStrings {
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    @Override
    public String text(TextKey key, MessageArgs arguments) {
        requireArguments(key, arguments);
        return render(key.english(), arguments);
    }

    @Override
    public MenuLines lines(LinesKey key, MessageArgs arguments, int chestHintLines) {
        requireArguments(key, arguments);
        List<String> resolved = new ArrayList<>(key.english().size());
        for (String line : key.english()) {
            resolved.add(render(line, arguments));
        }
        return MenuLines.split(resolved, key, chestHintLines);
    }

    private static void requireArguments(MessageKey key, MessageArgs arguments) {
        if (!key.placeholders().equals(arguments.names())) {
            throw new IllegalArgumentException("arguments " + arguments.names() + " do not match " + key.id() + " " + key.placeholders());
        }
    }

    public static String render(String template, MessageArgs arguments) {
        String rendered = LEGACY.matcher(template).replaceAll("\u00a7$1");
        for (Map.Entry<String, MessageArgument> entry : arguments.arguments().entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue().value()));
        }
        return rendered;
    }
}
