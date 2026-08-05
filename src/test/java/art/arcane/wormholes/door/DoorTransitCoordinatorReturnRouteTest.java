package art.arcane.wormholes.door;

import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTransitCoordinatorReturnRouteTest
{
	@Test
	void relocatedSourceInsideAnotherPocketCannotReplaceTheSavedReturnRoute()
	{
		PlacedDoorEndpoint source = endpoint(DoorItemIdentity.personal(UUID.randomUUID()));

		assertFalse(DoorTransitCoordinator.canRouteReturnToCurrentEndpoint(
			source,
			world(PocketWorldService.WORLD_KEY)));
	}

	@Test
	void liveNonPocketSourceCanStillReplaceTheSavedReturnRoute()
	{
		PlacedDoorEndpoint source = endpoint(DoorItemIdentity.personal(UUID.randomUUID()));

		assertTrue(DoorTransitCoordinator.canRouteReturnToCurrentEndpoint(
			source,
			world(NamespacedKey.minecraft("overworld"))));
	}

	@Test
	void missingWorldAndReturnEndpointsFallBackToTheSavedTicket()
	{
		PlacedDoorEndpoint source = endpoint(DoorItemIdentity.personal(UUID.randomUUID()));
		PlacedDoorEndpoint returnDoor = endpoint(DoorItemIdentity.returnDoor(
			UUID.randomUUID(), UUID.randomUUID()));

		assertFalse(DoorTransitCoordinator.canRouteReturnToCurrentEndpoint(source, null));
		assertFalse(DoorTransitCoordinator.canRouteReturnToCurrentEndpoint(
			returnDoor,
			world(NamespacedKey.minecraft("overworld"))));
	}

	private static PlacedDoorEndpoint endpoint(DoorItemIdentity identity)
	{
		return new PlacedDoorEndpoint(
			new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 0, 64, 0),
			identity);
	}

	private static World world(NamespacedKey key)
	{
		return (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getKey"))
				{
					return key;
				}
				throw new AssertionError("Unexpected world method " + method.getName());
			});
	}
}
