package art.arcane.wormholes.door;

import art.arcane.wormholes.config.toml.RecipesConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorRecipeSpecTest {
    @Test
    void configuredProductsAndSkinTogglesResolveTogether() {
        RecipesConfig configured = new RecipesConfig();
        configured.pairKit.enabled = false;
        configured.personalDoor.shape = "A";
        configured.personalDoor.ingredients = "A=STONE";
        configured.trapdoorSkin.enabled = false;
        EnumMap<DoorCraftProduct, RuntimeException> failures = new EnumMap<>(DoorCraftProduct.class);

        DoorRecipeSettings recipes = DoorRecipeSettings.from(configured, failures::put);

        assertFalse(recipes.isCraftable(DoorCraftProduct.PAIR_KIT));
        assertEquals(DoorRecipeSpec.parse("A", "A=STONE"), recipes.spec(DoorCraftProduct.PERSONAL_DOOR).orElseThrow());
        assertTrue(recipes.doorSkinEnabled());
        assertFalse(recipes.trapdoorSkinEnabled());
        assertTrue(failures.isEmpty());
    }

    @Test
    void unusableRecipesReportTheCauseAndRetainCraftableDefaults() {
        RecipesConfig configured = new RecipesConfig();
        configured.pairKit.shape = "A";
        configured.pairKit.ingredients = "B=STONE";
        configured.personalDoor.shape = null;
        configured.publicDoor.enabled = false;
        configured.publicDoor.shape = null;
        EnumMap<DoorCraftProduct, RuntimeException> failures = new EnumMap<>(DoorCraftProduct.class);

        DoorRecipeSettings recipes = DoorRecipeSettings.from(configured, failures::put);

        assertEquals(DoorCraftProduct.PAIR_KIT.defaultSpec(), recipes.spec(DoorCraftProduct.PAIR_KIT).orElseThrow());
        assertEquals(DoorCraftProduct.PERSONAL_DOOR.defaultSpec(), recipes.spec(DoorCraftProduct.PERSONAL_DOOR).orElseThrow());
        assertTrue(failures.get(DoorCraftProduct.PAIR_KIT) instanceof IllegalArgumentException);
        assertTrue(failures.get(DoorCraftProduct.PERSONAL_DOOR) instanceof NullPointerException);
        assertEquals(2, failures.size());
        assertFalse(recipes.isCraftable(DoorCraftProduct.PUBLIC_DOOR));
    }

    @Test
    void everyShippedProductRecipeParsesBackIntoItself() {
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            DoorRecipeSpec spec = product.defaultSpec();

            assertEquals(product.defaultShape(), spec.shape().toString(), product.name());
            assertEquals(product.defaultIngredients(), spec.ingredientsToString(), product.name());
        }
    }

    @Test
    void rowsShorterThanTheWidestArePaddedSoALostTrailingSpaceIsNotFatal() {
        DoorRecipeShape padded = DoorRecipeShape.parse("RDR| E | L");
        DoorRecipeShape explicit = DoorRecipeShape.parse("RDR| E | L ");

        assertEquals(explicit, padded);
        assertEquals(List.of("RDR", " E ", " L "), padded.rows());
    }

    @Test
    void gridsOutsideThreeByThreeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeShape.parse("AAAA"));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeShape.parse("A|A|A|A"));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeShape.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeShape.parse("   "));
        assertThrows(NullPointerException.class, () -> DoorRecipeShape.parse(null));
    }

    @Test
    void onlyNonEmptySlotsCountAsSymbols() {
        DoorRecipeShape shape = DoorRecipeShape.parse("AB | A");

        assertEquals(List.of('A', 'B'), List.copyOf(shape.symbols()));
        assertEquals(List.of("AB ", " A "), shape.rows());
    }

    @Test
    void ingredientListsParseIntoOneEntryPerSlot() {
        Map<Character, String> parsed = DoorRecipeSpec.parseIngredients("E=ENDER_EYE, D=#doors , O=OBSIDIAN");

        assertEquals(Map.of('E', "ENDER_EYE", 'D', "#doors", 'O', "OBSIDIAN"), parsed);
    }

    @Test
    void malformedIngredientListsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeSpec.parseIngredients("ENDER_EYE"));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeSpec.parseIngredients("EE=ENDER_EYE"));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeSpec.parseIngredients("E="));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeSpec.parseIngredients("E=A, E=B"));
        assertThrows(IllegalArgumentException.class, () -> DoorRecipeSpec.parseIngredients(""));
    }

    @Test
    void aShapeAndItsIngredientsHaveToDescribeTheSameSlots() {
        assertThrows(IllegalArgumentException.class,
            () -> DoorRecipeSpec.parse("AB", "A=STONE"), "B has no ingredient");
        assertThrows(IllegalArgumentException.class,
            () -> DoorRecipeSpec.parse("AA", "A=STONE, B=DIRT"), "B is never placed");
        assertTrue(DoorRecipeSpec.parse("A A", "A=STONE").ingredients().containsKey('A'));
    }

    @Test
    void aDisabledProductIsSimplyAbsentFromTheLiveRules() {
        DoorRecipeSettings defaults = DoorRecipeSettings.defaults();

        assertTrue(defaults.isCraftable(DoorCraftProduct.PAIR_KIT));
        assertTrue(defaults.doorSkinEnabled());
        assertTrue(defaults.trapdoorSkinEnabled());

        DoorRecipeSettings withoutPairs = new DoorRecipeSettings(
            Map.of(DoorCraftProduct.PUBLIC_DOOR, DoorCraftProduct.PUBLIC_DOOR.defaultSpec()), false, false);

        assertTrue(withoutPairs.isCraftable(DoorCraftProduct.PUBLIC_DOOR));
        assertTrue(withoutPairs.spec(DoorCraftProduct.PAIR_KIT).isEmpty());
        assertTrue(!withoutPairs.isCraftable(DoorCraftProduct.PAIR_KIT));
    }
}
