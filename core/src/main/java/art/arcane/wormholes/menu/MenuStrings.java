package art.arcane.wormholes.menu;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;

public interface MenuStrings {
    String text(TextKey key, MessageArgs arguments);

    MenuLines lines(LinesKey key, MessageArgs arguments, int chestHintLines);

    default String text(TextKey key) {
        return text(key, MessageArgs.empty());
    }

    default String settingName(LinesKey key, MessageArgs arguments, String valueArgument) {
        return MenuText.settingName(lines(key, marked(arguments, valueArgument), 0).label());
    }

    default String settingName(TextKey key, MessageArgs arguments, String valueArgument) {
        return MenuText.settingName(text(key, marked(arguments, valueArgument)));
    }

    private static MessageArgs marked(MessageArgs arguments, String valueArgument) {
        arguments.require(valueArgument);
        MessageArgs.Builder marked = MessageArgs.builder();
        for (MessageArgument argument : arguments.arguments().values()) {
            if (argument.name().equals(valueArgument)) {
                marked.untrusted(valueArgument, MenuText.VALUE_MARK);
            } else {
                marked.add(argument);
            }
        }
        return marked.build();
    }
}
