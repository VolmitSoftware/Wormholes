package art.arcane.wormholes.menu.cosmetics;

import java.util.Locale;

import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuText;

public record DyeSwatch(String name, String itemId, int rgb) {
    public DyeSwatch {
        name = MenuText.id(name, "dye name").toLowerCase(Locale.ROOT);
        itemId = MenuIcon.item(itemId).itemId();
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("dye colour " + Integer.toHexString(rgb) + " is outside 0..ffffff");
        }
    }

    public String displayName() {
        String[] parts = name.split("_");
        StringBuilder builder = new StringBuilder(name.length());
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                builder.append(' ');
            }
            String part = parts[index];
            if (!part.isEmpty()) {
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
