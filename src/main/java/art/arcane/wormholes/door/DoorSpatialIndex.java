package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk-bucketed index for placed doors. Movement checks query only the current
 * and adjacent chunks instead of scanning every dimensional door on the server.
 */
public final class DoorSpatialIndex<T>
{
	private final ConcurrentHashMap<UUID, Entry<T>> byDoorId;
	private final ConcurrentHashMap<ChunkKey, ConcurrentHashMap<UUID, Entry<T>>> byChunk;

	public DoorSpatialIndex()
	{
		byDoorId = new ConcurrentHashMap<>();
		byChunk = new ConcurrentHashMap<>();
	}

	public synchronized void put(
		UUID doorId,
		UUID worldId,
		int blockX,
		int blockY,
		int blockZ,
		T value)
	{
		Entry<T> replacement = new Entry<>(doorId, worldId, blockX, blockY, blockZ, value);
		Entry<T> previous = byDoorId.put(doorId, replacement);
		if(previous != null)
		{
			removeFromBucket(previous);
		}

		byChunk.computeIfAbsent(replacement.chunkKey(), ignored -> new ConcurrentHashMap<>())
			.put(doorId, replacement);
	}

	public synchronized Optional<Entry<T>> remove(UUID doorId)
	{
		Objects.requireNonNull(doorId, "doorId");
		Entry<T> removed = byDoorId.remove(doorId);
		if(removed != null)
		{
			removeFromBucket(removed);
		}
		return Optional.ofNullable(removed);
	}

	public Optional<Entry<T>> get(UUID doorId)
	{
		Objects.requireNonNull(doorId, "doorId");
		return Optional.ofNullable(byDoorId.get(doorId));
	}

	public List<Entry<T>> nearby(UUID worldId, int blockX, int blockZ, int chunkRadius)
	{
		Objects.requireNonNull(worldId, "worldId");
		if(chunkRadius < 0)
		{
			throw new IllegalArgumentException("Chunk radius cannot be negative");
		}

		int centerChunkX = Math.floorDiv(blockX, 16);
		int centerChunkZ = Math.floorDiv(blockZ, 16);
		ChunkLookup lookup = new ChunkLookup(worldId, centerChunkX, centerChunkZ);
		List<Entry<T>> found = null;
		for(int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++)
		{
			for(int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++)
			{
				lookup.move(chunkX, chunkZ);
				ConcurrentHashMap<UUID, Entry<T>> bucket = byChunk.get(lookup);
				if(bucket != null)
				{
					if(found == null)
					{
						found = new ArrayList<>();
					}
					found.addAll(bucket.values());
				}
			}
		}
		return found == null ? List.of() : found;
	}

	public int size()
	{
		return byDoorId.size();
	}

	public synchronized void clear()
	{
		byDoorId.clear();
		byChunk.clear();
	}

	private void removeFromBucket(Entry<T> entry)
	{
		ChunkKey key = entry.chunkKey();
		ConcurrentHashMap<UUID, Entry<T>> bucket = byChunk.get(key);
		if(bucket == null)
		{
			return;
		}

		bucket.remove(entry.doorId(), entry);
		if(bucket.isEmpty())
		{
			byChunk.remove(key, bucket);
		}
	}

	public record Entry<T>(
		UUID doorId,
		UUID worldId,
		int blockX,
		int blockY,
		int blockZ,
		T value)
	{
		public Entry
		{
			Objects.requireNonNull(doorId, "doorId");
			Objects.requireNonNull(worldId, "worldId");
			Objects.requireNonNull(value, "value");
		}

		public ChunkKey chunkKey()
		{
			return ChunkKey.fromBlock(worldId, blockX, blockZ);
		}
	}

	public record ChunkKey(UUID worldId, int chunkX, int chunkZ)
	{
		public ChunkKey
		{
			Objects.requireNonNull(worldId, "worldId");
		}

		public static ChunkKey fromBlock(UUID worldId, int blockX, int blockZ)
		{
			return new ChunkKey(
				worldId,
				Math.floorDiv(blockX, 16),
				Math.floorDiv(blockZ, 16));
		}

		@Override
		public int hashCode()
		{
			return chunkHash(worldId, chunkX, chunkZ);
		}
	}

	private static int chunkHash(UUID worldId, int chunkX, int chunkZ)
	{
		int result = worldId.hashCode();
		result = 31 * result + chunkX;
		return 31 * result + chunkZ;
	}

	private static final class ChunkLookup
	{
		private final UUID worldId;
		private int chunkX;
		private int chunkZ;

		private ChunkLookup(UUID worldId, int chunkX, int chunkZ)
		{
			this.worldId = worldId;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		private void move(int chunkX, int chunkZ)
		{
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		@Override
		public boolean equals(Object value)
		{
			return value instanceof ChunkKey key
				&& worldId.equals(key.worldId())
				&& chunkX == key.chunkX()
				&& chunkZ == key.chunkZ();
		}

		@Override
		public int hashCode()
		{
			return chunkHash(worldId, chunkX, chunkZ);
		}
	}
}
