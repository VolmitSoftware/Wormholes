package art.arcane.wormholes.door;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorAccessAuthorizerTest
{
	private static final UUID PLAYER_ID = new UUID(31L, 37L);

	@Test
	void remotePlayerStateIsOnlyReadInsideThePlayerDispatch()
	{
		AtomicInteger permissionReads = new AtomicInteger();
		Player shooter = player(permissionReads, true, true);
		AtomicReference<Runnable> scheduled = new AtomicReference<>();
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			server(shooter),
			(player, task, retired) ->
			{
				assertSame(shooter, player);
				scheduled.set(task);
				return true;
			});
		AtomicReference<DoorAccessCredentials> resolved = new AtomicReference<>();
		AtomicBoolean unavailable = new AtomicBoolean();

		authorizer.resolve(projectile(shooter), resolved::set, () -> unavailable.set(true));

		assertNull(resolved.get());
		assertEquals(0, permissionReads.get());
		assertFalse(unavailable.get());

		scheduled.get().run();

		assertEquals(PLAYER_ID, resolved.get().playerId());
		assertTrue(resolved.get().bypass());
		assertEquals(1, permissionReads.get());
	}

	@Test
	void retiredResponsiblePlayerFailsClosed()
	{
		Player shooter = player(new AtomicInteger(), false, true);
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			server(shooter),
			(player, task, retired) ->
			{
				retired.run();
				return false;
			});
		AtomicReference<DoorAccessCredentials> resolved = new AtomicReference<>();
		AtomicBoolean unavailable = new AtomicBoolean();

		authorizer.resolve(projectile(shooter), resolved::set, () -> unavailable.set(true));

		assertNull(resolved.get());
		assertTrue(unavailable.get());
	}

	@Test
	void dispatchRejectionWithoutRetirementDoesNotAuthorize()
	{
		Player shooter = player(new AtomicInteger(), false, true);
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			server(shooter),
			(player, task, retired) -> false);
		AtomicReference<DoorAccessCredentials> resolved = new AtomicReference<>();
		AtomicBoolean unavailable = new AtomicBoolean();

		authorizer.resolve(projectile(shooter), resolved::set, () -> unavailable.set(true));

		assertNull(resolved.get());
		assertTrue(unavailable.get());
	}

	@Test
	void offlineResponsiblePlayerFailsClosedBeforePermissionLookup()
	{
		AtomicInteger permissionReads = new AtomicInteger();
		Player shooter = player(permissionReads, true, false);
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			server(shooter),
			(player, task, retired) ->
			{
				task.run();
				return true;
			});
		AtomicReference<DoorAccessCredentials> resolved = new AtomicReference<>();
		AtomicBoolean unavailable = new AtomicBoolean();

		authorizer.resolve(projectile(shooter), resolved::set, () -> unavailable.set(true));

		assertNull(resolved.get());
		assertTrue(unavailable.get());
		assertEquals(0, permissionReads.get());
	}

	@Test
	void replacedPlayerIdentityFailsClosedBeforePermissionLookup()
	{
		AtomicInteger permissionReads = new AtomicInteger();
		Player shooter = player(permissionReads, true, true);
		Player replacement = player(new AtomicInteger(), false, true);
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			server(replacement),
			(player, task, retired) ->
			{
				task.run();
				return true;
			});
		AtomicReference<DoorAccessCredentials> resolved = new AtomicReference<>();
		AtomicBoolean unavailable = new AtomicBoolean();

		authorizer.resolve(projectile(shooter), resolved::set, () -> unavailable.set(true));

		assertNull(resolved.get());
		assertTrue(unavailable.get());
		assertEquals(0, permissionReads.get());
	}

	@Test
	void unownedTravelerNeedsNoPlayerDispatch()
	{
		DoorAccessAuthorizer authorizer = new DoorAccessAuthorizer(
			server(null),
			(player, task, retired) ->
			{
				throw new AssertionError("Unowned traveler dispatched to a player");
			});
		AtomicReference<DoorAccessCredentials> resolved = new AtomicReference<>();

		authorizer.resolve(orb(), resolved::set, () -> {
			throw new AssertionError("Unowned traveler was unavailable");
		});

		assertNull(resolved.get().playerId());
	}

	@Test
	void capturedCredentialsApplyPolicyWithoutReadingThePlayerAgain()
	{
		UUID itemId = new UUID(41L, 43L);
		DoorAccessRecord record = new DoorAccessRecord(
			itemId,
			new UUID(47L, 53L),
			Map.of(PLAYER_ID, DoorAccessState.BLACKLIST));

		assertFalse(DoorAccessCredentials.of(PLAYER_ID, false).canUse(record));
		assertTrue(DoorAccessCredentials.of(PLAYER_ID, true).canUse(record));
		assertTrue(DoorAccessCredentials.ungated().canUse(record));
	}

	private static Player player(AtomicInteger permissionReads, boolean bypass, boolean online)
	{
		return (Player) Proxy.newProxyInstance(
			Player.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUniqueId" -> PLAYER_ID;
				case "isOnline" -> online;
				case "hasPermission" ->
				{
					permissionReads.incrementAndGet();
					yield bypass;
				}
				case "toString" -> "player";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> throw new AssertionError("Unexpected player method " + method.getName());
			});
	}

	private static Entity projectile(Player shooter)
	{
		return (Entity) Proxy.newProxyInstance(
			Projectile.class.getClassLoader(),
			new Class<?>[]{Projectile.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getShooter"))
				{
					return shooter;
				}
				throw new AssertionError("Unexpected projectile method " + method.getName());
			});
	}

	private static Entity orb()
	{
		return (Entity) Proxy.newProxyInstance(
			ExperienceOrb.class.getClassLoader(),
			new Class<?>[]{ExperienceOrb.class},
			(proxy, method, arguments) ->
			{
				throw new AssertionError("Unexpected orb method " + method.getName());
			});
	}

	private static Server server(Player onlinePlayer)
	{
		return (Server) Proxy.newProxyInstance(
			Server.class.getClassLoader(),
			new Class<?>[]{Server.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getPlayer" -> onlinePlayer;
				case "toString" -> "server";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> throw new AssertionError("Unexpected server method " + method.getName());
			});
	}
}
