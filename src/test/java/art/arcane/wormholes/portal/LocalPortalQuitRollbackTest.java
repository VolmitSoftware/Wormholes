package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.service.WormholesTelemetry;
import net.kyori.adventure.text.Component;

public final class LocalPortalQuitRollbackTest
{
	private static final String QUIT_REASON = "TRAVERSAL_QUIT_MID_TRANSIT";

	@Test
	public void quittingAfterTheCommitmentPointClearsTheTeleportCooldownForTheTraversalThatNeverHappened()
	{
		UUID playerId = UUID.randomUUID();
		long now = System.currentTimeMillis();
		LocalPortal.markTeleportInFlight(playerId, now);
		LocalPortal.latchReentry(playerId, UUID.randomUUID());
		LocalPortal.markTeleportCooldown(playerId, now);
		assertTrue(LocalPortal.isTeleportCoolingDown(playerId, now));
		long before = quitFailures();

		new TraversableManager().on(new PlayerQuitEvent(player(playerId), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

		assertFalse(LocalPortal.isTeleportCoolingDown(playerId, System.currentTimeMillis()),
				"a player who quit mid-traversal must not come back still cooling down for a traversal that never happened");
		assertFalse(LocalPortal.isReentryLatched(playerId));
		assertFalse(LocalPortal.clearTeleportInFlight(playerId));
		assertEquals(before + 1L, quitFailures(),
				"abandoning a committed traversal is a terminal failure and must be counted");
	}

	@Test
	public void quittingWithoutACommittedTraversalIsNotCountedAsAFailure()
	{
		UUID playerId = UUID.randomUUID();
		long before = quitFailures();

		new TraversableManager().on(new PlayerQuitEvent(player(playerId), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

		assertEquals(before, quitFailures(), "an ordinary quit is not a traversal failure");
	}

	private static long quitFailures()
	{
		return WormholesTelemetry.failureBreakdown().getOrDefault(QUIT_REASON, Long.valueOf(0L)).longValue();
	}

	private static Player player(UUID playerId)
	{
		return (Player) Proxy.newProxyInstance(
				LocalPortalQuitRollbackTest.class.getClassLoader(),
				new Class<?>[] {Player.class},
				(Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch(method.getName())
				{
					case "getUniqueId" -> playerId;
					case "getName" -> "quitter";
					case "isOnline", "isValid" -> Boolean.TRUE;
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "toString" -> "QuitTestPlayer[" + playerId + "]";
					default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
				});
	}
}
