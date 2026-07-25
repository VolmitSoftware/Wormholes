package art.arcane.wormholes.portal.rtp;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class RtpFailureThrottle
{
	private static final long FAILURE_RELOG_MILLIS = 60_000L;
	private static final int RETAINED_CONTEXT_LIMIT = 256;

	private final BukkitRtpRuntime.Environment environment;
	private final Map<String, Long> reported;

	RtpFailureThrottle(BukkitRtpRuntime.Environment environment)
	{
		this.environment = environment;
		this.reported = new ConcurrentHashMap<String, Long>();
	}

	void report(String context, Throwable failure)
	{
		Throwable requiredFailure = Objects.requireNonNull(failure, "failure");
		long nowMillis = environment.nowMillis();
		Long previous = reported.get(context);
		if(previous != null && nowMillis - previous.longValue() < FAILURE_RELOG_MILLIS)
		{
			return;
		}
		prune(nowMillis);
		reported.put(context, Long.valueOf(nowMillis));
		try
		{
			environment.reportFailure(context, requiredFailure);
		}
		catch(RuntimeException ignored)
		{
		}
	}

	int retainedContexts()
	{
		return reported.size();
	}

	private void prune(long nowMillis)
	{
		if(reported.size() < RETAINED_CONTEXT_LIMIT)
		{
			return;
		}
		reported.entrySet().removeIf(entry -> nowMillis - entry.getValue().longValue() >= FAILURE_RELOG_MILLIS);
	}
}
