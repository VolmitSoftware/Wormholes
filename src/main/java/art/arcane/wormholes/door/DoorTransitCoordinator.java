package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
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
		DoorTransitAttempt attempt,
		DoorAccessCredentials credentials)
	{
		DoorTransitAttempt requiredAttempt = Objects.requireNonNull(attempt, "attempt");
		DoorAccessCredentials requiredCredentials = Objects.requireNonNull(credentials, "credentials");
		Entity traveler = requiredAttempt.traveler();
		UUID travelerId = requiredAttempt.travelerId();
		RuntimeDoor runtime = requiredAttempt.runtime();
		if(guard.closed()
			|| (!guard.acceptingEntries() && runtime.endpoint().identity().kind() != DoorKind.RETURN))
		{
			refuseEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_TRANSIT_SHUTDOWN,
				DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN);
			return;
		}
		if(!ledger.claim(travelerId, traveler))
		{
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		World sourceWorld = requiredAttempt.sourceWorld();
		if(!regions.run(sourceWorld,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> claim(requiredAttempt, requiredCredentials)))
		{
			abortEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE,
				DoorTransitFailures.Failure.ENTRY_REGION_UNAVAILABLE);
		}
	}

	private void claim(
		DoorTransitAttempt attempt,
		DoorAccessCredentials credentials)
	{
		Entity traveler = attempt.traveler();
		UUID travelerId = attempt.travelerId();
		RuntimeDoor runtime = attempt.runtime();
		if(guard.closed())
		{
			abortEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_TRANSIT_SHUTDOWN,
				DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN);
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		if(!guard.acceptingEntries() && endpoint.identity().kind() != DoorKind.RETURN)
		{
			abortEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_TRANSIT_SHUTDOWN,
				DoorTransitFailures.Failure.ENTRY_SHUTTING_DOWN);
			return;
		}
		if(!hasDoorAccess(endpoint, credentials))
		{
			abortEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_ACCESS_TRANSIT_DENIED,
				DoorTransitFailures.Failure.ENTRY_ACCESS_DENIED);
			return;
		}
		World sourceWorld = attempt.sourceWorld();
		Optional<VanillaDoorSnapshot> captured = runtimes.capture(endpoint, sourceWorld);
		if(captured.isEmpty())
		{
			runtimes.reconcile(runtime);
			abortEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_SOURCE_MISSING,
				DoorTransitFailures.Failure.ENTRY_SOURCE_MISSING);
			return;
		}
		VanillaDoorSnapshot sourceSnapshot = captured.get();
		DoorTransit transit = attempt.transit();
		VanillaDoorSnapshot crossingSnapshot = attempt.crossingSnapshot();
		if(!crossingSnapshot.worldId().equals(sourceSnapshot.worldId())
			|| !transit.sourcePlane().equals(sourceSnapshot.plane())
			|| !passesGate(
				transit.travelerClass(),
				sourceSnapshot.plane(),
				runtime.cycle(),
				crossingSnapshot.portalLive(),
				sourceSnapshot.portalLive()))
		{
			runtimes.reconcile(runtime);
			abortEntry(
				traveler,
				travelerId,
				WormholesMessages.DOOR_CYCLE_CONSUMED,
				DoorTransitFailures.Failure.ENTRY_CYCLE_UNAVAILABLE);
			return;
		}
		runtime.update(sourceSnapshot);

		DoorDestination destination = guard.state().resolveDestination(endpoint.identity(), travelerId);
		switch(destination)
		{
			case PairedDoorDestination paired -> beginPaired(traveler, travelerId, runtime, paired, transit);
			case PocketDoorDestination pocket -> beginPocket(
				traveler, travelerId, runtime, pocket, sourceWorld, transit);
			case ReturnDoorDestination ignored -> beginReturn(traveler, travelerId, runtime, transit);
		}
	}

	private void beginPaired(
		Entity traveler,
		UUID travelerId,
		RuntimeDoor source,
		PairedDoorDestination destination,
		DoorTransit transit)
	{
		TransitContext context = TransitContext.none(travelerId, transit);
		Optional<PlacedDoorEndpoint> target = guard.state().findPairedEndpoint(
			destination.pairId(), destination.endpoint());
		if(target.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_LINK_NOT_PLACED, context);
			return;
		}
		arrivals.loadEndpointArrival(target.get(), transit, arrival ->
			closeAndTeleport(traveler, source, arrival.location(), context.at(arrival.plane())),
			() -> abortTransit(traveler, source, WormholesMessages.DOOR_LINK_UNAVAILABLE, context));
	}

	private void beginPocket(
		Entity traveler,
		UUID travelerId,
		RuntimeDoor source,
		PocketDoorDestination destination,
		World sourceWorld,
		DoorTransit transit)
	{
		TransitContext ticketless = TransitContext.none(travelerId, transit);
		if(PocketWorldService.isPocketWorld(sourceWorld))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_NESTED_POCKET, ticketless);
			return;
		}
		World pocketWorld = pocketWorldService.world().orElse(null);
		if(pocketWorld == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_NOT_READY, ticketless);
			return;
		}

		PocketSpace space;
		try
		{
			space = guard.mutate(() -> guard.state().getOrAllocatePocket(destination.binding(), Settings.POCKET_SHELL));
			pockets.index(space);
		}
		catch(IOException | RuntimeException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not allocate dimensional pocket", ex);
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ALLOCATION_FAILED, ticketless);
			return;
		}
		// An object has no identity to come back as, so it is never issued a return
		// ticket; persisting one per arrow would only churn the ticket store.
		boolean ticketed = transit.travelerClass() != DoorTravelerClass.OBJECT;
		Location savedReturnLocation = null;
		if(ticketed)
		{
			Optional<Location> safeReturn = arrivals.safeSourceDoorReturn(sourceWorld, transit);
			if(safeReturn.isEmpty())
			{
				abortTransit(traveler, source, WormholesMessages.DOOR_SAFE_RETURN_NOT_FOUND, ticketless);
				return;
			}
			savedReturnLocation = safeReturn.get();
		}
		UUID sourceEndpointId = source.endpoint().identity().itemId();
		ReturnTicket pendingTicket = savedReturnLocation == null ? null : buildReturnTicket(
			travelerId, sourceEndpointId, savedReturnLocation);

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
				abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_PREPARE_FAILED, ticketless);
				return;
			}

			TransitContext context = ticketless;
			if(pendingTicket != null)
			{
				try
				{
					tickets.store(pendingTicket);
				}
				catch(IOException ex)
				{
					plugin.getLogger().log(Level.SEVERE, "Could not save a pocket return ticket", ex);
					abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_TICKET_SAVE_FAILED, ticketless);
					return;
				}
				context = TransitContext.keep(travelerId, transit, pendingTicket);
			}

			Location arrival = pocketStructures.entryLocation(pocketWorld, space);
			arrival.setYaw(transit.yaw());
			arrival.setPitch(transit.pitch());
			if(!DoorArrivalResolver.isSafeArrival(arrival, transit))
			{
				if(pendingTicket != null)
				{
					tickets.removeQuietly(travelerId, pendingTicket);
				}
				abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ENTRY_UNSAFE, ticketless);
				return;
			}
			closeAndTeleport(traveler, source, arrival, context);
		}, () -> abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ENTRY_CHUNK_FAILED, ticketless));
	}

	private static ReturnTicket buildReturnTicket(UUID travelerId, UUID sourceEndpointId, Location savedReturn)
	{
		World savedReturnWorld = savedReturn.getWorld();
		return new ReturnTicket(
			travelerId,
			sourceEndpointId,
			savedReturnWorld.getUID(),
			WorldIdentity.serialize(savedReturnWorld),
			savedReturn.getX(),
			savedReturn.getY(),
			savedReturn.getZ(),
			savedReturn.getYaw(),
			savedReturn.getPitch());
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

	private void beginReturn(
		Entity traveler,
		UUID travelerId,
		RuntimeDoor source,
		DoorTransit transit)
	{
		TransitContext ticketless = TransitContext.none(travelerId, transit);
		Optional<ReturnTicket> found = tickets.find(travelerId);
		if(found.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_NO_RETURN_ROUTE, ticketless);
			return;
		}
		ReturnTicket ticket = found.get();
		Optional<PlacedDoorEndpoint> currentSource = guard.state().findEndpointByItem(ticket.sourceEndpointId());
		if(currentSource.isPresent())
		{
			PlacedDoorEndpoint endpoint = currentSource.get();
			World endpointWorld = runtimes.world(endpoint.position());
			if(canRouteReturnToCurrentEndpoint(endpoint, endpointWorld))
			{
				arrivals.loadEndpointArrival(endpoint, transit, arrival ->
					closeAndTeleport(
						traveler,
						source,
						arrival.location(),
						TransitContext.remove(travelerId, transit, ticket).at(arrival.plane())),
					() -> abortTransit(
						traveler,
						source,
						WormholesMessages.DOOR_RETURN_UNAVAILABLE,
						ticketless));
				return;
			}
		}
		loadTicketFallback(traveler, source, transit, ticket);
	}

	static boolean canRouteReturnToCurrentEndpoint(PlacedDoorEndpoint endpoint, World world)
	{
		return endpoint != null
			&& endpoint.identity().kind() != DoorKind.RETURN
			&& world != null
			&& !PocketWorldService.isPocketWorld(world);
	}

	private void loadTicketFallback(
		Entity traveler,
		RuntimeDoor source,
		DoorTransit transit,
		ReturnTicket ticket)
	{
		TransitContext ticketless = TransitContext.none(ticket.playerId(), transit);
		World world = DoorWorlds.of(plugin.getServer(), ticket);
		if(world == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_WORLD_UNLOADED, ticketless);
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
					abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_POINT_OBSTRUCTED, ticketless);
					return;
				}
				closeAndTeleport(
					traveler,
					source,
					safe.get(),
					TransitContext.remove(ticket.playerId(), transit, ticket));
			},
			() -> abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_CHUNK_FAILED, ticketless));
	}

	private void closeAndTeleport(Entity traveler, RuntimeDoor source, Location target, TransitContext context)
	{
		if(guard.closed())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_SHUTDOWN, context);
			return;
		}
		PlacedDoorEndpoint endpoint = source.endpoint();
		World sourceWorld = runtimes.world(endpoint.position());
		if(sourceWorld == null || !regions.run(sourceWorld,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4, () ->
			{
				if(guard.closed())
				{
					abortTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_SHUTDOWN, context);
					return;
				}
				Optional<VanillaDoorSnapshot> fresh = runtimes.capture(endpoint, sourceWorld);
				if(fresh.isEmpty() || !fresh.get().portalLive())
				{
					failTransit(traveler, source, WormholesMessages.DOOR_CLOSED_DURING_TRANSIT, context);
					return;
				}
				// An object leaves the source door standing open so the rest of the
				// volley can follow it through the same swing, and a contact pad is
				// never swung shut at all - closing it would open the hole underneath.
				if(context.transit().claimsOpenCycle())
				{
					try
					{
						runtimes.closePhysicalDoor(
							sourceWorld, fresh.get().plane(), endpoint.identity().itemId());
					}
					catch(Throwable ex)
					{
						plugin.getLogger().log(Level.WARNING, "Could not close the source dimensional door", ex);
						failTransit(traveler, source, WormholesMessages.DOOR_SOURCE_CLOSE_FAILED, context);
						return;
					}
					runtimes.hideTransitVisual(endpoint.identity().itemId());
				}
				if(!travelers.scheduleWithRetirement(
					traveler,
					() -> teleport(traveler, source, target, context),
					() -> retireScheduledTransit(traveler, source, context)))
				{
					rejectScheduledTransit(traveler, source, context);
				}
		}))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE, context);
		}
	}

	private void teleport(Entity traveler, RuntimeDoor source, Location target, TransitContext context)
	{
		if(guard.closed())
		{
			finishClosedTransit(
				traveler, source, false, context, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
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
			failTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_START_FAILED, context);
			return;
		}
		DoorVec3 arrivalVelocity = arrivalVelocity(context, target);
		teleportFuture.whenComplete((success, error) ->
		{
			boolean moved = error == null && Boolean.TRUE.equals(success);
			if(guard.closed())
			{
				finishClosedTransit(
					traveler, source, moved, context, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
				return;
			}
			Runnable retired = () -> retireCompletedTransit(traveler, source, moved, context);
			boolean scheduled = travelers.scheduleWithRetirement(traveler, () ->
			{
				if(guard.closed())
				{
					finishClosedTransit(
						traveler, source, moved, context, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
					return;
				}
				if(moved)
				{
					ledger.startCooldown(context.travelerId(), traveler);
					travelers.settle(traveler, arrivalVelocity);
				}
				completeCycle(source, context, moved, false);
				ledger.release(context.travelerId(), traveler);
				if(moved && context.action() == TicketAction.REMOVE_ON_SUCCESS)
				{
					tickets.removeQuietly(context.travelerId(), context.expected());
				}
				else if(!moved && context.action() == TicketAction.KEEP_ON_SUCCESS)
				{
					tickets.removeQuietly(context.travelerId(), context.expected());
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
					context,
					DoorTransitFailures.Failure.TRANSIT_SCHEDULE_REJECTED);
			}
		});
	}

	private void retireScheduledTransit(Entity traveler, RuntimeDoor source, TransitContext context)
	{
		failures.record(
			DoorTransitFailures.Failure.TRANSIT_RETIRED,
			context.travelerId(),
			WormholesMessages.DOOR_TRANSIT_CANCELLED.id());
		releaseScheduledTransit(traveler, source, context);
	}

	private void rejectScheduledTransit(Entity traveler, RuntimeDoor source, TransitContext context)
	{
		failures.record(
			DoorTransitFailures.Failure.TRANSIT_SCHEDULE_REJECTED,
			context.travelerId(),
			WormholesMessages.DOOR_TRANSIT_SHUTDOWN.id());
		releaseScheduledTransit(traveler, source, context);
		travelers.message(traveler, WormholesMessages.DOOR_TRANSIT_SHUTDOWN);
	}

	private void releaseScheduledTransit(Entity traveler, RuntimeDoor source, TransitContext context)
	{
		completeCycle(source, context, false, false);
		ledger.release(context.travelerId(), traveler);
		if(context.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			tickets.removeAfterRetirement(context.travelerId(), context.expected());
		}
	}

	private boolean hasDoorAccess(PlacedDoorEndpoint endpoint, DoorAccessCredentials credentials)
	{
		if(endpoint.identity().kind() == DoorKind.RETURN)
		{
			return true;
		}
		return credentials.canUse(
			guard.state().accessRecord(endpoint.identity().itemId()).orElse(null));
	}

	private void refuseEntry(
		Entity traveler,
		UUID travelerId,
		TextKey reason,
		DoorTransitFailures.Failure failure)
	{
		failures.record(failure, travelerId, reason.id());
		travelers.message(traveler, reason);
	}

	private void abortEntry(
		Entity traveler,
		UUID travelerId,
		TextKey reason,
		DoorTransitFailures.Failure failure)
	{
		ledger.release(travelerId, traveler);
		refuseEntry(traveler, travelerId, reason, failure);
	}

	private void finishRetiredTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TransitContext context)
	{
		completeCycle(source, context, moved, false);
		ledger.release(context.travelerId(), traveler);
		if((moved && context.action() == TicketAction.REMOVE_ON_SUCCESS)
			|| (!moved && context.action() == TicketAction.KEEP_ON_SUCCESS))
		{
			tickets.removeAfterRetirement(context.travelerId(), context.expected());
		}
	}

	private void retireCompletedTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TransitContext context)
	{
		if(!moved)
		{
			failures.record(
				DoorTransitFailures.Failure.TRANSIT_RETIRED,
				context.travelerId(),
				WormholesMessages.DOOR_TRANSIT_CANCELLED.id());
		}
		finishRetiredTransit(traveler, source, moved, context);
	}

	private void finishClosedTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TransitContext context,
		DoorTransitFailures.Failure failure)
	{
		if(!moved)
		{
			failures.record(
				failure, context.travelerId(), WormholesMessages.DOOR_TRANSIT_SHUTDOWN.id());
		}
		finishRetiredTransit(traveler, source, moved, context);
		if(!moved)
		{
			travelers.message(traveler, WormholesMessages.DOOR_TRANSIT_SHUTDOWN);
		}
	}

	private void abortTransit(Entity traveler, RuntimeDoor source, TextKey reason, TransitContext context)
	{
		endTransit(traveler, source, source.cycle().portalActive(), reason, context);
	}

	private void failTransit(Entity traveler, RuntimeDoor source, TextKey reason, TransitContext context)
	{
		endTransit(traveler, source, false, reason, context);
	}

	private void endTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean open,
		TextKey reason,
		TransitContext context)
	{
		failures.record(
			open ? DoorTransitFailures.Failure.TRANSIT_ABORTED : DoorTransitFailures.Failure.TRANSIT_FAILED,
			context.travelerId(),
			reason.id());
		completeCycle(source, context, false, open);
		ledger.release(context.travelerId(), traveler);
		if(context.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			tickets.removeQuietly(context.travelerId(), context.expected());
		}
		travelers.message(traveler, reason);
	}

	/**
	 * A transit that never claimed the open cycle must never complete one either:
	 * doing so would consume the single armed transit a player is queued for, and
	 * {@code open} here is what this transit did to the door rather than a reading
	 * of it - an object leaves the door standing open.
	 */
	private void completeCycle(RuntimeDoor source, TransitContext context, boolean success, boolean open)
	{
		DoorTransitGate.complete(source.cycle(), context.transit(), success, open);
	}

	/**
	 * Only a living traveler leaving through a swing consumes the open cycle. An
	 * object never does, and neither does a contact pad, which has no swing to
	 * arm and would otherwise fire exactly once and then be dead forever.
	 */
	private static boolean passesGate(
		DoorTravelerClass travelerClass,
		DoorwayPlane sourcePlane,
		DoorOpenCycle cycle,
		boolean liveAtCrossing,
		boolean stillLive)
	{
		return travelerClass == DoorTravelerClass.OBJECT || sourcePlane.openState() == DoorOpenState.CLOSED
			? DoorTransitGate.passThrough(cycle, liveAtCrossing, stillLive)
			: DoorTransitGate.claim(cycle, liveAtCrossing, stillLive);
	}

	private static DoorVec3 arrivalVelocity(TransitContext context, Location target)
	{
		DoorTransit transit = context.transit();
		if(transit.velocity() == null)
		{
			return null;
		}
		DoorwayPlane destination = context.destinationPlane();
		if(destination != null && (transit.sourcePlane().horizontal() || destination.horizontal()))
		{
			// The planes are no longer parallel, so a yaw delta cannot express the turn.
			return DoorVelocityTransform.map(transit.sourcePlane(), destination, transit.velocity());
		}
		// The arrival yaw already encodes the source-to-destination rotation, so the
		// same delta turns the momentum with it.
		return DoorVelocityTransform.rotateYaw(transit.velocity(), target.getYaw() - transit.yaw());
	}

	private enum TicketAction
	{
		NONE,
		KEEP_ON_SUCCESS,
		REMOVE_ON_SUCCESS
	}

	private record TransitContext(
		UUID travelerId,
		DoorTransit transit,
		TicketAction action,
		ReturnTicket expected,
		DoorwayPlane destinationPlane)
	{
		private TransitContext
		{
			Objects.requireNonNull(travelerId, "travelerId");
			Objects.requireNonNull(transit, "transit");
			Objects.requireNonNull(action, "action");
			if(action != TicketAction.NONE)
			{
				Objects.requireNonNull(expected, "expected");
			}
		}

		/** A pocket arrival has no far plane, so the destination stays null there. */
		private TransitContext at(DoorwayPlane plane)
		{
			return new TransitContext(travelerId, transit, action, expected, plane);
		}

		private static TransitContext none(UUID travelerId, DoorTransit transit)
		{
			return new TransitContext(travelerId, transit, TicketAction.NONE, null, null);
		}

		private static TransitContext keep(
			UUID travelerId,
			DoorTransit transit,
			ReturnTicket ticket)
		{
			return new TransitContext(
				travelerId, transit, TicketAction.KEEP_ON_SUCCESS, ticket, null);
		}

		private static TransitContext remove(
			UUID travelerId,
			DoorTransit transit,
			ReturnTicket ticket)
		{
			return new TransitContext(
				travelerId, transit, TicketAction.REMOVE_ON_SUCCESS, ticket, null);
		}
	}
}
