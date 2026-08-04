package art.arcane.wormholes.door;

import org.bukkit.Material;

import java.util.Objects;

final class DimensionalDoorSounds
{
	private DimensionalDoorSounds()
	{
	}

	static String teleportSound()
	{
		return teleportCue().key();
	}

	static String closeSound(Material doorMaterial)
	{
		return closeCue(doorMaterial).key();
	}

	static String openSound(Material doorMaterial)
	{
		return openCue(doorMaterial).key();
	}

	static String denyBassSound()
	{
		return denyBassCue().key();
	}

	static String denyThudSound()
	{
		return denyThudCue().key();
	}

	static String portalAmbientSound()
	{
		return portalAmbientCue().key();
	}

	static SoundCue teleportCue()
	{
		return SoundCue.PLAYER_TELEPORT;
	}

	static SoundCue closeCue(Material doorMaterial)
	{
		Objects.requireNonNull(doorMaterial, "doorMaterial");
		return switch(doorMaterial)
		{
			case IRON_DOOR -> SoundCue.IRON_DOOR_CLOSE;
			case CRIMSON_DOOR, WARPED_DOOR -> SoundCue.NETHER_WOOD_DOOR_CLOSE;
			case IRON_TRAPDOOR -> SoundCue.IRON_TRAPDOOR_CLOSE;
			case CRIMSON_TRAPDOOR, WARPED_TRAPDOOR -> SoundCue.NETHER_WOOD_TRAPDOOR_CLOSE;
			default -> DoorSkin.isTrapdoor(doorMaterial)
				? SoundCue.WOODEN_TRAPDOOR_CLOSE
				: SoundCue.WOODEN_DOOR_CLOSE;
		};
	}

	static SoundCue openCue(Material doorMaterial)
	{
		Objects.requireNonNull(doorMaterial, "doorMaterial");
		return switch(doorMaterial)
		{
			case IRON_DOOR -> SoundCue.IRON_DOOR_OPEN;
			case CRIMSON_DOOR, WARPED_DOOR -> SoundCue.NETHER_WOOD_DOOR_OPEN;
			case IRON_TRAPDOOR -> SoundCue.IRON_TRAPDOOR_OPEN;
			case CRIMSON_TRAPDOOR, WARPED_TRAPDOOR -> SoundCue.NETHER_WOOD_TRAPDOOR_OPEN;
			default -> DoorSkin.isTrapdoor(doorMaterial)
				? SoundCue.WOODEN_TRAPDOOR_OPEN
				: SoundCue.WOODEN_DOOR_OPEN;
		};
	}

	static SoundCue denyBassCue()
	{
		return SoundCue.DENY_BASS;
	}

	static SoundCue denyThudCue()
	{
		return SoundCue.DENY_THUD;
	}

	static SoundCue portalAmbientCue()
	{
		return SoundCue.PORTAL_AMBIENT;
	}

	enum SoundCue
	{
		PLAYER_TELEPORT("entity.player.teleport"),
		IRON_DOOR_CLOSE("block.iron_door.close"),
		NETHER_WOOD_DOOR_CLOSE("block.nether_wood_door.close"),
		WOODEN_DOOR_CLOSE("block.wooden_door.close"),
		IRON_DOOR_OPEN("block.iron_door.open"),
		NETHER_WOOD_DOOR_OPEN("block.nether_wood_door.open"),
		WOODEN_DOOR_OPEN("block.wooden_door.open"),
		IRON_TRAPDOOR_CLOSE("block.iron_trapdoor.close"),
		NETHER_WOOD_TRAPDOOR_CLOSE("block.nether_wood_trapdoor.close"),
		WOODEN_TRAPDOOR_CLOSE("block.wooden_trapdoor.close"),
		IRON_TRAPDOOR_OPEN("block.iron_trapdoor.open"),
		NETHER_WOOD_TRAPDOOR_OPEN("block.nether_wood_trapdoor.open"),
		WOODEN_TRAPDOOR_OPEN("block.wooden_trapdoor.open"),
		DENY_BASS("block.note_block.bass"),
		DENY_THUD("entity.warden.heartbeat"),
		PORTAL_AMBIENT("block.portal.ambient");

		private final String key;

		SoundCue(String key)
		{
			this.key = key;
		}

		String key()
		{
			return key;
		}
	}
}
