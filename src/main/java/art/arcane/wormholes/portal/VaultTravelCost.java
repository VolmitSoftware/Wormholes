package art.arcane.wormholes.portal;

import java.math.BigDecimal;
import java.util.Objects;

import org.bukkit.entity.Player;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.json.JSONObject;

public final class VaultTravelCost implements PortalTravelCost
{
	public static final BigDecimal MAX_AMOUNT = TravelCurrencyAmount.MAX_AMOUNT;

	private final BigDecimal amount;
	private final OwnerRefundSettlement.Executor<Player> refundExecutor;

	private VaultTravelCost(BigDecimal amount)
	{
		this(amount, BukkitOwnerRefundExecutor.INSTANCE);
	}

	VaultTravelCost(BigDecimal amount, OwnerRefundSettlement.Executor<Player> refundExecutor)
	{
		this.amount = TravelCurrencyAmount.normalize(amount);
		this.refundExecutor = refundExecutor;
	}

	public static VaultTravelCost of(String amount)
	{
        return new VaultTravelCost(TravelCurrencyAmount.parse(amount));
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


	static final class Reservation implements PortalTravelCost.Reservation
	{
		private final Runnable commitAction;
		private final OwnerRefundSettlement<Player> settlement;

		Reservation(
			Player player,
			Runnable commitAction,
			OwnerRefundSettlement.RefundAction<Player> refundAction,
			OwnerRefundSettlement.Executor<Player> refundExecutor)
		{
			this.commitAction = Objects.requireNonNull(commitAction, "commitAction");
			settlement = new OwnerRefundSettlement<>(new OwnerRefundSettlement.Options<>(
				player, player.getUniqueId(),
				refundExecutor,
				refundAction,
				"Vault portal travel cost", BukkitOwnerRefundExecutor.logger()));
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
