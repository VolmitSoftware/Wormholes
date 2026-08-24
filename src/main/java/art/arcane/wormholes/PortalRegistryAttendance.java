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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

final class PortalRegistryAttendance
{
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

	boolean hasPlayerWithin(
		UUID worldId,
		double x,
		double y,
		double z,
		double rangeSquared)
	{
		if(worldId == null
			|| !Double.isFinite(x)
			|| !Double.isFinite(y)
			|| !Double.isFinite(z)
			|| !Double.isFinite(rangeSquared)
			|| rangeSquared < 0.0D)
		{
			return false;
		}
		WorldCells worldCells = playerCellsByWorld.get(worldId);
		if(worldCells == null || worldCells.players.isEmpty())
		{
			return false;
		}
		double range = Math.sqrt(rangeSquared);
		int minimumCellX = cellCoordinate(x - range);
		int maximumCellX = cellCoordinate(x + range);
		int minimumCellZ = cellCoordinate(z - range);
		int maximumCellZ = cellCoordinate(z + range);
		long cellCount = ((long) maximumCellX - minimumCellX + 1L)
			* ((long) maximumCellZ - minimumCellZ + 1L);
		if(cellCount >= worldCells.players.size())
		{
			for(UUID playerId : worldCells.players.keySet())
			{
				if(isWithin(worldId, x, y, z, rangeSquared, playerPositions.get(playerId)))
				{
					return true;
				}
			}
			return false;
		}

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
					if(position != null
						&& worldId.equals(position.worldId())
						&& cellKey(position) == cell
						&& isWithin(worldId, x, y, z, rangeSquared, position))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	void refresh(List<ILocalPortal> snapshot)
	{
		refreshCount++;
		WormholesPlaceholders placeholders = Wormholes.placeholders;
		boolean publishPlaceholders = placeholders != null && (refreshCount % PLACEHOLDER_PUBLISH_INTERVAL) == 0L;
		PortalProximityIndex index = publishPlaceholders ? new PortalProximityIndex() : null;
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

		for(ILocalPortal portal : snapshot)
		{
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				portal.setAmbientAttended(false);
				continue;
			}

			double threshold = PortalAttendanceIndex.threshold(portal);
			double thresholdSquared = threshold * threshold;
			UUID worldId = center.getWorld().getUID();
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
					if(!isWithin(worldId, center.getX(), center.getY(), center.getZ(), thresholdSquared, position))
					{
						continue;
					}
					attended = true;
					break;
				}
			}
			else if(worldCells != null)
			{
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
								|| !isWithin(worldId, center.getX(), center.getY(), center.getZ(), thresholdSquared, position))
							{
								continue;
							}
							attended = true;
							break;
						}
						if(attended)
						{
							break;
						}
					}
					if(attended)
					{
						break;
					}
				}
			}
			portal.setAmbientAttended(attended);
		}

		if(index != null)
		{
			PortalAttendanceIndex portalIndex = PortalAttendanceIndex.capture(snapshot);
			for(Map.Entry<UUID, PlayerPosition> player : playerPositions.entrySet())
			{
				PlayerPosition position = player.getValue();
				portalIndex.offerNearest(index, player.getKey(), position.worldId(), position.x(), position.y(), position.z(), position.yaw(), position.pitch());
			}
			WormholesPlaceholderPublisher.publish(placeholders, snapshot, index, playerPositions.keySet(), System.currentTimeMillis());
		}
	}

	private static boolean isWithin(
		UUID worldId,
		double x,
		double y,
		double z,
		double rangeSquared,
		PlayerPosition position)
	{
		if(position == null || !worldId.equals(position.worldId()))
		{
			return false;
		}
		double dx = x - position.x();
		double dy = y - position.y();
		double dz = z - position.z();
		return (dx * dx) + (dy * dy) + (dz * dz) <= rangeSquared;
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

	private record PlayerPosition(UUID worldId, double x, double y, double z, float yaw, float pitch)
	{
	}

	private static final class WorldCells
	{
		private final Long2ObjectOpenHashMap<Map<UUID, Boolean>> cells = new Long2ObjectOpenHashMap<Map<UUID, Boolean>>();
		private final Map<UUID, Boolean> players = new ConcurrentHashMap<UUID, Boolean>();
	}
}
