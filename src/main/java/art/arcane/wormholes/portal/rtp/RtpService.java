package art.arcane.wormholes.portal.rtp;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class RtpService
{
	private static final long TRAVERSAL_DISPATCH_DEADLINE_MILLIS = 10_000L;

	private final Dependencies dependencies;
	private final ConcurrentMap<UUID, RtpPortalEntry> entries;
	private final ConcurrentMap<UUID, Snapshot> published;

	public RtpService(Dependencies dependencies)
	{
		this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
		this.entries = new ConcurrentHashMap<UUID, RtpPortalEntry>();
		this.published = new ConcurrentHashMap<UUID, Snapshot>();
	}

	public CompletableFuture<Snapshot> register(Registration registration)
	{
		Registration requiredRegistration = Objects.requireNonNull(registration, "registration");
		return onSource(requiredRegistration.portalId(), () -> registerOnSource(requiredRegistration));
	}

	public CompletableFuture<Boolean> unregister(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> unregisterOnSource(requiredPortalId));
	}

	public CompletableFuture<Boolean> updateSettings(UUID portalId, RtpSettings settings)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		return onSource(requiredPortalId, () -> updateSettingsOnSource(requiredPortalId, requiredSettings));
	}

	public CompletableFuture<Boolean> touchViewer(UUID portalId, UUID viewerId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		return onSource(requiredPortalId, () -> touchViewerOnSource(requiredPortalId, requiredViewerId));
	}

	public CompletableFuture<Boolean> leaveViewer(UUID portalId, UUID viewerId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		return onSource(requiredPortalId, () -> leaveViewerOnSource(requiredPortalId, requiredViewerId));
	}

	public CompletableFuture<Boolean> tick(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> tickOnSource(requiredPortalId));
	}

	public CompletableFuture<Boolean> manualReroll(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> manualRerollOnSource(requiredPortalId));
	}

	public CompletableFuture<Set<RtpDestination>> rebuildPool(UUID portalId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		return onSource(requiredPortalId, () -> rebuildPoolOnSource(requiredPortalId));
	}

	public CompletableFuture<Optional<TraversalPreparation>> claimTraversal(UUID portalId, TraversalActor actor)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		TraversalActor requiredActor = Objects.requireNonNull(actor, "actor");
		CompletableFuture<Optional<TraversalPreparation>> result = new CompletableFuture<Optional<TraversalPreparation>>();
		try
		{
			dependencies.sourceDispatcher().execute(requiredPortalId, () ->
			{
				try
				{
					beginTraversal(requiredPortalId, requiredActor, result);
				}
				catch(RuntimeException exception)
				{
					if(!result.completeExceptionally(exception))
					{
						throw exception;
					}
				}
			});
		}
		catch(RuntimeException exception)
		{
			result.completeExceptionally(exception);
		}
		return result;
	}

	public CompletableFuture<Boolean> markTraversalDispatched(TraversalPreparation preparation)
	{
		TraversalPreparation requiredPreparation = Objects.requireNonNull(preparation, "preparation");
		return onSource(requiredPreparation.portalId(), () ->
		{
			boolean dispatched = requiredPreparation.request().markDispatched(requiredPreparation.generation());
			if(dispatched)
			{
				dependencies.sourceDispatcher().schedule(
						requiredPreparation.portalId(),
						() -> requiredPreparation.request().reconcileDispatchedTimeout(requiredPreparation.generation(), false),
						TRAVERSAL_DISPATCH_DEADLINE_MILLIS);
			}
			return dispatched;
		});
	}

	public CompletableFuture<Boolean> completeTraversal(TraversalPreparation preparation, boolean succeeded)
	{
		TraversalPreparation requiredPreparation = Objects.requireNonNull(preparation, "preparation");
		return onSource(requiredPreparation.portalId(), () -> succeeded
				? requiredPreparation.request().markSucceeded(requiredPreparation.generation())
				: requiredPreparation.request().markFailed(requiredPreparation.generation()));
	}

	public Optional<Snapshot> snapshot(UUID portalId)
	{
		return Optional.ofNullable(published.get(Objects.requireNonNull(portalId, "portalId")));
	}

	public RtpProjectionView projectionView(UUID portalId, UUID viewerId)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		Snapshot snapshot = published.get(requiredPortalId);
		if(snapshot == null)
		{
			return RtpProjectionView.none(requiredViewerId, 0L);
		}
		RtpProjectionView view = snapshot.views().get(requiredViewerId);
		return view == null ? RtpProjectionView.none(requiredViewerId, snapshot.revision()) : view;
	}

	public Optional<RtpRimRenderer.Sample> rimSample(
			UUID portalId,
			UUID viewerId,
			RtpRimRenderer.Phase phase,
			long elapsedMillis)
	{
		UUID requiredPortalId = Objects.requireNonNull(portalId, "portalId");
		UUID requiredViewerId = Objects.requireNonNull(viewerId, "viewerId");
		RtpRimRenderer.Phase requiredPhase = Objects.requireNonNull(phase, "phase");
		Snapshot snapshot = published.get(requiredPortalId);
		if(snapshot == null)
		{
			return Optional.empty();
		}
		RtpProjectionView view = snapshot.views().getOrDefault(
				requiredViewerId,
				RtpProjectionView.none(requiredViewerId, snapshot.revision()));
		RtpRimRenderer.Input input = new RtpRimRenderer.Input(
				requiredViewerId,
				view,
				snapshot.settings().isRimEnabled(),
				snapshot.viewers().contains(requiredViewerId),
				snapshot.runtime().rotationMode(),
				requiredPhase,
				elapsedMillis,
				snapshot.settings().getCycleDurationMillis());
		return dependencies.rimRenderer().calculate(input);
	}

	Dependencies dependencies()
	{
		return dependencies;
	}

	private Snapshot registerOnSource(Registration registration)
	{
		RtpPortalEntry previous = entries.get(registration.portalId());
		long generation = previous == null
				? ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE >> 8)
				: nextGeneration(previous.generation);
		Set<UUID> viewers = previous == null ? Set.of() : Set.copyOf(previous.viewers);
		RtpPortalEntry replacement = new RtpPortalEntry(this, registration, generation, createRuntime(generation, registration.settings()));
		replacement.viewers.addAll(viewers);
		entries.put(registration.portalId(), replacement);
		if(previous != null)
		{
			closeEntry(previous);
		}
		activateViewers(replacement);
		replacement.markChanged();
		maintain(replacement);
		return published.get(registration.portalId());
	}

	private boolean unregisterOnSource(UUID portalId)
	{
		RtpPortalEntry entry = entries.remove(portalId);
		if(entry == null)
		{
			return false;
		}
		published.remove(portalId);
		closeEntry(entry);
		return true;
	}

	private boolean updateSettingsOnSource(UUID portalId, RtpSettings settings)
	{
		RtpPortalEntry previous = entries.get(portalId);
		if(previous == null || previous.registration.settings().equals(settings))
		{
			return false;
		}
		Registration registration = new Registration(
				portalId,
				settings,
				previous.registration.centerX(),
				previous.registration.centerZ(),
				previous.registration.seed());
		long generation = nextGeneration(previous.generation);
		RtpPortalEntry replacement = new RtpPortalEntry(
				this,
				registration,
				generation,
				createRuntime(generation, settings));
		replacement.viewers.addAll(previous.viewers);
		entries.put(portalId, replacement);
		closeEntry(previous);
		activateViewers(replacement);
		replacement.markChanged();
		maintain(replacement);
		return true;
	}

	private boolean touchViewerOnSource(UUID portalId, UUID viewerId)
	{
		RtpPortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			return false;
		}
		boolean changed = entry.viewers.add(viewerId);
		entry.idleToken = nextGeneration(entry.idleToken);
		RtpProjectionView currentView = entry.views.view(viewerId);
		boolean refreshAccess = changed
				|| currentView == null
				|| currentView.state() == RtpProjectionView.State.DENIED
				|| currentView.state() == RtpProjectionView.State.FAILED;
		if(refreshAccess)
		{
			entry.views.forget(viewerId);
		}
		if(entry.runtime.snapshot().allocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			changed = entry.runtime.touchPlayer(viewerId) || changed;
		}
		if(changed || refreshAccess)
		{
			entry.markChanged();
		}
		maintain(entry);
		return changed;
	}

	private boolean leaveViewerOnSource(UUID portalId, UUID viewerId)
	{
		RtpPortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			return false;
		}
		boolean changed = entry.viewers.remove(viewerId);
		entry.views.forget(viewerId);
		if(entry.runtime.snapshot().allocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			changed = entry.runtime.leavePlayer(viewerId, dependencies.timeSource().nowMillis()) || changed;
		}
		if(entry.viewers.isEmpty())
		{
			scheduleIdle(entry);
		}
		entry.markChanged();
		maintain(entry);
		return changed;
	}

	private boolean tickOnSource(UUID portalId)
	{
		RtpPortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			return false;
		}
		maintain(entry);
		return true;
	}

	private boolean manualRerollOnSource(UUID portalId)
	{
		RtpPortalEntry entry = entries.get(portalId);
		if(entry == null || !entry.runtime.startManualReroll())
		{
			return false;
		}
		entry.search.resetBackoff();
		entry.markChanged();
		maintain(entry);
		return true;
	}

	private Set<RtpDestination> rebuildPoolOnSource(UUID portalId)
	{
		RtpPortalEntry entry = entries.get(portalId);
		if(entry == null || entry.runtime.snapshot().allocationMode() != RtpAllocationMode.PER_PLAYER)
		{
			return Set.of();
		}
		Set<RtpDestination> invalidated = entry.runtime.rebuildPool();
		for(RtpDestination destination : invalidated)
		{
			entry.destinations.release(destination);
		}
		entry.search.resetBackoff();
		entry.markChanged();
		maintain(entry);
		return invalidated;
	}

	private void activateViewers(RtpPortalEntry entry)
	{
		if(entry.runtime.snapshot().allocationMode() != RtpAllocationMode.PER_PLAYER)
		{
			return;
		}
		for(UUID viewerId : entry.viewers)
		{
			entry.runtime.touchPlayer(viewerId);
		}
	}

	void maintain(RtpPortalEntry entry)
	{
		if(!isCurrent(entry))
		{
			return;
		}
		long nowMillis = dependencies.timeSource().nowMillis();
		RtpRuntimeSnapshot before = entry.runtime.snapshot();
		if(before.allocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			entry.runtime.expireReservations(nowMillis);
			entry.runtime.rotatePlayerReservations(nowMillis);
			assignReservations(entry, nowMillis);
		}
		else
		{
			entry.runtime.advanceTimedRotation(nowMillis, !entry.viewers.isEmpty());
		}
		RtpRuntimeSnapshot after = entry.runtime.snapshot();
		if(!before.equals(after))
		{
			entry.markChanged();
			entry.pruneSharedRetentions(after);
		}
		if(entry.viewers.isEmpty())
		{
			publish(entry);
			return;
		}
		entry.destinations.prepareVisible();
		entry.views.refreshAll();
		entry.search.ensure();
		publish(entry);
	}

	private void assignReservations(RtpPortalEntry entry, long nowMillis)
	{
		for(UUID viewerId : entry.viewers)
		{
			if(entry.runtime.reservationFor(viewerId).isEmpty())
			{
				entry.runtime.reservePlayer(viewerId, nowMillis);
			}
		}
	}

	private void beginTraversal(
			UUID portalId,
			TraversalActor actor,
			CompletableFuture<Optional<TraversalPreparation>> admission)
	{
		RtpPortalEntry entry = entries.get(portalId);
		if(entry == null)
		{
			admission.complete(Optional.empty());
			return;
		}
		entry.traversals.begin(actor, admission);
	}

	private void idleEntry(RtpPortalEntry entry)
	{
		entry.search.cancel();
		entry.destinations.releaseAll();
		entry.destinations.clearFailures();
		entry.views.clearAll();
	}

	private void scheduleIdle(RtpPortalEntry entry)
	{
		long token = nextGeneration(entry.idleToken);
		entry.idleToken = token;
		dependencies.sourceDispatcher().schedule(
				entry.portalId(),
				() ->
				{
					if(!isCurrent(entry) || !entry.viewers.isEmpty() || entry.idleToken != token)
					{
						return;
					}
					idleEntry(entry);
					entry.markChanged();
					publish(entry);
				},
				entry.registration.settings().getLeaseIdleMillis());
	}

	private void closeEntry(RtpPortalEntry entry)
	{
		entry.closed = true;
		entry.search.cancel();
		entry.traversals.cancelAll();
		entry.views.clearAttempts();
		entry.destinations.releaseAll();
		entry.views.clearViews();
		entry.viewers.clear();
	}

	private RtpPortalRuntime createRuntime(long generation, RtpSettings settings)
	{
		return settings.getAllocationMode() == RtpAllocationMode.SHARED
				? RtpPortalRuntime.shared(generation, settings.getRotationMode(), settings.getCycleDurationMillis())
				: RtpPortalRuntime.perPlayer(
						generation,
						settings.getPrivateReleaseMillis(),
						settings.getCycleDurationMillis());
	}

	void dispatch(RtpPortalEntry entry, Runnable command)
	{
		dependencies.sourceDispatcher().execute(entry.portalId(), command);
	}

	void dispatchClosing(RtpPortalEntry entry, Runnable cleanupOnReject, Runnable command)
	{
		try
		{
			dispatch(entry, command);
		}
		catch(RuntimeException | Error failure)
		{
			cleanupOnReject.run();
			throw failure;
		}
	}

	boolean isCurrent(RtpPortalEntry entry)
	{
		return !entry.closed && entries.get(entry.portalId()) == entry;
	}

	void publish(RtpPortalEntry entry)
	{
		if(!isCurrent(entry))
		{
			return;
		}
		Snapshot snapshot = new Snapshot(
				entry.portalId(),
				entry.generation,
				entry.revision,
				entry.search.nextAllowedAtMillis(),
				!entry.accessIntegrationFailed,
				entry.registration.settings(),
				entry.runtime.snapshot(),
				Set.copyOf(entry.viewers),
				entry.views.published());
		published.put(entry.portalId(), snapshot);
	}

	private long nextGeneration(long current)
	{
		return current == Long.MAX_VALUE ? 1L : current + 1L;
	}

	private <T> CompletableFuture<T> onSource(UUID portalId, Supplier<T> operation)
	{
		CompletableFuture<T> result = new CompletableFuture<T>();
		try
		{
			dependencies.sourceDispatcher().execute(portalId, () ->
			{
				try
				{
					result.complete(operation.get());
				}
				catch(RuntimeException exception)
				{
					result.completeExceptionally(exception);
				}
			});
		}
		catch(RuntimeException exception)
		{
			result.completeExceptionally(exception);
		}
		return result;
	}

	public record Dependencies(
			SourceDispatcher sourceDispatcher,
			SearchExecutor searchExecutor,
			TimeSource timeSource,
			Sampler sampler,
			CandidateLoader candidateLoader,
			SafetyValidator safetyValidator,
			AccessChecker accessChecker,
			ProjectionFactory projectionFactory,
			RtpRimRenderer rimRenderer)
	{
		public Dependencies
		{
			Objects.requireNonNull(sourceDispatcher, "sourceDispatcher");
			Objects.requireNonNull(searchExecutor, "searchExecutor");
			Objects.requireNonNull(timeSource, "timeSource");
			Objects.requireNonNull(sampler, "sampler");
			Objects.requireNonNull(candidateLoader, "candidateLoader");
			Objects.requireNonNull(safetyValidator, "safetyValidator");
			Objects.requireNonNull(accessChecker, "accessChecker");
			Objects.requireNonNull(projectionFactory, "projectionFactory");
			Objects.requireNonNull(rimRenderer, "rimRenderer");
		}
	}

	public record Registration(UUID portalId, RtpSettings settings, double centerX, double centerZ, long seed)
	{
		public Registration
		{
			Objects.requireNonNull(portalId, "portalId");
			Objects.requireNonNull(settings, "settings");
			if(!Double.isFinite(centerX) || !Double.isFinite(centerZ))
			{
				throw new IllegalArgumentException("center coordinates must be finite");
			}
		}
	}

	public record SearchRequest(UUID portalId, long generation, RtpSettings settings, RtpDestination destination)
	{
		public SearchRequest
		{
			Objects.requireNonNull(portalId, "portalId");
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(destination, "destination");
		}
	}

	public record LoadedCandidate(RtpValidationRequest validationRequest, Retention retention)
	{
		public LoadedCandidate
		{
			Objects.requireNonNull(validationRequest, "validationRequest");
			Objects.requireNonNull(retention, "retention");
		}
	}

	public record Snapshot(
			UUID portalId,
			long generation,
			long revision,
			long nextSearchAllowedAtMillis,
			boolean integrationAvailable,
			RtpSettings settings,
			RtpRuntimeSnapshot runtime,
			Set<UUID> viewers,
			Map<UUID, RtpProjectionView> views)
	{
		public Snapshot
		{
			Objects.requireNonNull(portalId, "portalId");
			if(nextSearchAllowedAtMillis < 0L)
			{
				throw new IllegalArgumentException("nextSearchAllowedAtMillis must be non-negative");
			}
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(runtime, "runtime");
			viewers = Set.copyOf(Objects.requireNonNull(viewers, "viewers"));
			views = Map.copyOf(Objects.requireNonNull(views, "views"));
		}
	}

	public record TraversalActor(UUID claimId, UUID playerIdValue)
	{
		public TraversalActor
		{
			Objects.requireNonNull(claimId, "claimId");
		}

		public static TraversalActor player(UUID claimId, UUID playerId)
		{
			return new TraversalActor(claimId, Objects.requireNonNull(playerId, "playerId"));
		}

		public static TraversalActor anonymous(UUID claimId)
		{
			return new TraversalActor(claimId, null);
		}

		public Optional<UUID> playerId()
		{
			return Optional.ofNullable(playerIdValue);
		}
	}

	public static final class TraversalPreparation
	{
		private final UUID portalId;
		private final long generation;
		private final TraversalActor actor;
		private final RtpPortalRuntime.TraversalClaim claim;
		private final RtpTraversalRequest request;
		final CompletableFuture<Optional<TraversalPreparation>> admission;
		final AtomicBoolean terminalized;

		TraversalPreparation(
				UUID portalId,
				long generation,
				TraversalActor actor,
				RtpPortalRuntime.TraversalClaim claim,
				RtpTraversalRequest request,
				CompletableFuture<Optional<TraversalPreparation>> admission)
		{
			this.portalId = portalId;
			this.generation = generation;
			this.actor = actor;
			this.claim = claim;
			this.request = request;
			this.admission = admission;
			this.terminalized = new AtomicBoolean(false);
		}

		public UUID portalId()
		{
			return portalId;
		}

		public long generation()
		{
			return generation;
		}

		public TraversalActor actor()
		{
			return actor;
		}

		public RtpPortalRuntime.TraversalClaim claim()
		{
			return claim;
		}

		public RtpTraversalRequest request()
		{
			return request;
		}
	}

	@FunctionalInterface
	public interface SourceDispatcher
	{
		void execute(UUID portalId, Runnable command);

		default void schedule(UUID portalId, Runnable command, long delayMillis)
		{
			throw new UnsupportedOperationException("delayed source dispatch is required");
		}
	}

	@FunctionalInterface
	public interface SearchExecutor
	{
		void execute(Runnable command);
	}

	@FunctionalInterface
	public interface TimeSource
	{
		long nowMillis();
	}

	@FunctionalInterface
	public interface Sampler
	{
		RtpDestination sample(Registration registration, long generation, int attempt);
	}

	@FunctionalInterface
	public interface CandidateLoader
	{
		CompletionStage<LoadedCandidate> load(SearchRequest request);
	}

	@FunctionalInterface
	public interface SafetyValidator
	{
		CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request);
	}

	@FunctionalInterface
	public interface AccessChecker
	{
		CompletionStage<RtpAccessResult> canUse(UUID portalId, Optional<UUID> viewerId, RtpDestination destination);
	}

	@FunctionalInterface
	public interface ProjectionFactory
	{
		RtpProjectionView.ReadyData create(UUID portalId, UUID viewerId, RtpDestination destination, long routeRevision);
	}

	@FunctionalInterface
	public interface Retention
	{
		void close();
	}
}
