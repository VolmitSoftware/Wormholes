package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public final class AmbientSparkCadenceTest
{
	@Test
	public void everyTickCadenceKeepsTheLegacyPerTickCounts()
	{
		for(long sequence = 0; sequence < 10; sequence++)
		{
			assertEquals(4, AmbientSparkCadence.burst(sequence, 1, true));
			assertEquals(1, AmbientSparkCadence.burst(sequence, 1, false));
		}
	}

	@Test
	public void slowerCadenceEmitsOneScaledBurstPerInterval()
	{
		assertEquals(20, AmbientSparkCadence.burst(0, 5, true));
		assertEquals(0, AmbientSparkCadence.burst(1, 5, true));
		assertEquals(0, AmbientSparkCadence.burst(4, 5, true));
		assertEquals(20, AmbientSparkCadence.burst(5, 5, true));
		assertEquals(5, AmbientSparkCadence.burst(10, 5, false));
		assertEquals(0, AmbientSparkCadence.burst(11, 5, false));
	}

	@Test
	public void everyCadenceKeepsTheSameMeanDensity()
	{
		for(int interval : new int[] {1, 2, 3, 5, 8, 40})
		{
			int open = 0;
			int closed = 0;
			for(long sequence = 0; sequence < 40L * 120L; sequence++)
			{
				open += AmbientSparkCadence.burst(sequence, interval, true);
				closed += AmbientSparkCadence.burst(sequence, interval, false);
			}
			assertEquals(4 * 40 * 120, open, "interval " + interval);
			assertEquals(40 * 120, closed, "interval " + interval);
		}
	}

	@Test
	public void outOfRangeIntervalsClampToEveryTick()
	{
		assertEquals(4, AmbientSparkCadence.burst(3, 0, true));
		assertEquals(1, AmbientSparkCadence.burst(7, -5, false));
	}

	@Test
	public void cellSpreadMatchesTheVarianceOfUniformPlacementInsideOneBlock()
	{
		assertEquals(Math.sqrt(1.0D / 12.0D), AmbientSparkCadence.CELL_SPREAD, 1.0E-12D);
	}
}
