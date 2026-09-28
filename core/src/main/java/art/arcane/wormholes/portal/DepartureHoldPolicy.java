package art.arcane.wormholes.portal;

public final class DepartureHoldPolicy
{
	public static final double FAR_DRIFT_SQUARED = 256.0D;
	public static final double RETREAT_FREE_DISTANCE = 0.25D;
	public static final double RETREAT_CANCEL_DRIFT_SQUARED = 4.0D;
	public static final double LEASH_DRIFT_SQUARED = 0.5625D;

	private DepartureHoldPolicy()
	{
	}

	public enum Decision
	{
		STOP,
		CANCEL_RETREAT,
		HOLD_PIN
	}

	public static Decision decide(
			boolean inFlight,
			boolean sameWorld,
			double sideDistance,
			double driftSquared,
			long remainingMillis)
	{
		if(!inFlight)
		{
			return Decision.STOP;
		}
		if(!sameWorld)
		{
			return Decision.STOP;
		}
		if(driftSquared > FAR_DRIFT_SQUARED)
		{
			return Decision.STOP;
		}
		if(remainingMillis <= 0L)
		{
			return Decision.STOP;
		}
		if(sideDistance > RETREAT_FREE_DISTANCE && driftSquared > RETREAT_CANCEL_DRIFT_SQUARED)
		{
			return Decision.CANCEL_RETREAT;
		}
		return Decision.HOLD_PIN;
	}
}
