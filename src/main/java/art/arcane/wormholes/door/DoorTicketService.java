package art.arcane.wormholes.door;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

final class DoorTicketService
{
	private final Plugin plugin;
	private final DoorStateGuard guard;

	DoorTicketService(Plugin plugin, DoorStateGuard guard)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.guard = Objects.requireNonNull(guard, "guard");
	}

	Optional<ReturnTicket> find(UUID playerId)
	{
		return guard.state().getReturnTicket(playerId);
	}

	void store(ReturnTicket ticket) throws IOException
	{
		guard.mutate(() ->
		{
			guard.state().putReturnTicket(ticket);
			return null;
		});
	}

	void removeQuietly(UUID playerId, ReturnTicket expected)
	{
		if(expected == null)
		{
			return;
		}
		if(guard.closed())
		{
			plugin.getLogger().warning(
				"Kept the dimensional return ticket for " + playerId + " because Dimensional Doors are shutting down.");
			return;
		}
		try
		{
			guard.mutate(() ->
			{
				Optional<ReturnTicket> current = guard.state().getReturnTicket(playerId);
				return current.filter(expected::equals).isPresent()
					? guard.state().removeReturnTicket(playerId)
					: Optional.<ReturnTicket>empty();
			});
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not remove dimensional return ticket for " + playerId, ex);
		}
	}

	void removeAfterRetirement(UUID playerId, ReturnTicket expected)
	{
		if(expected == null)
		{
			return;
		}
		if(!FoliaScheduler.runAsync(plugin, () -> removeQuietly(playerId, expected)))
		{
			plugin.getLogger().warning("Could not schedule retired dimensional-door ticket cleanup for " + playerId);
		}
	}
}
