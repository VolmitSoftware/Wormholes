package art.arcane.wormholes.portal.rtp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendCapture;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendProvider;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendTransaction;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.PortalTravelCost;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.AxisAlignedBB;

final class RtpTraversalPipeline
{
	private static final double SOURCE_CAPTURE_MARGIN = 2.0D;
	private static final double ARRIVAL_TOLERANCE = 0.001D;
	private static final String FAILURE_DUPLICATE_CLAIM = "RTP_TRAVERSAL_DUPLICATE_CLAIM";
	private static final String FAILURE_CLAIM_REJECTED = "RTP_TRAVERSAL_CLAIM_REJECTED";
	private static final String FAILURE_CLAIM_SUPERSEDED = "RTP_TRAVERSAL_CLAIM_SUPERSEDED";
	private static final String FAILURE_CANCELLED = "RTP_TRAVERSAL_CANCELLED";
	private static final String FAILURE_STAGE = "RTP_TRAVERSAL_STAGE_FAILED";
	private static final String FAILURE_SCHEDULER_REJECTED = "RTP_TRAVERSAL_ENTITY_SCHEDULER_REJECTED";
	private static final int SUCCESS_SETTLEMENT_ATTEMPTS = 4;
	private static final long SUCCESS_SETTLEMENT_RETRY_TICKS = 1L;

	private final RtpService service;
	private final BukkitRtpRuntime.Environment environment;
	private final RtpFailureThrottle failures;
	private final Map<UUID, Active> active;
	private final AtomicLong terminalFailures;
	private final AtomicLong recoveredArrivals;

	RtpTraversalPipeline(RtpService service, BukkitRtpRuntime.Environment environment, RtpFailureThrottle failures)
	{
		this.service = service;
		this.environment = environment;
		this.failures = failures;
		this.active = new ConcurrentHashMap<UUID, Active>();
		this.terminalFailures = new AtomicLong();
		this.recoveredArrivals = new AtomicLong();
	}

	long terminalFailures()
	{
		return terminalFailures.get();
	}

	long recoveredArrivals()
	{
		return recoveredArrivals.get();
	}

	boolean begin(LocalPortal portal, Entity entity, Traversive traversive)
	{
		if(!sourceEligible(portal, entity) || !portal.beginRtpTraversal(entity, environment.nowMillis()))
		{
			return false;
		}
		PortalTravelCost travelCost = entity instanceof Player ? portal.getTravelCost() : null;
		if(travelCost != null && travelCost.status((Player) entity) != PortalTravelCost.Status.AVAILABLE)
		{
			portal.cancelRtpTraversal(entity);
			return false;
		}
		Active claimed = new Active(portal, entity, travelCost);
		if(active.putIfAbsent(entity.getUniqueId(), claimed) != null)
		{
			countTerminalFailure(FAILURE_DUPLICATE_CLAIM);
			failures.report("duplicate-traversal:" + portal.getId(),
					new IllegalStateException("RTP traversal already in progress for " + entity.getUniqueId()));
			claimed.refund(TraversalRefundReason.TRAVERSAL_ABORTED);
			return false;
		}
		RtpService.TraversalActor actor = entity instanceof Player
				? RtpService.TraversalActor.player(UUID.randomUUID(), entity.getUniqueId())
				: RtpService.TraversalActor.anonymous(UUID.randomUUID());
		service.claimTraversal(portal.getId(), actor).whenComplete((preparation, failure) ->
		{
			if(failure != null || preparation == null || preparation.isEmpty())
			{
				if(failure != null)
				{
					failures.report("claim:" + portal.getId(), failure);
				}
				countTerminalFailure(FAILURE_CLAIM_REJECTED);
				if(active.remove(entity.getUniqueId(), claimed))
				{
					claimed.refund(TraversalRefundReason.TRAVERSAL_ABORTED);
					portal.cancelRtpTraversal(entity);
				}
				return;
			}
			RtpService.TraversalPreparation admitted = preparation.get();
			claimed.admit(admitted);
			if(active.get(entity.getUniqueId()) != claimed)
			{
				countTerminalFailure(FAILURE_CLAIM_SUPERSEDED);
				claimed.refund(TraversalRefundReason.TRAVERSAL_ABORTED);
				releaseClaim(portal.getId(), admitted);
				return;
			}
			try
			{
				boolean scheduled = environment.scheduleEntity(entity,
						() -> guard(portal, entity, admitted, null,
								() -> prepare(portal, entity, traversive, admitted)),
						() -> fail(portal, entity, admitted, null), 0L);
				if(!scheduled)
				{
					fail(portal, entity, admitted,
							new IllegalStateException("Entity scheduler rejected RTP traversal preparation"),
							FAILURE_SCHEDULER_REJECTED);
				}
			}
			catch(RuntimeException exception)
			{
				fail(portal, entity, admitted, exception);
			}
		});
		return true;
	}

	void cancelPortal(UUID portalId)
	{
		for(Map.Entry<UUID, Active> entry : List.copyOf(active.entrySet()))
		{
			Active traversal = entry.getValue();
			if(traversal.portal().getId().equals(portalId) && active.remove(entry.getKey(), traversal))
			{
				cancel(traversal, TraversalRefundReason.DESTINATION_UNAVAILABLE);
			}
		}
	}

	void cancelEntity(UUID entityId)
	{
		Active traversal = active.remove(entityId);
		if(traversal != null)
		{
			cancel(traversal, TraversalRefundReason.TRAVELER_LEFT);
		}
	}

	void cancelAll()
	{
		for(Map.Entry<UUID, Active> entry : List.copyOf(active.entrySet()))
		{
			Active traversal = entry.getValue();
			if(active.remove(entry.getKey(), traversal))
			{
				cancel(traversal, TraversalRefundReason.SERVER_SHUTDOWN);
			}
		}
	}

	private void cancel(Active traversal, TraversalRefundReason reason)
	{
		traversal.cancel();
		countTerminalFailure(FAILURE_CANCELLED);
		refund(traversal, reason);
		traversal.portal().cancelRtpTraversal(traversal.entity());
		RtpService.TraversalPreparation preparation = traversal.preparation();
		if(preparation != null)
		{
			releaseClaim(traversal.portal().getId(), preparation);
		}
	}

	private void prepare(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation)
	{
		if(!sourceEligible(portal, entity) || !portal.canContinueRtpTraversal(entity))
		{
			fail(portal, entity, preparation, null);
			return;
		}
		Optional<RtpService.Snapshot> snapshot = service.snapshot(portal.getId());
		if(snapshot.isEmpty() || snapshot.get().generation() != preparation.generation())
		{
			fail(portal, entity, preparation, null);
			return;
		}
		RtpValidationRequest.EntityEnvelope envelope = entityEnvelope(entity);
		RtpService.SearchRequest request = new RtpService.SearchRequest(
				portal.getId(),
				preparation.generation(),
				snapshot.get().settings(),
				preparation.claim().destination());
		CompletionStage<RtpService.LoadedCandidate> loadStage;
		try
		{
			loadStage = Objects.requireNonNull(environment.loadTraversal(request, envelope), "traversal load stage");
		}
		catch(RuntimeException exception)
		{
			fail(portal, entity, preparation, exception);
			return;
		}
		loadStage.whenComplete((loaded, loadFailure) -> guard(portal, entity, preparation, null, () ->
		{
			if(loadFailure != null || loaded == null)
			{
				fail(portal, entity, preparation, loadFailure);
				return;
			}
			Retained retained = new Retained(loaded.retention());
			validate(portal, entity, traversive, preparation, loaded.validationRequest(), retained);
		}));
	}

	private void validate(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			RtpValidationRequest validationRequest,
			Retained retained)
	{
		CompletionStage<RtpSafetyResult> validationStage;
		try
		{
			validationStage = Objects.requireNonNull(environment.validate(validationRequest), "traversal validation stage");
		}
		catch(RuntimeException exception)
		{
			retained.close();
			fail(portal, entity, preparation, exception);
			return;
		}
		validationStage.whenComplete((safety, validationFailure) -> guard(portal, entity, preparation, retained, () ->
		{
			if(validationFailure != null || safety == null || !safety.safe()
					|| !preparation.claim().destination().equals(safety.destination()))
			{
				retained.close();
				fail(portal, entity, preparation, validationFailure);
				return;
			}
			checkAccess(portal, entity, traversive, preparation, validationRequest.entityEnvelope(), retained);
		}));
	}

	private void checkAccess(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			RtpValidationRequest.EntityEnvelope envelope,
			Retained retained)
	{
		if(!(entity instanceof Player player))
		{
			dispatch(portal, entity, traversive, preparation, envelope, retained);
			return;
		}
		CompletionStage<RtpAccessResult> accessStage;
		try
		{
			accessStage = Objects.requireNonNull(
					environment.canUse(player, preparation.claim().destination()),
					"traversal access stage");
		}
		catch(RuntimeException exception)
		{
			retained.close();
			fail(portal, entity, preparation, exception);
			return;
		}
		accessStage.whenComplete((access, accessFailure) -> guard(portal, entity, preparation, retained, () ->
		{
			if(accessFailure != null || access == null || !access.allowed())
			{
				retained.close();
				fail(portal, entity, preparation, accessFailure != null
						? accessFailure : access == null ? null : access.failure().orElse(null));
				return;
			}
			dispatch(portal, entity, traversive, preparation, envelope, retained);
		}));
	}

	private void dispatch(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			RtpValidationRequest.EntityEnvelope envelope,
			Retained retained)
	{
		Active current = active.get(entity.getUniqueId());
		if(current == null || !current.matches(preparation))
		{
			retained.close();
			releaseClaim(portal.getId(), preparation);
			return;
		}
		boolean scheduled = environment.scheduleEntity(entity, () -> guard(portal, entity, preparation, retained, () ->
		{
			if(!sourceEligible(portal, entity) || !portal.canContinueRtpTraversal(entity))
			{
				retained.close();
				fail(portal, entity, preparation, null);
				return;
			}
			World targetWorld = environment.resolveWorld(preparation.claim().destination().worldKey());
			if(targetWorld == null)
			{
				retained.close();
				fail(portal, entity, preparation, null);
				return;
			}
			PortalFrame targetFrame = BukkitRtpRuntime.targetFrameFor(portal.getFrame());
			Location target = targetLocation(targetWorld, preparation.claim().destination(), traversive, targetFrame, envelope);
			service.markTraversalDispatched(preparation).whenComplete((marked, markFailure) -> guard(portal, entity, preparation, retained, () ->
			{
				if(markFailure != null || !Boolean.TRUE.equals(marked))
				{
					retained.close();
					fail(portal, entity, preparation, markFailure);
					return;
				}
				marshalDestinationDispatch(
					portal, entity, traversive, preparation, targetFrame, target, targetWorld, retained);
			}));
		}), () ->
		{
			retained.close();
			fail(portal, entity, preparation, null);
		}, 0L);
		if(!scheduled)
		{
			retained.close();
			fail(portal, entity, preparation,
					new IllegalStateException("Entity scheduler rejected RTP teleport dispatch"),
					FAILURE_SCHEDULER_REJECTED);
		}
	}

	private void marshalDestinationDispatch(
		LocalPortal portal,
		Entity entity,
		Traversive traversive,
		RtpService.TraversalPreparation preparation,
		PortalFrame targetFrame,
		Location target,
		World targetWorld,
		Retained retained)
	{
		Active current = active.get(entity.getUniqueId());
		if(current == null || !current.matches(preparation))
		{
			retained.close();
			releaseClaim(portal.getId(), preparation);
			return;
		}
		Runnable retired = () ->
		{
			retained.close();
			fail(portal, entity, preparation, null);
		};
		boolean scheduled = environment.scheduleEntity(entity, () -> guard(
			portal, entity, preparation, retained, () -> reserveAndDispatch(
				portal, entity, traversive, preparation, targetFrame, target, targetWorld, retained)), retired, 0L);
		if(!scheduled)
		{
			retired.run();
		}
	}

	private void reserveAndDispatch(
		LocalPortal portal,
		Entity entity,
		Traversive traversive,
		RtpService.TraversalPreparation preparation,
		PortalFrame targetFrame,
		Location target,
		World targetWorld,
		Retained retained)
	{
		Active current = active.get(entity.getUniqueId());
		if(current == null || !current.matches(preparation) || !current.openTraversalCost(traversive))
		{
			retained.close();
			fail(portal, entity, preparation, null);
			return;
		}
		PortalTravelCost.Status reserveStatus = current.reserve();
		if(reserveStatus != PortalTravelCost.Status.AVAILABLE)
		{
			portal.rejectRtpCost(entity, traversive, current.travelCost(), reserveStatus);
			retained.close();
			fail(portal, entity, preparation, null, FAILURE_STAGE, TraversalRefundReason.CHARGE_ROLLBACK);
			return;
		}
		BukkitChunkPreSendCapture capture = capturePreSend(current, portal);
		if(active.get(entity.getUniqueId()) != current || !current.canProceed())
		{
			retained.close();
			fail(portal, entity, preparation, null);
			return;
		}
		if(capture == null)
		{
			teleport(portal, entity, traversive, preparation, targetFrame, target, retained, current);
			return;
		}
		AtomicBoolean destinationPending = new AtomicBoolean(true);
		Runnable retired = () ->
		{
			if(!destinationPending.compareAndSet(true, false))
			{
				return;
			}
			retained.close();
			fail(portal, entity, preparation,
				new IllegalStateException("Destination region retired RTP chunk pre-send work"),
				FAILURE_SCHEDULER_REJECTED,
				TraversalRefundReason.DESTINATION_UNAVAILABLE);
		};
		boolean destinationScheduled = environment.scheduleRegion(
			targetWorld,
			target.getBlockX() >> 4,
			target.getBlockZ() >> 4,
			() ->
			{
				if(destinationPending.compareAndSet(true, false))
				{
					guard(portal, entity, preparation, retained, () ->
						preparePreSendAndDispatchTeleport(portal, entity, traversive, preparation,
							targetFrame, target, retained, current, capture));
				}
			},
			retired);
		if(!destinationScheduled)
		{
			retired.run();
		}
		}

	private void preparePreSendAndDispatchTeleport(
		LocalPortal portal,
		Entity entity,
		Traversive traversive,
		RtpService.TraversalPreparation preparation,
		PortalFrame targetFrame,
		Location target,
		Retained retained,
		Active traversal,
		BukkitChunkPreSendCapture capture)
	{
		try
		{
			if(!traversal.preSend(capture, target))
			{
				retained.close();
				releaseClaim(portal.getId(), preparation);
				return;
			}
		}
		catch(RuntimeException exception)
		{
			failures.report("chunk-pre-send:" + portal.getId(), exception);
		}
		dispatchPreparedTeleport(
			portal, entity, traversive, preparation, targetFrame, target, retained, traversal);
	}

	private void dispatchPreparedTeleport(
		LocalPortal portal,
		Entity entity,
		Traversive traversive,
		RtpService.TraversalPreparation preparation,
		PortalFrame targetFrame,
		Location target,
		Retained retained,
		Active traversal)
	{
		AtomicBoolean travelerPending = new AtomicBoolean(true);
		Runnable retired = () ->
		{
			if(!travelerPending.compareAndSet(true, false))
			{
				return;
			}
			retained.close();
			fail(portal, entity, preparation,
				new IllegalStateException("Traveler owner retired RTP teleport work"),
				FAILURE_SCHEDULER_REJECTED,
				TraversalRefundReason.DESTINATION_UNAVAILABLE);
		};
		boolean scheduled;
		try
		{
			scheduled = environment.scheduleEntity(entity, () ->
			{
				if(travelerPending.compareAndSet(true, false))
				{
					guard(portal, entity, preparation, retained, () ->
						teleport(portal, entity, traversive, preparation, targetFrame, target, retained, traversal));
				}
			}, retired, 0L);
		}
		catch(RuntimeException exception)
		{
			failures.report("teleport-owner-schedule:" + portal.getId(), exception);
			scheduled = false;
		}
		if(!scheduled)
		{
			retired.run();
		}
	}

	private BukkitChunkPreSendCapture capturePreSend(Active traversal, LocalPortal portal)
	{
		try
		{
			return traversal.capturePreSend();
		}
		catch(RuntimeException exception)
		{
			failures.report("chunk-pre-send-capture:" + portal.getId(), exception);
			return null;
		}
	}

	private void teleport(
		LocalPortal portal,
		Entity entity,
		Traversive traversive,
		RtpService.TraversalPreparation preparation,
		PortalFrame targetFrame,
		Location target,
		Retained retained,
		Active traversal)
	{
		if(active.get(entity.getUniqueId()) != traversal)
		{
			retained.close();
			fail(portal, entity, preparation, null);
			return;
		}
		CompletionStage<Boolean> teleportStage;
		try
		{
			teleportStage = Objects.requireNonNull(environment.teleport(entity, target), "teleport stage");
		}
		catch(RuntimeException exception)
		{
			retained.close();
			fail(portal, entity, preparation, exception, FAILURE_STAGE, TraversalRefundReason.TELEPORT_FAILED);
			return;
		}
		teleportStage.whenComplete((teleported, teleportFailure) -> guard(portal, entity, preparation, retained, () ->
		{
			if(teleportFailure != null || !Boolean.TRUE.equals(teleported))
			{
				retained.close();
				fail(portal, entity, preparation, teleportFailure, FAILURE_STAGE, TraversalRefundReason.TELEPORT_FAILED);
				return;
			}
			beginSuccessfulTeleport(
				portal, entity, traversive, preparation, targetFrame, target, retained, traversal);
		}));
	}

	private void beginSuccessfulTeleport(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			PortalFrame targetFrame,
			Location target,
			Retained retained,
			Active traversal)
	{
		if(!traversal.matches(preparation) || !active.remove(entity.getUniqueId(), traversal))
		{
			retained.close();
			releaseClaim(portal.getId(), preparation);
			return;
		}
		SuccessfulTeleport successful = new SuccessfulTeleport(
			portal, entity, traversive, preparation, targetFrame, target, retained, traversal);
		scheduleSuccessfulSettlement(successful, SUCCESS_SETTLEMENT_ATTEMPTS);
	}

	private void scheduleSuccessfulSettlement(SuccessfulTeleport successful, int attemptsRemaining)
	{
		AtomicBoolean attemptFinished = new AtomicBoolean(false);
		Runnable settlement = () ->
		{
			if(attemptFinished.compareAndSet(false, true))
			{
				settleSuccessfulTeleportOnEntity(successful);
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
				scheduleSuccessfulSettlement(successful, attemptsRemaining - 1);
				return;
			}
			settleRetiredSuccessfulTeleport(successful);
		};
		boolean scheduled;
		try
		{
			scheduled = environment.scheduleEntity(
				successful.entity(), settlement, retired, SUCCESS_SETTLEMENT_RETRY_TICKS);
		}
		catch(RuntimeException exception)
		{
			failures.report("success-settlement-schedule:" + successful.portal().getId(), exception);
			scheduled = false;
		}
		if(!scheduled)
		{
			retired.run();
		}
	}

	private void settleSuccessfulTeleportOnEntity(SuccessfulTeleport successful)
	{
		if(!successful.settlementFinished().compareAndSet(false, true))
		{
			return;
		}
		Entity entity = successful.entity();
		boolean arrived = arrivedAt(entity, successful.target()) && !attached(entity);
		if(!arrived && withinSource(successful.portal(), entity))
		{
			successful.retained().close();
			successful.traversal().cancel();
			refund(successful.traversal(), TraversalRefundReason.TELEPORT_FAILED);
			successful.portal().cancelRtpTraversal(entity);
			releaseClaim(successful.portal().getId(), successful.preparation());
			return;
		}
		successful.traversal().commit();
		completeSuccessfulSource(successful);
	}

	private void settleRetiredSuccessfulTeleport(SuccessfulTeleport successful)
	{
		if(!successful.settlementFinished().compareAndSet(false, true))
		{
			return;
		}
		successful.traversal().deferCommit();
		completeSuccessfulSource(successful);
	}

	private void completeSuccessfulSource(SuccessfulTeleport successful)
	{
		CompletionStage<Boolean> completion;
		try
		{
			completion = Objects.requireNonNull(
				service.completeTraversal(successful.preparation(), true), "successful traversal completion stage");
		}
		catch(RuntimeException exception)
		{
			successful.retained().close();
			failures.report("complete-success:" + successful.portal().getId(), exception);
			recoveredArrivals.incrementAndGet();
			scheduleSuccessfulArrival(successful, SUCCESS_SETTLEMENT_ATTEMPTS);
			return;
		}
		completion.whenComplete((completed, completionFailure) ->
		{
			successful.retained().close();
			if(completionFailure != null)
			{
				failures.report("complete-success:" + successful.portal().getId(), completionFailure);
			}
			if(completionFailure != null || !Boolean.TRUE.equals(completed))
			{
				recoveredArrivals.incrementAndGet();
			}
			scheduleSuccessfulArrival(successful, SUCCESS_SETTLEMENT_ATTEMPTS);
		});
	}

	private void scheduleSuccessfulArrival(SuccessfulTeleport successful, int attemptsRemaining)
	{
		AtomicBoolean attemptFinished = new AtomicBoolean(false);
		Runnable arrival = () ->
		{
			if(!attemptFinished.compareAndSet(false, true)
				|| !successful.arrivalFinished().compareAndSet(false, true))
			{
				return;
			}
			try
			{
				environment.completeSuccess(
					successful.portal(), successful.entity(), successful.traversive(),
					successful.targetFrame(), successful.target());
			}
			catch(RuntimeException exception)
			{
				failures.report("arrival-success:" + successful.portal().getId(), exception);
				finishRetiredSuccessfulArrival(successful, false);
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
				scheduleSuccessfulArrival(successful, attemptsRemaining - 1);
				return;
			}
			if(successful.arrivalFinished().compareAndSet(false, true))
			{
				finishRetiredSuccessfulArrival(successful, true);
			}
		};
		boolean scheduled;
		try
		{
			scheduled = environment.scheduleEntity(
				successful.entity(), arrival, retired, SUCCESS_SETTLEMENT_RETRY_TICKS);
		}
		catch(RuntimeException exception)
		{
			failures.report("arrival-success-schedule:" + successful.portal().getId(), exception);
			scheduled = false;
		}
		if(!scheduled)
		{
			retired.run();
		}
	}

	private void finishRetiredSuccessfulArrival(SuccessfulTeleport successful, boolean countTraversal)
	{
		UUID entityId = successful.entity().getUniqueId();
		LocalPortal.clearTeleportInFlight(entityId);
		LocalPortal.markRefusedBounce(entityId, successful.portal().getId());
		LocalPortal.latchReentry(entityId, successful.portal().getId());
		if(countTraversal)
		{
			WormholesTelemetry.countTraversal();
		}
		recoveredArrivals.incrementAndGet();
		failures.report("arrival-success-retired:" + successful.portal().getId(),
			new IllegalStateException("Deferred RTP arrival effects because traveler terminal work repeatedly retired"));
	}

	private void guard(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			Retained retained,
			Runnable stage)
	{
		try
		{
			stage.run();
		}
		catch(RuntimeException exception)
		{
			if(retained != null)
			{
				retained.close();
			}
			fail(portal, entity, preparation, exception);
		}
	}

	private void fail(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			Throwable failure)
	{
		fail(portal, entity, preparation, failure, FAILURE_STAGE, TraversalRefundReason.TRAVERSAL_ABORTED);
	}

	private void fail(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			Throwable failure,
			String reason)
	{
		fail(portal, entity, preparation, failure, reason, TraversalRefundReason.TRAVERSAL_ABORTED);
	}

	private void fail(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			Throwable failure,
			String reason,
			TraversalRefundReason refundReason)
	{
		if(failure != null)
		{
			failures.report("traversal:" + portal.getId(), failure);
		}
		countTerminalFailure(reason);
		Active current = active.get(entity.getUniqueId());
		boolean removed = current != null
			&& current.matches(preparation)
			&& active.remove(entity.getUniqueId(), current);
		if(removed)
		{
			current.cancel();
			refund(current, refundReason);
			portal.cancelRtpTraversal(entity);
		}
		releaseClaim(portal.getId(), preparation);
	}

	private void refund(Active traversal, TraversalRefundReason reason)
	{
		Runnable settlement = () -> traversal.refund(reason);
		AtomicBoolean deferred = new AtomicBoolean(false);
		Runnable retired = () -> deferTraversalApiRefund(traversal, reason, deferred);
		try
		{
			rollbackPreSend(traversal);
			if(environment.scheduleEntity(traversal.entity(), settlement, retired, 0L))
			{
				return;
			}
		}
		catch(RuntimeException exception)
		{
			failures.report("cost-refund:" + traversal.portal().getId(), exception);
		}
		retired.run();
	}

	private void rollbackPreSend(Active traversal)
	{
		BukkitChunkPreSendTransaction transaction = traversal.preSendTransaction();
		if(transaction == null)
		{
			return;
		}
		AtomicBoolean pending = new AtomicBoolean(true);
		Runnable rollback = () ->
		{
			if(pending.compareAndSet(true, false))
			{
				traversal.rollbackPreSend();
			}
		};
		Runnable retired = () ->
		{
			if(pending.compareAndSet(true, false))
			{
				traversal.discardPreSend();
			}
		};
		boolean regionScheduled = environment.scheduleRegion(
			transaction.sourceWorld(), transaction.sourceChunkX(), transaction.sourceChunkZ(), rollback, retired);
		if(!regionScheduled)
		{
			retired.run();
		}
	}

	private void deferTraversalApiRefund(
			Active traversal,
			TraversalRefundReason reason,
			AtomicBoolean deferred)
	{
		if(!deferred.compareAndSet(false, true))
		{
			return;
		}
		traversal.refund(reason);
		failures.report("cost-refund-deferred:" + traversal.portal().getId(),
				new IllegalStateException("Retained traversal API refund for owner-dispatch retry because the entity scheduler rejected terminal work"));
	}

	private void countTerminalFailure(String reason)
	{
		terminalFailures.incrementAndGet();
		WormholesTelemetry.countFailure(reason);
	}

	private void releaseClaim(UUID portalId, RtpService.TraversalPreparation preparation)
	{
		service.completeTraversal(preparation, false).whenComplete((completed, completionFailure) ->
		{
			if(completionFailure != null)
			{
				failures.report("complete-failure:" + portalId, completionFailure);
			}
		});
	}

	private boolean sourceEligible(LocalPortal portal, Entity entity)
	{
		if(entity == null || !entity.isValid() || attached(entity) || !BukkitRtpRuntime.physicallyTraversable(entity))
		{
			return false;
		}
		return withinSource(portal, entity);
	}

	private boolean withinSource(LocalPortal portal, Entity entity)
	{
		PortalStructure structure = portal.getStructure();
		Location location = entity.getLocation();
		if(structure == null || structure.getWorld() == null || location.getWorld() == null
				|| !structure.getWorld().equals(location.getWorld()))
		{
			return false;
		}
		AxisAlignedBB area = structure.getArea();
		return area != null
				&& location.getX() >= area.getXa() - SOURCE_CAPTURE_MARGIN
				&& location.getX() <= area.getXb() + SOURCE_CAPTURE_MARGIN
				&& location.getY() >= area.getYa() - SOURCE_CAPTURE_MARGIN
				&& location.getY() <= area.getYb() + SOURCE_CAPTURE_MARGIN
				&& location.getZ() >= area.getZa() - SOURCE_CAPTURE_MARGIN
				&& location.getZ() <= area.getZb() + SOURCE_CAPTURE_MARGIN;
	}

	private boolean attached(Entity entity)
	{
		return entity.getVehicle() != null || !entity.getPassengers().isEmpty();
	}

	private RtpValidationRequest.EntityEnvelope entityEnvelope(Entity entity)
	{
		Location location = entity.getLocation();
		BoundingBox box = entity.getBoundingBox();
		double[] x = BukkitRtpRuntime.normalizedEnvelopeAxis(box.getMinX() - location.getX(), box.getMaxX() - location.getX());
		double[] y = BukkitRtpRuntime.normalizedEnvelopeAxis(box.getMinY() - location.getY(), box.getMaxY() - location.getY());
		double[] z = BukkitRtpRuntime.normalizedEnvelopeAxis(box.getMinZ() - location.getZ(), box.getMaxZ() - location.getZ());
		return new RtpValidationRequest.EntityEnvelope(x[0], x[1], y[0], y[1], z[0], z[1]);
	}

	private Location targetLocation(
			World world,
			RtpDestination destination,
			Traversive traversive,
			PortalFrame targetFrame,
			RtpValidationRequest.EntityEnvelope envelope)
	{
		double centerXOffset = (envelope.minimumXOffset() + envelope.maximumXOffset()) / 2.0D;
		double centerZOffset = (envelope.minimumZOffset() + envelope.maximumZOffset()) / 2.0D;
		Location target = new Location(
				world,
				destination.blockX() + 0.5D - centerXOffset,
				destination.feetY() - envelope.minimumYOffset(),
				destination.blockZ() + 0.5D - centerZOffset);
		Vector look = traversive.getOutLook(targetFrame);
		if(look.lengthSquared() > 1.0E-12D)
		{
			target.setDirection(look);
		}
		return target;
	}

	private boolean arrivedAt(Entity entity, Location target)
	{
		Location current = entity.getLocation();
		return current.getWorld() != null
				&& current.getWorld().equals(target.getWorld())
				&& Math.abs(current.getX() - target.getX()) <= ARRIVAL_TOLERANCE
				&& Math.abs(current.getY() - target.getY()) <= ARRIVAL_TOLERANCE
				&& Math.abs(current.getZ() - target.getZ()) <= ARRIVAL_TOLERANCE;
	}

	private static final class Active
	{
		private final LocalPortal portal;
		private final Entity entity;
		private final PortalTravelCost travelCost;
		private PortalTravelCost.Reservation reservation;
		private RtpService.TraversalPreparation preparation;
		private TraversalCostGateway.Admission traversalAdmission;
		private BukkitChunkPreSendTransaction preSendTransaction;
		private boolean cancelled;
		private boolean settled;
		private boolean builtInSettled;

		private Active(LocalPortal portal, Entity entity, PortalTravelCost travelCost)
		{
			this.portal = Objects.requireNonNull(portal, "portal");
			this.entity = Objects.requireNonNull(entity, "entity");
			this.travelCost = travelCost;
		}

		private LocalPortal portal()
		{
			return portal;
		}

		private Entity entity()
		{
			return entity;
		}

		private PortalTravelCost travelCost()
		{
			return travelCost;
		}

		private synchronized void admit(RtpService.TraversalPreparation admitted)
		{
			preparation = Objects.requireNonNull(admitted, "admitted");
		}

		private synchronized RtpService.TraversalPreparation preparation()
		{
			return preparation;
		}

		private synchronized boolean matches(RtpService.TraversalPreparation expected)
		{
			return preparation == expected;
		}

		private synchronized PortalTravelCost.Status reserve()
		{
			if(travelCost == null || reservation != null)
			{
				return PortalTravelCost.Status.AVAILABLE;
			}
			PortalTravelCost.ReserveResult result = travelCost.reserve((Player) entity);
			reservation = result.reservation();
			return result.status();
		}

		private synchronized boolean openTraversalCost(Traversive traversive)
		{
			if(!(entity instanceof Player player))
			{
				return true;
			}
			TraversalCostGateway gateway = Wormholes.traversalCostGateway;
			if(gateway == null)
			{
				return true;
			}
			World sourceWorld = portal.getStructure().getWorld();
			Location origin = traversive.getInPoint().toLocation(sourceWorld);
			traversalAdmission = gateway.open(TraversalContext.randomTeleport(
					player, portal.getId(), portal.getName(), origin));
			return traversalAdmission.allowed();
		}

		private synchronized BukkitChunkPreSendCapture capturePreSend()
		{
			if(!(entity instanceof Player player))
			{
				return null;
			}
			return BukkitChunkPreSendProvider.capture(player);
		}

		private synchronized boolean preSend(BukkitChunkPreSendCapture capture, Location target)
		{
			if(cancelled || settled)
			{
				return false;
			}
			if(preSendTransaction == null)
			{
				preSendTransaction = capture.preSend(target);
			}
			return true;
		}

		private synchronized void cancel()
		{
			cancelled = true;
		}

		private synchronized boolean canProceed()
		{
			return !cancelled && !settled;
		}

		private synchronized BukkitChunkPreSendTransaction preSendTransaction()
		{
			return preSendTransaction;
		}

		private synchronized void commit()
		{
			if(settled)
			{
				return;
			}
			settled = true;
			if(reservation != null && !builtInSettled)
			{
				builtInSettled = true;
				reservation.commit();
			}
			if(traversalAdmission != null)
			{
				traversalAdmission.commit();
			}
			if(preSendTransaction != null)
			{
				preSendTransaction.commit();
			}
		}

		private synchronized void deferCommit()
		{
			if(settled)
			{
				return;
			}
			settled = true;
			if(reservation != null && !builtInSettled)
			{
				builtInSettled = true;
				reservation.commit();
			}
			if(traversalAdmission != null)
			{
				traversalAdmission.deferCommit();
			}
			if(preSendTransaction != null)
			{
				preSendTransaction.commit();
			}
		}

		private synchronized void refund(TraversalRefundReason reason)
		{
			if(settled)
			{
				return;
			}
			settled = true;
			if(traversalAdmission != null)
			{
				traversalAdmission.refund(reason);
			}
			if(reservation != null && !builtInSettled)
			{
				builtInSettled = true;
				reservation.refund();
			}
		}

		private synchronized void rollbackPreSend()
		{
			if(preSendTransaction != null)
			{
				preSendTransaction.rollback();
			}
		}

		private synchronized void discardPreSend()
		{
			if(preSendTransaction != null)
			{
				preSendTransaction.commit();
			}
		}

	}

	private static final class SuccessfulTeleport
	{
		private final LocalPortal portal;
		private final Entity entity;
		private final Traversive traversive;
		private final RtpService.TraversalPreparation preparation;
		private final PortalFrame targetFrame;
		private final Location target;
		private final Retained retained;
		private final Active traversal;
		private final AtomicBoolean settlementFinished;
		private final AtomicBoolean arrivalFinished;

		private SuccessfulTeleport(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			PortalFrame targetFrame,
			Location target,
			Retained retained,
			Active traversal)
		{
			this.portal = portal;
			this.entity = entity;
			this.traversive = traversive;
			this.preparation = preparation;
			this.targetFrame = targetFrame;
			this.target = target;
			this.retained = retained;
			this.traversal = traversal;
			settlementFinished = new AtomicBoolean(false);
			arrivalFinished = new AtomicBoolean(false);
		}

		private LocalPortal portal()
		{
			return portal;
		}

		private Entity entity()
		{
			return entity;
		}

		private Traversive traversive()
		{
			return traversive;
		}

		private RtpService.TraversalPreparation preparation()
		{
			return preparation;
		}

		private PortalFrame targetFrame()
		{
			return targetFrame;
		}

		private Location target()
		{
			return target;
		}

		private Retained retained()
		{
			return retained;
		}

		private Active traversal()
		{
			return traversal;
		}

		private AtomicBoolean settlementFinished()
		{
			return settlementFinished;
		}

		private AtomicBoolean arrivalFinished()
		{
			return arrivalFinished;
		}
	}

	private static final class Retained implements RtpService.Retention
	{
		private final RtpService.Retention retention;
		private final AtomicBoolean closed;

		private Retained(RtpService.Retention retention)
		{
			this.retention = Objects.requireNonNull(retention, "retention");
			closed = new AtomicBoolean(false);
		}

		@Override
		public void close()
		{
			if(closed.compareAndSet(false, true))
			{
				retention.close();
			}
		}
	}
}
