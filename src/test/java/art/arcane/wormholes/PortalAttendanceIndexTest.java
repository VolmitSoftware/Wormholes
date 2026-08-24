package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.papi.PortalProximityIndex;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

public final class PortalAttendanceIndexTest
{
	@Test
	public void spatialMatchesRemainIdenticalToTheFullPortalSweep()
	{
		World world = world(UUID.fromString("931bdbe5-2fd2-4ea9-9fe8-09fbfa25f99f"));
		World otherWorld = world(UUID.fromString("10c787cc-a652-45c7-ad0e-35872603aeb9"));
		Random random = new Random(7843658921L);
		List<ILocalPortal> portals = new ArrayList<ILocalPortal>(400);
		for(int index = 0; index < 400; index++)
		{
			World portalWorld = index % 11 == 0 ? otherWorld : world;
			double x = random.nextDouble(-2000.0D, 2000.0D);
			double y = random.nextDouble(-32.0D, 320.0D);
			double z = random.nextDouble(-2000.0D, 2000.0D);
			double size = random.nextDouble(1.0D, 96.0D);
			portals.add(portal(portalWorld, x, y, z, size));
		}
		PortalAttendanceIndex spatial = PortalAttendanceIndex.capture(portals);

		for(int probe = 0; probe < 1_000; probe++)
		{
			double x = random.nextDouble(-2100.0D, 2100.0D);
			double y = random.nextDouble(-48.0D, 336.0D);
			double z = random.nextDouble(-2100.0D, 2100.0D);
			float yaw = random.nextFloat(-180.0F, 180.0F);
			float pitch = random.nextFloat(-90.0F, 90.0F);
			UUID playerId = new UUID(4L, probe + 1L);
			PortalProximityIndex expected = bruteForce(portals, world, playerId, x, y, z, yaw, pitch);
			PortalProximityIndex actual = new PortalProximityIndex();

			spatial.offerNearest(actual, playerId, world.getUID(), x, y, z, yaw, pitch);

			assertEquals(expected.match(playerId), actual.match(playerId), "probe " + probe);
		}
	}

	@Test
	public void aFartherFacingPortalStillBeatsTheNearestPortal()
	{
		World world = world(UUID.fromString("389580ed-df57-4077-9760-41e99405b5fd"));
		List<ILocalPortal> portals = new ArrayList<ILocalPortal>(65);
		for(int portalIndex = 0; portalIndex < 64; portalIndex++)
		{
			portals.add(portal(world, 2.0D + (portalIndex * 0.01D), 64.0D, 0.0D, 1.0D));
		}
		portals.add(portal(world, 0.0D, 64.0D, 50.0D, 1.0D));
		PortalAttendanceIndex spatial = PortalAttendanceIndex.capture(portals);
		PortalProximityIndex matches = new PortalProximityIndex();
		UUID playerId = UUID.fromString("3fb6a012-e95d-47ef-94c0-d45e376683c7");

		spatial.offerNearest(matches, playerId, world.getUID(), 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);

		assertEquals(64, matches.match(playerId).portalIndex());
	}

	@Test
	public void oneThousandColocatedPlayersAndPortalsAvoidTheQuadraticSweep()
	{
		World world = world(UUID.fromString("82324517-5136-43cb-bdf6-fda6ca06376b"));
		List<ILocalPortal> portals = new ArrayList<ILocalPortal>(1_000);
		for(int portalIndex = 0; portalIndex < 1_000; portalIndex++)
		{
			portals.add(portal(world, 0.0D, 70.0D, 10.0D, 1.0D));
		}
		PortalAttendanceIndex spatial = PortalAttendanceIndex.capture(portals);
		PortalProximityIndex matches = new PortalProximityIndex();
		long evaluated = 0L;

		for(int playerIndex = 0; playerIndex < 1_000; playerIndex++)
		{
			UUID playerId = new UUID(9L, playerIndex + 1L);
			evaluated += spatial.offerNearest(matches, playerId, world.getUID(),
				0.0D, 70.0D, 0.0D, 0.0F, 0.0F);
			PortalProximityIndex.Match match = matches.match(playerId);
			assertNotNull(match);
			assertEquals(0, match.portalIndex());
		}

		assertEquals(1_000, matches.size());
		assertTrue(evaluated <= 8_000L,
			"the dense lookup evaluated " + evaluated + " portal/player pairs instead of pruning tied subtrees");
	}

	private static PortalProximityIndex bruteForce(
		List<ILocalPortal> portals,
		World playerWorld,
		UUID playerId,
		double x,
		double y,
		double z,
		float yaw,
		float pitch)
	{
		PortalProximityIndex expected = new PortalProximityIndex();
		for(int portalIndex = 0; portalIndex < portals.size(); portalIndex++)
		{
			ILocalPortal portal = portals.get(portalIndex);
			Location center = portal.getCenter();
			if(!playerWorld.getUID().equals(center.getWorld().getUID()))
			{
				continue;
			}
			double dx = center.getX() - x;
			double dy = center.getY() - y;
			double dz = center.getZ() - z;
			double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
			double threshold = PortalAttendanceIndex.threshold(portal);
			if(distanceSquared <= threshold * threshold)
			{
				expected.offer(playerId, portalIndex, distanceSquared,
					PortalProximityIndex.facingCosine(yaw, pitch, dx, dy, dz, distanceSquared));
			}
		}
		return expected;
	}

	private static ILocalPortal portal(World world, double x, double y, double z, double size)
	{
		Location center = new Location(world, x, y, z);
		AxisAlignedBB area = new AxisAlignedBB(
			x - (size * 0.5D), x + (size * 0.5D),
			y - (size * 0.5D), y + (size * 0.5D),
			z - (size * 0.5D), z + (size * 0.5D));
		return (ILocalPortal) Proxy.newProxyInstance(
			PortalAttendanceIndexTest.class.getClassLoader(),
			new Class<?>[] {ILocalPortal.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getCenter" -> center;
				case "getArea" -> area;
				case "equals" -> proxy == arguments[0];
				case "hashCode" -> System.identityHashCode(proxy);
				case "toString" -> "PortalAttendanceIndexTestPortal";
				default -> defaultValue(method);
			});
	}

	private static World world(UUID worldId)
	{
		return (World) Proxy.newProxyInstance(
			PortalAttendanceIndexTest.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUID" -> worldId;
				case "equals" -> proxy == arguments[0];
				case "hashCode" -> worldId.hashCode();
				case "toString" -> "PortalAttendanceIndexTestWorld";
				default -> defaultValue(method);
			});
	}

	private static Object defaultValue(Method method)
	{
		Class<?> type = method.getReturnType();
		if(!type.isPrimitive())
		{
			return null;
		}
		if(type == boolean.class)
		{
			return false;
		}
		if(type == int.class)
		{
			return 0;
		}
		if(type == long.class)
		{
			return 0L;
		}
		if(type == double.class)
		{
			return 0.0D;
		}
		if(type == float.class)
		{
			return 0.0F;
		}
		if(type == short.class)
		{
			return (short) 0;
		}
		if(type == byte.class)
		{
			return (byte) 0;
		}
		if(type == char.class)
		{
			return (char) 0;
		}
		throw new IllegalStateException("Unsupported primitive return type " + type);
	}
}
