package art.arcane.wormholes.portal;

final class AmbientSparkCadence
{
	private static final int OPEN_SPARKS_PER_TICK = 4;
	private static final int CLOSED_SPARKS_PER_TICK = 1;

	private AmbientSparkCadence()
	{
	}

	static int burst(long sequence, int intervalTicks, boolean open)
	{
		int interval = Math.max(1, intervalTicks);
		if(Math.floorMod(sequence, (long) interval) != 0L)
		{
			return 0;
		}
		return (open ? OPEN_SPARKS_PER_TICK : CLOSED_SPARKS_PER_TICK) * interval;
	}

	static double spread(double extent)
	{
		return Math.abs(extent) * 0.25D;
	}
}
