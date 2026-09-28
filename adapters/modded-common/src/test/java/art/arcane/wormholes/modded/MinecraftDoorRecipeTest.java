package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.RecipesConfig;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorRecipeSpec;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftDoorRecipeTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : List.of(Items.BUNDLE, Items.OAK_DOOR, Items.OAK_TRAPDOOR, Items.DARK_OAK_DOOR,
            Items.DARK_OAK_TRAPDOOR, Items.PALE_OAK_DOOR, Items.PALE_OAK_TRAPDOOR, Items.BIRCH_DOOR,
            Items.DARK_PRISMARINE, Items.STONE, Items.DIRT, Items.GLOWSTONE_DUST)) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
    }

    @Test
    public void everyProductMintsIndependentIdentities() {
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            ItemStack first = MinecraftDoorRecipes.mint(product);
            ItemStack second = MinecraftDoorRecipes.mint(product);
            if (product.kind() == DoorKind.PAIR) {
                MinecraftDoorItems.PairKit a = MinecraftDoorItems.kit(first).orElseThrow();
                MinecraftDoorItems.PairKit b = MinecraftDoorItems.kit(second).orElseThrow();
                assertEquals(product.form(), a.form());
                assertNotEquals(a.kitId(), b.kitId());
            } else {
                DoorItemIdentity a = MinecraftDoorItems.identity(first).orElseThrow();
                DoorItemIdentity b = MinecraftDoorItems.identity(second).orElseThrow();
                assertEquals(product.form(), a.form());
                assertEquals(product.kind(), a.kind());
                assertNotEquals(a.itemId(), b.itemId());
            }
        }
    }

    @Test
    public void shapedRecipesAcceptMirrorAndRequireExactEmptyCells() {
        MinecraftDoorRecipes.Grid recipe = MinecraftDoorRecipes.resolve(DoorRecipeSpec.parse("AB| C", "A=STONE, B=DIRT, C=GLOWSTONE_DUST"));
        assertTrue(recipe.matches(CraftingInput.of(2, 2, List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT),
            ItemStack.EMPTY, new ItemStack(Items.GLOWSTONE_DUST)))));
        assertTrue(recipe.matches(CraftingInput.of(2, 2, List.of(new ItemStack(Items.DIRT), new ItemStack(Items.STONE),
            new ItemStack(Items.GLOWSTONE_DUST), ItemStack.EMPTY))));
        assertFalse(recipe.matches(CraftingInput.of(2, 2, List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT),
            new ItemStack(Items.STONE), new ItemStack(Items.GLOWSTONE_DUST)))));
    }

    @Test
    public void wormholeRuneIngredientRejectsOrdinaryDarkPrismarine() {
        MinecraftDoorRecipes.Grid recipe = MinecraftDoorRecipes.resolve(DoorRecipeSpec.parse("R", "R=#wormhole-rune"));
        assertTrue(recipe.matches(CraftingInput.of(1, 1, List.of(MinecraftDoorItems.wormholeRune()))));
        assertFalse(recipe.matches(CraftingInput.of(1, 1, List.of(new ItemStack(Items.DARK_PRISMARINE)))));
    }

    @Test
    public void reskinPreservesIdentityAndRejectsFormChanges() {
        DoorItemIdentity identity = DoorItemIdentity.newPersonal();
        CraftingInput input = CraftingInput.of(2, 1, List.of(MinecraftDoorItems.door(identity), new ItemStack(Items.BIRCH_DOOR)));
        ItemStack output = MinecraftDoorRecipes.skin(DoorForm.DOOR, input);
        assertTrue(output.is(Items.BIRCH_DOOR));
        assertEquals(identity, MinecraftDoorItems.identity(output).orElseThrow());
        assertTrue(MinecraftDoorRecipes.skin(DoorForm.TRAPDOOR, input).isEmpty());
        CraftingInput duplicate = CraftingInput.of(2, 1, List.of(MinecraftDoorItems.door(identity), MinecraftDoorItems.door(identity)));
        assertTrue(MinecraftDoorRecipes.skin(DoorForm.DOOR, duplicate).isEmpty());
    }

    @Test
    public void configuredRecipesBuildOnlyEnabledHoldersWithConfiguredShape() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(doors.enabled()).thenReturn(true);
        RecipesConfig first = doorRecipesOnly();
        first.personalDoor.shape = "A";
        first.personalDoor.ingredients = "A=STONE";
        when(settings.getRecipes()).thenReturn(first);
        MinecraftDoorRecipes recipes = new MinecraftDoorRecipes(runtime, doors);
        List<ResourceKey<Recipe<?>>> doorKeys = List.of(MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT),
            MinecraftDoorRecipes.key(DoorCraftProduct.PERSONAL_DOOR), MinecraftDoorRecipes.key(DoorCraftProduct.PUBLIC_DOOR),
            MinecraftDoorRecipes.skinKey(DoorForm.DOOR));
        List<RecipeHolder<?>> built = recipes.build();
        assertEquals(doorKeys, built.stream().<ResourceKey<Recipe<?>>>map(RecipeHolder::id).toList());
        RecipeHolder<?> personal = built.get(1);
        assertTrue(((CraftingRecipe) personal.value()).matches(CraftingInput.of(1, 1, List.of(new ItemStack(Items.STONE))), null));
        assertFalse(personal.value().isSpecial());
        assertFalse(personal.value().placementInfo().isImpossibleToPlace());
        RecipesConfig second = doorRecipesOnly();
        second.personalDoor.enabled = false;
        second.doorSkin.enabled = false;
        when(settings.getRecipes()).thenReturn(second);
        assertEquals(List.of(MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT), MinecraftDoorRecipes.key(DoorCraftProduct.PUBLIC_DOOR)),
            recipes.build().stream().<ResourceKey<Recipe<?>>>map(RecipeHolder::id).toList());
        when(doors.enabled()).thenReturn(false);
        assertTrue(recipes.build().isEmpty());
    }

    private static RecipesConfig doorRecipesOnly() {
        RecipesConfig recipes = new RecipesConfig();
        recipes.trapdoorPairKit.enabled = false;
        recipes.personalTrapdoor.enabled = false;
        recipes.publicTrapdoor.enabled = false;
        recipes.trapdoorSkin.enabled = false;
        return recipes;
    }
}
