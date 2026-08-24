package art.arcane.wormholes.portal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;

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
			return WormholesPlatform.teleport(Wormholes.instance, entity, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
	};

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
