package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.api.traversal.internal.TraversalCostPolicy;
import art.arcane.wormholes.api.traversal.internal.TraversalCostRegistration;
import art.arcane.wormholes.api.traversal.internal.TraversalEventSink;
import art.arcane.wormholes.chunk.presend.RecordingBukkitChunkPreSend;
import art.arcane.wormholes.util.Cuboid;

public final class LocalTraversalApiIntegrationTest
{
	@AfterEach
	public void clearGateway()
	{
		TraversalCostGateway gateway = Wormholes.traversalCostGateway;
		Wormholes.traversalCostGateway = null;
		if(gateway != null)
		{
			gateway.shutdown();
		}
	}

	@Test
	public void localTraversalBuildsContextAndCommitsAfterTeleport()
	{
		World world = LocalPortalTestSupport.world("traversal-api");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		destination.getStructure().setArea(new Cuboid(
				new Location(world, 20.0D, 64.0D, 0.0D), new Location(world, 20.0D, 66.0D, 2.0D)));
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
				"Traveler", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
				source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicReference<TraversalContext> captured = new AtomicReference<TraversalContext>();
		AtomicInteger commits = new AtomicInteger();
		Wormholes.traversalCostGateway = gateway(captured, commits);
		LocalPortalTraversal sourceTraversal = new LocalPortalTraversal(source, successfulRuntime());

		TraversalCostGateway.Admission admission = sourceTraversal.evaluateLocalTraversalCost(
				traveler.entity(), new LocalTunnel(destination), traversive);

		assertTrue(admission.allowed());
		assertEquals(TraversalKind.LOCAL, captured.get().kind());
		assertTrue(captured.get().destination().orElseThrow().sameServer());
		assertEquals(destination.getId(), captured.get().destination().orElseThrow().portalId().orElseThrow());
		new LocalPortalTraversal(destination, successfulRuntime()).receive(traversive, null, admission);
		assertEquals(1, commits.get());
		assertFalse(Wormholes.traversalCostGateway.isOpen(captured.get().traversalId()));
	}

	@Test
	public void deniedLocalTraversalNeverReservesOrMovesThePlayer()
	{
		World world = LocalPortalTestSupport.world("traversal-api-denied");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
				"Denied Traveler", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
				source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicInteger quotes = new AtomicInteger();
		AtomicInteger reservations = new AtomicInteger();
		Wormholes.traversalCostGateway = gateway(new TraversalCostProvider()
		{
			@Override
			public TraversalQuote quote(TraversalContext context)
			{
				quotes.incrementAndGet();
				return TraversalQuote.denied("closed for maintenance");
			}

			@Override
			public TraversalReservation reserve(TraversalContext context, TraversalQuote quote)
			{
				reservations.incrementAndGet();
				return TraversalReservation.reserved(TraversalReceipt.of("unexpected"));
			}
		});

		TraversalCostGateway.Admission admission = new LocalPortalTraversal(source, successfulRuntime())
				.evaluateLocalTraversalCost(traveler.entity(), new LocalTunnel(destination), traversive);

		assertFalse(admission.allowed());
		assertEquals(1, quotes.get());
		assertEquals(0, reservations.get());
		assertTrue(traveler.teleports().isEmpty());
	}

	@Test
	public void failedLocalTeleportRefundsTheApiReservationExactlyOnce()
	{
		World world = LocalPortalTestSupport.world("traversal-api-refund");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = silentPortal(world);
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
				"Refunded Traveler", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
				source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicInteger commits = new AtomicInteger();
		AtomicInteger refunds = new AtomicInteger();
		AtomicReference<TraversalRefundReason> reason = new AtomicReference<TraversalRefundReason>();
		Wormholes.traversalCostGateway = gateway(new TraversalCostProvider()
		{
			@Override
			public TraversalQuote quote(TraversalContext context)
			{
				return TraversalQuote.payable("one token");
			}

			@Override
			public TraversalReservation reserve(TraversalContext context, TraversalQuote quote)
			{
				return TraversalReservation.reserved(TraversalReceipt.of("local-refund"));
			}

			@Override
			public void commit(TraversalReceipt receipt)
			{
				commits.incrementAndGet();
			}

			@Override
			public void refund(TraversalReceipt receipt, TraversalRefundReason refundReason)
			{
				refunds.incrementAndGet();
				reason.set(refundReason);
			}
		});
		TraversalCostGateway.Admission admission = new LocalPortalTraversal(source, successfulRuntime())
				.evaluateLocalTraversalCost(traveler.entity(), new LocalTunnel(destination), traversive);

		new LocalPortalTraversal(destination, failedRuntime()).receive(traversive, null, admission);

		assertEquals(0, commits.get());
		assertEquals(1, refunds.get());
		assertEquals(TraversalRefundReason.TELEPORT_FAILED, reason.get());
		assertFalse(Wormholes.traversalCostGateway.isOpen(admission.decision().traversalId()));
	}

	@Test
	public void retiredArrivalRetriesAndCommitsBothCostsExactlyOnce()
	{
		World world = LocalPortalTestSupport.world("traversal-api-retired-arrival");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = silentPortal(world);
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
				"Retired Arrival", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
				source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicReference<TraversalContext> captured = new AtomicReference<TraversalContext>();
		AtomicInteger apiCommits = new AtomicInteger();
		AtomicInteger builtInCommits = new AtomicInteger();
		AtomicInteger builtInRefunds = new AtomicInteger();
		AtomicInteger dispatches = new AtomicInteger();
		Wormholes.traversalCostGateway = gateway(captured, apiCommits);
		TraversalCostGateway.Admission admission = new LocalPortalTraversal(source, successfulRuntime())
				.evaluateLocalTraversalCost(traveler.entity(), new LocalTunnel(destination), traversive);
		PortalTravelCost.Reservation reservation = new PortalTravelCost.Reservation()
		{
			@Override
			public void commit()
			{
				builtInCommits.incrementAndGet();
			}

			@Override
			public void refund()
			{
				builtInRefunds.incrementAndGet();
			}
		};
		LocalPortalRuntime runtime = new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				if(dispatches.incrementAndGet() == 1)
				{
					retired.run();
					return false;
				}
				task.run();
				return true;
			}

			@Override
			public boolean dispatchRegion(World targetWorld, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.TRUE);
			}
		};

		new LocalPortalTraversal(destination, runtime).receive(traversive, reservation, admission);

		assertEquals(2, dispatches.get());
		assertEquals(1, builtInCommits.get());
		assertEquals(0, builtInRefunds.get());
		assertEquals(1, apiCommits.get());
		assertFalse(Wormholes.traversalCostGateway.isOpen(captured.get().traversalId()));
	}

	@Test
	public void repeatedlyRetiredArrivalDefersTheSuccessfulApiCommit()
	{
		World world = LocalPortalTestSupport.world("traversal-api-retired-exhausted");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = silentPortal(world);
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
				"Exhausted Arrival", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
				source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicReference<TraversalContext> captured = new AtomicReference<TraversalContext>();
		AtomicInteger apiCommits = new AtomicInteger();
		AtomicInteger builtInCommits = new AtomicInteger();
		AtomicInteger dispatches = new AtomicInteger();
		Wormholes.traversalCostGateway = gateway(captured, apiCommits);
		TraversalCostGateway.Admission admission = new LocalPortalTraversal(source, successfulRuntime())
				.evaluateLocalTraversalCost(traveler.entity(), new LocalTunnel(destination), traversive);
		PortalTravelCost.Reservation reservation = new PortalTravelCost.Reservation()
		{
			@Override
			public void commit()
			{
				builtInCommits.incrementAndGet();
			}

			@Override
			public void refund()
			{
			}
		};
		LocalPortalRuntime runtime = new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				dispatches.incrementAndGet();
				retired.run();
				return false;
			}

			@Override
			public boolean dispatchRegion(World targetWorld, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				return false;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.TRUE);
			}
		};

		new LocalPortalTraversal(destination, runtime).receive(traversive, reservation, admission);

		assertEquals(5, dispatches.get());
		assertEquals(1, builtInCommits.get());
		assertEquals(1, apiCommits.get());
		assertFalse(Wormholes.traversalCostGateway.isOpen(captured.get().traversalId()));
	}

	@Test
	public void retiredTravelerDispatchRefundsTheApiAfterSourceRollback()
	{
		World world = LocalPortalTestSupport.world("traversal-api-retired-dispatch");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = silentPortal(world);
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
			"Retired Dispatch", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
			source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicInteger apiCommits = new AtomicInteger();
		AtomicInteger apiRefunds = new AtomicInteger();
		AtomicReference<TraversalRefundReason> apiReason = new AtomicReference<TraversalRefundReason>();
		AtomicInteger builtInRefunds = new AtomicInteger();
		AtomicInteger teleports = new AtomicInteger();
		AtomicInteger regionDispatches = new AtomicInteger();
		AtomicReference<String> owner = new AtomicReference<String>("traveler");
		Wormholes.traversalCostGateway = gateway(new TraversalCostProvider()
		{
			@Override
			public TraversalQuote quote(TraversalContext context)
			{
				return TraversalQuote.payable("one token");
			}

			@Override
			public TraversalReservation reserve(TraversalContext context, TraversalQuote quote)
			{
				return TraversalReservation.reserved(TraversalReceipt.of("retired-dispatch"));
			}

			@Override
			public void commit(TraversalReceipt receipt)
			{
				apiCommits.incrementAndGet();
			}

			@Override
			public void refund(TraversalReceipt receipt, TraversalRefundReason reason)
			{
				apiRefunds.incrementAndGet();
				apiReason.set(reason);
			}
		});
		TraversalCostGateway.Admission admission = new LocalPortalTraversal(source, successfulRuntime())
			.evaluateLocalTraversalCost(traveler.entity(), new LocalTunnel(destination), traversive);
		PortalTravelCost.Reservation reservation = new PortalTravelCost.Reservation()
		{
			@Override
			public void commit()
			{
			}

			@Override
			public void refund()
			{
				builtInRefunds.incrementAndGet();
			}
		};
		LocalPortalRuntime runtime = new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				retired.run();
				return false;
			}

			@Override
			public boolean dispatchRegion(World targetWorld, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				String previous = owner.get();
				owner.set(regionDispatches.incrementAndGet() == 1 ? "destination" : "source");
				try
				{
					task.run();
				}
				finally
				{
					owner.set(previous);
				}
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				teleports.incrementAndGet();
				return CompletableFuture.completedFuture(Boolean.TRUE);
			}
		};

		try(RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(owner::get))
		{
			new LocalPortalTraversal(destination, runtime).receive(traversive, reservation, admission);

			assertEquals(0, teleports.get());
			assertEquals(List.of("destination", "source"), recording.announcementOwners());
			assertEquals(1, builtInRefunds.get());
			assertEquals(0, apiCommits.get());
			assertEquals(1, apiRefunds.get());
			assertEquals(TraversalRefundReason.DESTINATION_UNAVAILABLE, apiReason.get());
			assertFalse(Wormholes.traversalCostGateway.isOpen(admission.decision().traversalId()));
		}
	}

	@Test
	public void nonPlayerLocalTraversalBypassesTheApiProvider()
	{
		World world = LocalPortalTestSupport.world("traversal-api-entity");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.entity(
				"Falling Block", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(
				source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		AtomicInteger quotes = new AtomicInteger();
		Wormholes.traversalCostGateway = gateway(context ->
		{
			quotes.incrementAndGet();
			return TraversalQuote.denied("players only");
		});

		TraversalCostGateway.Admission admission = new LocalPortalTraversal(source, successfulRuntime())
				.evaluateLocalTraversalCost(traveler.entity(), new LocalTunnel(destination), traversive);

		assertNull(admission);
		assertEquals(0, quotes.get());
	}

	private static TraversalCostGateway gateway(
			AtomicReference<TraversalContext> captured,
			AtomicInteger commits)
	{
		TraversalCostProvider provider = new TraversalCostProvider()
		{
			@Override
			public String providerId()
			{
				return "local-test";
			}

			@Override
			public TraversalQuote quote(TraversalContext context)
			{
				captured.set(context);
				return TraversalQuote.payable("one token");
			}

			@Override
			public TraversalReservation reserve(TraversalContext context, TraversalQuote quote)
			{
				return TraversalReservation.reserved(TraversalReceipt.of("local-test"));
			}

			@Override
			public void commit(TraversalReceipt receipt)
			{
				commits.incrementAndGet();
			}
		};
		return gateway(provider);
	}

	private static TraversalCostGateway gateway(TraversalCostProvider provider)
	{
		TraversalCostRegistration registration = TraversalCostRegistration.of(
				provider, "local-test", "LocalTraversalApiIntegrationTest", ServicePriority.Normal);
		return new TraversalCostGateway(
				() -> List.of(registration),
				TraversalCostPolicy::defaults,
				TraversalEventSink.NONE,
				Logger.getLogger("LocalTraversalApiIntegrationTest"),
				System::currentTimeMillis);
	}

	private static LocalPortal silentPortal(World world)
	{
		LocalPortal base = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		return new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, base.getStructure())
		{
			@Override
			public void playEffect(PortalEffect effect, Location location)
			{
			}
		};
	}

	private static LocalPortalRuntime failedRuntime()
	{
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}
		};
	}

	private static LocalPortalRuntime successfulRuntime()
	{
		return new LocalPortalRuntime()
		{
			@Override
			public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks)
			{
				task.run();
				return true;
			}

			@Override
			public CompletionStage<Boolean> teleport(Entity entity, Location target)
			{
				return CompletableFuture.completedFuture(Boolean.TRUE);
			}
		};
	}
}
