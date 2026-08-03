package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionalDoorManagerEntityMoveProbeTest
{
	@Test
	void probeFindsEntityMoveEventOnPaperClasspath()
	{
		assertTrue(DimensionalDoorManager.isEntityMoveEventAvailable(getClass().getClassLoader()));
	}

	@Test
	void probeReportsUnavailableWhenLoaderCannotResolveEvent()
	{
		try(URLClassLoader isolated = new URLClassLoader(new URL[0], null))
		{
			assertFalse(DimensionalDoorManager.isEntityMoveEventAvailable(isolated));
		}
		catch(IOException ex)
		{
			throw new AssertionError(ex);
		}
	}
}
