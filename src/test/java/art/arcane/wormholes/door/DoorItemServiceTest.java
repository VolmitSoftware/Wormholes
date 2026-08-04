package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Pins the crafting surface of dimensional trapdoors. A live {@code DoorItemService}
 * cannot be built headlessly - every mint touches {@code ItemMeta} - so the parts
 * under test here are the tables the service is driven from.
 */
final class DoorItemServiceTest
{
	/** Only hand-openable trapdoors; iron and copper need redstone and can never carry an identity. */
	private static final Predicate<Material> WOODEN_TRAPDOORS = Set.of(
		Material.OAK_TRAPDOOR,
		Material.DARK_OAK_TRAPDOOR,
		Material.PALE_OAK_TRAPDOOR,
		Material.BAMBOO_TRAPDOOR,
		Material.WARPED_TRAPDOOR)::contains;

	@Test
	void everyProductRegistersUnderItsOwnRecipeName()
	{
		HashSet<String> names = new HashSet<>();
		for(DoorCraftProduct product : DoorCraftProduct.values())
		{
			assertTrue(names.add(product.recipeName()), product + " reuses a recipe name");
			assertEquals(
				Optional.of(product),
				DoorCraftProduct.forRecipeName(product.recipeName()),
				product + " does not resolve back from its own name");
		}

		assertEquals(DoorCraftProduct.values().length, names.size());
		assertEquals(Optional.empty(), DoorCraftProduct.forRecipeName("dimensional_door_skin"));
		assertEquals(Optional.empty(), DoorCraftProduct.forRecipeName("stone_pickaxe"));
		assertThrows(NullPointerException.class, () -> DoorCraftProduct.forRecipeName(null));
	}

	@Test
	void theTrapdoorRecipeNamesAreTheOnesTheServerAlreadyHasRegistered()
	{
		assertEquals("dimensional_trapdoor_pair_kit", DoorCraftProduct.TRAPDOOR_PAIR_KIT.recipeName());
		assertEquals("personal_dimensional_trapdoor", DoorCraftProduct.PERSONAL_TRAPDOOR.recipeName());
		assertEquals("public_dimensional_trapdoor", DoorCraftProduct.PUBLIC_TRAPDOOR.recipeName());
		assertEquals("dimensional_door_pair_kit", DoorCraftProduct.PAIR_KIT.recipeName());
		assertEquals("personal_dimensional_door", DoorCraftProduct.PERSONAL_DOOR.recipeName());
		assertEquals("public_dimensional_door", DoorCraftProduct.PUBLIC_DOOR.recipeName());
	}

	@Test
	void mintingRoutesEveryProductToTheRightKindAndForm()
	{
		Map<DoorCraftProduct, DoorForm> forms = new EnumMap<>(DoorCraftProduct.class);
		Map<DoorCraftProduct, DoorKind> kinds = new EnumMap<>(DoorCraftProduct.class);
		for(DoorCraftProduct product : DoorCraftProduct.values())
		{
			forms.put(product, product.form());
			kinds.put(product, product.kind());
		}

		assertEquals(
			Map.of(
				DoorCraftProduct.PAIR_KIT, DoorForm.DOOR,
				DoorCraftProduct.PERSONAL_DOOR, DoorForm.DOOR,
				DoorCraftProduct.PUBLIC_DOOR, DoorForm.DOOR,
				DoorCraftProduct.TRAPDOOR_PAIR_KIT, DoorForm.TRAPDOOR,
				DoorCraftProduct.PERSONAL_TRAPDOOR, DoorForm.TRAPDOOR,
				DoorCraftProduct.PUBLIC_TRAPDOOR, DoorForm.TRAPDOOR),
			forms);
		assertEquals(
			Map.of(
				DoorCraftProduct.PAIR_KIT, DoorKind.PAIR,
				DoorCraftProduct.PERSONAL_DOOR, DoorKind.PERSONAL,
				DoorCraftProduct.PUBLIC_DOOR, DoorKind.PUBLIC,
				DoorCraftProduct.TRAPDOOR_PAIR_KIT, DoorKind.PAIR,
				DoorCraftProduct.PERSONAL_TRAPDOOR, DoorKind.PERSONAL,
				DoorCraftProduct.PUBLIC_TRAPDOOR, DoorKind.PUBLIC),
			kinds);
	}

	@Test
	void aMintedTrapdoorProductDropsAsATrapdoorMaterial()
	{
		for(DoorCraftProduct product : DoorCraftProduct.values())
		{
			if(product.kind() == DoorKind.PAIR)
			{
				// kits are bundles until they are unpacked into two endpoint identities
				continue;
			}
			Material material = DoorItemService.defaultMaterial(product.kind(), product.form());

			assertEquals(product.form(), DoorSkin.formOf(material), product + " default skin");
		}

		assertEquals(
			Material.OAK_TRAPDOOR,
			DoorItemService.defaultMaterial(DoorKind.PAIR, DoorForm.TRAPDOOR),
			"an unpacked trapdoor pair endpoint");
		assertNotEquals(
			DoorItemService.defaultMaterial(DoorKind.PERSONAL, DoorForm.TRAPDOOR),
			DoorItemService.defaultMaterial(DoorKind.PUBLIC, DoorForm.TRAPDOOR));
		assertThrows(
			IllegalArgumentException.class,
			() -> DoorItemService.defaultMaterial(DoorKind.RETURN, DoorForm.TRAPDOOR));
	}

	@Test
	void aTrapdoorIdentityReskinsOnlyOntoAnotherHandOpenableTrapdoor()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID(), DoorForm.TRAPDOOR);

		DoorSkinRecipe.Result accepted = resolve(
			Material.OAK_TRAPDOOR, identity, Material.WARPED_TRAPDOOR).orElseThrow();

		assertEquals(identity, accepted.identity());
		assertEquals(Material.WARPED_TRAPDOOR, accepted.material());
		assertEquals(DoorForm.TRAPDOOR, accepted.identity().form());
	}

	@Test
	void redstoneOnlyTrapdoorsAndCrossFormSwapsAreRefused()
	{
		DoorItemIdentity trapdoor = DoorItemIdentity.personal(UUID.randomUUID(), DoorForm.TRAPDOOR);
		DoorItemIdentity door = DoorItemIdentity.personal(UUID.randomUUID());

		assertTrue(resolve(Material.OAK_TRAPDOOR, trapdoor, Material.IRON_TRAPDOOR).isEmpty(),
			"iron trapdoors need redstone");
		assertTrue(resolve(Material.OAK_TRAPDOOR, trapdoor, Material.COPPER_TRAPDOOR).isEmpty(),
			"copper trapdoors need redstone");
		assertTrue(resolve(Material.OAK_TRAPDOOR, trapdoor, Material.OAK_DOOR).isEmpty(),
			"a trapdoor identity never becomes a door");
		assertTrue(resolve(Material.OAK_DOOR, door, Material.OAK_TRAPDOOR).isEmpty(),
			"a door identity never becomes a trapdoor");
		assertTrue(resolve(Material.OAK_TRAPDOOR, trapdoor, Material.OAK_TRAPDOOR).isEmpty(),
			"reskinning to the same material is not a craft");
	}

	private static Optional<DoorSkinRecipe.Result> resolve(
		Material sourceMaterial,
		DoorItemIdentity identity,
		Material targetMaterial)
	{
		return DoorSkinRecipe.resolve(
			List.of(
				new DoorSkinRecipe.Ingredient(sourceMaterial, identity),
				new DoorSkinRecipe.Ingredient(targetMaterial, null)),
			material -> WOODEN_TRAPDOORS.test(material) || DoorSkin.isDoor(material));
	}
}
