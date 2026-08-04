package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DoorOpenStateTest
{
	@Test
	void statesMatchOnlyTheirPhysicalDoorState()
	{
		assertTrue(DoorOpenState.OPEN.matches(true));
		assertFalse(DoorOpenState.OPEN.matches(false));
		assertTrue(DoorOpenState.CLOSED.matches(false));
		assertFalse(DoorOpenState.CLOSED.matches(true));
	}

	@Test
	void flippingAndLegacyConversionAreExact()
	{
		assertEquals(DoorOpenState.CLOSED, DoorOpenState.OPEN.flipped());
		assertEquals(DoorOpenState.OPEN, DoorOpenState.CLOSED.flipped());
		assertEquals(DoorOpenState.OPEN, DoorOpenState.fromLegacy(true));
		assertEquals(DoorOpenState.CLOSED, DoorOpenState.fromLegacy(false));
	}
}
