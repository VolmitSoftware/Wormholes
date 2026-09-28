package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class RtpDestinationCache
{
	private final RtpService service;
	private final RtpPortalEntry entry;
	private final Map<RtpDestination, RtpManagedRetention> retentions;
	private final Map<RtpDestination, Preparation> attempts;
	private final Set<RtpDestination> failures;

	RtpDestinationCache(RtpService service, RtpPortalEntry entry)
	{
		this.service = service;
		this.entry = entry;
		this.retentions = new LinkedHashMap<RtpDestination, RtpManagedRetention>();
		this.attempts = new LinkedHashMap<RtpDestination, Preparation>();
		this.failures = new LinkedHashSet<RtpDestination>();
	}

	boolean isRetained(RtpDestination destination)
	{
		return retentions.containsKey(destination);
	}

	boolean hasFailed(RtpDestination destination)
	{
		return failures.contains(destination);
	}

	void retain(RtpDestination destination, RtpManagedRetention retention)
	{
		retentions.put(destination, retention);
	}

	void prepareVisible()
	{
		RtpRuntimeSnapshot snapshot = entry.runtime.snapshot();
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			if(snapshot.active() != null)
			{
				ensurePrepared(snapshot.active());
			}
			if(snapshot.standby() != null)
			{
				ensurePrepared(snapshot.standby());
			}
			return;
		}
		for(UUID viewerId : entry.viewers)
		{
			entry.runtime.reservationFor(viewerId).ifPresent(destination -> ensurePrepared(destination));
		}
	}

	void retainOnly(Set<RtpDestination> retained)
	{
		List<RtpDestination> obsolete = new ArrayList<RtpDestination>();
		for(RtpDestination destination : retentions.keySet())
		{
			if(!retained.contains(destination))
			{
				obsolete.add(destination);
			}
		}
		for(RtpDestination destination : attempts.keySet())
		{
			if(!retained.contains(destination) && !obsolete.contains(destination))
			{
				obsolete.add(destination);
			}
		}
		for(RtpDestination destination : obsolete)
		{
			release(destination);
		}
	}

	void release(RtpDestination destination)
	{
		Preparation preparation = attempts.remove(destination);
		if(preparation != null)
		{
			cancel(preparation);
		}
		RtpManagedRetention retention = retentions.remove(destination);
		if(retention != null)
		{
			retention.close();
		}
	}

	void releaseAll()
	{
		for(RtpManagedRetention retention : retentions.values())
		{
			retention.close();
		}
		retentions.clear();
		for(Preparation preparation : attempts.values())
		{
			cancel(preparation);
		}
		attempts.clear();
	}

	void clearFailures()
	{
		failures.clear();
	}

	private void ensurePrepared(RtpDestination destination)
	{
		if(retentions.containsKey(destination)
				|| attempts.containsKey(destination)
				|| failures.contains(destination))
		{
			return;
		}
		RtpService.SearchRequest request = new RtpService.SearchRequest(
				entry.portalId(),
				entry.generation,
				entry.registration.settings(),
				destination);
		Preparation preparation = new Preparation(entry.generation, destination, request);
		attempts.put(destination, preparation);
		service.dependencies().searchExecutor().execute(() -> load(preparation));
	}

	private void load(Preparation preparation)
	{
		if(!isCurrent(preparation))
		{
			return;
		}
		CompletionStage<RtpService.LoadedCandidate> stage;
		try
		{
			stage = Objects.requireNonNull(
					service.dependencies().candidateLoader().load(preparation.request),
					"candidate loader stage");
		}
		catch(RuntimeException exception)
		{
			service.dispatch(entry, () -> finishLoad(preparation, null, exception));
			return;
		}
		stage.whenComplete((loaded, failure) -> service.dispatchClosing(
				entry,
				() -> RtpManagedRetention.closeLoaded(loaded),
				() -> finishLoad(preparation, loaded, failure)));
	}

	private void finishLoad(Preparation preparation, RtpService.LoadedCandidate loaded, Throwable failure)
	{
		if(!isCurrent(preparation))
		{
			RtpManagedRetention.closeLoaded(loaded);
			return;
		}
		if(failure != null || loaded == null)
		{
			attempts.remove(preparation.destination);
			discard(preparation.destination);
			entry.markChanged();
			service.maintain(entry);
			return;
		}
		RtpManagedRetention retention = new RtpManagedRetention(loaded.retention());
		preparation.retention = retention;
		service.dependencies().searchExecutor().execute(
				() -> validate(preparation, loaded.validationRequest(), retention));
	}

	private void validate(Preparation preparation, RtpValidationRequest request, RtpManagedRetention retention)
	{
		CompletionStage<RtpSafetyResult> stage;
		try
		{
			stage = Objects.requireNonNull(service.dependencies().safetyValidator().validate(request), "safety validator stage");
		}
		catch(RuntimeException exception)
		{
			service.dispatchClosing(entry, retention::close, () -> finishValidation(preparation, retention, null, exception));
			return;
		}
		stage.whenComplete((result, failure) -> service.dispatchClosing(
				entry,
				retention::close,
				() -> finishValidation(preparation, retention, result, failure)));
	}

	private void finishValidation(
			Preparation preparation,
			RtpManagedRetention retention,
			RtpSafetyResult result,
			Throwable failure)
	{
		if(!isCurrent(preparation))
		{
			retention.close();
			return;
		}
		attempts.remove(preparation.destination);
		if(failure != null || result == null || !result.safe() || !preparation.destination.equals(result.destination()))
		{
			retention.close();
			discard(preparation.destination);
		}
		else
		{
			retentions.put(preparation.destination, retention);
			failures.remove(preparation.destination);
		}
		entry.markChanged();
		service.maintain(entry);
	}

	private void discard(RtpDestination destination)
	{
		if(entry.runtime.invalidateDestination(destination))
		{
			failures.remove(destination);
			return;
		}
		failures.add(destination);
	}

	private boolean isCurrent(Preparation preparation)
	{
		return service.isCurrent(entry)
				&& preparation.generation == entry.generation
				&& attempts.get(preparation.destination) == preparation;
	}

	private void cancel(Preparation preparation)
	{
		service.dependencies().candidateLoader().cancel(preparation.request);
		if(preparation.retention != null)
		{
			preparation.retention.close();
		}
	}

	private static final class Preparation
	{
		private final long generation;
		private final RtpDestination destination;
		private final RtpService.SearchRequest request;
		private RtpManagedRetention retention;

		private Preparation(
				long generation,
				RtpDestination destination,
				RtpService.SearchRequest request)
		{
			this.generation = generation;
			this.destination = destination;
			this.request = request;
		}
	}
}
