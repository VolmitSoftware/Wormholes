package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import org.bukkit.Material;

import java.util.Objects;

/** Menu element helpers for the rules lane. Mirrors the portal package's package-private text helpers. */
final class RulesMenuText {
    private RulesMenuText() {
    }

    static UIElement element(String id, LinesKey key, MessageArgs arguments, Material material) {
        UIElement element = new UIElement(id);
        element.setMaterial(new MaterialBlock(material));
        Wormholes.text().apply(element, key, arguments);
        return element;
    }

    static MessageArgs arguments(Object... nameValuePairs) {
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

    static String localized(TextKey key) {
        return Wormholes.text().plain(key);
    }
}
