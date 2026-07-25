package art.arcane.wormholes.door;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTransitLedgerTest
{
	@Test
	void aSecondClaimForTheSameTravelerIsRefused()
	{
		DoorTransitLedger ledger = ledger();
		Entity traveler = entity(new UUID(0L, 1L));

		assertTrue(ledger.claim(traveler));
		assertFalse(ledger.claim(traveler));
		assertTrue(ledger.isTraveling(traveler.getUniqueId()));
	}

	@Test
	void releasingAFailedTransitFreesTheTravelerForAnotherAttempt()
	{
		DoorTransitLedger ledger = ledger();
		Entity traveler = entity(new UUID(0L, 2L));
		ledger.claim(traveler);

		ledger.release(traveler);

		assertFalse(ledger.isTraveling(traveler.getUniqueId()));
		assertFalse(ledger.hasActiveTransits());
		assertTrue(ledger.claim(traveler));
	}

	@Test
	void releaseIgnoresAStaleEntityHoldingTheSameIdentity()
	{
		DoorTransitLedger ledger = ledger();
		UUID travelerId = new UUID(0L, 3L);
		Entity live = entity(travelerId);
		Entity stale = entity(travelerId);
		ledger.claim(live);

		ledger.release(stale);

		assertTrue(ledger.isTraveling(travelerId));
	}

	@Test
	void rescueIsActiveOnlyWhileBothTheTransitAndRescueClaimsAreHeld()
	{
		DoorTransitLedger ledger = ledger();
		Player player = player(new UUID(0L, 4L));

		assertTrue(ledger.claim(player));
		assertFalse(ledger.isActiveRescue(player));
		assertTrue(ledger.claimRescue(player));
		assertTrue(ledger.isActiveRescue(player));
		assertTrue(ledger.isRescuing(player.getUniqueId()));

		ledger.releaseRescue(player);

		assertFalse(ledger.isActiveRescue(player));
		assertFalse(ledger.isRescuing(player.getUniqueId()));
		assertFalse(ledger.isTraveling(player.getUniqueId()));
	}

	@Test
	void escapeGlitchIsConsumedExactlyOnce()
	{
		DoorTransitLedger ledger = ledger();
		UUID playerId = new UUID(0L, 5L);
		ledger.markEscapeGlitch(playerId);

		assertTrue(ledger.consumeEscapeGlitch(playerId));
		assertFalse(ledger.consumeEscapeGlitch(playerId));
	}

	@Test
	void quittingClearsEveryTransitClaimForThatPlayer()
	{
		DoorTransitLedger ledger = ledger();
		Player player = player(new UUID(0L, 6L));
		ledger.claim(player);
		ledger.claimRescue(player);
		ledger.markEscapeGlitch(player.getUniqueId());

		ledger.forget(player);

		assertFalse(ledger.isTraveling(player.getUniqueId()));
		assertFalse(ledger.isRescuing(player.getUniqueId()));
		assertFalse(ledger.consumeEscapeGlitch(player.getUniqueId()));
	}

	@Test
	void clearingTheLedgerDropsEveryOutstandingClaim()
	{
		DoorTransitLedger ledger = ledger();
		ledger.claim(entity(new UUID(0L, 7L)));
		ledger.claim(entity(new UUID(0L, 8L)));

		ledger.clear();

		assertFalse(ledger.hasActiveTransits());
	}

	private static DoorTransitLedger ledger()
	{
		return new DoorTransitLedger(plugin());
	}

	private static Plugin plugin()
	{
		Logger logger = Logger.getLogger(DoorTransitLedgerTest.class.getName());
		logger.setLevel(Level.OFF);
		return (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[]{Plugin.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getLogger" -> logger;
				case "isEnabled" -> Boolean.FALSE;
				case "toString" -> "plugin";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> null;
			});
	}

	private static Entity entity(UUID identity)
	{
		return (Entity) Proxy.newProxyInstance(
			Entity.class.getClassLoader(),
			new Class<?>[]{Entity.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUniqueId" -> identity;
				case "toString" -> "entity";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> null;
			});
	}

	private static Player player(UUID identity)
	{
		return (Player) Proxy.newProxyInstance(
			Player.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUniqueId" -> identity;
				case "toString" -> "player";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> null;
			});
	}
}
