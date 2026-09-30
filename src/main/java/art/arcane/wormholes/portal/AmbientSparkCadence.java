package art.arcane.wormholes.portal;

final class AmbientSparkCadence
{
	static final double CELL_SPREAD = Math.sqrt(1.0D / 12.0D);
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
}
