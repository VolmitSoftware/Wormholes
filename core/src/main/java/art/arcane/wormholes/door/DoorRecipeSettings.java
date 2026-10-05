package art.arcane.wormholes.door;

import art.arcane.wormholes.config.toml.RecipeConfig;
import art.arcane.wormholes.config.toml.RecipesConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The live door-crafting rules: which products can be crafted at all, and the
 * grid each one is crafted on.
 *
 * <p>A product missing from {@link #products()} is simply not craftable. The two
 * reskin recipes only toggle, because their result is derived from the two items
 * put in rather than from a fixed grid.</p>
 */
public record DoorRecipeSettings(
    Map<DoorCraftProduct, DoorRecipeSpec> products,
    boolean doorSkinEnabled,
    boolean trapdoorSkinEnabled
) {
    public DoorRecipeSettings {
        Objects.requireNonNull(products, "products");
        products = Map.copyOf(products);
    }

    public static DoorRecipeSettings defaults() {
        EnumMap<DoorCraftProduct, DoorRecipeSpec> products = new EnumMap<>(DoorCraftProduct.class);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            products.put(product, product.defaultSpec());
        }
        return new DoorRecipeSettings(products, true, true);
    }

    public static DoorRecipeSettings from(RecipesConfig configured,
                                          BiConsumer<DoorCraftProduct, RuntimeException> failure) {
        Objects.requireNonNull(failure, "failure");
        RecipesConfig recipes = configured == null ? new RecipesConfig() : configured;
        EnumMap<DoorCraftProduct, DoorRecipeSpec> products = new EnumMap<>(DoorCraftProduct.class);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            RecipeConfig recipe = recipes.forProduct(product);
            if (recipe == null || !recipe.enabled) {
                continue;
            }
            DoorRecipeSpec spec;
            try {
                spec = DoorRecipeSpec.parse(recipe.shape, recipe.ingredients);
            } catch (IllegalArgumentException | NullPointerException rejected) {
                failure.accept(product, rejected);
                spec = product.defaultSpec();
            }
            products.put(product, spec);
        }
        return new DoorRecipeSettings(products,
            recipes.doorSkin == null || recipes.doorSkin.enabled,
            recipes.trapdoorSkin == null || recipes.trapdoorSkin.enabled);
    }

    public Optional<DoorRecipeSpec> spec(DoorCraftProduct product) {
        return Optional.ofNullable(products.get(Objects.requireNonNull(product, "product")));
    }

    public boolean isCraftable(DoorCraftProduct product) {
        return products.containsKey(Objects.requireNonNull(product, "product"));
    }
}
