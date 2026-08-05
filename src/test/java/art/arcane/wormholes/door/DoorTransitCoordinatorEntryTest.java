package art.arcane.wormholes.door;

import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTransitCoordinatorEntryTest
{
	private static final UUID WORLD_ID = new UUID(7L, 11L);
	private static final UUID TRAVELER_ID = new UUID(3L, 5L);

	@Test
	void refusedEntryRegionReleasesTheClaimAndTellsTheTraveler()
	{
		Harness harness = new Harness((world, chunkX, chunkZ, task) -> false);

		harness.walkThroughDoor();

		assertFalse(harness.ledger.isTraveling(TRAVELER_ID));
		assertEquals(1, harness.messages.size());
		assertEquals(1L, harness.failures.failed());
		assertEquals(
			Map.of(DoorTransitFailures.Failure.ENTRY_REGION_UNAVAILABLE.name(), Long.valueOf(1L)),
			harness.failures.breakdown());
	}

	@Test
	void closingBeforeTheEntryBodyRunsReleasesTheClaimAndTellsTheTraveler()
	{
		List<Runnable> dispatched = new ArrayList<>();
		Harness harness = new Harness((world, chunkX, chunkZ, task) -> dispatched.add(task));

		harness.walkThroughDoor();
		assertTrue(harness.ledger.isTraveling(TRAVELER_ID));
		harness.guard.markClosed();
		dispatched.getFirst().run();

		assertFalse(harness.ledger.isTraveling(TRAVELER_ID));
		assertEquals(1, harness.messages.size());
		assertEquals(
			Map.of(DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN.name(), Long.valueOf(1L)),
			harness.failures.breakdown());
	}

	@Test
	void drainingBeforeTheEntryBodyRunsReleasesTheClaimAndTellsTheTraveler()
	{
		List<Runnable> dispatched = new ArrayList<>();
		Harness harness = new Harness((world, chunkX, chunkZ, task) -> dispatched.add(task));

		harness.walkThroughDoor();
		harness.guard.beginDrain();
		dispatched.getFirst().run();

		assertFalse(harness.ledger.isTraveling(TRAVELER_ID));
		assertEquals(1, harness.messages.size());
		assertEquals(
			Map.of(DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN.name(), Long.valueOf(1L)),
			harness.failures.breakdown());
	}

	@Test
	void entryDuringShutdownTellsTheTravelerWithoutStealingAnotherDoorsClaim()
	{
		Harness harness = new Harness(Harness.dispatchImmediately());
		harness.ledger.claim(harness.traveler);
		harness.guard.markClosed();

		harness.walkThroughDoor();

		assertTrue(harness.ledger.isTraveling(TRAVELER_ID));
		assertEquals(1, harness.messages.size());
		assertEquals(
			Map.of(DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN.name(), Long.valueOf(1L)),
			harness.failures.breakdown());
	}

	@Test
	void delayedAuthorizationDispatchesBoundaryAttemptWithoutReadingTravelerAgain()
	{
		List<Runnable> dispatched = new ArrayList<>();
		AtomicInteger chunkX = new AtomicInteger(Integer.MIN_VALUE);
		AtomicInteger chunkZ = new AtomicInteger(Integer.MIN_VALUE);
		Harness harness = new Harness((world, requestedChunkX, requestedChunkZ, task) ->
		{
			chunkX.set(requestedChunkX);
			chunkZ.set(requestedChunkZ);
			dispatched.add(task);
			return true;
		});
		UUID travelerId = new UUID(13L, 17L);
		Player shooter = Harness.responsiblePlayer();
		Entity traveler = Harness.projectile(shooter);
		DoorTransitAttempt attempt = harness.attempt(traveler, travelerId, 16);
		AtomicReference<Runnable> authorization = new AtomicReference<>();
		AtomicBoolean unavailable = new AtomicBoolean();
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			Harness.authorizerServer(shooter),
			(player, task, retired) ->
			{
				authorization.set(task);
				return true;
			});

		authorizer.resolve(
			traveler,
			credentials -> harness.coordinator.begin(attempt, credentials),
			() -> unavailable.set(true));

		assertEquals(Integer.MIN_VALUE, chunkX.get());
		assertFalse(harness.ledger.isTraveling(travelerId));

		authorization.get().run();

		assertEquals(1, chunkX.get());
		assertEquals(0, chunkZ.get());
		assertTrue(harness.ledger.isTraveling(travelerId));
		assertFalse(unavailable.get());

		harness.guard.markClosed();
		dispatched.getFirst().run();

		assertFalse(harness.ledger.isTraveling(travelerId));
	}

	private static final class Harness
	{
		private final DoorStateGuard guard;
		private final DoorTransitLedger ledger;
		private final DoorTransitFailures failures;
		private final DoorTransitCoordinator coordinator;
		private final List<Component> messages;
		private final Player traveler;
		private final World world;

		private Harness(DoorChunkLoader.RegionDispatch regions)
		{
			messages = new ArrayList<>();
			world = world();
			traveler = traveler(messages);
			Plugin plugin = plugin(server(world));
			Logger logger = Logger.getLogger(DoorTransitCoordinatorEntryTest.class.getName());
			logger.setLevel(Level.OFF);
			guard = new DoorStateGuard();
			ledger = new DoorTransitLedger(plugin);
			failures = new DoorTransitFailures(logger);
			PocketWorldService pocketWorldService = new PocketWorldService(plugin);
			DoorRuntimeIndex runtimes = new DoorRuntimeIndex(plugin, guard, pocketWorldService);
			DoorChunkLoader chunkLoader = new DoorChunkLoader(
				logger, guard::closed, (chunkWorld, chunkX, chunkZ) -> null, regions);
			PocketStructureService pocketStructures = new PocketStructureService();
			coordinator = new DoorTransitCoordinator(
				plugin,
				guard,
				ledger,
				runtimes,
				chunkLoader,
				regions,
				new DoorArrivalResolver(runtimes, chunkLoader),
				new DoorTicketService(plugin, guard),
				new DoorTravelerService(plugin, guard),
				new PocketSpaceIndex(pocketStructures),
				pocketStructures,
				pocketWorldService,
				failures);
		}

		private void walkThroughDoor()
		{
			coordinator.begin(
				attempt(traveler, TRAVELER_ID, 0),
				DoorAccessCredentials.ungated());
		}

		private DoorTransitAttempt attempt(Entity activeTraveler, UUID travelerId, int blockX)
		{
			DoorwayPlane plane = new DoorwayPlane(blockX, 64, 0, BlockFace.NORTH);
			PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
				new DoorPosition(WORLD_ID, "minecraft:overworld", blockX, 64, 0),
				new DoorItemIdentity(new UUID(1L, 2L), DoorKind.PUBLIC, null, null, null));
			VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
				WORLD_ID, plane, Door.Hinge.LEFT, true, false);
			RuntimeDoor runtime = new RuntimeDoor(endpoint);
			runtime.update(snapshot);
			DoorwayCrossing crossing = new DoorwayCrossing(
				plane.center(),
				1.0D,
				0.0D,
				1.0D,
				DoorwayCrossing.Direction.FRONT_TO_BACK);
			DoorTransit transit = new DoorTransit(
				plane,
				crossing,
				0.0F,
				0.0F,
				0.3D,
				1.8D,
				activeTraveler instanceof Player ? DoorTravelerClass.LIVING : DoorTravelerClass.OBJECT,
				null);
			return new DoorTransitAttempt(
				activeTraveler,
				travelerId,
				world,
				runtime,
				snapshot,
				transit);
		}

		private static DoorChunkLoader.RegionDispatch dispatchImmediately()
		{
			return (world, chunkX, chunkZ, task) ->
			{
				task.run();
				return true;
			};
		}

		private static World world()
		{
			return (World) Proxy.newProxyInstance(
				World.class.getClassLoader(),
				new Class<?>[]{World.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getUID" -> WORLD_ID;
					case "getName" -> "world";
					case "toString" -> "world";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> null;
				});
		}

		private static Player traveler(List<Component> messages)
		{
			return (Player) Proxy.newProxyInstance(
				Player.class.getClassLoader(),
				new Class<?>[]{Player.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getUniqueId" -> TRAVELER_ID;
					case "getName" -> "traveler";
					case "sendMessage" ->
					{
						if(arguments != null && arguments.length == 1 && arguments[0] instanceof Component component)
						{
							messages.add(component);
						}
						yield null;
					}
					case "toString" -> "traveler";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> null;
				});
		}

		private static Entity projectile(Player shooter)
		{
			AtomicBoolean shooterRead = new AtomicBoolean();
			return (Entity) Proxy.newProxyInstance(
				Projectile.class.getClassLoader(),
				new Class<?>[]{Projectile.class},
				(proxy, method, arguments) ->
				{
					if(method.getName().equals("getShooter") && shooterRead.compareAndSet(false, true))
					{
						return shooter;
					}
					throw new AssertionError("Delayed source-region handoff read Projectile." + method.getName());
				});
		}

		private static Player responsiblePlayer()
		{
			return (Player) Proxy.newProxyInstance(
				Player.class.getClassLoader(),
				new Class<?>[]{Player.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "isOnline" -> Boolean.TRUE;
					case "getUniqueId" -> new UUID(19L, 23L);
					case "hasPermission" -> Boolean.FALSE;
					case "toString" -> "shooter";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> throw new AssertionError("Unexpected shooter method " + method.getName());
				});
		}

		private static Server authorizerServer(Player shooter)
		{
			return (Server) Proxy.newProxyInstance(
				Server.class.getClassLoader(),
				new Class<?>[]{Server.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getPlayer" -> shooter;
					case "toString" -> "server";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> throw new AssertionError("Unexpected server method " + method.getName());
				});
		}

		private static Server server(World world)
		{
			return (Server) Proxy.newProxyInstance(
				Server.class.getClassLoader(),
				new Class<?>[]{Server.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getWorld" -> world;
					case "toString" -> "server";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> null;
				});
		}

		private static Plugin plugin(Server server)
		{
			Logger logger = Logger.getLogger(DoorTransitCoordinatorEntryTest.class.getName() + ".plugin");
			logger.setLevel(Level.OFF);
			return (Plugin) Proxy.newProxyInstance(
				Plugin.class.getClassLoader(),
				new Class<?>[]{Plugin.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getName", "namespace" -> "wormholes";
					case "getServer" -> server;
					case "getLogger" -> logger;
					case "isEnabled" -> Boolean.FALSE;
					case "toString" -> "plugin";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> null;
				});
		}
	}
}
