package art.arcane.wormholes.portal;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.bukkit.entity.Player;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.json.JSONObject;

public final class VaultTravelCost implements PortalTravelCost
{
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
	private static final int MAX_SCALE = 8;

	private final BigDecimal amount;

	private VaultTravelCost(BigDecimal amount)
	{
		this.amount = normalize(amount);
	}

	public static VaultTravelCost of(String amount)
	{
		if(amount == null || amount.isBlank())
		{
			throw new IllegalArgumentException("Vault travel cost must be a decimal amount");
		}
		try
		{
			return new VaultTravelCost(new BigDecimal(amount));
		}
		catch(NumberFormatException exception)
		{
			throw new IllegalArgumentException("Vault travel cost must be a decimal amount", exception);
		}
	}

	static VaultTravelCost fromJson(JSONObject json)
	{
		return of(json.optString("amount", ""));
	}

	@Override
	public Type getType()
	{
		return Type.VAULT;
	}

	public BigDecimal getAmount()
	{
		return amount;
	}

	public double getDoubleAmount()
	{
		return amount.doubleValue();
	}

	public String getPlainAmount()
	{
		return amount.toPlainString();
	}

	public String getFormattedAmount()
	{
		VaultEconomy economy = Wormholes.vaultEconomy;
		return economy == null ? getPlainAmount() : economy.format(getDoubleAmount());
	}

	@Override
	public Status status(Player player)
	{
		VaultEconomy economy = Wormholes.vaultEconomy;
		if(economy == null || !economy.isAvailable())
		{
			return Status.UNAVAILABLE;
		}
		return economy.canAfford(player, getDoubleAmount()) ? Status.AVAILABLE : Status.INSUFFICIENT;
	}

	@Override
	public ReserveResult reserve(Player player)
	{
		VaultEconomy economy = Wormholes.vaultEconomy;
		if(economy == null)
		{
			return ReserveResult.failed(Status.UNAVAILABLE);
		}
		VaultEconomy.ChargeResult result = economy.withdraw(player, getDoubleAmount(),
				"Wormholes portal travel for " + player.getUniqueId());
		if(result.successful())
		{
			return ReserveResult.reserved(new Reservation(result.charge()));
		}
		return switch(result.status())
		{
			case INSUFFICIENT_FUNDS -> ReserveResult.failed(Status.INSUFFICIENT);
			case VAULT_UNAVAILABLE, PROVIDER_UNAVAILABLE -> ReserveResult.failed(Status.UNAVAILABLE);
			case SUCCESS, INVALID_AMOUNT, TRANSACTION_FAILED -> ReserveResult.failed(Status.FAILED);
		};
	}

	@Override
	public JSONObject toJson()
	{
		return new JSONObject()
				.put("type", Type.VAULT.name())
				.put("amount", getPlainAmount());
	}

	private static BigDecimal normalize(BigDecimal amount)
	{
		if(amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0)
		{
			throw new IllegalArgumentException("Vault travel cost must be greater than zero and at most " + MAX_AMOUNT);
		}
		BigDecimal normalized = amount.setScale(Math.min(Math.max(amount.scale(), 0), MAX_SCALE), RoundingMode.HALF_UP)
				.stripTrailingZeros();
		if(normalized.signum() <= 0)
		{
			throw new IllegalArgumentException("Vault travel cost is too small");
		}
		if(normalized.scale() < 0)
		{
			normalized = normalized.setScale(0);
		}
		return normalized;
	}

	private static final class Reservation implements PortalTravelCost.Reservation
	{
		private final VaultEconomy.Charge charge;

		private Reservation(VaultEconomy.Charge charge)
		{
			this.charge = charge;
		}

		@Override
		public void commit()
		{
			charge.commit();
		}

		@Override
		public void refund()
		{
			charge.refund();
		}
	}
}
