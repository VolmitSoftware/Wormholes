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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

final class PortalRegistryAttendance
{
	private static final double BASE_RANGE = 64.0D;
	private static final int CELL_SIZE = 64;
	private static final long PLACEHOLDER_PUBLISH_INTERVAL = 4L;

	private final Map<UUID, PlayerPosition> playerPositions = new ConcurrentHashMap<UUID, PlayerPosition>();
	private final Map<UUID, WorldCells> playerCellsByWorld = new ConcurrentHashMap<UUID, WorldCells>();
	private long refreshCount;

	void record(Player player, Location location)
	{
		if(location == null || location.getWorld() == null)
		{
			return;
		}
		UUID playerId = player.getUniqueId();
		PlayerPosition updated = new PlayerPosition(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
		PlayerPosition previous = playerPositions.get(playerId);
		long updatedCell = cellKey(updated);
		if(previous != null)
		{
			if(previous.worldId().equals(updated.worldId()) && cellKey(previous) == updatedCell)
			{
				playerPositions.put(playerId, updated);
				return;
			}
		}
		addToCell(updated, updatedCell, playerId);
		playerPositions.put(playerId, updated);
		if(previous != null)
		{
			removeFromCell(previous, playerId, !previous.worldId().equals(updated.worldId()));
		}
	}

	private void addToCell(PlayerPosition position, long cell, UUID playerId)
	{
		WorldCells worldCells = playerCellsByWorld.computeIfAbsent(position.worldId(), ignored -> new WorldCells());
		synchronized(worldCells)
		{
			Map<UUID, Boolean> cellPlayers = worldCells.cells.get(cell);
			if(cellPlayers == null)
			{
				cellPlayers = new ConcurrentHashMap<UUID, Boolean>();
				worldCells.cells.put(cell, cellPlayers);
			}
			cellPlayers.put(playerId, Boolean.TRUE);
			worldCells.players.put(playerId, Boolean.TRUE);
		}
	}

	void forget(UUID playerId)
	{
		PlayerPosition removed = playerPositions.remove(playerId);
		if(removed != null)
		{
			removeFromCell(removed, playerId, true);
		}
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
		if(playerPositions.isEmpty())
		{
			for(ILocalPortal portal : snapshot)
			{
				portal.setAmbientAttended(false);
			}
			if(index != null)
			{
				WormholesPlaceholderPublisher.publish(placeholders, snapshot, index, playerPositions.keySet(), System.currentTimeMillis());
			}
			return;
		}

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
			PortalQuery query = new PortalQuery(index, worldId, center, thresholdSquared, portalIndex);
			boolean attended = false;
			int minimumCellX = cellCoordinate(center.getX() - threshold);
			int maximumCellX = cellCoordinate(center.getX() + threshold);
			int minimumCellZ = cellCoordinate(center.getZ() - threshold);
			int maximumCellZ = cellCoordinate(center.getZ() + threshold);
			long cellCount = ((long) maximumCellX - minimumCellX + 1L)
				* ((long) maximumCellZ - minimumCellZ + 1L);
			WorldCells worldCells = playerCellsByWorld.get(worldId);
			if(worldCells != null && cellCount >= worldCells.players.size())
			{
				for(UUID playerId : worldCells.players.keySet())
				{
					PlayerPosition position = playerPositions.get(playerId);
					if(position == null || !offerIfWithin(query, playerId, position))
					{
						continue;
					}
					attended = true;
					if(index == null)
					{
						break;
					}
				}
			}
			else if(worldCells != null)
			{
				cellScan:
				for(int cellX = minimumCellX; cellX <= maximumCellX; cellX++)
				{
					for(int cellZ = minimumCellZ; cellZ <= maximumCellZ; cellZ++)
					{
						long cell = cellKey(cellX, cellZ);
						Map<UUID, Boolean> cellPlayers;
						synchronized(worldCells)
						{
							cellPlayers = worldCells.cells.get(cell);
						}
						if(cellPlayers == null)
						{
							continue;
						}
						for(UUID playerId : cellPlayers.keySet())
						{
							PlayerPosition position = playerPositions.get(playerId);
							if(position == null || !worldId.equals(position.worldId()) || cellKey(position) != cell
								|| !offerIfWithin(query, playerId, position))
							{
								continue;
							}
							attended = true;
							if(index == null)
							{
								break cellScan;
							}
						}
					}
				}
			}
			portal.setAmbientAttended(attended);
		}

		if(index != null)
		{
			WormholesPlaceholderPublisher.publish(placeholders, snapshot, index, playerPositions.keySet(), System.currentTimeMillis());
		}
	}

	private static boolean offerIfWithin(
		PortalQuery query,
		UUID playerId,
		PlayerPosition position
	)
	{
		if(!query.worldId().equals(position.worldId()))
		{
			return false;
		}
		double dx = query.center().getX() - position.x();
		double dy = query.center().getY() - position.y();
		double dz = query.center().getZ() - position.z();
		double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
		if(distanceSquared > query.thresholdSquared())
		{
			return false;
		}
		if(query.index() != null)
		{
			query.index().offer(playerId, query.portalIndex(), distanceSquared, PortalProximityIndex.facingCosine(position.yaw(), position.pitch(), dx, dy, dz, distanceSquared));
		}
		return true;
	}

	private void removeFromCell(PlayerPosition position, UUID playerId, boolean removeFromWorld)
	{
		WorldCells worldCells = playerCellsByWorld.get(position.worldId());
		if(worldCells == null)
		{
			return;
		}
		synchronized(worldCells)
		{
			long cell = cellKey(position);
			Map<UUID, Boolean> cellPlayers = worldCells.cells.get(cell);
			if(cellPlayers == null)
			{
				if(removeFromWorld)
				{
					worldCells.players.remove(playerId);
				}
				return;
			}
			cellPlayers.remove(playerId);
			if(removeFromWorld)
			{
				worldCells.players.remove(playerId);
			}
			if(!cellPlayers.isEmpty())
			{
				return;
			}
			worldCells.cells.remove(cell);
		}
	}

	private static int cellCoordinate(double coordinate)
	{
		return (int) Math.floor(coordinate / CELL_SIZE);
	}

	private static long cellKey(PlayerPosition position)
	{
		return cellKey(cellCoordinate(position.x()), cellCoordinate(position.z()));
	}

	private static long cellKey(int x, int z)
	{
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	private record PortalQuery(
		PortalProximityIndex index,
		UUID worldId,
		Location center,
		double thresholdSquared,
		int portalIndex
	)
	{
	}

	private record PlayerPosition(UUID worldId, double x, double y, double z, float yaw, float pitch)
	{
	}

	private static final class WorldCells
	{
		private final Long2ObjectOpenHashMap<Map<UUID, Boolean>> cells = new Long2ObjectOpenHashMap<Map<UUID, Boolean>>();
		private final Map<UUID, Boolean> players = new ConcurrentHashMap<UUID, Boolean>();
	}
}
