package art.arcane.wormholes.door;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DoorChunkLoader
{
	private static final long LOAD_TIMEOUT_SECONDS = 10L;

	private final Logger logger;
	private final BooleanSupplier closed;
	private final ChunkRequest request;
	private final RegionDispatch dispatch;

	DoorChunkLoader(Logger logger, BooleanSupplier closed, ChunkRequest request, RegionDispatch dispatch)
	{
		this.logger = Objects.requireNonNull(logger, "logger");
		this.closed = Objects.requireNonNull(closed, "closed");
		this.request = Objects.requireNonNull(request, "request");
		this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
	}

	void loadChunk(World world, int blockX, int blockZ, Runnable success, Runnable failure)
	{
		if(closed.getAsBoolean())
		{
			failure.run();
			return;
		}
		CompletableFuture<Chunk> future;
		try
		{
			future = request.load(world, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
		}
		catch(Throwable ex)
		{
			logger.log(Level.WARNING, "Could not request dimensional-door chunk", ex);
			failure.run();
			return;
		}
		future.orTimeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((chunk, error) ->
		{
			if(closed.getAsBoolean())
			{
				failure.run();
				return;
			}
			if(error != null || chunk == null)
			{
				if(error != null)
				{
					logger.log(Level.WARNING, "Could not load dimensional-door chunk", error);
				}
				failure.run();
				return;
			}
			dispatchGuarded(world, chunk.getX(), chunk.getZ(), success, failure);
		});
	}

	void loadPocket(World world, PocketSpace space, PocketLayout layout, Runnable success, Runnable failure)
	{
		if(closed.getAsBoolean())
		{
			failure.run();
			return;
		}
		List<CompletableFuture<Chunk>> loads = new ArrayList<>();
		try
		{
			for(int chunkX = layout.minX() >> 4; chunkX <= layout.maxX() >> 4; chunkX++)
			{
				for(int chunkZ = layout.minZ() >> 4; chunkZ <= layout.maxZ() >> 4; chunkZ++)
				{
					loads.add(request.load(world, chunkX, chunkZ));
				}
			}
		}
		catch(Throwable ex)
		{
			logger.log(Level.WARNING, "Could not request pocket chunks", ex);
			failure.run();
			return;
		}
		CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
			.orTimeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, error) ->
		{
			if(closed.getAsBoolean())
			{
				failure.run();
				return;
			}
			if(error != null)
			{
				logger.log(Level.WARNING, "Could not load pocket chunks", error);
				failure.run();
				return;
			}
			dispatchGuarded(world, space.centerX() >> 4, space.centerZ() >> 4, success, failure);
		});
	}

	private void dispatchGuarded(World world, int chunkX, int chunkZ, Runnable success, Runnable failure)
	{
		AtomicBoolean pending = new AtomicBoolean(true);
		Runnable completion = () ->
		{
			if(!pending.compareAndSet(true, false))
			{
				return;
			}
			if(closed.getAsBoolean())
			{
				failure.run();
				return;
			}
			success.run();
		};
		Runnable retired = () ->
		{
			if(pending.compareAndSet(true, false))
			{
				failure.run();
			}
		};
		if(!dispatch.run(world, chunkX, chunkZ, completion, retired))
		{
			retired.run();
		}
	}

	@FunctionalInterface
	interface ChunkRequest
	{
		CompletableFuture<Chunk> load(World world, int chunkX, int chunkZ);
	}

	@FunctionalInterface
	interface RegionDispatch
	{
		boolean run(World world, int chunkX, int chunkZ, Runnable task);

		default boolean run(
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			Runnable retired)
		{
			boolean scheduled = run(world, chunkX, chunkZ, task);
			if(!scheduled)
			{
				retired.run();
			}
			return scheduled;
		}
	}
}
