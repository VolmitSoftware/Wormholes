package art.arcane.wormholes;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.WormholesPlatform;

final class EffectDisplayRegistry
{
	private static final String EFFECT_ENTITY_TAG = "wormholes_fx";
	private final Set<UUID> temporaryDisplays = ConcurrentHashMap.newKeySet();
	private final Object displayLifecycleLock = new Object();
	private volatile boolean closing;

	static boolean isEffectEntity(Entity entity)
	{
		return entity instanceof Display && entity.getScoreboardTags().contains(EFFECT_ENTITY_TAG);
	}

	boolean isClosing()
	{
		return closing;
	}

	void cleanupOrphaned()
	{
		int rejected = 0;
		for(World world : Bukkit.getWorlds())
		{
			rejected += sweepOrphaned(world, (target, chunkX, chunkZ, task) -> FoliaScheduler.runRegion(Wormholes.instance, target, chunkX, chunkZ, task));
		}
		if(rejected > 0)
		{
			Wormholes.w("[EffectManager] orphan fx sweep could not reach " + rejected + " chunk(s); those fx displays stay until a later startup sweep reaches them");
		}
	}

	static int sweepOrphaned(World world, ChunkSweepDispatch dispatch)
	{
		int rejected = 0;
		for(Chunk chunk : world.getLoadedChunks())
		{
			if(!dispatch.dispatch(world, chunk.getX(), chunk.getZ(), () -> removeEffectEntities(chunk)))
			{
				rejected++;
			}
		}
		return rejected;
	}

	private static void removeEffectEntities(Chunk chunk)
	{
		for(Entity entity : chunk.getEntities())
		{
			if(isEffectEntity(entity))
			{
				entity.remove();
			}
		}
	}

	void track(Display display)
	{
		synchronized(displayLifecycleLock)
		{
			if(closing)
			{
				display.remove();
				return;
			}
			display.addScoreboardTag(EFFECT_ENTITY_TAG);
			temporaryDisplays.add(display.getUniqueId());
		}
	}

	void remove(Entity display)
	{
		if(display == null)
		{
			return;
		}
		temporaryDisplays.remove(display.getUniqueId());
		if(display.isValid())
		{
			display.remove();
		}
	}

	Set<UUID> beginShutdown()
	{
		synchronized(displayLifecycleLock)
		{
			if(closing)
			{
				return null;
			}
			closing = true;
			return new HashSet<UUID>(temporaryDisplays);
		}
	}

	void drain(Set<UUID> displays)
	{
		boolean folia = FoliaScheduler.isFoliaThreading(Bukkit.getServer());
		CountDownLatch removals = new CountDownLatch(displays.size());
		AtomicInteger abandoned = new AtomicInteger();
		for(UUID displayId : displays)
		{
			Entity entity = Bukkit.getEntity(displayId);
			boolean regionOwned = entity != null && (!folia || FoliaScheduler.isOwnedByCurrentRegion(entity));
			drainDisplay(displayId, entity, regionOwned, removed ->
			{
				if(!removed)
				{
					abandoned.incrementAndGet();
				}
				removals.countDown();
			}, (display, removal, retired) -> WormholesPlatform.scheduleEntity(Wormholes.instance, display, removal, retired, 1L));
		}
		if(folia && removals.getCount() > 0)
		{
			try
			{
				removals.await(1L, TimeUnit.SECONDS);
			}
			catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
		int leaked = abandoned.get();
		if(leaked > 0)
		{
			Wormholes.w("[EffectManager] " + leaked + " fx display(s) could not be removed on shutdown; they stay tagged and are cleared by the next startup sweep");
		}
	}

	void drainDisplay(UUID displayId, Entity display, boolean regionOwned, DrainCompletion completion, DisplayRemovalDispatch dispatch)
	{
		AtomicBoolean completed = new AtomicBoolean();
		if(display == null)
		{
			temporaryDisplays.remove(displayId);
			complete(completed, completion, true);
			return;
		}
		if(regionOwned)
		{
			remove(display);
			complete(completed, completion, true);
			return;
		}
		boolean scheduled = dispatch.schedule(display, () ->
		{
			remove(display);
			complete(completed, completion, true);
		}, () ->
		{
			temporaryDisplays.remove(displayId);
			complete(completed, completion, false);
		});
		if(!scheduled)
		{
			temporaryDisplays.remove(displayId);
			complete(completed, completion, false);
		}
	}

	private static void complete(AtomicBoolean completed, DrainCompletion completion, boolean removed)
	{
		if(completed.compareAndSet(false, true))
		{
			completion.drained(removed);
		}
	}

	@FunctionalInterface
	interface ChunkSweepDispatch
	{
		boolean dispatch(World world, int chunkX, int chunkZ, Runnable task);
	}

	@FunctionalInterface
	interface DisplayRemovalDispatch
	{
		boolean schedule(Entity display, Runnable removal, Runnable retired);
	}

	@FunctionalInterface
	interface DrainCompletion
	{
		void drained(boolean removed);
	}
}
