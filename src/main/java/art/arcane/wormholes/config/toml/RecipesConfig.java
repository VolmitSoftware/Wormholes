package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Dimensional Door crafting. Changes hot-reload; recipes are re-registered and",
    "re-sent to every online player's recipe book.",
    "A recipe that fails to parse is logged and falls back to its shipped shape,",
    "so a typo never leaves a product uncraftable by accident."
})
public class RecipesConfig {
    public RecipeConfig pairKit = RecipeConfig.of(DoorCraftProduct.PAIR_KIT);
    public RecipeConfig personalDoor = RecipeConfig.of(DoorCraftProduct.PERSONAL_DOOR);
    public RecipeConfig publicDoor = RecipeConfig.of(DoorCraftProduct.PUBLIC_DOOR);
    public RecipeConfig trapdoorPairKit = RecipeConfig.of(DoorCraftProduct.TRAPDOOR_PAIR_KIT);
    public RecipeConfig personalTrapdoor = RecipeConfig.of(DoorCraftProduct.PERSONAL_TRAPDOOR);
    public RecipeConfig publicTrapdoor = RecipeConfig.of(DoorCraftProduct.PUBLIC_TRAPDOOR);
    public ReskinRecipeConfig doorSkin = new ReskinRecipeConfig();
    public ReskinRecipeConfig trapdoorSkin = new ReskinRecipeConfig();

    public RecipeConfig forProduct(DoorCraftProduct product) {
        return switch (product) {
            case PAIR_KIT -> pairKit;
            case PERSONAL_DOOR -> personalDoor;
            case PUBLIC_DOOR -> publicDoor;
            case TRAPDOOR_PAIR_KIT -> trapdoorPairKit;
            case PERSONAL_TRAPDOOR -> personalTrapdoor;
            case PUBLIC_TRAPDOOR -> publicTrapdoor;
        };
    }
}
