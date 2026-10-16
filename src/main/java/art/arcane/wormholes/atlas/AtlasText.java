package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;

import java.util.Objects;

/** Message helpers for the atlas command and guide. */
final class AtlasText {
    private AtlasText() {
    }

    static MessageArgs args(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Localization arguments require name-value pairs");
        }
        MessageArgument[] arguments = new MessageArgument[nameValuePairs.length / 2];
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            String name = Objects.requireNonNull((String) nameValuePairs[index], "Localization argument name");
            Object value = Objects.requireNonNull(nameValuePairs[index + 1], "Localization argument value");
            arguments[index / 2] = MessageArgument.untrusted(name, value);
        }
        return WormholesLocalization.args(arguments);
    }

    static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }

    static void sendLines(CommandSender sender, LinesKey key, MessageArgs arguments) {
        Wormholes.text().components(sender, key, arguments)
                .forEach(line -> WormholesAudience.sendMessage(sender, line));
    }
}
