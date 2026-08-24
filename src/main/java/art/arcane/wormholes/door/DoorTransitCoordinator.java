package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalDestination;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendCapture;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendProvider;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendTransaction;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class DoorTransitCoordinator
{
	private static final int TERMINAL_SETTLEMENT_ATTEMPTS = 4;

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
		AtomicBoolean entryPending = new AtomicBoolean(true);
		Runnable retired = () ->
		{
			if(entryPending.compareAndSet(true, false))
			{
				retireEntry(traveler, travelerId);
			}
		};
		boolean scheduled = regions.run(
			sourceWorld,
			endpoint.position().x() >> 4,
			endpoint.position().z() >> 4,
			() ->
			{
				if(entryPending.compareAndSet(true, false))
				{
					claim(requiredAttempt, requiredCredentials);
				}
			},
			retired);
		if(!scheduled)
		{
			retired.run();
			travelers.message(traveler, WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE);
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
		if(guard.pocketQuarantined(space.spaceId()))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_NOT_READY, ticketless);
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
		AtomicBoolean pending = new AtomicBoolean(true);
		Runnable retired = () ->
		{
			if(pending.compareAndSet(true, false))
			{
				retireScheduledTransit(traveler, source, context);
			}
		};
		boolean scheduled = travelers.scheduleWithRetirement(
			traveler,
			() ->
			{
				if(pending.compareAndSet(true, false))
				{
					admitAndClose(traveler, source, target, context);
				}
			},
			retired);
		if(!scheduled && pending.compareAndSet(true, false))
		{
			rejectScheduledTransit(traveler, source, context);
		}
	}

	private void admitAndClose(Entity traveler, RuntimeDoor source, Location target, TransitContext context)
	{
		PlacedDoorEndpoint endpoint = source.endpoint();
		World sourceWorld = runtimes.world(endpoint.position());
		if(sourceWorld == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE, context);
			return;
		}
		TransitContext admitted = openTraversalCost(traveler, endpoint, sourceWorld, target, context);
		if(admitted.traversalAdmission() != null && !admitted.traversalAdmission().allowed())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_ACCESS_TRANSIT_DENIED, admitted);
			return;
		}
		AtomicBoolean regionPending = new AtomicBoolean(true);
		Runnable regionRetired = () ->
		{
			if(regionPending.compareAndSet(true, false))
			{
				retireScheduledTransit(traveler, source, admitted);
			}
		};
		boolean regionScheduled = regions.run(
			sourceWorld,
			endpoint.position().x() >> 4,
			endpoint.position().z() >> 4,
			() ->
			{
				if(!regionPending.compareAndSet(true, false))
				{
					return;
				}
				if(guard.closed())
				{
					abortTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_SHUTDOWN, admitted);
					return;
				}
				Optional<VanillaDoorSnapshot> fresh = runtimes.capture(endpoint, sourceWorld);
				if(fresh.isEmpty() || !fresh.get().portalLive())
				{
					failTransit(traveler, source, WormholesMessages.DOOR_CLOSED_DURING_TRANSIT, admitted);
					return;
				}
				// An object leaves the source door standing open so the rest of the
				// volley can follow it through the same swing, and a contact pad is
				// never swung shut at all - closing it would open the hole underneath.
				if(admitted.transit().claimsOpenCycle())
				{
					try
					{
						runtimes.closePhysicalDoor(
							sourceWorld, fresh.get().plane(), endpoint.identity().itemId());
					}
					catch(Throwable ex)
					{
						plugin.getLogger().log(Level.WARNING, "Could not close the source dimensional door", ex);
						failTransit(traveler, source, WormholesMessages.DOOR_SOURCE_CLOSE_FAILED, admitted);
						return;
					}
					runtimes.hideTransitVisual(endpoint.identity().itemId());
				}
				AtomicBoolean travelerPending = new AtomicBoolean(true);
				Runnable travelerRetired = () ->
				{
					if(travelerPending.compareAndSet(true, false))
					{
						retireScheduledTransit(traveler, source, admitted);
					}
				};
				boolean travelerScheduled = travelers.scheduleWithRetirement(
					traveler,
					() ->
					{
						if(travelerPending.compareAndSet(true, false))
						{
							teleport(traveler, source, target, admitted);
						}
					},
					travelerRetired);
				if(!travelerScheduled && travelerPending.compareAndSet(true, false))
				{
					rejectScheduledTransit(traveler, source, admitted);
				}
			},
			regionRetired);
		if(!regionScheduled && regionPending.compareAndSet(true, false))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE, admitted);
		}
	}

	private TransitContext openTraversalCost(
		Entity traveler,
		PlacedDoorEndpoint endpoint,
		World sourceWorld,
		Location target,
		TransitContext context)
	{
		TraversalCostGateway gateway = Wormholes.traversalCostGateway;
		if(gateway == null || !(traveler instanceof Player player))
		{
			return context;
		}
		DoorVec3 crossing = context.transit().crossing().point();
		Location origin = new Location(sourceWorld, crossing.x(), crossing.y(), crossing.z());
		TraversalDestination destination = TraversalDestination.portal(null, "", target);
		TraversalCostGateway.Admission admission = gateway.open(TraversalContext.dimensionalDoor(
			player,
			endpoint.identity().itemId(),
			"",
			origin,
			destination));
		return context.withTraversalAdmission(admission);
	}

	private void teleport(Entity traveler, RuntimeDoor source, Location target, TransitContext context)
	{
		if(guard.closed())
		{
			finishClosedTransit(
				traveler, source, false, context, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
			return;
		}
		BukkitChunkPreSendCapture capture = captureChunkPreSend(traveler);
		World destinationWorld = target.getWorld();
		if(capture != null && destinationWorld != null)
		{
			AtomicBoolean destinationPending = new AtomicBoolean(true);
			Runnable retired = () -> recoverRetiredDestinationDispatch(
				traveler, source, context, destinationPending);
			boolean scheduled = regions.run(
				destinationWorld,
				target.getBlockX() >> 4,
				target.getBlockZ() >> 4,
				() ->
				{
					if(destinationPending.compareAndSet(true, false))
					{
						TransitContext prepared = prepareChunkPreSend(capture, target, context);
						dispatchPreparedTeleport(traveler, source, target, prepared);
					}
				},
				retired);
			if(scheduled)
			{
				return;
			}
			retired.run();
			return;
		}
		teleportPrepared(traveler, source, target, context);
	}

	private void dispatchPreparedTeleport(
		Entity traveler,
		RuntimeDoor source,
		Location target,
		TransitContext prepared)
	{
		AtomicBoolean travelerPending = new AtomicBoolean(true);
		AtomicBoolean recoveryStarted = new AtomicBoolean(false);
		Runnable retired = () ->
		{
			if(travelerPending.get() && recoveryStarted.compareAndSet(false, true))
			{
				recoverRetiredDestinationDispatch(traveler, source, prepared, travelerPending);
			}
		};
		boolean scheduled = travelers.scheduleWithRetirement(
			traveler,
			() ->
			{
				if(travelerPending.compareAndSet(true, false))
				{
					teleportPrepared(traveler, source, target, prepared);
				}
			},
			retired);
		if(!scheduled)
		{
			retired.run();
		}
	}

	private void recoverRetiredDestinationDispatch(
		Entity traveler,
		RuntimeDoor source,
		TransitContext context,
		AtomicBoolean destinationPending)
	{
		AtomicBoolean sourceFallbackStarted = new AtomicBoolean(false);
		Runnable sourceFallback = () ->
		{
			if(destinationPending.get() && sourceFallbackStarted.compareAndSet(false, true))
			{
				recoverRetiredDestinationOnSource(traveler, source, context, destinationPending);
			}
		};
		boolean entityScheduled = travelers.scheduleWithRetirement(
			traveler,
			() ->
			{
				if(destinationPending.compareAndSet(true, false))
				{
					retireScheduledTransit(traveler, source, context);
				}
			},
			sourceFallback);
		if(!entityScheduled)
		{
			sourceFallback.run();
		}
	}

	private void recoverRetiredDestinationOnSource(
		Entity traveler,
		RuntimeDoor source,
		TransitContext context,
		AtomicBoolean destinationPending)
	{
		PlacedDoorEndpoint endpoint = source.endpoint();
		World sourceWorld = runtimes.world(endpoint.position());
		Runnable sourceRecovery = () ->
		{
			if(destinationPending.compareAndSet(true, false))
			{
				releaseRetiredSourceState(traveler, source, context);
			}
		};
		Runnable sourceRetired = () ->
		{
			if(destinationPending.compareAndSet(true, false))
			{
				releaseRetiredSourceState(traveler, source, context);
			}
		};
		boolean regionScheduled = sourceWorld != null && regions.run(
			sourceWorld,
			endpoint.position().x() >> 4,
			endpoint.position().z() >> 4,
			sourceRecovery,
			sourceRetired);
		if(!regionScheduled)
		{
			sourceRetired.run();
		}
	}

	private void teleportPrepared(Entity traveler, RuntimeDoor source, Location target, TransitContext prepared)
	{
		if(guard.closed())
		{
			finishClosedTransit(
				traveler, source, false, prepared, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
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
			rollbackChunkPreSend(prepared);
			failTransit(traveler, source, WormholesMessages.DOOR_TRANSIT_START_FAILED, prepared);
			return;
		}
		DoorVec3 arrivalVelocity = arrivalVelocity(prepared, target);
		teleportFuture.whenComplete((success, error) ->
		{
			boolean moved = error == null && Boolean.TRUE.equals(success);
			AtomicBoolean completionPending = new AtomicBoolean(true);
			Runnable retired = () ->
			{
				if(completionPending.compareAndSet(true, false))
				{
					retireCompletedTransit(traveler, source, moved, prepared);
				}
			};
			boolean scheduled = travelers.scheduleWithRetirement(traveler, () ->
			{
				if(!completionPending.compareAndSet(true, false))
				{
					return;
				}
				if(guard.closed())
				{
					finishClosedTransit(
						traveler, source, moved, prepared, DoorTransitFailures.Failure.TRANSIT_SHUTDOWN);
					return;
				}
				if(moved)
				{
					commitChunkPreSend(prepared);
					settleTraversalCost(traveler, prepared, true, TraversalRefundReason.TELEPORT_FAILED);
					ledger.startCooldown(prepared.travelerId(), traveler);
					travelers.settle(traveler, arrivalVelocity);
				}
				completeCycle(source, prepared, moved, false);
				ledger.release(prepared.travelerId(), traveler);
				if(moved && prepared.action() == TicketAction.REMOVE_ON_SUCCESS)
				{
					tickets.removeQuietly(prepared.travelerId(), prepared.expected());
				}
				else if(!moved && prepared.action() == TicketAction.KEEP_ON_SUCCESS)
				{
					tickets.removeQuietly(prepared.travelerId(), prepared.expected());
				}
				if(!moved)
				{
					rollbackChunkPreSend(prepared);
					settleTraversalCost(traveler, prepared, false, TraversalRefundReason.TELEPORT_FAILED);
					travelers.message(traveler, WormholesMessages.DOOR_TRANSIT_CANCELLED);
				}
			}, retired);
			if(!scheduled && completionPending.compareAndSet(true, false))
			{
				failures.record(
					DoorTransitFailures.Failure.TRANSIT_SCHEDULE_REJECTED,
					prepared.travelerId(),
					WormholesMessages.DOOR_TRANSIT_SHUTDOWN.id());
				finishRetiredTransit(traveler, source, moved, prepared);
			}
		});
	}

	private BukkitChunkPreSendCapture captureChunkPreSend(Entity traveler)
	{
		if(!(traveler instanceof Player player))
		{
			return null;
		}
		try
		{
			return BukkitChunkPreSendProvider.capture(player);
		}
		catch(RuntimeException exception)
		{
			plugin.getLogger().log(Level.WARNING,
				"Could not pre-send dimensional-door destination chunks for " + traveler.getUniqueId(), exception);
			return null;
		}
	}

	private TransitContext prepareChunkPreSend(
		BukkitChunkPreSendCapture capture,
		Location target,
		TransitContext context)
	{
		try
		{
			return context.withChunkPreSend(capture.preSend(target));
		}
		catch(RuntimeException exception)
		{
			plugin.getLogger().log(Level.WARNING,
				"Could not pre-send dimensional-door destination chunks for " + context.travelerId(), exception);
			return context;
		}
	}

	private void rollbackChunkPreSend(TransitContext context)
	{
		BukkitChunkPreSendTransaction transaction = context.chunkPreSendTransaction();
		if(transaction == null)
		{
			return;
		}
		AtomicBoolean pending = new AtomicBoolean(true);
		Runnable rollback = () ->
		{
			if(pending.compareAndSet(true, false))
			{
				rollbackChunkPreSendNow(context, transaction);
			}
		};
		Runnable retired = () ->
		{
			if(pending.compareAndSet(true, false))
			{
				transaction.commit();
				plugin.getLogger().warning("Discarded dimensional-door chunk pre-send rollback after both source schedulers retired for "
					+ context.travelerId());
			}
		};
		boolean regionScheduled = regions.run(
			transaction.sourceWorld(),
			transaction.sourceChunkX(),
			transaction.sourceChunkZ(),
			rollback,
			retired);
		if(!regionScheduled)
		{
			retired.run();
		}
	}

	private static void commitChunkPreSend(TransitContext context)
	{
		BukkitChunkPreSendTransaction transaction = context.chunkPreSendTransaction();
		if(transaction != null)
		{
			transaction.commit();
		}
	}

	private void rollbackChunkPreSendNow(
		TransitContext context,
		BukkitChunkPreSendTransaction transaction)
	{
		try
		{
			transaction.rollback();
		}
		catch(RuntimeException exception)
		{
			plugin.getLogger().log(Level.WARNING,
				"Could not roll back dimensional-door chunk pre-send for " + context.travelerId(), exception);
		}
	}

	private void retireScheduledTransit(Entity traveler, RuntimeDoor source, TransitContext context)
	{
		failures.record(
			DoorTransitFailures.Failure.TRANSIT_RETIRED,
			context.travelerId(),
			WormholesMessages.DOOR_TRANSIT_CANCELLED.id());
		rollbackChunkPreSend(context);
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
		settleTraversalCost(traveler, context, false, TraversalRefundReason.DESTINATION_UNAVAILABLE);
		completeCycle(source, context, false, false);
		ledger.release(context.travelerId(), traveler);
		if(context.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			tickets.removeAfterRetirement(context.travelerId(), context.expected());
		}
	}

	private void releaseRetiredSourceState(Entity traveler, RuntimeDoor source, TransitContext context)
	{
		failures.record(
			DoorTransitFailures.Failure.TRANSIT_RETIRED,
			context.travelerId(),
			WormholesMessages.DOOR_TRANSIT_CANCELLED.id());
		rollbackChunkPreSend(context);
		settleTraversalCost(traveler, context, false, TraversalRefundReason.DESTINATION_UNAVAILABLE);
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

	private void retireEntry(Entity traveler, UUID travelerId)
	{
		ledger.release(travelerId, traveler);
		failures.record(
			DoorTransitFailures.Failure.ENTRY_REGION_UNAVAILABLE,
			travelerId,
			WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE.id());
	}

	private void finishRetiredTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TransitContext context)
	{
		if(!moved)
		{
			rollbackChunkPreSend(context);
		}
		else
		{
			commitChunkPreSend(context);
		}
		settleTraversalCost(traveler, context, moved, moved
			? TraversalRefundReason.TRAVERSAL_ABORTED
			: TraversalRefundReason.TRAVELER_LEFT);
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
		settleTraversalCost(traveler, context, false, TraversalRefundReason.DESTINATION_UNAVAILABLE);
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

	private void settleTraversalCost(
		Entity traveler,
		TransitContext context,
		boolean succeeded,
		TraversalRefundReason failureReason)
	{
		TraversalCostGateway.Admission admission = context.traversalAdmission();
		if(admission == null)
		{
			return;
		}
		if(!succeeded)
		{
			admission.refund(failureReason);
			return;
		}
		if(WormholesPlatform.isOwnedByCurrentRegion(traveler))
		{
			admission.commit();
			return;
		}
		retrySuccessfulTraversalCost(traveler, admission, TERMINAL_SETTLEMENT_ATTEMPTS);
	}

	private void retrySuccessfulTraversalCost(
		Entity traveler,
		TraversalCostGateway.Admission admission,
		int attemptsRemaining)
	{
		AtomicBoolean attemptFinished = new AtomicBoolean(false);
		Runnable settlement = () ->
		{
			if(attemptFinished.compareAndSet(false, true))
			{
				admission.commit();
			}
		};
		Runnable retired = () ->
		{
			if(!attemptFinished.compareAndSet(false, true))
			{
				return;
			}
			if(attemptsRemaining > 1)
			{
				retrySuccessfulTraversalCost(traveler, admission, attemptsRemaining - 1);
				return;
			}
			if(admission.deferCommit())
			{
				plugin.getLogger().warning(
					"Deferred successful dimensional-door traversal-cost provider work after traveler terminal work repeatedly retired for "
						+ traveler.getUniqueId());
			}
		};
		if(!travelers.scheduleWithRetirement(traveler, settlement, retired))
		{
			retired.run();
		}
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
		DoorwayPlane destinationPlane,
		TraversalCostGateway.Admission traversalAdmission,
		BukkitChunkPreSendTransaction chunkPreSendTransaction)
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
			return new TransitContext(
				travelerId, transit, action, expected, plane, traversalAdmission, chunkPreSendTransaction);
		}

		private TransitContext withTraversalAdmission(TraversalCostGateway.Admission admission)
		{
			return new TransitContext(
				travelerId, transit, action, expected, destinationPlane, admission, chunkPreSendTransaction);
		}

		private TransitContext withChunkPreSend(BukkitChunkPreSendTransaction transaction)
		{
			return new TransitContext(
				travelerId, transit, action, expected, destinationPlane, traversalAdmission, transaction);
		}

		private static TransitContext none(UUID travelerId, DoorTransit transit)
		{
			return new TransitContext(travelerId, transit, TicketAction.NONE, null, null, null, null);
		}

		private static TransitContext keep(
			UUID travelerId,
			DoorTransit transit,
			ReturnTicket ticket)
		{
			return new TransitContext(
				travelerId, transit, TicketAction.KEEP_ON_SUCCESS, ticket, null, null, null);
		}

		private static TransitContext remove(
			UUID travelerId,
			DoorTransit transit,
			ReturnTicket ticket)
		{
			return new TransitContext(
				travelerId, transit, TicketAction.REMOVE_ON_SUCCESS, ticket, null, null, null);
		}
	}
}
