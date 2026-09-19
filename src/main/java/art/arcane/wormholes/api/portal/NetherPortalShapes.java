package art.arcane.wormholes.api.portal;

import java.util.Set;

import org.bukkit.Axis;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BlockVector;

public interface NetherPortalShapes
{
	Result submit(World world, Set<BlockVector> cells, Axis axis, Entity creator);

	boolean owns(World world, Set<BlockVector> cells);

	enum Result
	{
		ACCEPTED,
		UNAVAILABLE,
		REJECTED
	}

}
