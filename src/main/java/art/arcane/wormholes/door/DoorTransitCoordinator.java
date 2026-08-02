package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class DoorTransitCoordinator
{
	private final Plugin plugin;
	private final DoorStateGuard guard;
	private final DoorTransitLedger ledger;
	private final DoorRuntimeIndex runtimes;
	private final DoorChunkLoader chunkLoader;
	private final DoorChunkLoader.RegionDispatch regions;
	private final DoorArrivalResolver arrivals;
	private final DoorTicketService tickets;
	private final DoorTravelerService travelers;
	private final PocketSpaceIndex pockets;
	private final PocketStructureService pocketStructures;
	private final PocketWorldService pocketWorldService;
	private final DoorTransitFailures failures;

	DoorTransitCoordinator(
		Plugin plugin,
		DoorStateGuard guard,
		DoorTransitLedger ledger,
		DoorRuntimeIndex runtimes,
		DoorChunkLoader chunkLoader,
		DoorChunkLoader.RegionDispatch regions,
		DoorArrivalResolver arrivals,
		DoorTicketService tickets,
		DoorTravelerService travelers,
		PocketSpaceIndex pockets,
		PocketStructureService pocketStructures,
		PocketWorldService pocketWorldService,
		DoorTransitFailures failures)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.guard = Objects.requireNonNull(guard, "guard");
		this.ledger = Objects.requireNonNull(ledger, "ledger");
		this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
		this.chunkLoader = Objects.requireNonNull(chunkLoader, "chunkLoader");
		this.regions = Objects.requireNonNull(regions, "regions");
		this.arrivals = Objects.requireNonNull(arrivals, "arrivals");
		this.tickets = Objects.requireNonNull(tickets, "tickets");
		this.travelers = Objects.requireNonNull(travelers, "travelers");
		this.pockets = Objects.requireNonNull(pockets, "pockets");
		this.pocketStructures = Objects.requireNonNull(pocketStructures, "pocketStructures");
		this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
		this.failures = Objects.requireNonNull(failures, "failures");
	}

	void begin(
		Entity traveler,
		RuntimeDoor runtime,
		Location sourceLocation,
		DoorwayCrossing.Direction direction,
		VanillaDoorSnapshot crossingSnapshot)
	{
		if(guard.closed()
			|| (!guard.acceptingEntries() && runtime.endpoint().identity().kind() != DoorKind.RETURN))
		{
			refuseEntry(
				traveler,
				WormholesMessages.DOOR_TRANSIT_SHUTDOWN,
				DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN);
			return;
		}
		if(!ledger.claim(traveler))
		{
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		World world = runtimes.world(endpoint.position());
		if(world == null || !regions.run(world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> claim(traveler, runtime, sourceLocation, direction, crossingSnapshot)))
		{
			abortEntry(
				traveler,
				WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE,
				DoorTransitFailures.Failure.ENTRY_REGION_UNAVAILABLE);
		}
	}

	private void claim(
		Entity traveler,
		RuntimeDoor runtime,
		Location sourceLocation,
		DoorwayCrossing.Direction direction,
		VanillaDoorSnapshot crossingSnapshot)
	{
		if(guard.closed())
		{
			abortEntry(
				traveler,
				WormholesMessages.DOOR_TRANSIT_SHUTDOWN,
				DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN);
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		if(!guard.acceptingEntries() && endpoint.identity().kind() != DoorKind.RETURN)
		{
			abortEntry(
				traveler,
				WormholesMessages.DOOR_TRANSIT_SHUTDOWN,
				DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN);
			return;
		}
		if(!hasDoorAccess(endpoint, traveler))
		{
			abortEntry(
				traveler,
				WormholesMessages.DOOR_ACCESS_TRANSIT_DENIED,
				DoorTransitFailures.Failure.ENTRY_ACCESS_DENIED);
			return;
		}
		World sourceWorld = runtimes.world(endpoint.position());
		if(sourceWorld == null)
		{
			abortEntry(
				traveler,
				WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE,
				DoorTransitFailures.Failure.ENTRY_REGION_UNAVAILABLE);
			return;
		}
		Optional<VanillaDoorSnapshot> captured = runtimes.capture(endpoint, sourceWorld);
		if(captured.isEmpty())
		{
			runtimes.reconcile(runtime);
			abortEntry(
				traveler,
				WormholesMessages.DOOR_SOURCE_MISSING,
				DoorTransitFailures.Failure.ENTRY_SOURCE_MISSING);
			return;
		}
		VanillaDoorSnapshot sourceSnapshot = captured.get();
		if(!crossingSnapshot.worldId().equals(sourceSnapshot.worldId())
			|| !crossingSnapshot.plane().equals(sourceSnapshot.plane())
			|| !DoorTransitGate.claim(
				runtime.cycle(), crossingSnapshot.open(), sourceSnapshot.open()))
		{
			runtimes.reconcile(runtime);
			abortEntry(
				traveler,
				WormholesMessages.DOOR_CYCLE_CONSUMED,
				DoorTransitFailures.Failure.ENTRY_CYCLE_UNAVAILABLE);
			return;
		}
		runtime.update(sourceSnapshot);
		DoorTransit transit = new DoorTransit(
			sourceSnapshot.plane(),
			direction,
			sourceLocation.getYaw(),
			sourceLocation.getPitch(),
			traveler.getWidth() / 2.0D,
			traveler.getHeight());

		DoorDestination destination = guard.state().resolveDestination(endpoint.identity(), traveler.getUniqueId());
		switch(destination)
		{
			case PairedDoorDestination paired -> beginPaired(traveler, runtime, paired, transit);
			case PocketDoorDestination pocket -> beginPocket(traveler, runtime, pocket, sourceWorld, transit);
			case ReturnDoorDestination ignored -> beginReturn(traveler, runtime, transit);
		}
	}

	private void beginPaired(
		Entity traveler,
		RuntimeDoor source,
		PairedDoorDestination destination,
		DoorTransit transit)
	{
		Optional<PlacedDoorEndpoint> target = guard.state().findPairedEndpoint(
			destination.pairId(), destination.endpoint());
		if(target.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_LINK_NOT_PLACED, TicketContext.NONE);
			return;
		}
		arrivals.loadEndpointArrival(target.get(), transit, arrival ->
			closeAndTeleport(traveler, source, arrival, TicketContext.NONE),
			() -> abortTransit(traveler, source, WormholesMessages.DOOR_LINK_UNAVAILABLE, TicketContext.NONE));
	}

	private void beginPocket(
		Entity traveler,
		RuntimeDoor source,
		PocketDoorDestination destination,
		World sourceWorld,
		DoorTransit transit)
	{
		if(PocketWorldService.isPocketWorld(sourceWorld))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_NESTED_POCKET, TicketContext.NONE);
			return;
		}
		World pocketWorld = pocketWorldService.world().orElse(null);
		if(pocketWorld == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_NOT_READY, TicketContext.NONE);
			return;
		}

		PocketSpace space;
		try
		{
			space = guard.mutate(() -> guard.state().getOrAllocatePocket(destination.binding()));
			pockets.index(space);
		}
		catch(IOException | RuntimeException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not allocate dimensional pocket", ex);
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ALLOCATION_FAILED, TicketContext.NONE);
			return;
		}
		Optional<Location> safeReturn = arrivals.safeSourceDoorReturn(sourceWorld, transit);
		if(safeReturn.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SAFE_RETURN_NOT_FOUND, TicketContext.NONE);
			return;
		}
		Location savedReturnLocation = safeReturn.get();
		UUID travelerId = traveler.getUniqueId();
		UUID sourceEndpointId = source.endpoint().identity().itemId();
		World savedReturnWorld = savedReturnLocation.getWorld();
		UUID savedReturnWorldId = savedReturnWorld.getUID();
		String savedReturnWorldKey = WorldIdentity.serialize(savedReturnWorld);
		double savedReturnX = savedReturnLocation.getX();
		double savedReturnY = savedReturnLocation.getY();
		double savedReturnZ = savedReturnLocation.getZ();
		float savedReturnYaw = savedReturnLocation.getYaw();
		float savedReturnPitch = savedReturnLocation.getPitch();

		chunkLoader.loadPocket(pocketWorld, space, pocketStructures.layout(space), () ->
		{
			try
			{
				PocketLayout layout = pocketStructures.layout(space);
				Optional<PlacedDoorEndpoint> existingReturn = guard.state().findEndpointByItem(
					layout.returnDoorIdentity().itemId());
				boolean initialize = existingReturn.isEmpty()
					|| !pocketStructures.isInitialized(pocketWorld, space);
				PlacedDoorEndpoint returnEndpoint;
				returnEndpoint = pocketStructures.provision(pocketWorld, space, initialize);
				PlacedDoorEndpoint previous = existingReturn
					.filter(endpoint -> !endpoint.equals(returnEndpoint))
					.orElse(null);
				if(previous != null)
				{
					guard.mutate(() -> guard.state().relocateEndpoint(previous, returnEndpoint));
					runtimes.remove(previous);
				}
				else
				{
					guard.mutate(() -> guard.state().registerEndpoint(returnEndpoint));
				}
				runtimes.reconcile(runtimes.install(returnEndpoint));
				retirePreviousReturnDoor(pocketWorld, space, previous);
			}
			catch(IOException | RuntimeException ex)
			{
				plugin.getLogger().log(Level.SEVERE, "Could not provision pocket " + space.spaceId(), ex);
				abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_PREPARE_FAILED, TicketContext.NONE);
				return;
			}

			ReturnTicket ticket = new ReturnTicket(
				travelerId,
				sourceEndpointId,
				savedReturnWorldId,
				savedReturnWorldKey,
				savedReturnX,
				savedReturnY,
				savedReturnZ,
				savedReturnYaw,
				savedReturnPitch);
			try
			{
				tickets.store(ticket);
			}
			catch(IOException ex)
			{
				plugin.getLogger().log(Level.SEVERE, "Could not save a pocket return ticket", ex);
				abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_TICKET_SAVE_FAILED, TicketContext.NONE);
				return;
			}

			Location arrival = pocketStructures.entryLocation(pocketWorld, space);
			arrival.setYaw(transit.yaw());
			arrival.setPitch(transit.pitch());
			if(!DoorArrivalResolver.isSafeStanding(arrival))
			{
				tickets.removeQuietly(travelerId, ticket);
				abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ENTRY_UNSAFE, TicketContext.NONE);
				return;
			}
			closeAndTeleport(traveler, source, arrival, TicketContext.keep(ticket));
		}, () -> abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ENTRY_CHUNK_FAILED, TicketContext.NONE));
	}

	private void retirePreviousReturnDoor(World world, PocketSpace space, PlacedDoorEndpoint previous)
	{
		if(previous == null)
		{
			return;
		}
		try
		{
			pocketStructures.retireReturnDoor(world, previous);
		}
		catch(RuntimeException cleanupFailure)
		{
			plugin.getLogger().log(Level.WARNING,
				"Could not remove the previous return door for pocket " + space.spaceId(), cleanupFailure);
		}
	}

	private void beginReturn(Entity traveler, RuntimeDoor source, DoorTransit transit)
	{
		Optional<ReturnTicket> found = tickets.find(traveler.getUniqueId());
		if(found.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_NO_RETURN_ROUTE, TicketContext.NONE);
			return;
		}
		ReturnTicket ticket = found.get();
		Optional<PlacedDoorEndpoint> currentSource = guard.state().findEndpointByItem(ticket.sourceEndpointId());
		if(currentSource.isPresent() && currentSource.get().identity().kind() != DoorKind.RETURN)
		{
			arrivals.loadEndpointArrival(currentSource.get(), transit, arrival ->
				closeAndTeleport(traveler, source, arrival, TicketContext.remove(ticket)),
				() -> abortTransit(
					traveler,
					source,
					WormholesMessages.DOOR_RETURN_UNAVAILABLE,
					TicketContext.NONE));
			return;
		}
		loadTicketFallback(traveler, source, ticket);
	}

	private void loadTicketFallback(Entity traveler, RuntimeDoor source, ReturnTicket ticket)
	{
		World world = DoorWorlds.of(plugin.getServer(), ticket);
		if(world == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_WORLD_UNLOADED, TicketContext.NONE);
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
					abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_POINT_OBSTRUCTED, TicketContext.NONE);
					return;
				}
				closeAndTeleport(traveler, source, safe.get(), TicketContext.remove(ticket));
			},
			() -> abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_CHUNK_FAILED, TicketContext.NONE));
	}

	private void closeAndTeleport(Entity traveler, RuntimeDoor source, Location target, TicketContext ticketContext)
	{
		if(guard.closed())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_SHUTDOWN, ticketContext);
			return;
		}
		PlacedDoorEndpoint endpoint = source.endpoint();
		World sourceWorld = runtimes.world(endpoint.position());
		if(sourceWorld == null || !regions.run(sourceWorld,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4, () ->
			{
				if(guard.closed())
				{
					abortTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_SHUTDOWN, ticketContext);
					return;
				}
				Optional<VanillaDoorSnapshot> fresh = runtimes.capture(endpoint, sourceWorld);
				if(fresh.isEmpty() || !fresh.get().open())
				{
					failTransit(traveler, source, WormholesMessages.DOOR_CLOSED_DURING_TRANSIT, ticketContext);
					return;
				}
				try
				{
					runtimes.closePhysicalDoor(sourceWorld, fresh.get().plane());
				}
				catch(Throwable ex)
				{
					plugin.getLogger().log(Level.WARNING, "Could not close the source dimensional door", ex);
					failTransit(traveler, source, WormholesMessages.DOOR_SOURCE_CLOSE_FAILED, ticketContext);
					return;
				}
				runtimes.hideTransitVisual(endpoint.identity().itemId());
				if(!travelers.scheduleWithRetirement(
					traveler,
					() -> teleport(traveler, source, target, ticketContext),
					() -> retireScheduledTransit(traveler, source, ticketContext)))
				{
					rejectScheduledTransit(traveler, source, ticketContext);
				}
		}))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE, ticketContext);
		}
	}

	private void teleport(Entity traveler, RuntimeDoor source, Location target, TicketContext ticketContext)
	{
		if(guard.closed())
		{
			finishClosedTransit(
				traveler, source, false, ticketContext, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
			return;
		}
		CompletableFuture<Boolean> teleportFuture;
		try
		{
			teleportFuture = WormholesPlatform.teleport(plugin, traveler, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not initiate dimensional-door teleport", ex);
			failTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_START_FAILED, ticketContext);
			return;
		}
		teleportFuture.whenComplete((success, error) ->
		{
			boolean moved = error == null && Boolean.TRUE.equals(success);
			if(guard.closed())
			{
				finishClosedTransit(
					traveler, source, moved, ticketContext, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
				return;
			}
			Runnable retired = () -> retireCompletedTransit(traveler, source, moved, ticketContext);
			boolean scheduled = travelers.scheduleWithRetirement(traveler, () ->
			{
				if(guard.closed())
				{
					finishClosedTransit(
						traveler, source, moved, ticketContext, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
					return;
				}
				if(moved)
				{
					ledger.startCooldown(traveler);
					travelers.settle(traveler);
				}
				completeCycle(source, moved, false);
				ledger.release(traveler);
				if(moved && ticketContext.action() == TicketAction.REMOVE_ON_SUCCESS)
				{
					tickets.removeQuietly(traveler.getUniqueId(), ticketContext.expected());
				}
				else if(!moved && ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
				{
					tickets.removeQuietly(traveler.getUniqueId(), ticketContext.expected());
				}
				if(!moved)
				{
					travelers.message(traveler, WormholesMessages.DOOR_TRANSIT_CANCELLED);
				}
			}, retired);
			if(!scheduled)
			{
				finishClosedTransit(
					traveler,
					source,
					moved,
					ticketContext,
					DoorTransitFailures.Failure.TRANSIT_SCHEDULE_REJECTED);
			}
		});
	}

	private void retireScheduledTransit(Entity traveler, RuntimeDoor source, TicketContext ticketContext)
	{
		failures.record(
			DoorTransitFailures.Failure.TRANSIT_RETIRED,
			traveler.getUniqueId(),
			WormholesMessages.DOOR_TRANSIT_CANCELLED.id());
		releaseScheduledTransit(traveler, source, ticketContext);
	}

	private void rejectScheduledTransit(Entity traveler, RuntimeDoor source, TicketContext ticketContext)
	{
		failures.record(
			DoorTransitFailures.Failure.TRANSIT_SCHEDULE_REJECTED,
			traveler.getUniqueId(),
			WormholesMessages.DOOR_TRANSIT_SHUTDOWN.id());
		releaseScheduledTransit(traveler, source, ticketContext);
		travelers.message(traveler, WormholesMessages.DOOR_TRANSIT_SHUTDOWN);
	}

	private void releaseScheduledTransit(Entity traveler, RuntimeDoor source, TicketContext ticketContext)
	{
		completeCycle(source, false, false);
		ledger.release(traveler);
		if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			tickets.removeAfterRetirement(traveler.getUniqueId(), ticketContext.expected());
		}
	}

	private boolean hasDoorAccess(PlacedDoorEndpoint endpoint, Entity traveler)
	{
		if(endpoint.identity().kind() == DoorKind.RETURN || !(traveler instanceof Player player))
		{
			return true;
		}
		return DoorAccessPolicy.canUse(
			guard.state().accessRecord(endpoint.identity().itemId()).orElse(null),
			player.getUniqueId(),
			player.hasPermission(DoorAccessPolicy.BYPASS_NODE));
	}

	private void refuseEntry(Entity traveler, TextKey reason, DoorTransitFailures.Failure failure)
	{
		failures.record(failure, traveler.getUniqueId(), reason.id());
		travelers.message(traveler, reason);
	}

	private void abortEntry(Entity traveler, TextKey reason, DoorTransitFailures.Failure failure)
	{
		ledger.release(traveler);
		refuseEntry(traveler, reason, failure);
	}

	private void finishRetiredTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TicketContext ticketContext)
	{
		completeCycle(source, moved, false);
		ledger.release(traveler);
		if((moved && ticketContext.action() == TicketAction.REMOVE_ON_SUCCESS)
			|| (!moved && ticketContext.action() == TicketAction.KEEP_ON_SUCCESS))
		{
			tickets.removeAfterRetirement(traveler.getUniqueId(), ticketContext.expected());
		}
	}

	private void retireCompletedTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TicketContext ticketContext)
	{
		if(!moved)
		{
			failures.record(
				DoorTransitFailures.Failure.TRANSIT_RETIRED,
				traveler.getUniqueId(),
				WormholesMessages.DOOR_TRANSIT_CANCELLED.id());
		}
		finishRetiredTransit(traveler, source, moved, ticketContext);
	}

	private void finishClosedTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TicketContext ticketContext,
		DoorTransitFailures.Failure failure)
	{
		if(!moved)
		{
			failures.record(
				failure, traveler.getUniqueId(), WormholesMessages.DOOR_TRANSIT_SHUTDOWN.id());
		}
		finishRetiredTransit(traveler, source, moved, ticketContext);
		if(!moved)
		{
			travelers.message(traveler, WormholesMessages.DOOR_TRANSIT_SHUTDOWN);
		}
	}

	private void abortTransit(Entity traveler, RuntimeDoor source, TextKey reason, TicketContext ticketContext)
	{
		endTransit(traveler, source, source.cycle().physicallyOpen(), reason, ticketContext);
	}

	private void failTransit(Entity traveler, RuntimeDoor source, TextKey reason, TicketContext ticketContext)
	{
		endTransit(traveler, source, false, reason, ticketContext);
	}

	private void endTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean open,
		TextKey reason,
		TicketContext ticketContext)
	{
		failures.record(
			open ? DoorTransitFailures.Failure.TRANSIT_ABORTED : DoorTransitFailures.Failure.TRANSIT_FAILED,
			traveler.getUniqueId(),
			reason.id());
		completeCycle(source, false, open);
		ledger.release(traveler);
		if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			tickets.removeQuietly(traveler.getUniqueId(), ticketContext.expected());
		}
		travelers.message(traveler, reason);
	}

	private void completeCycle(RuntimeDoor source, boolean success, boolean open)
	{
		try
		{
			source.cycle().complete(success, open);
		}
		catch(IllegalStateException ignored)
		{
		}
	}

	private enum TicketAction
	{
		NONE,
		KEEP_ON_SUCCESS,
		REMOVE_ON_SUCCESS
	}

	private record TicketContext(TicketAction action, ReturnTicket expected)
	{
		private static final TicketContext NONE = new TicketContext(TicketAction.NONE, null);

		private TicketContext
		{
			Objects.requireNonNull(action, "action");
			if(action != TicketAction.NONE)
			{
				Objects.requireNonNull(expected, "expected");
			}
		}

		private static TicketContext keep(ReturnTicket ticket)
		{
			return new TicketContext(TicketAction.KEEP_ON_SUCCESS, ticket);
		}

		private static TicketContext remove(ReturnTicket ticket)
		{
			return new TicketContext(TicketAction.REMOVE_ON_SUCCESS, ticket);
		}
	}
}
