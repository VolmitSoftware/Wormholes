package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
}
