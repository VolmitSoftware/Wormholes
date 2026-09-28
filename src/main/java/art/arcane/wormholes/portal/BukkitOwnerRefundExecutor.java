package art.arcane.wormholes.portal;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class BukkitOwnerRefundExecutor implements OwnerRefundSettlement.Executor<Player>
	{
		static final BukkitOwnerRefundExecutor INSTANCE = new BukkitOwnerRefundExecutor();

		private BukkitOwnerRefundExecutor()
		{
		}

        static Logger logger() {
            Wormholes plugin = Wormholes.instance;
            return plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
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
					Math.max(1L, delayTicks) * 50L,
					TimeUnit.MILLISECONDS).execute(task);
				return true;
			}
			catch(RuntimeException exception)
			{
				return false;
			}
		}
	}
