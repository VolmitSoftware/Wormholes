package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class DoorSkinRecipeTest
{
	private static final Predicate<Material> WOODEN_SKIN = Set.of(
		Material.OAK_DOOR,
		Material.SPRUCE_DOOR,
		Material.BIRCH_DOOR,
		Material.CHERRY_DOOR,
		Material.OAK_TRAPDOOR,
		Material.SPRUCE_TRAPDOOR,
		Material.BAMBOO_TRAPDOOR,
		Material.WARPED_TRAPDOOR)::contains;

	@Test
	void changesOnlyTheMaterialAndPreservesIdentity()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID());

		DoorSkinRecipe.Result result = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.CHERRY_DOOR, null)), WOODEN_SKIN).orElseThrow();

		assertEquals(identity, result.identity());
		assertEquals(Material.CHERRY_DOOR, result.material());
	}

	@Test
	void legacyIronIdentityCanBeConvertedToAPlayerOperableSkin()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID());

		DoorSkinRecipe.Result result = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, null),
			new DoorSkinRecipe.Ingredient(Material.IRON_DOOR, identity)), WOODEN_SKIN).orElseThrow();

		assertEquals(identity, result.identity());
		assertEquals(Material.OAK_DOOR, result.material());
	}

	@Test
	void aDoorIdentityReskinsOntoIronOrCopper()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID());

		DoorSkinRecipe.Result iron = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.PALE_OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.IRON_DOOR, null))).orElseThrow();
		DoorSkinRecipe.Result copper = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.PALE_OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.COPPER_DOOR, null))).orElseThrow();

		assertEquals(identity, iron.identity());
		assertEquals(Material.IRON_DOOR, iron.material());
		assertEquals(identity, copper.identity());
		assertEquals(Material.COPPER_DOOR, copper.material());
	}

	@Test
	void poweredTargetDuplicateIdentitiesAndExtraItemsAreRejected()
	{
		DoorItemIdentity identity = DoorItemIdentity.newPersonal();
		DoorItemIdentity secondIdentity = DoorItemIdentity.newPersonal();

		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.IRON_DOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, secondIdentity)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, null),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, null),
			new DoorSkinRecipe.Ingredient(Material.BIRCH_DOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, null)), WOODEN_SKIN).isEmpty());
	}

	@Test
	void trapdoorIdentitiesReskinIntoOtherHandOpenableTrapdoors()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID(), DoorForm.TRAPDOOR);

		DoorSkinRecipe.Result result = DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_TRAPDOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.WARPED_TRAPDOOR, null)), WOODEN_SKIN).orElseThrow();

		assertEquals(identity, result.identity());
		assertEquals(Material.WARPED_TRAPDOOR, result.material());
	}

	@Test
	void redstoneOnlyTrapdoorsAreNeverAValidSkin()
	{
		DoorItemIdentity identity = DoorItemIdentity.publicDoor(UUID.randomUUID(), DoorForm.TRAPDOOR);

		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_TRAPDOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.IRON_TRAPDOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_TRAPDOOR, identity),
			new DoorSkinRecipe.Ingredient(Material.COPPER_TRAPDOOR, null)), WOODEN_SKIN).isEmpty());
	}

	@Test
	void skinningNeverCrossesBetweenDoorsAndTrapdoors()
	{
		DoorItemIdentity doorIdentity = DoorItemIdentity.newPersonal();
		DoorItemIdentity trapdoorIdentity = DoorItemIdentity.newPersonal(DoorForm.TRAPDOOR);

		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, doorIdentity),
			new DoorSkinRecipe.Ingredient(Material.OAK_TRAPDOOR, null)), WOODEN_SKIN).isEmpty());
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_TRAPDOOR, trapdoorIdentity),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_DOOR, null)), WOODEN_SKIN).isEmpty());
		// a trapdoor identity stamped onto a door item cannot launder itself back
		assertTrue(DoorSkinRecipe.resolve(List.of(
			new DoorSkinRecipe.Ingredient(Material.OAK_DOOR, trapdoorIdentity),
			new DoorSkinRecipe.Ingredient(Material.SPRUCE_TRAPDOOR, null)), WOODEN_SKIN).isEmpty());
	}
}
