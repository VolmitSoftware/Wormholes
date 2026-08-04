package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTravelerPolicyTest
{
	private static boolean player(DoorKind kind)
	{
		return DoorTravelerPolicy.canEnter(kind, true, false, false, false, false, false, 0.6D, 1.8D);
	}

	private static boolean mob(DoorKind kind, boolean boss, boolean complex, boolean constrained, double width, double height)
	{
		return DoorTravelerPolicy.canEnter(kind, false, true, false, boss, complex, constrained, width, height);
	}

	private static boolean object(DoorKind kind, boolean constrained, double width, double height)
	{
		return DoorTravelerPolicy.canEnter(kind, false, false, true, false, false, constrained, width, height);
	}

	@Test
	void playersCanEnterEveryDoorKind()
	{
		for(DoorKind kind : DoorKind.values())
		{
			assertTrue(player(kind), kind.name());
		}
	}

	@Test
	void fittingOrdinaryMobCanEnterPairDoor()
	{
		assertTrue(mob(DoorKind.PAIR, false, false, false, 0.6D, 1.8D));
	}

	@Test
	void mobsCannotEnterPocketOrReturnDoors()
	{
		assertFalse(mob(DoorKind.PERSONAL, false, false, false, 0.6D, 1.8D));
		assertFalse(mob(DoorKind.PUBLIC, false, false, false, 0.6D, 1.8D));
		assertFalse(mob(DoorKind.RETURN, false, false, false, 0.6D, 1.8D));
	}

	@Test
	void bossesCannotEnterPairDoor()
	{
		assertFalse(mob(DoorKind.PAIR, true, false, false, 0.6D, 1.8D));
	}

	@Test
	void complexEntitiesCannotEnterPairDoor()
	{
		assertFalse(mob(DoorKind.PAIR, false, true, false, 0.6D, 1.8D));
	}

	@Test
	void constrainedEntitiesCannotEnterPairDoor()
	{
		assertFalse(mob(DoorKind.PAIR, false, false, true, 0.6D, 1.8D));
	}

	@Test
	void oversizedEntitiesCannotEnterPairDoor()
	{
		assertFalse(mob(DoorKind.PAIR, false, false, false, 1.01D, 1.8D));
		assertFalse(mob(DoorKind.PAIR, false, false, false, 0.6D, 2.01D));
	}

	@Test
	void nonFiniteEntitiesCannotEnterPairDoor()
	{
		assertFalse(mob(DoorKind.PAIR, false, false, false, Double.NaN, 1.8D));
		assertFalse(mob(DoorKind.PAIR, false, false, false, 0.6D, Double.POSITIVE_INFINITY));
	}

	@Test
	void objectsCanEnterPairAndPublicDoors()
	{
		assertTrue(object(DoorKind.PAIR, false, 0.5D, 0.5D));
		assertTrue(object(DoorKind.PUBLIC, false, 0.5D, 0.5D));
	}

	@Test
	void objectsCannotEnterPersonalOrReturnDoors()
	{
		assertFalse(object(DoorKind.PERSONAL, false, 0.5D, 0.5D));
		assertFalse(object(DoorKind.RETURN, false, 0.5D, 0.5D));
	}

	@Test
	void constrainedObjectsCannotEnterAnyDoor()
	{
		assertFalse(object(DoorKind.PAIR, true, 0.5D, 0.5D));
		assertFalse(object(DoorKind.PUBLIC, true, 0.5D, 0.5D));
	}

	@Test
	void oversizedOrNonFiniteObjectsCannotEnterAnyDoor()
	{
		assertFalse(object(DoorKind.PAIR, false, 1.01D, 0.5D));
		assertFalse(object(DoorKind.PUBLIC, false, 0.5D, 2.01D));
		assertFalse(object(DoorKind.PAIR, false, Double.NaN, 0.5D));
		assertFalse(object(DoorKind.PUBLIC, false, 0.5D, 0.0D));
	}

	@Test
	void mobileEntitiesAreNeverTreatedAsObjects()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PUBLIC, false, true, true, false, false, false, 0.6D, 1.8D));
		assertTrue(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, true, false, false, false, 0.6D, 1.8D));
	}
}
