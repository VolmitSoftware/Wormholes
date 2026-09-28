package art.arcane.wormholes.door;

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
			DimensionalDoorSounds.closeCue("IRON_DOOR"));
		assertEquals("block.iron_door.close", DimensionalDoorSounds.closeCue("IRON_DOOR").key());
		assertEquals("block.iron_door.close", DimensionalDoorSounds.closeSound("IRON_DOOR"));
	}

	@Test
	void netherWoodDoorsUseTheNetherWoodCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue("CRIMSON_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue("WARPED_DOOR"));
		assertEquals("block.nether_wood_door.close", DimensionalDoorSounds.closeCue("WARPED_DOOR").key());
	}

	@Test
	void otherDoorsUseTheWoodenCloseCue()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue("OAK_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue("BAMBOO_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_CLOSE,
			DimensionalDoorSounds.closeCue("PALE_OAK_DOOR"));
		assertEquals("block.wooden_door.close", DimensionalDoorSounds.closeCue("OAK_DOOR").key());
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
	void denyCuesAreDistinctFromDoorCloseCues()
	{
		assertNotEquals(DimensionalDoorSounds.denyBassSound(), DimensionalDoorSounds.denyThudSound());
		assertNotEquals(DimensionalDoorSounds.denyBassSound(), DimensionalDoorSounds.closeSound("OAK_DOOR"));
		assertNotEquals(DimensionalDoorSounds.denyThudSound(), DimensionalDoorSounds.teleportSound());
	}

	@Test
	void openCuesMirrorTheCloseCuesPerDoorMaterial()
	{
		assertEquals(
			DimensionalDoorSounds.SoundCue.IRON_DOOR_OPEN,
			DimensionalDoorSounds.openCue("IRON_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_OPEN,
			DimensionalDoorSounds.openCue("CRIMSON_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.NETHER_WOOD_DOOR_OPEN,
			DimensionalDoorSounds.openCue("WARPED_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_OPEN,
			DimensionalDoorSounds.openCue("OAK_DOOR"));
		assertEquals(
			DimensionalDoorSounds.SoundCue.WOODEN_DOOR_OPEN,
			DimensionalDoorSounds.openCue("PALE_OAK_DOOR"));
	}

	@Test
	void openCuesUseTheVanillaOpenSoundKeys()
	{
		assertEquals("block.iron_door.open", DimensionalDoorSounds.openSound("IRON_DOOR"));
		assertEquals("block.nether_wood_door.open", DimensionalDoorSounds.openSound("WARPED_DOOR"));
		assertEquals("block.wooden_door.open", DimensionalDoorSounds.openSound("OAK_DOOR"));
	}

	@Test
	void openAndCloseCuesAreNeverTheSame()
	{
		for(String material : new String[]{"IRON_DOOR", "CRIMSON_DOOR", "OAK_DOOR"})
		{
			assertNotEquals(
				DimensionalDoorSounds.closeSound(material),
				DimensionalDoorSounds.openSound(material),
				material);
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
