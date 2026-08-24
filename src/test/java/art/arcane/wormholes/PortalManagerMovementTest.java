package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

public final class PortalManagerMovementTest
{
	@Test
	public void lookOnlyMovementDoesNotTriggerPositionWork()
	{
		Location from = new Location(null, 1.25D, 64.0D, -3.5D, 10.0F, 20.0F);
		Location to = new Location(null, 1.25D, 64.0D, -3.5D, 80.0F, -15.0F);

		assertFalse(PortalManager.positionChanged(from, to));
	}

	@Test
	public void coordinateMovementTriggersPositionWork()
	{
		Location from = new Location(null, 1.25D, 64.0D, -3.5D);
		Location to = new Location(null, 1.2501D, 64.0D, -3.5D);

		assertTrue(PortalManager.positionChanged(from, to));
	}
}
