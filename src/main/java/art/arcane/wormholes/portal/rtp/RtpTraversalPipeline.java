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

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.Traversive;
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
		Active claimed = new Active(portal, entity);
		if(active.putIfAbsent(entity.getUniqueId(), claimed) != null)
		{
			countTerminalFailure(FAILURE_DUPLICATE_CLAIM);
			failures.report("duplicate-traversal:" + portal.getId(),
					new IllegalStateException("RTP traversal already in progress for " + entity.getUniqueId()));
			portal.cancelRtpTraversal(entity);
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
				active.remove(entity.getUniqueId(), claimed);
				portal.cancelRtpTraversal(entity);
				return;
			}
			RtpService.TraversalPreparation admitted = preparation.get();
			if(active.get(entity.getUniqueId()) != claimed)
			{
				countTerminalFailure(FAILURE_CLAIM_SUPERSEDED);
				releaseClaim(portal.getId(), admitted);
				return;
			}
			try
			{
				boolean scheduled = environment.scheduleEntity(entity,
						() -> guard(portal, entity, admitted, null,
								() -> prepare(portal, entity, traversive, admitted)),
						() -> fail(portal, entity, admitted, null));
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
				cancel(traversal);
			}
		}
	}

	void cancelEntity(UUID entityId)
	{
		Active traversal = active.remove(entityId);
		if(traversal != null)
		{
			cancel(traversal);
		}
	}

	void cancelAll()
	{
		for(Active traversal : List.copyOf(active.values()))
		{
			cancel(traversal);
		}
		active.clear();
	}

	private void cancel(Active traversal)
	{
		countTerminalFailure(FAILURE_CANCELLED);
		traversal.portal().cancelRtpTraversal(traversal.entity());
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
				CompletionStage<Boolean> teleportStage;
				try
				{
					teleportStage = Objects.requireNonNull(environment.teleport(entity, target), "teleport stage");
				}
				catch(RuntimeException exception)
				{
					retained.close();
					fail(portal, entity, preparation, exception);
					return;
				}
				teleportStage.whenComplete((teleported, teleportFailure) -> guard(portal, entity, preparation, retained, () ->
				{
					if(teleportFailure != null || !Boolean.TRUE.equals(teleported))
					{
						retained.close();
						fail(portal, entity, preparation, teleportFailure);
						return;
					}
					confirm(portal, entity, traversive, preparation, targetFrame, target, retained);
				}));
			}));
		}), () ->
		{
			retained.close();
			fail(portal, entity, preparation, null);
		});
		if(!scheduled)
		{
			retained.close();
			fail(portal, entity, preparation,
					new IllegalStateException("Entity scheduler rejected RTP teleport dispatch"),
					FAILURE_SCHEDULER_REJECTED);
		}
	}

	private void confirm(
			LocalPortal portal,
			Entity entity,
			Traversive traversive,
			RtpService.TraversalPreparation preparation,
			PortalFrame targetFrame,
			Location target,
			Retained retained)
	{
		boolean scheduled = environment.scheduleEntity(entity, () -> guard(portal, entity, preparation, retained, () ->
		{
			boolean arrived = arrivedAt(entity, target) && !attached(entity);
			if(!arrived && withinSource(portal, entity))
			{
				retained.close();
				fail(portal, entity, preparation, null);
				return;
			}
			if(!arrived)
			{
				recoveredArrivals.incrementAndGet();
				failures.report("arrival-mismatch:" + portal.getId(),
						new IllegalStateException("RTP traveller left the source without reaching the confirmed destination"));
			}
			service.completeTraversal(preparation, true).whenComplete((completed, completionFailure) -> guard(portal, entity, preparation, retained, () ->
			{
				retained.close();
				active.remove(entity.getUniqueId());
				if(completionFailure != null)
				{
					failures.report("complete-success:" + portal.getId(), completionFailure);
				}
				if(completionFailure != null || !Boolean.TRUE.equals(completed))
				{
					recoveredArrivals.incrementAndGet();
				}
				environment.completeSuccess(portal, entity, traversive, targetFrame, target);
			}));
		}), () ->
		{
			retained.close();
			fail(portal, entity, preparation, null);
		});
		if(!scheduled)
		{
			retained.close();
			fail(portal, entity, preparation,
					new IllegalStateException("Entity scheduler rejected RTP arrival confirmation"),
					FAILURE_SCHEDULER_REJECTED);
		}
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
		fail(portal, entity, preparation, failure, FAILURE_STAGE);
	}

	private void fail(
			LocalPortal portal,
			Entity entity,
			RtpService.TraversalPreparation preparation,
			Throwable failure,
			String reason)
	{
		if(failure != null)
		{
			failures.report("traversal:" + portal.getId(), failure);
		}
		countTerminalFailure(reason);
		active.remove(entity.getUniqueId());
		portal.cancelRtpTraversal(entity);
		releaseClaim(portal.getId(), preparation);
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

	private record Active(LocalPortal portal, Entity entity)
	{
		private Active
		{
			Objects.requireNonNull(portal, "portal");
			Objects.requireNonNull(entity, "entity");
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
