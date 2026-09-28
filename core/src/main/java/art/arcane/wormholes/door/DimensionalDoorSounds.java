package art.arcane.wormholes.door;


import java.util.Objects;
import java.util.Locale;

public final class DimensionalDoorSounds
{
	private DimensionalDoorSounds()
	{
	}

	public static String teleportSound()
	{
		return teleportCue().key();
	}

	public static String closeSound(String doorMaterial)
	{
		return closeCue(doorMaterial).key();
	}

	public static String openSound(String doorMaterial)
	{
		return openCue(doorMaterial).key();
	}

	public static String denyBassSound()
	{
		return denyBassCue().key();
	}

	public static String denyThudSound()
	{
		return denyThudCue().key();
	}

	public static SoundCue teleportCue()
	{
		return SoundCue.PLAYER_TELEPORT;
	}

	public static SoundCue closeCue(String doorMaterial)
	{
		Objects.requireNonNull(doorMaterial, "doorMaterial");
		return switch(doorMaterial.toUpperCase(Locale.ROOT))
		{
			case "IRON_DOOR" -> SoundCue.IRON_DOOR_CLOSE;
			case "CRIMSON_DOOR", "WARPED_DOOR" -> SoundCue.NETHER_WOOD_DOOR_CLOSE;
			case "IRON_TRAPDOOR" -> SoundCue.IRON_TRAPDOOR_CLOSE;
			case "CRIMSON_TRAPDOOR", "WARPED_TRAPDOOR" -> SoundCue.NETHER_WOOD_TRAPDOOR_CLOSE;
			default -> doorMaterial.toUpperCase(Locale.ROOT).endsWith("_TRAPDOOR")
				? SoundCue.WOODEN_TRAPDOOR_CLOSE
				: SoundCue.WOODEN_DOOR_CLOSE;
		};
	}

	public static SoundCue openCue(String doorMaterial)
	{
		Objects.requireNonNull(doorMaterial, "doorMaterial");
		return switch(doorMaterial.toUpperCase(Locale.ROOT))
		{
			case "IRON_DOOR" -> SoundCue.IRON_DOOR_OPEN;
			case "CRIMSON_DOOR", "WARPED_DOOR" -> SoundCue.NETHER_WOOD_DOOR_OPEN;
			case "IRON_TRAPDOOR" -> SoundCue.IRON_TRAPDOOR_OPEN;
			case "CRIMSON_TRAPDOOR", "WARPED_TRAPDOOR" -> SoundCue.NETHER_WOOD_TRAPDOOR_OPEN;
			default -> doorMaterial.toUpperCase(Locale.ROOT).endsWith("_TRAPDOOR")
				? SoundCue.WOODEN_TRAPDOOR_OPEN
				: SoundCue.WOODEN_DOOR_OPEN;
		};
	}

	public static SoundCue denyBassCue()
	{
		return SoundCue.DENY_BASS;
	}

	public static SoundCue denyThudCue()
	{
		return SoundCue.DENY_THUD;
	}

	public enum SoundCue
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
		DENY_THUD("entity.warden.heartbeat");

		private final String key;

		SoundCue(String key)
		{
			this.key = key;
		}

		public String key()
		{
			return key;
		}
	}
}
