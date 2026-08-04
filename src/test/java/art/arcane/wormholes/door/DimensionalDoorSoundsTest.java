package art.arcane.wormholes.door;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DimensionalDoorSoundsTest
{
	@Test
	void teleportUsesThePlayerTeleportCue()
	{
		assertEquals(DimensionalDoorSounds.SoundCue.PLAYER_TELEPORT, DimensionalDoorSounds.teleportCue());
		assertEquals("entity.player.teleport", DimensionalDoorSounds.teleportCue().key());
		assertEquals("entity.player.teleport", DimensionalDoorSounds.teleportSound());
	}

	@Test
	void ironDoorsUseTheIronCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.IRON_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.IRON_DOOR));
		assertEquals("block.iron_door.close", DimensionalDoorSounds.closeCue(Material.IRON_DOOR).key());
		assertEquals("block.iron_door.close", DimensionalDoorSounds.closeSound(Material.IRON_DOOR));
	}

	@Test
	void netherWoodDoorsUseTheNetherWoodCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.CRIMSON_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.WARPED_DOOR));
		assertEquals("block.nether_wood_door.close", DimensionalDoorSounds.closeCue(Material.WARPED_DOOR).key());
	}

	@Test
	void otherDoorsUseTheWoodenCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.OAK_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.BAMBOO_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue(Material.PALE_OAK_DOOR));
		assertEquals("block.wooden_door.close", DimensionalDoorSounds.closeCue(Material.OAK_DOOR).key());
	}

	@Test
	void deniedAccessUsesTheDeepBassCue()
	{
		assertEquals(DimensionalDoorSounds.SoundCue.DENY_BASS, DimensionalDoorSounds.denyBassCue());
		assertEquals("block.note_block.bass", DimensionalDoorSounds.denyBassCue().key());
		assertEquals("block.note_block.bass", DimensionalDoorSounds.denyBassSound());
	}

	@Test
	void deniedAccessUsesTheWardenHeartbeatThudCue()
	{
		assertEquals(DimensionalDoorSounds.SoundCue.DENY_THUD, DimensionalDoorSounds.denyThudCue());
		assertEquals("entity.warden.heartbeat", DimensionalDoorSounds.denyThudCue().key());
		assertEquals("entity.warden.heartbeat", DimensionalDoorSounds.denyThudSound());
	}

	@Test
	void portalSurfaceUsesTheAmbientPortalCue()
	{
		assertEquals(DimensionalDoorSounds.SoundCue.PORTAL_AMBIENT, DimensionalDoorSounds.portalAmbientCue());
		assertEquals("block.portal.ambient", DimensionalDoorSounds.portalAmbientCue().key());
		assertEquals("block.portal.ambient", DimensionalDoorSounds.portalAmbientSound());
	}

	@Test
	void denyCuesAreDistinctFromDoorCloseCues()
	{
		assertNotEquals(DimensionalDoorSounds.denyBassSound(), DimensionalDoorSounds.denyThudSound());
		assertNotEquals(DimensionalDoorSounds.denyBassSound(), DimensionalDoorSounds.closeSound(Material.OAK_DOOR));
		assertNotEquals(DimensionalDoorSounds.denyThudSound(), DimensionalDoorSounds.teleportSound());
	}

	@Test
	void openCuesMirrorTheCloseCuesPerDoorMaterial()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.IRON_DOOR_OPEN,
			DimensionalDoorSounds.openCue(Material.IRON_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_OPEN,
			DimensionalDoorSounds.openCue(Material.CRIMSON_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_OPEN,
			DimensionalDoorSounds.openCue(Material.WARPED_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_OPEN,
			DimensionalDoorSounds.openCue(Material.OAK_DOOR));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_OPEN,
			DimensionalDoorSounds.openCue(Material.PALE_OAK_DOOR));
	}

	@Test
	void openCuesUseTheVanillaOpenSoundKeys()
	{
		assertEquals("block.iron_door.open", DimensionalDoorSounds.openSound(Material.IRON_DOOR));
		assertEquals("block.nether_wood_door.open", DimensionalDoorSounds.openSound(Material.WARPED_DOOR));
		assertEquals("block.wooden_door.open", DimensionalDoorSounds.openSound(Material.OAK_DOOR));
	}

	@Test
	void openAndCloseCuesAreNeverTheSame()
	{
		for(Material material : new Material[]{Material.IRON_DOOR, Material.CRIMSON_DOOR, Material.OAK_DOOR})
		{
			assertNotEquals(
				DimensionalDoorSounds.closeSound(material),
				DimensionalDoorSounds.openSound(material),
				material.name());
		}
	}

	@Test
	void closeCueRejectsNullMaterial()
	{
		assertThrows(NullPointerException.class, () -> DimensionalDoorSounds.closeCue(null));
	}

	@Test
	void openCueRejectsNullMaterial()
	{
		assertThrows(NullPointerException.class, () -> DimensionalDoorSounds.openCue(null));
	}
}
