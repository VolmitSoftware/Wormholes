package art.arcane.wormholes.portal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

interface LocalPortalRuntime
{
	LocalPortalRuntime BUKKIT = new LocalPortalRuntime()
	{
		@Override
		public boolean dispatch(Entity entity, Runnable task, long delayTicks)
		{
			return FoliaScheduler.runEntity(Wormholes.instance, entity, task, delayTicks);
		}

		@Override
		public CompletionStage<Boolean> teleport(Entity entity, Location target)
		{
			if(Wormholes.instance == null)
			{
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}
			return WormholesPlatform.teleport(Wormholes.instance, entity, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
	};

	boolean dispatch(Entity entity, Runnable task, long delayTicks);

	CompletionStage<Boolean> teleport(Entity entity, Location target);
}
