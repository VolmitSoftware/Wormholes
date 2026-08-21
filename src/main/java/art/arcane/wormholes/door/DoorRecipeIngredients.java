package art.arcane.wormholes.door;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns a configured ingredient token into something Bukkit can match against.
 *
 * <p>A token is either a group name like {@code #doors}, a single block name, or
 * several block names separated by {@code /}. This is the only place a recipe's
 * text becomes server types, which keeps configuration parsing free of the block
 * registry.</p>
 */
final class DoorRecipeIngredients {
    static final String GROUP_PREFIX = "#";
    static final String ALTERNATIVE_SEPARATOR = "/";
    static final String GROUP_DOORS = "#doors";
    static final String GROUP_TRAPDOORS = "#trapdoors";
    static final String GROUP_ANY_TRAPDOORS = "#any-trapdoors";
    static final String GROUP_WORMHOLE_RUNE = "#wormhole-rune";

    private DoorRecipeIngredients() {
    }

    /**
     * @param wormholeRunes the exact rune items {@code #wormhole-rune} matches
     * @throws IllegalArgumentException when the token names nothing usable
     */
    static RecipeChoice resolve(String token, List<ItemStack> wormholeRunes) {
        String required = Objects.requireNonNull(token, "token").trim();
        Objects.requireNonNull(wormholeRunes, "wormholeRunes");
        if (required.isEmpty()) {
            throw new IllegalArgumentException("ingredient is blank");
        }

        if (required.startsWith(GROUP_PREFIX)) {
            return group(required.toLowerCase(Locale.ROOT).replace('_', '-'), wormholeRunes);
        }

        List<Material> materials = new ArrayList<>();
        for (String name : required.split(ALTERNATIVE_SEPARATOR)) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Material material = Material.matchMaterial(trimmed.toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("'" + trimmed + "' is not a craftable item");
            }
            materials.add(material);
        }
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("'" + required + "' names no item");
        }
        return new RecipeChoice.MaterialChoice(materials);
    }

    private static RecipeChoice group(String group, List<ItemStack> wormholeRunes) {
        return switch (group) {
            case GROUP_DOORS -> new RecipeChoice.MaterialChoice(DoorSkin.doorMaterials());
            // Only hand-openable trapdoors can become dimensional trapdoors.
            case GROUP_TRAPDOORS -> new RecipeChoice.MaterialChoice(DoorSkin.playerOperableTrapdoorMaterials());
            case GROUP_ANY_TRAPDOORS -> new RecipeChoice.MaterialChoice(DoorSkin.trapdoorMaterials());
            case GROUP_WORMHOLE_RUNE -> {
                if (wormholeRunes.isEmpty()) {
                    throw new IllegalArgumentException("the Wormhole Rune item is not available yet");
                }
                yield new RecipeChoice.ExactChoice(List.copyOf(wormholeRunes));
            }
            default -> throw new IllegalArgumentException("unknown ingredient group '" + group + "'");
        };
    }
}
