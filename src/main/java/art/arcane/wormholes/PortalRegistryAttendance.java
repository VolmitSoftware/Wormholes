package art.arcane.wormholes;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

final class PortalRegistryAttendance
{
	private static final double BASE_RANGE = 64.0D;

	private final Map<UUID, PlayerPosition> playerPositions = new ConcurrentHashMap<UUID, PlayerPosition>();

	void record(Player player, Location location)
	{
		if(location == null || location.getWorld() == null)
		{
			return;
		}
		playerPositions.put(player.getUniqueId(), new PlayerPosition(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ()));
	}

	void forget(UUID playerId)
	{
		playerPositions.remove(playerId);
	}

	void refresh(List<ILocalPortal> snapshot)
	{
		Collection<PlayerPosition> positions = playerPositions.values();

		for(ILocalPortal portal : snapshot)
		{
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				portal.setAmbientAttended(false);
				continue;
			}

			AxisAlignedBB area = portal.getArea();
			double threshold = BASE_RANGE;
			if(area != null)
			{
				threshold += 0.5D * Math.sqrt((area.sizeX() * area.sizeX()) + (area.sizeY() * area.sizeY()) + (area.sizeZ() * area.sizeZ()));
			}
			double thresholdSquared = threshold * threshold;
			UUID worldId = center.getWorld().getUID();
			boolean attended = false;
			for(PlayerPosition position : positions)
			{
				if(!worldId.equals(position.worldId()))
				{
					continue;
				}
				double dx = position.x() - center.getX();
				double dy = position.y() - center.getY();
				double dz = position.z() - center.getZ();
				if((dx * dx) + (dy * dy) + (dz * dz) <= thresholdSquared)
				{
					attended = true;
					break;
				}
			}
			portal.setAmbientAttended(attended);
		}
	}

	private record PlayerPosition(UUID worldId, double x, double y, double z)
	{
	}
}
