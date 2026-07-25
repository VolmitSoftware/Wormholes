package art.arcane.wormholes.door;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DoorTransitFailures
{
	enum Failure
	{
		ENTRY_SHUTTING_DOWN,
		ENTRY_REGION_UNAVAILABLE,
		ENTRY_SOURCE_MISSING,
		ENTRY_CYCLE_UNAVAILABLE,
		TRANSIT_ABORTED,
		TRANSIT_FAILED,
		TRANSIT_RETIRED,
		TRANSIT_SHUTDOWN,
		TRANSIT_SCHEDULE_REJECTED
	}

	private final Logger logger;
	private final AtomicLong failed;
	private final ConcurrentHashMap<Failure, AtomicLong> counters;

	DoorTransitFailures(Logger logger)
	{
		this.logger = Objects.requireNonNull(logger, "logger");
		failed = new AtomicLong();
		counters = new ConcurrentHashMap<>();
	}

	void record(Failure failure, UUID travelerId, String detail)
	{
		Failure recorded = Objects.requireNonNull(failure, "failure");
		failed.incrementAndGet();
		counters.computeIfAbsent(recorded, ignored -> new AtomicLong()).incrementAndGet();
		logger.log(Level.FINE, () -> "[door] FAILED " + recorded.name() + " traveler=" + travelerId
			+ (detail == null || detail.isBlank() ? "" : " reason=" + detail));
	}

	long failed()
	{
		return failed.get();
	}

	Map<String, Long> breakdown()
	{
		Map<String, Long> snapshot = new LinkedHashMap<>();
		for(Failure failure : Failure.values())
		{
			AtomicLong counter = counters.get(failure);
			if(counter != null)
			{
				snapshot.put(failure.name(), Long.valueOf(counter.get()));
			}
		}
		return Collections.unmodifiableMap(snapshot);
	}
}
