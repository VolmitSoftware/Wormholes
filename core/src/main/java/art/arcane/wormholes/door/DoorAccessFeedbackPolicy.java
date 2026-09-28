package art.arcane.wormholes.door;

public final class DoorAccessFeedbackPolicy {
    public static final long DENY_COOLDOWN_MILLIS = 1500L;

    private DoorAccessFeedbackPolicy() {
    }
	public static boolean isCoolingDown(Long nextAllowedMillis, long nowMillis)
	{
		return nextAllowedMillis != null && nextAllowedMillis.longValue() > nowMillis;
	}

	public static long nextAllowedMillis(long nowMillis)
	{
		return nowMillis + DENY_COOLDOWN_MILLIS;
	}

}
