package art.arcane.wormholes.portal.rtp;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class RtpPortalEntry
{
	final RtpService.Registration registration;
	final long generation;
	final RtpPortalRuntime runtime;
	final Set<UUID> viewers;
	final RtpDestinationCache destinations;
	final RtpViewDirectory views;
	final RtpSearchDriver search;
	final RtpTraversalDirectory traversals;
	long revision;
	boolean accessIntegrationFailed;
	boolean closed;
	long idleToken;

	RtpPortalEntry(RtpService service, RtpService.Registration registration, long generation, RtpPortalRuntime runtime)
	{
		this.registration = registration;
		this.generation = generation;
		this.runtime = runtime;
		this.viewers = new LinkedHashSet<UUID>();
		this.destinations = new RtpDestinationCache(service, this);
		this.views = new RtpViewDirectory(service, this);
		this.search = new RtpSearchDriver(service, this);
		this.traversals = new RtpTraversalDirectory(service, this);
		this.revision = 1L;
	}

	UUID portalId()
	{
		return registration.portalId();
	}

	void markChanged()
	{
		nextRevision();
	}

	long nextRevision()
	{
		revision = revision == Long.MAX_VALUE ? 1L : revision + 1L;
		return revision;
	}

	void pruneSharedRetentions(RtpRuntimeSnapshot snapshot)
	{
		if(snapshot.allocationMode() != RtpAllocationMode.SHARED || snapshot.rerolling())
		{
			return;
		}
		Set<RtpDestination> retained = new LinkedHashSet<RtpDestination>();
		if(snapshot.active() != null)
		{
			retained.add(snapshot.active());
		}
		if(snapshot.standby() != null)
		{
			retained.add(snapshot.standby());
		}
		views.collectRetained(retained);
		traversals.collectRetained(retained);
		destinations.retainOnly(retained);
	}
}
