package art.arcane.wormholes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import art.arcane.wormholes.papi.PortalProximityIndex;
import art.arcane.wormholes.papi.WormholesPlaceholderPublisher;
import art.arcane.wormholes.papi.WormholesPlaceholders;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

final class PortalRegistryAttendance
{
	private static final double BASE_RANGE = 64.0D;
	private static final long PLACEHOLDER_PUBLISH_INTERVAL = 4L;

	private final Map<UUID, PlayerPosition> playerPositions = new ConcurrentHashMap<UUID, PlayerPosition>();
	private long refreshCount;

	void record(Player player, Location location)
	{
		if(location == null || location.getWorld() == null)
		{
			return;
		}
		playerPositions.put(player.getUniqueId(), new PlayerPosition(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
	}

	void forget(UUID playerId)
	{
		playerPositions.remove(playerId);
		WormholesPlaceholders placeholders = Wormholes.placeholders;
		if(placeholders != null)
		{
			placeholders.forget(playerId);
		}
	}

	void refresh(List<ILocalPortal> snapshot)
	{
		refreshCount++;
		WormholesPlaceholders placeholders = Wormholes.placeholders;
		PortalProximityIndex index = placeholders != null && (refreshCount % PLACEHOLDER_PUBLISH_INTERVAL) == 0L ? new PortalProximityIndex() : null;

		for(int portalIndex = 0; portalIndex < snapshot.size(); portalIndex++)
		{
			ILocalPortal portal = snapshot.get(portalIndex);
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
			for(Map.Entry<UUID, PlayerPosition> entry : playerPositions.entrySet())
			{
				PlayerPosition position = entry.getValue();
				if(!worldId.equals(position.worldId()))
				{
					continue;
				}
				double dx = center.getX() - position.x();
				double dy = center.getY() - position.y();
				double dz = center.getZ() - position.z();
				double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
				if(distanceSquared > thresholdSquared)
				{
					continue;
				}
				attended = true;
				if(index == null)
				{
					break;
				}
				index.offer(entry.getKey(), portalIndex, distanceSquared, PortalProximityIndex.facingCosine(position.yaw(), position.pitch(), dx, dy, dz, distanceSquared));
			}
			portal.setAmbientAttended(attended);
		}

		if(index != null)
		{
			WormholesPlaceholderPublisher.publish(placeholders, snapshot, index, playerPositions.keySet(), System.currentTimeMillis());
		}
	}

	private record PlayerPosition(UUID worldId, double x, double y, double z, float yaw, float pitch)
	{
	}
}
