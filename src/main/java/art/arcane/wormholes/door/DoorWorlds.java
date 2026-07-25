package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.Server;
import org.bukkit.World;

final class DoorWorlds
{
	private DoorWorlds()
	{
	}

	static World of(Server server, DoorPosition position)
	{
		World byId = server.getWorld(position.worldId());
		return byId == null ? WorldIdentity.resolve(position.worldKey()).orElse(null) : byId;
	}

	static World of(Server server, ReturnTicket ticket)
	{
		World byId = server.getWorld(ticket.sourceWorldId());
		return byId == null ? WorldIdentity.resolve(ticket.sourceWorldKey()).orElse(null) : byId;
	}
}
