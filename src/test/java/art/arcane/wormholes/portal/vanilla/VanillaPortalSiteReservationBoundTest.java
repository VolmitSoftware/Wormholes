package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public final class VanillaPortalSiteReservationBoundTest
{
	private static final int SATURATION_ATTEMPTS = 400;

	@Test
	@Timeout(60)
	public void netherSiteSearchGivesUpInsteadOfWalkingForeverUnderTheReservationLock()
	{
		VanillaPortalNetherSites sites = new VanillaPortalNetherSites();
		World world = world();
		Set<VanillaPortalNetherSites.BuildTarget> reserved = new HashSet<VanillaPortalNetherSites.BuildTarget>();
		boolean exhausted = false;

		for(int attempt = 0; attempt < SATURATION_ATTEMPTS; attempt++)
		{
			VanillaPortalNetherSites.BuildTarget target = sites.reserve(world, 0, 0, 2, 3, Set.of());
			if(target == null)
			{
				exhausted = true;
				break;
			}
			assertTrue(reserved.add(target));
		}

		assertTrue(exhausted, "nether counterpart site search never gave up; the fallback loop is unbounded");
		assertTrue(reserved.size() < SATURATION_ATTEMPTS);
	}

	@Test
	@Timeout(60)
	public void releasedNetherFootprintsBecomeReservableAgain()
	{
		VanillaPortalNetherSites sites = new VanillaPortalNetherSites();
		World world = world();

		VanillaPortalNetherSites.BuildTarget first = sites.reserve(world, 0, 0, 2, 3, Set.of());
		assertNotNull(first);
		sites.release(first);
		VanillaPortalNetherSites.BuildTarget second = sites.reserve(world, 0, 0, 2, 3, Set.of());

		assertNotNull(second);
		assertTrue(first.x() == second.x() && first.z() == second.z());
	}

	@Test
	@Timeout(60)
	public void endSiteSearchGivesUpInsteadOfWalkingForeverUnderTheReservationLock()
	{
		VanillaPortalEndSites sites = new VanillaPortalEndSites(new VanillaPortalIndex());
		World world = world();
		Set<VanillaPortalEndSites.BuildTarget> reserved = new HashSet<VanillaPortalEndSites.BuildTarget>();
		boolean exhausted = false;

		for(int attempt = 0; attempt < SATURATION_ATTEMPTS; attempt++)
		{
			VanillaPortalEndSites.BuildTarget target = sites.reserve(world);
			if(target == null)
			{
				exhausted = true;
				break;
			}
			assertTrue(reserved.add(target));
		}

		assertTrue(exhausted, "End arrival search never gave up; the fallback loop is unbounded");
		assertTrue(reserved.size() < SATURATION_ATTEMPTS);
	}

	@Test
	@Timeout(60)
	public void releasedEndWindowsBecomeReservableAgain()
	{
		VanillaPortalEndSites sites = new VanillaPortalEndSites(new VanillaPortalIndex());
		World world = world();

		VanillaPortalEndSites.BuildTarget first = sites.reserve(world);
		assertNotNull(first);
		sites.release(first);
		VanillaPortalEndSites.BuildTarget second = sites.reserve(world);

		assertNotNull(second);
		assertTrue(first.target().equals(second.target()));
	}

	private static World world()
	{
		UUID id = UUID.randomUUID();
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getUID" -> id;
			case "getName" -> "world_the_end";
			case "toString" -> "WorldProxy";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
