package art.arcane.wormholes.rules;

import java.util.Locale;
import java.util.UUID;

/**
 * Item identity used by item conditions and costs. {@code material} is a Bukkit material name;
 * {@code identity} is the {@code wormholes:key} PDC value a minted key or ticket carries; {@code exactMeta}
 * requires the held stack to be similar to the authored template rather than only the same material.
 */
public record ItemMatcher(String material, UUID identity, boolean exactMeta) {
    public ItemMatcher {
        material = material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
    }

    public static ItemMatcher material(String material) {
        return new ItemMatcher(material, null, false);
    }
}
