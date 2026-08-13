package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.Tag;

final class DoorSkin
{
	private DoorSkin()
	{
	}

	static boolean isDoor(Material material)
	{
		Objects.requireNonNull(material, "material");
		return material.name().endsWith("_DOOR");
	}

	/** "OAK_TRAPDOOR" does not end with "_DOOR", so the two suffixes never overlap. */
	static boolean isTrapdoor(Material material)
	{
		Objects.requireNonNull(material, "material");
		return material.name().endsWith("_TRAPDOOR");
	}

	static boolean isDoorLike(Material material)
	{
		return isDoor(material) || isTrapdoor(material);
	}

	/** @return the form this material can carry, or null when it is not door-like */
	static DoorForm formOf(Material material)
	{
		if(isDoor(material))
		{
			return DoorForm.DOOR;
		}
		return isTrapdoor(material) ? DoorForm.TRAPDOOR : null;
	}

	static boolean isPlayerOperableTrapdoor(Material material)
	{
		Objects.requireNonNull(material, "material");
		return Tag.WOODEN_TRAPDOORS.isTagged(material);
	}

	static boolean isSupportedSkin(Material material)
	{
		return isSupportedSkin(material, formOf(material));
	}

	static boolean isSupportedSkin(Material material, DoorForm form)
	{
		if(form == null)
		{
			return false;
		}
		return switch(form)
		{
			case DOOR -> isDoor(material);
			case TRAPDOOR -> isPlayerOperableTrapdoor(material);
		};
	}

	static List<Material> doorMaterials()
	{
		return materials(DoorSkin::isDoor);
	}

	static List<Material> trapdoorMaterials()
	{
		return materials(DoorSkin::isTrapdoor);
	}

	static List<Material> playerOperableTrapdoorMaterials()
	{
		return materials(DoorSkin::isPlayerOperableTrapdoor);
	}

	private static List<Material> materials(Predicate<Material> accepted)
	{
		ArrayList<Material> materials = new ArrayList<>();
		for(Material material : Material.values())
		{
			if(!material.isLegacy() && accepted.test(material))
			{
				materials.add(material);
			}
		}
		return List.copyOf(materials);
	}
}
