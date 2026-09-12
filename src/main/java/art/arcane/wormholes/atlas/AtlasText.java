package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

import java.util.Objects;

/** Element and message helpers for the atlas command and menu. */
final class AtlasText {
    private AtlasText() {
    }

    static UIElement element(String id, LinesKey key, MessageArgs arguments, Material material) {
        UIElement element = new UIElement(id);
        element.setMaterial(new MaterialBlock(material));
        Wormholes.text().apply(element, key, arguments);
        return element;
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
