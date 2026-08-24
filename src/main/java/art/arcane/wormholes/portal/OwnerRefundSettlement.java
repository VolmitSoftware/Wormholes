package art.arcane.wormholes.portal;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;

final class OwnerRefundSettlement
{
	private static final int OPEN = 0;
	private static final int PENDING = 1;
	private static final int DISPATCHED = 2;
	private static final int RESTORING = 3;
	private static final int REFUNDED = 4;
	private static final int COMMITTED = 5;
	private static final int FAILED = 6;
	private static final int MAX_RETRY_ATTEMPTS = 8;
	private static final long DISPATCH_WATCHDOG_TICKS = 20L;
	private static final long MILLIS_PER_TICK = 50L;

	private final UUID playerId;
	private final Player player;
	private final Executor executor;
	private final RefundAction refundAction;
	private final String description;
	private final AtomicInteger state;
	private final AtomicInteger retryAttempts;
	private final AtomicBoolean retryQueued;

	OwnerRefundSettlement(Player player, Executor executor, RefundAction refundAction, String description)
	{
		this.player = Objects.requireNonNull(player, "player");
		playerId = player.getUniqueId();
		this.executor = Objects.requireNonNull(executor, "executor");
		this.refundAction = Objects.requireNonNull(refundAction, "refundAction");
		this.description = Objects.requireNonNull(description, "description");
		state = new AtomicInteger(OPEN);
		retryAttempts = new AtomicInteger();
		retryQueued = new AtomicBoolean();
	}

	boolean commit()
	{
		return state.compareAndSet(OPEN, COMMITTED);
	}

	void refund()
	{
		if(state.compareAndSet(OPEN, PENDING))
		{
			attempt();
		}
	}

	boolean refundPending()
	{
		int current = state.get();
		return current == PENDING || current == DISPATCHED || current == RESTORING;
	}

	boolean refunded()
	{
		return state.get() == REFUNDED;
	}

	boolean failed()
	{
		return state.get() == FAILED;
	}

	private void attempt()
	{
		if(state.get() != PENDING)
		{
			return;
		}
		if(!executor.active())
		{
			fail(PENDING, "Wormholes stopped before an owner retry could run", null);
			return;
		}
		if(executor.isOwned(player))
		{
			restore(PENDING);
			return;
		}
		dispatch();
	}

	private void dispatch()
	{
		if(!state.compareAndSet(PENDING, DISPATCHED))
		{
			return;
		}
		AtomicBoolean dispatchPending = new AtomicBoolean(true);
		Runnable owned = () ->
		{
			if(!dispatchPending.compareAndSet(true, false))
			{
				return;
			}
			if(!executor.isOwned(player))
			{
				retireDispatchState();
				return;
			}
			restore(DISPATCHED);
		};
		Runnable retired = () -> retireDispatch(dispatchPending);
		boolean accepted;
		try
		{
			accepted = executor.dispatch(player, owned, retired);
		}
		catch(RuntimeException exception)
		{
			accepted = false;
			log(Level.WARNING, "Owner dispatch threw while refunding " + description + " for " + playerId, exception);
		}
		if(!accepted)
		{
			retired.run();
			return;
		}
		if(state.get() == DISPATCHED)
		{
			executor.retry(() -> recoverStalledDispatch(dispatchPending), DISPATCH_WATCHDOG_TICKS);
		}
	}

	private void recoverStalledDispatch(AtomicBoolean dispatchPending)
	{
		if(dispatchPending.compareAndSet(true, false))
		{
			retireDispatchState();
		}
	}

	private void retireDispatch(AtomicBoolean dispatchPending)
	{
		if(dispatchPending.compareAndSet(true, false))
		{
			retireDispatchState();
		}
	}

	private void retireDispatchState()
	{
		if(state.compareAndSet(DISPATCHED, PENDING))
		{
			queueRetry();
		}
	}

	private void restore(int expectedState)
	{
		if(!state.compareAndSet(expectedState, RESTORING))
		{
			return;
		}
		boolean restored;
		try
		{
			restored = refundAction.refund(player);
		}
		catch(RuntimeException exception)
		{
			fail(RESTORING, "the owner refund threw after restoration began", exception);
			return;
		}
		if(restored)
		{
			state.compareAndSet(RESTORING, REFUNDED);
			return;
		}
		if(state.compareAndSet(RESTORING, PENDING))
		{
			queueRetry();
		}
	}

	private void queueRetry()
	{
		if(state.get() != PENDING || !retryQueued.compareAndSet(false, true))
		{
			return;
		}
		if(!executor.active())
		{
			retryQueued.set(false);
			fail(PENDING, "Wormholes stopped before an owner retry could run", null);
			return;
		}
		int attempt = retryAttempts.getAndIncrement();
		if(attempt >= MAX_RETRY_ATTEMPTS)
		{
			retryQueued.set(false);
			fail(PENDING, "the traveler left or rejected " + MAX_RETRY_ATTEMPTS + " owner retries", null);
			return;
		}
		long delayTicks = 1L << attempt;
		boolean accepted = executor.retry(() ->
		{
			retryQueued.set(false);
			attempt();
		}, delayTicks);
		if(!accepted)
		{
			retryQueued.set(false);
			fail(PENDING, "the retry executor rejected terminal refund work", null);
		}
	}

	private void fail(int expectedState, String reason, RuntimeException exception)
	{
		if(!state.compareAndSet(expectedState, FAILED))
		{
			return;
		}
		log(Level.SEVERE, "Could not refund " + description + " for " + playerId + ": " + reason, exception);
	}

	private static void log(Level level, String message, RuntimeException exception)
	{
		Wormholes plugin = Wormholes.instance;
		if(plugin == null)
		{
			Wormholes.w(message + (exception == null ? "" : ": " + exception));
			return;
		}
		if(exception == null)
		{
			plugin.getLogger().log(level, message);
			return;
		}
		plugin.getLogger().log(level, message, exception);
	}

	interface Executor
	{
		boolean active();

		boolean isOwned(Player player);

		boolean dispatch(Player player, Runnable task, Runnable retired);

		boolean retry(Runnable task, long delayTicks);
	}

	@FunctionalInterface
	interface RefundAction
	{
		boolean refund(Player player);
	}

	static final class BukkitExecutor implements Executor
	{
		static final BukkitExecutor INSTANCE = new BukkitExecutor();

		private BukkitExecutor()
		{
		}

		@Override
		public boolean active()
		{
			Wormholes plugin = Wormholes.instance;
			return plugin != null && plugin.isEnabled();
		}

		@Override
		public boolean isOwned(Player player)
		{
			return FoliaScheduler.isOwnedByCurrentRegion(player);
		}

		@Override
		public boolean dispatch(Player player, Runnable task, Runnable retired)
		{
			Wormholes plugin = Wormholes.instance;
			return plugin != null && FoliaScheduler.runEntity(plugin, player, task, 0L, retired);
		}

		@Override
		public boolean retry(Runnable task, long delayTicks)
		{
			try
			{
				CompletableFuture.delayedExecutor(
					Math.max(1L, delayTicks) * MILLIS_PER_TICK,
					TimeUnit.MILLISECONDS).execute(task);
				return true;
			}
			catch(RuntimeException exception)
			{
				return false;
			}
		}
	}
}
