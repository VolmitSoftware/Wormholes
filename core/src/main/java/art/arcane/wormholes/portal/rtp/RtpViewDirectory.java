package art.arcane.wormholes.portal.rtp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class RtpViewDirectory
{
	private final RtpService service;
	private final RtpPortalEntry entry;
	private final Map<UUID, RtpProjectionView> views;
	private final Map<UUID, Identity> identities;
	private final Map<UUID, Attempt> attempts;

	RtpViewDirectory(RtpService service, RtpPortalEntry entry)
	{
		this.service = service;
		this.entry = entry;
		this.views = new LinkedHashMap<UUID, RtpProjectionView>();
		this.identities = new LinkedHashMap<UUID, Identity>();
		this.attempts = new LinkedHashMap<UUID, Attempt>();
	}

	RtpProjectionView view(UUID viewerId)
	{
		return views.get(viewerId);
	}

	Map<UUID, RtpProjectionView> published()
	{
		return Map.copyOf(views);
	}

	void forget(UUID viewerId)
	{
		attempts.remove(viewerId);
		identities.remove(viewerId);
		views.remove(viewerId);
	}

	void clearAll()
	{
		attempts.clear();
		identities.clear();
		views.clear();
	}

	void clearAttempts()
	{
		attempts.clear();
	}

	void clearViews()
	{
		views.clear();
	}

	void collectRetained(Set<RtpDestination> retained)
	{
		for(Identity identity : identities.values())
		{
			retained.add(identity.destination());
		}
		for(Attempt attempt : attempts.values())
		{
			retained.add(attempt.identity().destination());
		}
	}

	void refreshAll()
	{
		for(UUID viewerId : entry.viewers)
		{
			refresh(viewerId);
		}
	}

	private void refresh(UUID viewerId)
	{
		RtpRuntimeSnapshot runtimeSnapshot = entry.runtime.snapshot();
		RtpDestination destination = destinationFor(viewerId, runtimeSnapshot);
		if(destination == null)
		{
			setWarmingUnlessReady(viewerId, null, 0L);
			return;
		}
		long routeRevision = routeRevision(runtimeSnapshot, destination);
		Identity identity = new Identity(destination, routeRevision);
		if(entry.destinations.hasFailed(destination))
		{
			setView(viewerId, State.FAILED, identity, routeRevision);
			entry.pruneSharedRetentions(runtimeSnapshot);
			return;
		}
		if(!ready(destination))
		{
			setWarmingUnlessReady(viewerId, identity, routeRevision);
			return;
		}
		Attempt existingAttempt = attempts.get(viewerId);
		if(existingAttempt != null && existingAttempt.identity.equals(identity))
		{
			setWarmingUnlessReady(viewerId, identity, routeRevision);
			return;
		}
		Identity publishedIdentity = identities.get(viewerId);
		RtpProjectionView publishedView = views.get(viewerId);
		if(identity.equals(publishedIdentity)
				&& publishedView != null
				&& publishedView.state() != RtpProjectionView.State.WARMING)
		{
			return;
		}
		startAccess(viewerId, identity, routeRevision);
	}

	private RtpDestination destinationFor(UUID viewerId, RtpRuntimeSnapshot snapshot)
	{
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			return snapshot.ready() ? snapshot.active() : null;
		}
		return entry.runtime.reservationFor(viewerId).orElse(null);
	}

	private boolean ready(RtpDestination destination)
	{
		return entry.destinations.isRetained(destination);
	}

	private long routeRevision(RtpRuntimeSnapshot snapshot, RtpDestination destination)
	{
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			return Math.max(1L, snapshot.routeRevision());
		}
		long attemptRevision = (long) destination.attempt() + 1L;
		return attemptRevision > 0L ? attemptRevision : entry.generation;
	}

	private void startAccess(UUID viewerId, Identity identity, long routeRevision)
	{
		Attempt attempt = new Attempt(identity);
		attempts.put(viewerId, attempt);
		setWarmingUnlessReady(viewerId, identity, routeRevision);
		CompletionStage<RtpAccessResult> stage;
		try
		{
			stage = Objects.requireNonNull(
					service.dependencies().accessChecker().canUse(entry.portalId(), Optional.of(viewerId), identity.destination()),
					"access checker stage");
		}
		catch(RuntimeException exception)
		{
			finishAccess(viewerId, attempt, null, exception);
			return;
		}
		stage.whenComplete((result, failure) -> service.dispatch(entry, () -> finishAccess(viewerId, attempt, result, failure)));
	}

	private void finishAccess(UUID viewerId, Attempt attempt, RtpAccessResult result, Throwable failure)
	{
		if(!service.isCurrent(entry) || !entry.viewers.contains(viewerId) || attempts.get(viewerId) != attempt)
		{
			return;
		}
		attempts.remove(viewerId);
		RtpRuntimeSnapshot runtimeSnapshot = entry.runtime.snapshot();
		RtpDestination currentDestination = destinationFor(viewerId, runtimeSnapshot);
		if(currentDestination == null
				|| !attempt.identity.destination().equals(currentDestination)
				|| !ready(currentDestination))
		{
			setWarmingUnlessReady(viewerId, null, 0L);
			service.publish(entry);
			return;
		}
		if(failure != null || result == null || result.status() == RtpAccessResult.Status.FAILURE)
		{
			entry.accessIntegrationFailed = true;
			setView(viewerId, State.FAILED, attempt.identity, attempt.identity.routeRevision());
		}
		else if(!result.allowed())
		{
			entry.accessIntegrationFailed = false;
			setView(viewerId, State.DENIED, attempt.identity, attempt.identity.routeRevision());
		}
		else
		{
			entry.accessIntegrationFailed = false;
			setReadyView(viewerId, attempt.identity);
		}
		entry.pruneSharedRetentions(runtimeSnapshot);
		service.publish(entry);
	}

	private void setReadyView(UUID viewerId, Identity identity)
	{
		try
		{
			RtpProjectionView.ReadyData readyData = service.dependencies().projectionFactory().create(
					entry.portalId(),
					viewerId,
					identity.destination(),
					identity.routeRevision());
			long revision = entry.nextRevision();
			views.put(viewerId, RtpProjectionView.ready(viewerId, revision, readyData));
			identities.put(viewerId, identity);
		}
		catch(RuntimeException exception)
		{
			setView(viewerId, State.FAILED, identity, identity.routeRevision());
		}
	}

	private void setWarmingUnlessReady(UUID viewerId, Identity identity, long routeRevision)
	{
		RtpProjectionView current = views.get(viewerId);
		if(current != null && current.state() == RtpProjectionView.State.READY)
		{
			return;
		}
		setView(viewerId, State.WARMING, identity, routeRevision);
	}

	private void setView(UUID viewerId, State state, Identity identity, long routeRevision)
	{
		RtpProjectionView current = views.get(viewerId);
		Identity currentIdentity = identities.get(viewerId);
		RtpProjectionView.State requestedState = projectionState(state);
		if(current != null && current.state() == requestedState && Objects.equals(currentIdentity, identity))
		{
			return;
		}
		long revision = entry.nextRevision();
		RtpProjectionView view = switch(state)
		{
			case WARMING -> RtpProjectionView.warming(viewerId, revision);
			case DENIED -> RtpProjectionView.denied(viewerId, revision);
			case FAILED -> RtpProjectionView.failed(viewerId, revision);
		};
		views.put(viewerId, view);
		if(identity == null)
		{
			identities.remove(viewerId);
		}
		else
		{
			identities.put(viewerId, new Identity(identity.destination(), routeRevision));
		}
	}

	private RtpProjectionView.State projectionState(State state)
	{
		return switch(state)
		{
			case WARMING -> RtpProjectionView.State.WARMING;
			case DENIED -> RtpProjectionView.State.DENIED;
			case FAILED -> RtpProjectionView.State.FAILED;
		};
	}

	private enum State
	{
		WARMING,
		DENIED,
		FAILED
	}

	private record Identity(RtpDestination destination, long routeRevision)
	{
		private Identity
		{
			Objects.requireNonNull(destination, "destination");
		}
	}

	private record Attempt(Identity identity)
	{
		private Attempt
		{
			Objects.requireNonNull(identity, "identity");
		}
	}
}
