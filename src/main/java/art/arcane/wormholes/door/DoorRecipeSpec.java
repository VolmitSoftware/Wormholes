package art.arcane.wormholes.door;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One operator-defined door recipe, still in plain text.
 *
 * <p>Ingredient values stay unresolved strings here so configuration can be read
 * and validated without touching the server's block registry; the world layer
 * turns them into recipe choices when the recipe is registered.</p>
 */
public record DoorRecipeSpec(DoorRecipeShape shape, Map<Character, String> ingredients) {
    public static final char ASSIGNMENT = '=';
    public static final char INGREDIENT_SEPARATOR = ',';

    public DoorRecipeSpec {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(ingredients, "ingredients");
        ingredients = Collections.unmodifiableMap(new LinkedHashMap<>(ingredients));
        for (Character symbol : shape.symbols()) {
            if (!ingredients.containsKey(symbol)) {
                throw new IllegalArgumentException("shape uses '" + symbol + "' with no ingredient for it");
            }
        }
        for (Character symbol : ingredients.keySet()) {
            if (!shape.symbols().contains(symbol)) {
                throw new IllegalArgumentException("ingredient '" + symbol + "' is never used by the shape");
            }
        }
    }

    public static DoorRecipeSpec parse(String shape, String ingredients) {
        return new DoorRecipeSpec(DoorRecipeShape.parse(shape), parseIngredients(ingredients));
    }

    /** Parses {@code "E=ENDER_EYE, D=#doors"} into one entry per slot symbol. */
    public static Map<Character, String> parseIngredients(String ingredients) {
        String required = Objects.requireNonNull(ingredients, "ingredients");
        Map<Character, String> parsed = new LinkedHashMap<>();
        for (String entry : required.split(String.valueOf(INGREDIENT_SEPARATOR), -1)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int assignment = trimmed.indexOf(ASSIGNMENT);
            if (assignment < 0) {
                throw new IllegalArgumentException("ingredient '" + trimmed + "' is not symbol=material");
            }
            String symbol = trimmed.substring(0, assignment).trim();
            String value = trimmed.substring(assignment + 1).trim();
            if (symbol.length() != 1 || symbol.charAt(0) == DoorRecipeShape.EMPTY) {
                throw new IllegalArgumentException("ingredient symbol '" + symbol + "' must be one character");
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("ingredient '" + symbol + "' has no material");
            }
            if (parsed.put(Character.valueOf(symbol.charAt(0)), value) != null) {
                throw new IllegalArgumentException("ingredient '" + symbol + "' is defined twice");
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("a recipe needs at least one ingredient");
        }
        return parsed;
    }

    /** The canonical config form of this spec's ingredient list. */
    public String ingredientsToString() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Character, String> ingredient : ingredients.entrySet()) {
            if (!out.isEmpty()) {
                out.append(INGREDIENT_SEPARATOR).append(' ');
            }
            out.append(ingredient.getKey()).append(ASSIGNMENT).append(ingredient.getValue());
        }
        return out.toString();
    }
}
