package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;

public final class BukkitRtpRuntime implements ProjectionManager.RtpProjectionProvider, AutoCloseable
{
	private static final double MINIMUM_ENVELOPE_EXTENT = 0.05D;

	private final RtpService service;
	private final Environment environment;
	private final RtpFailureThrottle failures;
	private final RtpAttendanceRegistry attendance;
	private final RtpTraversalPipeline traversals;
	private final Map<UUID, PortalRegistration> registrations;
	private final AtomicBoolean closed;

	public BukkitRtpRuntime(RtpService service, Environment environment, long attendanceIdleMillis)
	{
		this.service = Objects.requireNonNull(service, "service");
		this.environment = Objects.requireNonNull(environment, "environment");
		if(attendanceIdleMillis <= 0L)
		{
			throw new IllegalArgumentException("attendanceIdleMillis must be positive");
		}
		failures = new RtpFailureThrottle(environment);
		attendance = new RtpAttendanceRegistry(service, failures, attendanceIdleMillis);
		traversals = new RtpTraversalPipeline(service, environment, failures);
		registrations = new ConcurrentHashMap<UUID, PortalRegistration>();
		closed = new AtomicBoolean(false);
	}

	public void synchronize(LocalPortal portal)
	{
		LocalPortal requiredPortal = Objects.requireNonNull(portal, "portal");
		if(closed.get() || requiredPortal.getType() != PortalType.RTP || requiredPortal.getRtpSettings() == null)
		{
			unregister(requiredPortal.getId());
			return;
		}
		PortalRegistration replacement = registration(requiredPortal);
		environment.sourceRegistered(requiredPortal.getId(), requiredPortal.getCenter());
		PortalRegistration previous = registrations.put(requiredPortal.getId(), replacement);
		if(previous != null && replacement.hasSameRouteAs(previous))
		{
			return;
		}
		if(previous != null)
		{
			traversals.cancelPortal(requiredPortal.getId());
		}
		service.register(replacement.registration()).whenComplete((snapshot, failure) ->
		{
			if(failure != null)
			{
				failures.report("register:" + requiredPortal.getId(), failure);
			}
		});
	}

	public void unregister(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		PortalRegistration removed = registrations.remove(requiredPortalId);
		attendance.forgetPortal(requiredPortalId);
		traversals.cancelPortal(requiredPortalId);
		if(removed == null && service.snapshot(requiredPortalId).isEmpty())
		{
			environment.sourceUnregistered(requiredPortalId);
			return;
		}
		service.unregister(requiredPortalId).whenComplete((changed, failure) ->
		{
			environment.sourceUnregistered(requiredPortalId);
			if(failure != null)
			{
				failures.report("unregister:" + requiredPortalId, failure);
			}
		});
	}

	public void tick(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		if(closed.get() || !registrations.containsKey(requiredPortalId))
		{
			return;
		}
		service.tick(requiredPortalId).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				failures.report("tick:" + requiredPortalId, failure);
			}
		});
	}

	public long traversalFailures()
	{
		return traversals.terminalFailures();
	}

	public long traversalRecoveries()
	{
		return traversals.recoveredArrivals();
	}

	public boolean isReady(UUID portalId)
	{
		if(closed.get())
		{
			return false;
		}
		Optional<RtpService.Snapshot> snapshot = service.snapshot(Objects.requireNonNull(portalId, "portalId"));
		return snapshot.isPresent()
				&& !snapshot.get().viewers().isEmpty()
				&& (snapshot.get().runtime().ready()
						|| snapshot.get().views().values().stream()
								.anyMatch(view -> view.state() == RtpProjectionView.State.READY));
	}

	public RtpService.Snapshot snapshotOrNull(UUID portalId)
	{
		if(closed.get() || portalId == null)
		{
			return null;
		}
		return service.snapshot(portalId).orElse(null);
	}

	public Optional<RtpPortalEditorModel.StatusSnapshot> editorStatus(UUID portalId)
	{
		if(closed.get())
		{
			return Optional.empty();
		}
		Optional<RtpService.Snapshot> snapshot = service.snapshot(Objects.requireNonNull(portalId, "portalId"));
		if(snapshot.isEmpty())
		{
			return Optional.empty();
		}
		RtpService.Snapshot current = snapshot.get();
		RtpPortalEditorModel.StatusContext context = new RtpPortalEditorModel.StatusContext(
				environment.resolveWorld(current.settings().getTargetWorldKey()) != null,
				current.integrationAvailable(),
				environment.nowMillis(),
				current.nextSearchAllowedAtMillis());
		return Optional.of(RtpPortalEditorModel.StatusSnapshot.from(current.runtime(), context));
	}

	public CompletableFuture<Boolean> requestManualReroll(UUID portalId)
	{
		if(closed.get())
		{
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}
		return service.manualReroll(Objects.requireNonNull(portalId, "portalId"));
	}

	public CompletableFuture<Set<RtpDestination>> requestPoolRebuild(UUID portalId)
	{
		if(closed.get())
		{
			return CompletableFuture.completedFuture(Set.of());
		}
		return service.rebuildPool(Objects.requireNonNull(portalId, "portalId"));
	}

	@Override
	public boolean supports(ILocalPortal portal)
	{
		return !closed.get() && portal instanceof LocalPortal localPortal && localPortal.getType() == PortalType.RTP;
	}

	@Override
	public ProjectionManager.RtpProjectionResult touch(ILocalPortal portal, Player observer)
	{
		Objects.requireNonNull(portal, "portal");
		Player requiredObserver = Objects.requireNonNull(observer, "observer");
		if(!(portal instanceof LocalPortal localPortal) || !supports(portal))
		{
			return projectionResult(portal, requiredObserver.getUniqueId(), false);
		}
		synchronize(localPortal);
		attendance.touch(localPortal, requiredObserver.getUniqueId(), environment.nowMillis());
		return projectionResult(localPortal, requiredObserver.getUniqueId(), true);
	}

	@Override
	public World resolveTargetWorld(String worldKey)
	{
		return environment.resolveWorld(Objects.requireNonNull(worldKey, "worldKey"));
	}

	@Override
	public void dispatchRim(ILocalPortal portal, Player observer, RtpRimRenderer.Sample sample)
	{
		if(portal instanceof LocalPortal localPortal && !closed.get())
		{
			environment.dispatchRim(localPortal, observer, sample);
		}
	}

	public void leaveViewer(UUID viewerId)
	{
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		traversals.cancelEntity(requiredViewerId);
		attendance.departViewer(requiredViewerId);
	}

	public void viewerMoved(Player viewer, Location destination)
	{
		Player requiredViewer = Objects.requireNonNull(viewer, "viewer");
		Location requiredDestination = Objects.requireNonNull(destination, "destination");
		attendance.departOutside(requiredViewer.getUniqueId(), requiredDestination);
	}

	public void sweepAttendance()
	{
		attendance.sweep(environment.nowMillis());
	}

	public void worldUnloaded(UUID worldId)
	{
		UUID requiredWorldId = Objects.requireNonNull(worldId, "worldId");
		environment.worldUnloaded(requiredWorldId);
		List<UUID> affected = new ArrayList<UUID>();
		for(Map.Entry<UUID, PortalRegistration> entry : registrations.entrySet())
		{
			PortalRegistration registration = entry.getValue();
			if(requiredWorldId.equals(registration.sourceWorldId()) || requiredWorldId.equals(registration.targetWorldId()))
			{
				affected.add(entry.getKey());
			}
		}
		for(UUID portalId : affected)
		{
			unregister(portalId);
		}
	}

	public boolean traverse(LocalPortal portal, Entity entity, Traversive traversive)
	{
		LocalPortal requiredPortal = Objects.requireNonNull(portal, "portal");
		Entity requiredEntity = Objects.requireNonNull(entity, "entity");
		Traversive requiredTraversive = Objects.requireNonNull(traversive, "traversive");
		if(closed.get() || !supports(requiredPortal) || !isReady(requiredPortal.getId()))
		{
			return false;
		}
		return traversals.begin(requiredPortal, requiredEntity, requiredTraversive);
	}

	public void abortTraversal(UUID entityId)
	{
		traversals.cancelEntity(Objects.requireNonNull(entityId, "entityId"));
	}

	@Override
	public void close()
	{
		if(!closed.compareAndSet(false, true))
		{
			return;
		}
		List<UUID> portalIds = List.copyOf(registrations.keySet());
		attendance.clear();
		registrations.clear();
		traversals.cancelAll();
		for(UUID portalId : portalIds)
		{
			service.unregister(portalId).whenComplete((changed, failure) ->
			{
				environment.sourceUnregistered(portalId);
				if(failure != null)
				{
					failures.report("shutdown-unregister:" + portalId, failure);
				}
			});
		}
		environment.close();
	}

	private ProjectionManager.RtpProjectionResult projectionResult(ILocalPortal portal, UUID viewerId, boolean attended)
	{
		Optional<RtpService.Snapshot> optionalSnapshot = service.snapshot(portal.getId());
		if(optionalSnapshot.isEmpty())
		{
			return new ProjectionManager.RtpProjectionResult(
					RtpProjectionView.none(viewerId, 0L),
					portal.isProjecting(),
					false,
					attended,
					RtpRotationMode.STATIC,
					RtpRimRenderer.Phase.PREPARING,
					0L,
					0L);
		}
		RtpService.Snapshot snapshot = optionalSnapshot.get();
		RtpRuntimeSnapshot runtime = snapshot.runtime();
		RtpSettings liveSettings = portal instanceof LocalPortal localPortal && localPortal.getRtpSettings() != null
				? localPortal.getRtpSettings() : snapshot.settings();
		long durationMillis = liveSettings.getRotationMode() == RtpRotationMode.TIMED
				? liveSettings.getCycleDurationMillis() : 0L;
		long elapsedMillis = durationMillis == 0L || runtime.nextRotationAtMillis() <= 0L
				? 0L : Math.max(0L, durationMillis - Math.max(0L, runtime.nextRotationAtMillis() - environment.nowMillis()));
		RtpProjectionView view = service.projectionView(portal.getId(), viewerId);
		RtpRimRenderer.Phase phase = runtime.ready() || view.state() == RtpProjectionView.State.READY
				? RtpRimRenderer.Phase.READY
				: runtime.sharedClaims() + runtime.playerClaims() + runtime.anonymousClaims() > 0
						? RtpRimRenderer.Phase.CLOSING : RtpRimRenderer.Phase.PREPARING;
		return new ProjectionManager.RtpProjectionResult(
				view,
				portal.isProjecting(),
				liveSettings.isRimEnabled(),
				attended,
				liveSettings.getRotationMode(),
				phase,
				elapsedMillis,
				durationMillis);
	}

	private PortalRegistration registration(LocalPortal portal)
	{
		RtpSettings settings = Objects.requireNonNull(portal.getRtpSettings(), "portal RTP settings");
		Location center = Objects.requireNonNull(portal.getCenter(), "portal center");
		double centerX = settings.getCenterMode() == RtpCenterMode.CUSTOM
				? Objects.requireNonNull(settings.getCustomCenterX(), "custom center X").doubleValue() : center.getX();
		double centerZ = settings.getCenterMode() == RtpCenterMode.CUSTOM
				? Objects.requireNonNull(settings.getCustomCenterZ(), "custom center Z").doubleValue() : center.getZ();
		World sourceWorld = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
		World targetWorld = settings.getTargetWorld();
		RtpService.Registration registration = new RtpService.Registration(
				portal.getId(), settings, centerX, centerZ, portal.getId().getMostSignificantBits() ^ portal.getId().getLeastSignificantBits());
		return new PortalRegistration(
				registration,
				sourceWorld.getUID(),
				targetWorld == null ? null : targetWorld.getUID());
	}

	public static boolean physicallyTraversable(Entity entity)
	{
		BoundingBox box = entity.getBoundingBox();
		return box.getMaxX() > box.getMinX() && box.getMaxY() > box.getMinY() && box.getMaxZ() > box.getMinZ();
	}

	static double[] normalizedEnvelopeAxis(double minimum, double maximum)
	{
		if(maximum - minimum >= MINIMUM_ENVELOPE_EXTENT)
		{
			return new double[] {minimum, maximum};
		}
		double center = (minimum + maximum) / 2.0D;
		double half = MINIMUM_ENVELOPE_EXTENT / 2.0D;
		return new double[] {center - half, center + half};
	}

	static PortalFrame targetFrameFor(PortalFrame sourceFrame)
	{
		Direction sourceNormal = sourceFrame.getNormal();
		Direction horizontal = sourceNormal.isVertical() ? sourceFrame.getUp() : sourceNormal;
		if(horizontal.isVertical())
		{
			horizontal = Direction.N;
		}
		return PortalFrame.fromNormalUp(horizontal, Direction.U);
	}

	public interface Environment extends AutoCloseable
	{
		long nowMillis();

		void sourceRegistered(UUID portalId, Location anchor);

		void sourceUnregistered(UUID portalId);

		World resolveWorld(String worldKey);

		CompletionStage<RtpService.LoadedCandidate> loadTraversal(
				RtpService.SearchRequest request,
				RtpValidationRequest.EntityEnvelope envelope);

		CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request);

		CompletionStage<RtpAccessResult> canUse(Player player, RtpDestination destination);

		boolean scheduleEntity(Entity entity, Runnable command, Runnable retired);

		CompletionStage<Boolean> teleport(Entity entity, Location target);

		void completeSuccess(LocalPortal portal, Entity entity, Traversive traversive, PortalFrame targetFrame, Location target);

		void dispatchRim(LocalPortal portal, Player observer, RtpRimRenderer.Sample sample);

		void worldUnloaded(UUID worldId);

		void reportFailure(String context, Throwable failure);

		@Override
		void close();
	}

	private record PortalRegistration(
			RtpService.Registration registration,
			UUID sourceWorldId,
			UUID targetWorldId)
	{
		private PortalRegistration
		{
			Objects.requireNonNull(registration, "registration");
			Objects.requireNonNull(sourceWorldId, "sourceWorldId");
		}

		private boolean hasSameRouteAs(PortalRegistration other)
		{
			return registration.portalId().equals(other.registration.portalId())
					&& Double.compare(registration.centerX(), other.registration.centerX()) == 0
					&& Double.compare(registration.centerZ(), other.registration.centerZ()) == 0
					&& registration.seed() == other.registration.seed()
					&& registration.settings().hasSameRouteAs(other.registration.settings())
					&& sourceWorldId.equals(other.sourceWorldId)
					&& Objects.equals(targetWorldId, other.targetWorldId);
		}
	}
}
