package art.arcane.wormholes.door;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class PocketRescueService
{
	private final Wormholes plugin;
	private final DoorStateGuard guard;
	private final DoorTransitLedger ledger;
	private final DoorChunkLoader chunkLoader;
	private final DoorArrivalResolver arrivals;
	private final DoorTicketService tickets;
	private final DoorTravelerService travelers;

	PocketRescueService(
		Wormholes plugin,
		DoorStateGuard guard,
		DoorTransitLedger ledger,
		DoorChunkLoader chunkLoader,
		DoorArrivalResolver arrivals,
		DoorTicketService tickets,
		DoorTravelerService travelers)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.guard = Objects.requireNonNull(guard, "guard");
		this.ledger = Objects.requireNonNull(ledger, "ledger");
		this.chunkLoader = Objects.requireNonNull(chunkLoader, "chunkLoader");
		this.arrivals = Objects.requireNonNull(arrivals, "arrivals");
		this.tickets = Objects.requireNonNull(tickets, "tickets");
		this.travelers = Objects.requireNonNull(travelers, "travelers");
	}

	void begin(Player player)
	{
		Optional<ReturnTicket> found = tickets.find(player.getUniqueId());
		if(found.isEmpty())
		{
			loadFallback(player, null, WormholesMessages.DOOR_RESCUE_NO_ROUTE);
			return;
		}
		ReturnTicket ticket = found.get();
		World world = DoorWorlds.of(plugin.getServer(), ticket);
		if(world == null || PocketWorldService.isPocketWorld(world))
		{
			loadFallback(player, ticket, WormholesMessages.DOOR_RESCUE_RETURN_WORLD_UNAVAILABLE);
			return;
		}
		World targetWorld = world;
		chunkLoader.loadChunk(
			targetWorld,
			DoorArrivalResolver.floor(ticket.x()),
			DoorArrivalResolver.floor(ticket.z()),
			() ->
			{
				Location stored = new Location(
					targetWorld, ticket.x(), ticket.y(), ticket.z(), ticket.yaw(), ticket.pitch());
				Optional<Location> safe = arrivals.findSafeNear(stored, 3);
				if(safe.isEmpty())
				{
					loadFallback(player, ticket, WormholesMessages.DOOR_RESCUE_RETURN_POINT_OBSTRUCTED);
					return;
				}
				beginTeleport(player, safe.get(), ticket);
			},
			() -> loadFallback(player, ticket, WormholesMessages.DOOR_RESCUE_RETURN_CHUNK_FAILED));
	}

	void playEscapeGlitch(Player player)
	{
		EffectManager effects = plugin.getEffectManager();
		if(effects != null)
		{
			effects.playGlitchOut(player.getLocation());
		}
	}

	private void loadFallback(Player player, ReturnTicket ticket, TextKey routeFailure)
	{
		if(!FoliaScheduler.runGlobal(plugin, () ->
		{
			World fallbackWorld = fallbackWorld();
			if(fallbackWorld == null)
			{
				fail(player, routeFailure, WormholesMessages.DOOR_RESCUE_NO_FALLBACK_WORLD);
				return;
			}
			Location spawn = fallbackWorld.getSpawnLocation();
			chunkLoader.loadChunk(fallbackWorld, spawn.getBlockX(), spawn.getBlockZ(), () ->
			{
				Location currentSpawn = fallbackWorld.getSpawnLocation();
				Optional<Location> safe = arrivals.findSafeNear(currentSpawn, 3);
				if(safe.isEmpty())
				{
					fail(player, routeFailure, WormholesMessages.DOOR_RESCUE_FALLBACK_OBSTRUCTED);
					return;
				}
				beginTeleport(player, safe.get(), ticket);
			}, () -> fail(player, routeFailure, WormholesMessages.DOOR_RESCUE_FALLBACK_CHUNK_FAILED));
		}))
		{
			fail(player, routeFailure, WormholesMessages.DOOR_RESCUE_FALLBACK_SCHEDULE_FAILED);
		}
	}

	private World fallbackWorld()
	{
		World firstAvailable = null;
		for(World candidate : plugin.getServer().getWorlds())
		{
			if(PocketWorldService.isPocketWorld(candidate))
			{
				continue;
			}
			if(candidate.getEnvironment() == World.Environment.NORMAL)
			{
				return candidate;
			}
			if(firstAvailable == null)
			{
				firstAvailable = candidate;
			}
		}
		return firstAvailable;
	}

	private void beginTeleport(Player player, Location target, ReturnTicket ticket)
	{
		Runnable retired = () -> ledger.releaseRescue(player);
		if(!travelers.scheduleWithRetirement(player, () -> teleport(player, target, ticket), retired))
		{
			retired.run();
		}
	}

	private void teleport(Player player, Location target, ReturnTicket ticket)
	{
		if(!ledger.isActiveRescue(player))
		{
			ledger.releaseRescue(player);
			return;
		}
		if(guard.closed())
		{
			ledger.releaseRescue(player);
			WormholesAudience.sendMessage(player, Wormholes.text().component(player, WormholesMessages.DOOR_RESCUE_CANCELLED));
			return;
		}
		if(!PocketWorldService.isPocketWorld(player.getWorld()))
		{
			ledger.releaseRescue(player);
			return;
		}

		CompletableFuture<Boolean> future;
		try
		{
			future = WormholesPlatform.teleport(plugin, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not initiate pocket rescue teleport", ex);
			fail(player, WormholesMessages.DOOR_RESCUE_START_FAILED, null);
			return;
		}
		future.whenComplete((success, error) ->
		{
			boolean moved = error == null && Boolean.TRUE.equals(success);
			Runnable retired = () -> finishRetired(player, moved, ticket);
			if(guard.closed())
			{
				retired.run();
				return;
			}
			boolean scheduled = travelers.scheduleWithRetirement(player, () ->
			{
				if(!ledger.isActiveRescue(player))
				{
					return;
				}
				if(moved)
				{
					travelers.settle(player);
					tickets.removeQuietly(player.getUniqueId(), ticket);
					if(ledger.consumeEscapeGlitch(player.getUniqueId()))
					{
						playEscapeGlitch(player);
					}
				}
				ledger.releaseRescue(player);
				if(!moved)
				{
					WormholesAudience.sendMessage(player, Wormholes.text().component(player, WormholesMessages.DOOR_RESCUE_CANCELLED));
				}
			}, retired);
			if(!scheduled)
			{
				retired.run();
			}
		});
	}

	private void finishRetired(Player player, boolean moved, ReturnTicket ticket)
	{
		if(!ledger.isActiveRescue(player))
		{
			return;
		}
		if(moved)
		{
			tickets.removeAfterRetirement(player.getUniqueId(), ticket);
		}
		ledger.releaseRescue(player);
	}

	private void fail(Player player, TextKey routeFailure, TextKey fallbackFailure)
	{
		Runnable delivery = () ->
		{
			ledger.releaseRescue(player);
			WormholesAudience.sendMessage(player, Wormholes.text().component(player,
				WormholesMessages.DOOR_RESCUE_FAILED,
				WormholesLocalization.args(
					MessageArgument.untrusted("route", Wormholes.text().plain(routeFailure)),
					MessageArgument.untrusted("fallback", fallbackFailure == null ? "" : Wormholes.text().plain(fallbackFailure)))));
		};
		if(!travelers.scheduleWithRetirement(player, delivery, () -> ledger.releaseRescue(player)))
		{
			ledger.releaseRescue(player);
			plugin.getLogger().warning(
				"Could not deliver a dimensional-door rescue failure to " + player.getUniqueId());
		}
	}
}
