package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.util.project.config.ConfigDescription;

/** One configurable dimensional-door recipe. */
public class RecipeConfig {
    @ConfigDescription("Whether this product can be crafted at all. False removes its recipe from the server.")
    public boolean enabled = true;
    @ConfigDescription("Crafting grid, rows separated by |, a space for an empty slot. At most 3 rows of 3.")
    public String shape = "";
    @ConfigDescription("Slot symbols, as symbol=material pairs separated by commas. A material may be a block name, several names separated by /, or one of #doors, #trapdoors, #any-trapdoors, #wormhole-rune.")
    public String ingredients = "";

    public RecipeConfig() {
    }

    public static RecipeConfig of(DoorCraftProduct product) {
        RecipeConfig config = new RecipeConfig();
        config.shape = product.defaultShape();
        config.ingredients = product.defaultIngredients();
        return config;
    }
}
