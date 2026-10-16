package art.arcane.wormholes.menu.portal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuViewer;

public record MenuExtensionEntry(String id, String chestItemId, String nativeSprite, LinesKey label, int chestHintLines,
                                 Function<UUID, MessageArgs> arguments, BiPredicate<MenuViewer, UUID> visible,
                                 BiPredicate<MenuViewer, UUID> enchanted, Function<UUID, MenuRoute> route) {
    public MenuExtensionEntry {
        MenuText.id(id, "extension id");
        MenuIcon.item(chestItemId);
        MenuIcon.sprite(nativeSprite);
        Objects.requireNonNull(label, "label");
        if (chestHintLines < 0) {
            throw new IllegalArgumentException("chest hint lines must not be negative");
        }
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(visible, "visible");
        Objects.requireNonNull(enchanted, "enchanted");
    }

    public boolean routed() {
        return route != null;
    }
}
