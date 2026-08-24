package art.arcane.wormholes.portal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.bukkit.entity.Player;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.json.JSONObject;

public final class VaultTravelCost implements PortalTravelCost
{
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
	private static final int MAX_SCALE = 8;

	private final BigDecimal amount;
	private final OwnerRefundSettlement.Executor refundExecutor;

	private VaultTravelCost(BigDecimal amount)
	{
		this(amount, OwnerRefundSettlement.BukkitExecutor.INSTANCE);
	}

	VaultTravelCost(BigDecimal amount, OwnerRefundSettlement.Executor refundExecutor)
	{
		this.amount = normalize(amount);
		this.refundExecutor = refundExecutor;
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
			VaultEconomy.Charge charge = result.charge();
			return ReserveResult.reserved(new Reservation(
				player,
				charge::commit,
				ownedPlayer -> charge.refund(),
				refundExecutor));
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

	static final class Reservation implements PortalTravelCost.Reservation
	{
		private final Runnable commitAction;
		private final OwnerRefundSettlement settlement;

		Reservation(
			Player player,
			Runnable commitAction,
			OwnerRefundSettlement.RefundAction refundAction,
			OwnerRefundSettlement.Executor refundExecutor)
		{
			this.commitAction = Objects.requireNonNull(commitAction, "commitAction");
			settlement = new OwnerRefundSettlement(
				player,
				refundExecutor,
				refundAction,
				"Vault portal travel cost");
		}

		@Override
		public void commit()
		{
			if(settlement.commit())
			{
				commitAction.run();
			}
		}

		@Override
		public void refund()
		{
			settlement.refund();
		}

		boolean refundPending()
		{
			return settlement.refundPending();
		}

		boolean refunded()
		{
			return settlement.refunded();
		}
	}
}
