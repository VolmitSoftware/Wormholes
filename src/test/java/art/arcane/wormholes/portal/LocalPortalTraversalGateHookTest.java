package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.WormholesMessages;

final class LocalPortalTraversalGateHookTest
{
	@AfterEach
	void clearHooks()
	{
		WormholesHooks.clear();
	}

	@Test
	void noGatesAllowsWithoutAllocatingAVerdict()
	{
		assertSame(TraversalVerdict.ALLOW, LocalPortalTraversal.evaluateGates(attempt()));
	}

	@Test
	void firstNonAllowVerdictInOrderWinsAndLaterGatesAreNotConsulted()
	{
		List<String> consulted = new ArrayList<String>();
		TraversalVerdict.Deny deny = TraversalVerdict.Deny.of(WormholesMessages.PORTAL_ACCESS_DENIED);
		WormholesHooks.install(new WormholesRegistrar()
			.traversalGate(gate("late", TraversalGate.ORDER_DEFAULT, consulted, TraversalVerdict.ALLOW))
			.traversalGate(gate("rules", TraversalGate.ORDER_RULES, consulted, deny))
			.traversalGate(gate("access", TraversalGate.ORDER_ACCESS, consulted, TraversalVerdict.ALLOW)));

		TraversalVerdict verdict = LocalPortalTraversal.evaluateGates(attempt());

		assertSame(deny, verdict);
		assertEquals(List.of("access", "rules"), consulted);
	}

	@Test
	void throwingGateIsSkippedSoTraversalFailsOpen()
	{
		WormholesHooks.install(new WormholesRegistrar().traversalGate(new TraversalGate()
		{
			@Override
			public TraversalVerdict evaluate(TraversalAttempt attempt)
			{
				throw new IllegalStateException("boom");
			}
		}));

		assertInstanceOf(TraversalVerdict.Allow.class, LocalPortalTraversal.evaluateGates(attempt()));
	}

	private static TraversalAttempt attempt()
	{
		World world = LocalPortalTestSupport.world("gate-hooks");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		return new TraversalAttempt(TraversalPhase.DEPART, portal, entity(), null, null, 1L);
	}

	private static Entity entity()
	{
		UUID id = UUID.randomUUID();
		return (Entity) Proxy.newProxyInstance(LocalPortalTraversalGateHookTest.class.getClassLoader(), new Class<?>[] {Entity.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUniqueId" -> id;
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "toString" -> "entity-" + id;
				default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
			});
	}

	private static TraversalGate gate(String name, int order, List<String> consulted, TraversalVerdict verdict)
	{
		return new TraversalGate()
		{
			@Override
			public int order()
			{
				return order;
			}

			@Override
			public TraversalVerdict evaluate(TraversalAttempt attempt)
			{
				consulted.add(name);
				return verdict;
			}
		};
	}
}
