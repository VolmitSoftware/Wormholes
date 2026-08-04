package art.arcane.wormholes.door;

import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
			DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
			PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
				new DoorPosition(WORLD_ID, "minecraft:overworld", 0, 64, 0),
				new DoorItemIdentity(new UUID(1L, 2L), DoorKind.PUBLIC, null, null, null));
			VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
				WORLD_ID, plane, Door.Hinge.LEFT, true, false);
			RuntimeDoor runtime = new RuntimeDoor(endpoint);
			runtime.update(snapshot);
			coordinator.begin(
				traveler,
				runtime,
				new Location(world, 0.5D, 64.0D, 0.5D),
				new DoorwayCrossing(
					plane.center(),
					1.0D,
					0.0D,
					1.0D,
					DoorwayCrossing.Direction.FRONT_TO_BACK),
				snapshot);
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
