package art.arcane.wormholes.portal.rtp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

final class RtpTraversalDirectory
{
	private static final long TRAVERSAL_PREPARATION_DEADLINE_MILLIS = 5_000L;

	private final RtpService service;
	private final RtpPortalEntry entry;
	private final Set<RtpService.TraversalPreparation> preparations;

	RtpTraversalDirectory(RtpService service, RtpPortalEntry entry)
	{
		this.service = service;
		this.entry = entry;
		this.preparations = new LinkedHashSet<RtpService.TraversalPreparation>();
	}

	void collectRetained(Set<RtpDestination> retained)
	{
		for(RtpService.TraversalPreparation preparation : preparations)
		{
			retained.add(preparation.claim().destination());
		}
	}

	void cancelAll()
	{
		List<RtpService.TraversalPreparation> pending = List.copyOf(preparations);
		RuntimeException cancellationFailure = null;
		for(RtpService.TraversalPreparation preparation : pending)
		{
			try
			{
				preparation.request().cancel(preparation.generation());
			}
			catch(RuntimeException exception)
			{
				if(cancellationFailure == null)
				{
					cancellationFailure = exception;
				}
				else
				{
					cancellationFailure.addSuppressed(exception);
				}
			}
			preparation.admission.complete(Optional.empty());
		}
		if(cancellationFailure != null)
		{
			throw cancellationFailure;
		}
	}

	void begin(RtpService.TraversalActor actor, CompletableFuture<Optional<RtpService.TraversalPreparation>> admission)
	{
		Optional<RtpPortalRuntime.TraversalClaim> claim = claim(actor);
		if(claim.isEmpty())
		{
			admission.complete(Optional.empty());
			return;
		}
		RtpPortalRuntime.TraversalClaim admittedClaim = claim.get();
		if(!entry.destinations.isRetained(admittedClaim.destination()))
		{
			entry.runtime.completeTraversal(admittedClaim, false, service.dependencies().timeSource().nowMillis());
			entry.markChanged();
			service.maintain(entry);
			admission.complete(Optional.empty());
			return;
		}
		UUID portalId = entry.portalId();
		AtomicReference<RtpService.TraversalPreparation> preparationReference =
				new AtomicReference<RtpService.TraversalPreparation>();
		RtpTraversalRequest request = RtpTraversalRequest.preparing(entry.generation, () ->
				service.dependencies().sourceDispatcher().execute(portalId, () -> finish(preparationReference.get())));
		RtpService.TraversalPreparation preparation = new RtpService.TraversalPreparation(
				portalId,
				entry.generation,
				actor,
				admittedClaim,
				request,
				admission);
		preparationReference.set(preparation);
		preparations.add(preparation);
		entry.markChanged();
		service.publish(entry);
		try
		{
			service.dependencies().sourceDispatcher().schedule(portalId, () ->
			{
				if(request.timeoutPreparing(entry.generation))
				{
					admission.complete(Optional.empty());
				}
			}, TRAVERSAL_PREPARATION_DEADLINE_MILLIS);
		}
		catch(RuntimeException exception)
		{
			admission.complete(Optional.empty());
			try
			{
				cancelAndRelease(preparation);
			}
			catch(RuntimeException cleanupFailure)
			{
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
		startAccess(preparation);
	}

	private Optional<RtpPortalRuntime.TraversalClaim> claim(RtpService.TraversalActor actor)
	{
		RtpRuntimeSnapshot snapshot = entry.runtime.snapshot();
		if(snapshot.allocationMode() == RtpAllocationMode.SHARED)
		{
			return entry.runtime.claimShared(actor.claimId());
		}
		return actor.playerId().isPresent()
				? entry.runtime.claimPlayer(actor.claimId(), actor.playerId().get())
				: entry.runtime.claimAnonymous(actor.claimId());
	}

	private void startAccess(RtpService.TraversalPreparation preparation)
	{
		Optional<UUID> playerId = preparation.actor().playerId();
		if(playerId.isEmpty())
		{
			finishAccess(preparation, RtpAccessResult.allowedResult(), null);
			return;
		}
		CompletionStage<RtpAccessResult> stage;
		try
		{
			stage = Objects.requireNonNull(
					service.dependencies().accessChecker().canUse(
							preparation.portalId(),
							playerId,
							preparation.claim().destination()),
					"access checker stage");
		}
		catch(RuntimeException exception)
		{
			finishAccess(preparation, null, exception);
			return;
		}
		stage.whenComplete((result, failure) -> service.dependencies().sourceDispatcher().execute(
				preparation.portalId(),
				() -> finishAccess(preparation, result, failure)));
	}

	private void finishAccess(RtpService.TraversalPreparation preparation, RtpAccessResult result, Throwable failure)
	{
		if(preparation.request().state() != RtpTraversalRequest.State.PREPARING)
		{
			preparation.admission.complete(Optional.empty());
			return;
		}
		if(failure != null || result == null || result.status() == RtpAccessResult.Status.FAILURE)
		{
			entry.accessIntegrationFailed = true;
			try
			{
				cancelAndRelease(preparation);
			}
			finally
			{
				preparation.admission.complete(Optional.empty());
				service.publish(entry);
			}
			return;
		}
		if(!service.isCurrent(entry) || entry.generation != preparation.generation() || !result.allowed())
		{
			try
			{
				cancelAndRelease(preparation);
			}
			finally
			{
				preparation.admission.complete(Optional.empty());
			}
			return;
		}
		entry.accessIntegrationFailed = false;
		preparation.admission.complete(Optional.of(preparation));
	}

	private void cancelAndRelease(RtpService.TraversalPreparation preparation)
	{
		RuntimeException recoveryFailure = null;
		try
		{
			preparation.request().cancel(preparation.generation());
		}
		catch(RuntimeException cancellationFailure)
		{
			recoveryFailure = cancellationFailure;
		}
		try
		{
			finish(preparation);
		}
		catch(RuntimeException releaseFailure)
		{
			if(recoveryFailure == null)
			{
				recoveryFailure = releaseFailure;
			}
			else
			{
				recoveryFailure.addSuppressed(releaseFailure);
			}
		}
		if(recoveryFailure != null)
		{
			throw recoveryFailure;
		}
	}

	private void finish(RtpService.TraversalPreparation preparation)
	{
		if(preparation == null || !preparation.terminalized.compareAndSet(false, true))
		{
			return;
		}
		boolean succeeded = preparation.request().state() == RtpTraversalRequest.State.SUCCEEDED;
		entry.runtime.completeTraversal(preparation.claim(), succeeded, service.dependencies().timeSource().nowMillis());
		preparations.remove(preparation);
		if(succeeded && preparation.claim().kind() != RtpPortalRuntime.ClaimKind.SHARED)
		{
			entry.destinations.release(preparation.claim().destination());
		}
		entry.pruneSharedRetentions(entry.runtime.snapshot());
		if(!preparation.admission.isDone())
		{
			preparation.admission.complete(Optional.empty());
		}
		if(service.isCurrent(entry))
		{
			entry.markChanged();
			service.maintain(entry);
		}
		else
		{
			entry.destinations.releaseAll();
		}
	}
}
