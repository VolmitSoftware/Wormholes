package art.arcane.wormholes.door;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class DoorAccessAuthorizer
{
	private final Server server;
	private final PlayerDispatch dispatch;

	DoorAccessAuthorizer(Plugin plugin)
	{
		this(
			Objects.requireNonNull(plugin, "plugin").getServer(),
			(player, task, retired) -> FoliaScheduler.runEntity(plugin, player, task, 0L, retired));
	}

	DoorAccessAuthorizer(Server server, PlayerDispatch dispatch)
	{
		this.server = Objects.requireNonNull(server, "server");
		this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
	}

	void resolve(
		Entity traveler,
		Consumer<DoorAccessCredentials> ready,
		Runnable unavailable)
	{
		Objects.requireNonNull(traveler, "traveler");
		Consumer<DoorAccessCredentials> requiredReady = Objects.requireNonNull(ready, "ready");
		Runnable requiredUnavailable = Objects.requireNonNull(unavailable, "unavailable");
		Player responsible = DoorTravelerOwnership.responsiblePlayer(server, traveler).orElse(null);
		if(responsible == null)
		{
			requiredReady.accept(DoorAccessCredentials.ungated());
			return;
		}

		AtomicBoolean completed = new AtomicBoolean();
		Runnable completeUnavailable = () ->
		{
			if(completed.compareAndSet(false, true))
			{
				requiredUnavailable.run();
			}
		};
		Runnable authorization = () ->
		{
			if(!responsible.isOnline())
			{
				completeUnavailable.run();
				return;
			}
			UUID playerId = responsible.getUniqueId();
			if(server.getPlayer(playerId) != responsible)
			{
				completeUnavailable.run();
				return;
			}
			DoorAccessCredentials credentials = DoorAccessCredentials.of(
				playerId,
				responsible.hasPermission(DoorAccessPolicy.BYPASS_NODE));
			if(completed.compareAndSet(false, true))
			{
				requiredReady.accept(credentials);
			}
		};
		boolean scheduled = dispatch.run(responsible, authorization, completeUnavailable);
		if(!scheduled)
		{
			completeUnavailable.run();
		}
	}

	@FunctionalInterface
	interface PlayerDispatch
	{
		boolean run(Player player, Runnable task, Runnable retired);
	}
}
