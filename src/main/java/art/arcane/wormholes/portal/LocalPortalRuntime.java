package art.arcane.wormholes.portal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

interface LocalPortalRuntime
{
	LocalPortalRuntime BUKKIT = new LocalPortalRuntime()
	{
		@Override
		public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
		{
			return FoliaScheduler.runEntity(Wormholes.instance, entity, task, delayTicks, retired);
		}

		@Override
		public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
		{
			return BukkitRegionTaskProvider.run(world, chunkX, chunkZ, task, () -> { }, delayTicks);
		}

		@Override
		public boolean dispatchRegion(
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			Runnable retired,
			long delayTicks)
		{
			return BukkitRegionTaskProvider.run(world, chunkX, chunkZ, task, retired, delayTicks);
		}

		@Override
		public CompletionStage<Boolean> teleport(Entity entity, Location target)
		{
			if(Wormholes.instance == null)
			{
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}
			if(!FoliaScheduler.isFoliaThreading(Wormholes.instance.getServer())
				&& FoliaScheduler.isOwnedByCurrentRegion(entity) && destinationChunksLoaded(entity, target))
			{
				return CompletableFuture.completedFuture(entity.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN));
			}
			return WormholesPlatform.teleport(Wormholes.instance, entity, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
	};

	static boolean destinationChunksLoaded(Entity entity, Location target)
	{
		World world = target.getWorld();
		if(world == null)
		{
			return false;
		}
		Location current = entity.getLocation();
		BoundingBox bounds = entity.getBoundingBox();
		int minChunkX = (((int) Math.floor(target.getX() + bounds.getMinX() - current.getX() - 1.0E-7D)) - 3) >> 4;
		int maxChunkX = (((int) Math.floor(target.getX() + bounds.getMaxX() - current.getX() + 1.0E-7D)) + 3) >> 4;
		int minChunkZ = (((int) Math.floor(target.getZ() + bounds.getMinZ() - current.getZ() - 1.0E-7D)) - 3) >> 4;
		int maxChunkZ = (((int) Math.floor(target.getZ() + bounds.getMaxZ() - current.getZ() + 1.0E-7D)) + 3) >> 4;
		for(int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
		{
			for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
			{
				if(!world.isChunkLoaded(chunkX, chunkZ))
				{
					return false;
				}
			}
		}
		return true;
	}

	boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks);

	default boolean dispatch(Entity entity, Runnable task, long delayTicks)
	{
		return dispatch(entity, task, () -> { }, delayTicks);
	}

	boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks);

	default boolean dispatchRegion(
		World world,
		int chunkX,
		int chunkZ,
		Runnable task,
		Runnable retired,
		long delayTicks)
	{
		boolean scheduled = dispatchRegion(world, chunkX, chunkZ, task, delayTicks);
		if(!scheduled)
		{
			retired.run();
		}
		return scheduled;
	}

	CompletionStage<Boolean> teleport(Entity entity, Location target);
}
