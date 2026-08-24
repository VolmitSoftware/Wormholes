package art.arcane.wormholes.door;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DoorChunkLoaderTest
{
	private static final World WORLD = null;

	@Test
	void closedLoaderReportsChunkFailureInsteadOfDroppingTheTraversal()
	{
		Outcome outcome = new Outcome();
		AtomicBoolean closed = new AtomicBoolean(true);
		DoorChunkLoader loader = loader(closed, (world, chunkX, chunkZ) -> completed(chunkX, chunkZ), dispatchImmediately());

		loader.loadChunk(WORLD, 0, 0, outcome.success, outcome.failure);

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void closingWhileTheChunkFutureIsInFlightReportsFailure()
	{
		Outcome outcome = new Outcome();
		AtomicBoolean closed = new AtomicBoolean(false);
		List<CompletableFuture<Chunk>> pending = new ArrayList<>();
		DoorChunkLoader loader = loader(closed, (world, chunkX, chunkZ) ->
		{
			CompletableFuture<Chunk> future = new CompletableFuture<>();
			pending.add(future);
			return future;
		}, dispatchImmediately());

		loader.loadChunk(WORLD, 0, 0, outcome.success, outcome.failure);
		closed.set(true);
		pending.getFirst().complete(chunk(0, 0));

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void closingBeforeTheRegionBodyRunsReportsFailure()
	{
		Outcome outcome = new Outcome();
		AtomicBoolean closed = new AtomicBoolean(false);
		List<Runnable> dispatched = new ArrayList<>();
		DoorChunkLoader loader = loader(closed, (world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			(world, chunkX, chunkZ, task) -> dispatched.add(task));

		loader.loadChunk(WORLD, 0, 0, outcome.success, outcome.failure);
		closed.set(true);
		dispatched.getFirst().run();

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void refusedRegionDispatchReportsFailure()
	{
		Outcome outcome = new Outcome();
		DoorChunkLoader loader = loader(new AtomicBoolean(false), (world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			(world, chunkX, chunkZ, task) -> false);

		loader.loadChunk(WORLD, 0, 0, outcome.success, outcome.failure);

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void acceptedRegionTaskRetirementReportsFailureExactlyOnce()
	{
		Outcome outcome = new Outcome();
		DoorChunkLoader.RegionDispatch dispatch = new DoorChunkLoader.RegionDispatch()
		{
			@Override
			public boolean run(World world, int chunkX, int chunkZ, Runnable task)
			{
				return true;
			}

			@Override
			public boolean run(
				World world,
				int chunkX,
				int chunkZ,
				Runnable task,
				Runnable retired)
			{
				retired.run();
				retired.run();
				return true;
			}
		};
		DoorChunkLoader loader = loader(
			new AtomicBoolean(false),
			(world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			dispatch);

		loader.loadChunk(WORLD, 0, 0, outcome.success, outcome.failure);

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void failedChunkRequestReportsFailure()
	{
		Outcome outcome = new Outcome();
		DoorChunkLoader loader = loader(new AtomicBoolean(false), (world, chunkX, chunkZ) ->
		{
			throw new IllegalStateException("no chunk system");
		}, dispatchImmediately());

		loader.loadChunk(WORLD, 0, 0, outcome.success, outcome.failure);

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void openLoaderDeliversChunkSuccessExactlyOnce()
	{
		Outcome outcome = new Outcome();
		DoorChunkLoader loader = loader(new AtomicBoolean(false), (world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			dispatchImmediately());

		loader.loadChunk(WORLD, 33, -17, outcome.success, outcome.failure);

		assertEquals(1, outcome.successes.get());
		assertEquals(0, outcome.failures.get());
	}

	@Test
	void closedLoaderReportsPocketFailureInsteadOfDroppingTheTraversal()
	{
		Outcome outcome = new Outcome();
		PocketSpace space = space();
		DoorChunkLoader loader = loader(new AtomicBoolean(true), (world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			dispatchImmediately());

		loader.loadPocket(WORLD, space, new PocketLayout(space), outcome.success, outcome.failure);

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void closingWhilePocketChunksAreInFlightReportsFailure()
	{
		Outcome outcome = new Outcome();
		PocketSpace space = space();
		AtomicBoolean closed = new AtomicBoolean(false);
		List<CompletableFuture<Chunk>> pending = new ArrayList<>();
		DoorChunkLoader loader = loader(closed, (world, chunkX, chunkZ) ->
		{
			CompletableFuture<Chunk> future = new CompletableFuture<>();
			pending.add(future);
			return future;
		}, dispatchImmediately());

		loader.loadPocket(WORLD, space, new PocketLayout(space), outcome.success, outcome.failure);
		closed.set(true);
		for (CompletableFuture<Chunk> future : pending)
		{
			future.complete(chunk(0, 0));
		}

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void closingBeforeThePocketRegionBodyRunsReportsFailure()
	{
		Outcome outcome = new Outcome();
		PocketSpace space = space();
		AtomicBoolean closed = new AtomicBoolean(false);
		List<Runnable> dispatched = new ArrayList<>();
		DoorChunkLoader loader = loader(closed, (world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			(world, chunkX, chunkZ, task) -> dispatched.add(task));

		loader.loadPocket(WORLD, space, new PocketLayout(space), outcome.success, outcome.failure);
		closed.set(true);
		dispatched.getFirst().run();

		assertEquals(0, outcome.successes.get());
		assertEquals(1, outcome.failures.get());
	}

	@Test
	void openLoaderDeliversPocketSuccessExactlyOnce()
	{
		Outcome outcome = new Outcome();
		PocketSpace space = space();
		DoorChunkLoader loader = loader(new AtomicBoolean(false), (world, chunkX, chunkZ) -> completed(chunkX, chunkZ),
			dispatchImmediately());

		loader.loadPocket(WORLD, space, new PocketLayout(space), outcome.success, outcome.failure);

		assertEquals(1, outcome.successes.get());
		assertEquals(0, outcome.failures.get());
	}

	private static DoorChunkLoader loader(
		AtomicBoolean closed,
		DoorChunkLoader.ChunkRequest request,
		DoorChunkLoader.RegionDispatch dispatch)
	{
		Logger logger = Logger.getLogger(DoorChunkLoaderTest.class.getName());
		logger.setLevel(Level.OFF);
		return new DoorChunkLoader(logger, closed::get, request, dispatch);
	}

	private static DoorChunkLoader.RegionDispatch dispatchImmediately()
	{
		return (world, chunkX, chunkZ, task) ->
		{
			task.run();
			return true;
		};
	}

	private static CompletableFuture<Chunk> completed(int chunkX, int chunkZ)
	{
		return CompletableFuture.completedFuture(chunk(chunkX, chunkZ));
	}

	private static PocketSpace space()
	{
		return new PocketSpace(new UUID(0L, 1L), PocketBinding.personal(new UUID(0L, 2L)), 0L, 8, 128, 8, PocketShell.defaults());
	}

	private static Chunk chunk(int chunkX, int chunkZ)
	{
		return (Chunk) Proxy.newProxyInstance(
			Chunk.class.getClassLoader(),
			new Class<?>[]{Chunk.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getX" -> chunkX;
				case "getZ" -> chunkZ;
				case "toString" -> "chunk";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> null;
			});
	}

	private static final class Outcome
	{
		private final AtomicInteger successes = new AtomicInteger();
		private final AtomicInteger failures = new AtomicInteger();
		private final Runnable success = successes::incrementAndGet;
		private final Runnable failure = failures::incrementAndGet;
	}
}
