package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.json.JSONObject;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class VaultTravelCostTest
{
	@AfterEach
	public void clearEconomy()
	{
		Wormholes.vaultEconomy = null;
	}

	@Test
	public void decimalAmountIsCanonicalAndPersistedWithItsType()
	{
		VaultTravelCost cost = VaultTravelCost.of("12.5000");

		assertEquals("12.5", cost.getPlainAmount());
		JSONObject json = cost.toJson();
		assertEquals("VAULT", json.getString("type"));
		assertEquals("12.5", json.getString("amount"));
		VaultTravelCost restored = assertInstanceOf(VaultTravelCost.class, PortalTravelCost.fromJson(json));
		assertEquals(cost.getAmount(), restored.getAmount());
	}

	@Test
	public void excessiveScaleRoundsToTheSupportedPrecision()
	{
		assertEquals("1.12345679", VaultTravelCost.of("1.123456789").getPlainAmount());
	}

	@Test
	public void invalidAmountsAreRejected()
	{
		assertThrows(IllegalArgumentException.class, () -> VaultTravelCost.of("0"));
		assertThrows(IllegalArgumentException.class, () -> VaultTravelCost.of("-1"));
		assertThrows(IllegalArgumentException.class, () -> VaultTravelCost.of("0.000000001"));
		assertThrows(IllegalArgumentException.class, () -> VaultTravelCost.of("1000000000001"));
		assertThrows(IllegalArgumentException.class, () -> VaultTravelCost.of("not-money"));
	}

	@Test
	public void configuredVaultCostFailsClosedWithoutVault()
	{
		VaultTravelCost cost = VaultTravelCost.of("4.25");

		assertEquals(PortalTravelCost.Status.UNAVAILABLE, cost.status(null));
		assertEquals(PortalTravelCost.Status.UNAVAILABLE, cost.reserve(null).status());
	}

	@Test
	public void offOwnerVaultRefundRetriesAndSettlesExactlyOnce()
	{
		RecordingRefundExecutor executor = new RecordingRefundExecutor();
		AtomicInteger commits = new AtomicInteger();
		AtomicInteger refunds = new AtomicInteger();
		Player player = player();
		VaultTravelCost.Reservation reservation = new VaultTravelCost.Reservation(
			player,
			commits::incrementAndGet,
			ownedPlayer ->
			{
				refunds.incrementAndGet();
				return true;
			},
			executor);

		reservation.refund();

		assertTrue(reservation.refundPending());
		assertEquals(0, refunds.get());
		assertEquals(1, executor.dispatches);

		executor.owned = true;
		executor.runRetry();
		reservation.refund();
		reservation.commit();

		assertTrue(reservation.refunded());
		assertEquals(1, refunds.get());
		assertEquals(0, commits.get());
	}

	private static Player player()
	{
		return (Player) Proxy.newProxyInstance(
			VaultTravelCostTest.class.getClassLoader(),
			new Class<?>[] {Player.class},
			(instance, method, arguments) -> switch(method.getName())
			{
				case "getUniqueId" -> UUID.fromString("3b7d581d-df2c-46ad-aa41-d5f71f1170ef");
				case "getName" -> "VaultTraveler";
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

	private static final class RecordingRefundExecutor implements OwnerRefundSettlement.Executor
	{
		private boolean owned;
		private int dispatches;
		private Runnable retry;

		@Override
		public boolean active()
		{
			return true;
		}

		@Override
		public boolean isOwned(Player player)
		{
			return owned;
		}

		@Override
		public boolean dispatch(Player player, Runnable task, Runnable retired)
		{
			dispatches++;
			return false;
		}

		@Override
		public boolean retry(Runnable task, long delayTicks)
		{
			retry = task;
			return true;
		}

		private void runRetry()
		{
			Runnable queued = retry;
			retry = null;
			queued.run();
		}
	}
}
