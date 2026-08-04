package art.arcane.wormholes.door;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTravelerOwnershipTest
{
	private static final UUID SHOOTER_ID = new UUID(11L, 13L);

	@Test
	void aProjectileIsJudgedByItsShooter()
	{
		Player shooter = player(SHOOTER_ID);

		Optional<Player> resolved = DoorTravelerOwnership.responsiblePlayer(
			server(Map.of()), projectile(shooter));

		assertSame(shooter, resolved.orElseThrow());
	}

	@Test
	void aProjectileFiredByABlockHasNoResponsiblePlayer()
	{
		ProjectileSource dispenser = (ProjectileSource) Proxy.newProxyInstance(
			DoorTravelerOwnershipTest.class.getClassLoader(),
			new Class<?>[]{BlockProjectileSource.class},
			(proxy, method, arguments) -> null);

		assertTrue(DoorTravelerOwnership
			.responsiblePlayer(server(Map.of()), projectile(dispenser))
			.isEmpty());
		assertTrue(DoorTravelerOwnership
			.responsiblePlayer(server(Map.of()), projectile(null))
			.isEmpty());
	}

	@Test
	void aDroppedItemIsJudgedByTheThrowerWhoIsStillOnline()
	{
		Player thrower = player(SHOOTER_ID);

		Optional<Player> resolved = DoorTravelerOwnership.responsiblePlayer(
			server(Map.of(SHOOTER_ID, thrower)), item(SHOOTER_ID));

		assertSame(thrower, resolved.orElseThrow());
	}

	@Test
	void aDroppedItemWithNoResolvableThrowerIsUngated()
	{
		assertTrue(DoorTravelerOwnership
			.responsiblePlayer(server(Map.of()), item(SHOOTER_ID))
			.isEmpty());
		assertTrue(DoorTravelerOwnership
			.responsiblePlayer(server(Map.of()), item(null))
			.isEmpty());
	}

	@Test
	void anExperienceOrbHasNoResponsiblePlayer()
	{
		Entity orb = (Entity) Proxy.newProxyInstance(
			DoorTravelerOwnershipTest.class.getClassLoader(),
			new Class<?>[]{ExperienceOrb.class},
			(proxy, method, arguments) -> null);

		assertTrue(DoorTravelerOwnership.responsiblePlayer(server(Map.of()), orb).isEmpty());
	}

	@Test
	void aPlayerIsResponsibleForItself()
	{
		Player player = player(SHOOTER_ID);

		assertSame(player, DoorTravelerOwnership
			.responsiblePlayer(server(Map.of()), player)
			.orElseThrow());
	}

	@Test
	void aMissingServerCannotResolveAThrower()
	{
		assertEquals(
			Optional.empty(),
			DoorTravelerOwnership.responsiblePlayer(null, item(SHOOTER_ID)));
	}

	private static Entity projectile(ProjectileSource shooter)
	{
		return (Entity) Proxy.newProxyInstance(
			DoorTravelerOwnershipTest.class.getClassLoader(),
			new Class<?>[]{Projectile.class},
			(proxy, method, arguments) -> "getShooter".equals(method.getName()) ? shooter : null);
	}

	private static Entity item(UUID thrower)
	{
		return (Entity) Proxy.newProxyInstance(
			DoorTravelerOwnershipTest.class.getClassLoader(),
			new Class<?>[]{Item.class},
			(proxy, method, arguments) -> "getThrower".equals(method.getName()) ? thrower : null);
	}

	private static Player player(UUID identity)
	{
		return (Player) Proxy.newProxyInstance(
			DoorTravelerOwnershipTest.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUniqueId" -> identity;
				case "toString" -> "player";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> null;
			});
	}

	private static Server server(Map<UUID, Player> online)
	{
		return (Server) Proxy.newProxyInstance(
			DoorTravelerOwnershipTest.class.getClassLoader(),
			new Class<?>[]{Server.class},
			(proxy, method, arguments) ->
			{
				if("getPlayer".equals(method.getName())
					&& arguments != null
					&& arguments.length == 1
					&& arguments[0] instanceof UUID identity)
				{
					return online.get(identity);
				}
				return switch(method.getName())
				{
					case "toString" -> "server";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> null;
				};
			});
	}
}
