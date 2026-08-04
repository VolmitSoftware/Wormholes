package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class DoorSkinTest
{
	@Test
	void doorKindsUseTheirRequestedPlayerOperableDefaults()
	{
		assertEquals(Material.OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PAIR));
		assertEquals(Material.DARK_OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PERSONAL));
		assertEquals(Material.PALE_OAK_DOOR, DoorItemService.defaultMaterial(DoorKind.PUBLIC));
	}

	@Test
	void trapdoorFormsUseTheirOwnDefaults()
	{
		assertEquals(Material.OAK_TRAPDOOR, DoorItemService.defaultMaterial(DoorKind.PAIR, DoorForm.TRAPDOOR));
		assertEquals(
			Material.DARK_OAK_TRAPDOOR,
			DoorItemService.defaultMaterial(DoorKind.PERSONAL, DoorForm.TRAPDOOR));
		assertEquals(Material.PALE_OAK_TRAPDOOR, DoorItemService.defaultMaterial(DoorKind.PUBLIC, DoorForm.TRAPDOOR));
		assertEquals(
			Material.OAK_DOOR,
			DoorItemService.defaultMaterial(DoorKind.PAIR, DoorForm.DOOR),
			"the one-argument overload must stay the door form");
		assertThrows(IllegalArgumentException.class,
			() -> DoorItemService.defaultMaterial(DoorKind.RETURN, DoorForm.TRAPDOOR));
	}

	@Test
	void broadDoorRecognitionExistsOnlyForIdentityAndLegacySourceDetection()
	{
		assertTrue(DoorSkin.isDoor(Material.OAK_DOOR));
		assertTrue(DoorSkin.isDoor(Material.IRON_DOOR));
		assertFalse(DoorSkin.isDoor(Material.OAK_TRAPDOOR));
	}

	@Test
	void doorAndTrapdoorSuffixesNeverOverlap()
	{
		assertTrue(DoorSkin.isTrapdoor(Material.OAK_TRAPDOOR));
		assertTrue(DoorSkin.isTrapdoor(Material.IRON_TRAPDOOR));
		assertFalse(DoorSkin.isTrapdoor(Material.OAK_DOOR));
		assertTrue(DoorSkin.isDoorLike(Material.OAK_DOOR));
		assertTrue(DoorSkin.isDoorLike(Material.BAMBOO_TRAPDOOR));
		assertFalse(DoorSkin.isDoorLike(Material.STONE));
	}

	@Test
	void materialFormIsResolvedFromTheMaterialAlone()
	{
		assertEquals(DoorForm.DOOR, DoorSkin.formOf(Material.CRIMSON_DOOR));
		assertEquals(DoorForm.TRAPDOOR, DoorSkin.formOf(Material.CRIMSON_TRAPDOOR));
		assertNull(DoorSkin.formOf(Material.STONE));
	}

	@Test
	void creationRecipesAcceptEveryVanillaDoorMaterial()
	{
		Set<Material> actual = Set.copyOf(DoorSkin.doorMaterials());

		assertTrue(actual.contains(Material.OAK_DOOR));
		assertTrue(actual.contains(Material.IRON_DOOR));
		assertTrue(actual.contains(Material.COPPER_DOOR));
		assertTrue(actual.contains(Material.DARK_OAK_DOOR));
		assertTrue(actual.contains(Material.PALE_OAK_DOOR));
		assertFalse(actual.stream().anyMatch(Material::isLegacy));
	}

	@Test
	void trapdoorMaterialsAreDisjointFromDoorMaterials()
	{
		Set<Material> trapdoors = Set.copyOf(DoorSkin.trapdoorMaterials());

		assertTrue(trapdoors.contains(Material.OAK_TRAPDOOR));
		assertTrue(trapdoors.contains(Material.BAMBOO_TRAPDOOR));
		assertTrue(trapdoors.contains(Material.WARPED_TRAPDOOR));
		assertTrue(trapdoors.contains(Material.IRON_TRAPDOOR));
		assertFalse(trapdoors.contains(Material.OAK_DOOR));
		assertFalse(trapdoors.stream().anyMatch(Material::isLegacy));
		assertTrue(trapdoors.stream().noneMatch(DoorSkin.doorMaterials()::contains));
	}
}
