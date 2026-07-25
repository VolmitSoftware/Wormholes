package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

final class RtpSearchDriver
{
	private static final int MAXIMUM_SEARCH_ATTEMPTS = 32;
	private static final long SEARCH_DEADLINE_MILLIS = 5_000L;
	private static final long SEARCH_RETRY_MILLIS = 1_000L;
	private static final long MAXIMUM_SEARCH_RETRY_MILLIS = 30_000L;

	private final RtpService service;
	private final RtpPortalEntry entry;
	private Campaign campaign;
	private long nextAllowedAtMillis;
	private long wakeScheduledAtMillis;
	private int consecutiveFailures;
	private int nextAttemptOrdinal;

	RtpSearchDriver(RtpService service, RtpPortalEntry entry)
	{
		this.service = service;
		this.entry = entry;
	}

	long nextAllowedAtMillis()
	{
		return nextAllowedAtMillis;
	}

	void resetBackoff()
	{
		nextAllowedAtMillis = 0L;
		consecutiveFailures = 0;
	}

	void ensure()
	{
		if(entry.viewers.isEmpty() || campaign != null)
		{
			return;
		}
		long nowMillis = service.dependencies().timeSource().nowMillis();
		if(nowMillis < nextAllowedAtMillis)
		{
			scheduleWake();
			return;
		}
		Optional<RtpPortalRuntime.SearchTicket> ticket = entry.runtime.beginSearch();
		if(ticket.isEmpty())
		{
			return;
		}
		Campaign started = new Campaign(entry.generation, ticket.get(), deadline(nowMillis, SEARCH_DEADLINE_MILLIS));
		campaign = started;
		entry.markChanged();
		service.dependencies().sourceDispatcher().schedule(
				entry.portalId(),
				() -> onDeadline(started),
				SEARCH_DEADLINE_MILLIS);
		startAttempt(started);
	}

	void cancel()
	{
		Campaign cancelled = campaign;
		if(cancelled == null)
		{
			return;
		}
		if(cancelled.activeRetention != null)
		{
			cancelled.activeRetention.close();
			cancelled.activeRetention = null;
		}
		entry.runtime.failSearch(cancelled.ticket, service.dependencies().timeSource().nowMillis());
		campaign = null;
		nextAllowedAtMillis = 0L;
	}

	private void scheduleWake()
	{
		if(wakeScheduledAtMillis == nextAllowedAtMillis)
		{
			return;
		}
		wakeScheduledAtMillis = nextAllowedAtMillis;
		long delayMillis = Math.max(0L, nextAllowedAtMillis - service.dependencies().timeSource().nowMillis());
		service.dependencies().sourceDispatcher().schedule(entry.portalId(), () ->
		{
			if(!service.isCurrent(entry) || wakeScheduledAtMillis != nextAllowedAtMillis)
			{
				return;
			}
			wakeScheduledAtMillis = 0L;
			service.maintain(entry);
		}, delayMillis);
	}

	private void onDeadline(Campaign expired)
	{
		if(!isCurrent(expired))
		{
			return;
		}
		fail(expired);
		service.maintain(entry);
	}

	private void startAttempt(Campaign active)
	{
		if(!isCurrent(active))
		{
			return;
		}
		long nowMillis = service.dependencies().timeSource().nowMillis();
		if(active.attemptsStarted >= MAXIMUM_SEARCH_ATTEMPTS || nowMillis >= active.deadlineMillis)
		{
			fail(active);
			service.maintain(entry);
			return;
		}
		int attempt = nextAttempt();
		active.attemptsStarted++;
		service.dependencies().searchExecutor().execute(() -> sampleAndLoad(active, attempt));
	}

	private void sampleAndLoad(Campaign active, int attempt)
	{
		RtpDestination destination;
		try
		{
			destination = Objects.requireNonNull(
					service.dependencies().sampler().sample(entry.registration, entry.generation, attempt),
					"sampled destination");
		}
		catch(RuntimeException exception)
		{
			service.dispatch(entry, () -> resume(active));
			return;
		}
		RtpService.SearchRequest request = new RtpService.SearchRequest(
				entry.portalId(),
				entry.generation,
				entry.registration.settings(),
				destination);
		CompletionStage<RtpService.LoadedCandidate> stage;
		try
		{
			stage = Objects.requireNonNull(service.dependencies().candidateLoader().load(request), "candidate loader stage");
		}
		catch(RuntimeException exception)
		{
			service.dispatch(entry, () -> resume(active));
			return;
		}
		stage.whenComplete((loaded, failure) -> service.dispatchClosing(
				entry,
				() -> RtpManagedRetention.closeLoaded(loaded),
				() -> finishLoad(active, loaded, failure)));
	}

	private void finishLoad(Campaign active, RtpService.LoadedCandidate loaded, Throwable failure)
	{
		if(!isCurrent(active))
		{
			RtpManagedRetention.closeLoaded(loaded);
			return;
		}
		if(service.dependencies().timeSource().nowMillis() >= active.deadlineMillis)
		{
			RtpManagedRetention.closeLoaded(loaded);
			fail(active);
			service.maintain(entry);
			return;
		}
		if(failure != null || loaded == null)
		{
			resume(active);
			return;
		}
		RtpManagedRetention retention = new RtpManagedRetention(loaded.retention());
		active.activeRetention = retention;
		service.dependencies().searchExecutor().execute(() -> validate(active, loaded.validationRequest(), retention));
	}

	private void validate(Campaign active, RtpValidationRequest request, RtpManagedRetention retention)
	{
		CompletionStage<RtpSafetyResult> stage;
		try
		{
			stage = Objects.requireNonNull(service.dependencies().safetyValidator().validate(request), "safety validator stage");
		}
		catch(RuntimeException exception)
		{
			service.dispatchClosing(entry, retention::close, () -> finishValidation(active, retention, null, exception));
			return;
		}
		stage.whenComplete((result, failure) -> service.dispatchClosing(
				entry,
				retention::close,
				() -> finishValidation(active, retention, result, failure)));
	}

	private void finishValidation(
			Campaign active,
			RtpManagedRetention retention,
			RtpSafetyResult result,
			Throwable failure)
	{
		if(active.activeRetention == retention)
		{
			active.activeRetention = null;
		}
		if(!isCurrent(active))
		{
			retention.close();
			return;
		}
		if(service.dependencies().timeSource().nowMillis() >= active.deadlineMillis)
		{
			retention.close();
			fail(active);
			service.maintain(entry);
			return;
		}
		if(failure != null || result == null || !result.safe())
		{
			retention.close();
			resume(active);
			return;
		}
		RtpPortalRuntime.SearchCompletion completion = entry.runtime.completeSearch(
				active.ticket,
				result.destination(),
				service.dependencies().timeSource().nowMillis());
		if(completion == RtpPortalRuntime.SearchCompletion.ADDED)
		{
			entry.destinations.retain(result.destination(), retention);
			campaign = null;
			nextAllowedAtMillis = 0L;
			consecutiveFailures = 0;
			entry.pruneSharedRetentions(entry.runtime.snapshot());
			entry.markChanged();
			service.maintain(entry);
			return;
		}
		retention.close();
		if(completion == RtpPortalRuntime.SearchCompletion.STALE)
		{
			campaign = null;
			entry.markChanged();
			service.maintain(entry);
			return;
		}
		Optional<RtpPortalRuntime.SearchTicket> renewed = entry.runtime.beginSearch();
		if(renewed.isEmpty())
		{
			campaign = null;
			entry.markChanged();
			service.maintain(entry);
			return;
		}
		active.ticket = renewed.get();
		resume(active);
	}

	private void resume(Campaign active)
	{
		if(!isCurrent(active))
		{
			return;
		}
		startAttempt(active);
	}

	private void fail(Campaign active)
	{
		if(!isCurrent(active))
		{
			return;
		}
		if(active.activeRetention != null)
		{
			active.activeRetention.close();
			active.activeRetention = null;
		}
		entry.runtime.failSearch(active.ticket, service.dependencies().timeSource().nowMillis());
		campaign = null;
		consecutiveFailures = Math.min(consecutiveFailures + 1, 16);
		long retryMillis = Math.min(SEARCH_RETRY_MILLIS << consecutiveFailures - 1, MAXIMUM_SEARCH_RETRY_MILLIS);
		nextAllowedAtMillis = deadline(service.dependencies().timeSource().nowMillis(), retryMillis);
		entry.pruneSharedRetentions(entry.runtime.snapshot());
		entry.markChanged();
	}

	private boolean isCurrent(Campaign candidate)
	{
		return service.isCurrent(entry)
				&& candidate.generation == entry.generation
				&& campaign == candidate;
	}

	private int nextAttempt()
	{
		int attempt = nextAttemptOrdinal;
		nextAttemptOrdinal = nextAttemptOrdinal == Integer.MAX_VALUE ? 0 : nextAttemptOrdinal + 1;
		return attempt;
	}

	private static long deadline(long nowMillis, long durationMillis)
	{
		return durationMillis > Long.MAX_VALUE - nowMillis ? Long.MAX_VALUE : nowMillis + durationMillis;
	}

	private static final class Campaign
	{
		private final long generation;
		private final long deadlineMillis;
		private RtpPortalRuntime.SearchTicket ticket;
		private RtpManagedRetention activeRetention;
		private int attemptsStarted;

		private Campaign(long generation, RtpPortalRuntime.SearchTicket ticket, long deadlineMillis)
		{
			this.generation = generation;
			this.ticket = ticket;
			this.deadlineMillis = deadlineMillis;
		}
	}
}
