package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorAutoCloseBookTest
{
	private static final UUID DOOR = new UUID(4L, 9L);
	private static final UUID OTHER_DOOR = new UUID(4L, 10L);

	@Test
	void anArmedDoorClosesOnceAndIsThenForgotten()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);

		assertTrue(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(DOOR, token, true, false, 0));
		assertFalse(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(DOOR, token, true, false, 0));
	}

	@Test
	void aDoorTheServerNeverOpenedIsNeverClosed()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();

		assertFalse(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(DOOR, 1L, true, false, 0));
	}

	@Test
	void aSecondArrivalSupersedesTheEarlierScheduledClose()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long first = book.arm(DOOR);
		long second = book.arm(DOOR);

		assertNotEquals(first, second);
		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(DOOR, first, true, false, 0));
		assertTrue(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(DOOR, second, true, false, 0));
	}

	@Test
	void aDoorAlreadyClosedByHandIsDroppedWithoutClosingAgain()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);

		assertEquals(DoorAutoCloseBook.Decision.ALREADY_CLOSED, book.decide(DOOR, token, false, false, 0));
		assertFalse(book.isArmed(DOOR));
	}

	@Test
	void aTransitInFlightDefersTheCloseAndKeepsTheDoorArmed()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);

		assertEquals(DoorAutoCloseBook.Decision.DEFER, book.decide(DOOR, token, true, true, 0));
		assertTrue(book.isArmed(DOOR));
		assertEquals(
			DoorAutoCloseBook.Decision.DEFER,
			book.decide(DOOR, token, true, true, DoorAutoCloseBook.MAX_DEFERRALS - 1));
		assertTrue(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(DOOR, token, true, false, 3));
	}

	@Test
	void aTransitStuckPastTheDeferralBudgetHandsTheDoorBack()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);

		assertEquals(
			DoorAutoCloseBook.Decision.ABANDONED,
			book.decide(DOOR, token, true, true, DoorAutoCloseBook.MAX_DEFERRALS));
		assertFalse(book.isArmed(DOOR));
	}

	@Test
	void doorsAreTrackedIndependently()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long first = book.arm(DOOR);
		long second = book.arm(OTHER_DOOR);

		assertEquals(2, book.size());
		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(OTHER_DOOR, first, true, false, 0));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(DOOR, first, true, false, 0));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(OTHER_DOOR, second, true, false, 0));
		assertEquals(0, book.size());
	}

	@Test
	void aSecondArrivalInsideTheOpenWindowExtendsTheCloseInsteadOfLettingItFire()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();

		assertEquals(DoorAutoCloseBook.Arrival.OPEN, book.decideArrival(DOOR, false));
		long first = book.arm(DOOR);

		assertEquals(DoorAutoCloseBook.Arrival.EXTEND, book.decideArrival(DOOR, true));
		long second = book.arm(DOOR);

		assertNotEquals(first, second);
		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(DOOR, first, true, false, 0));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(DOOR, second, true, false, 0));
	}

	@Test
	void aDoorAPlayerOpenedIsNeverAdoptedByAnArrival()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();

		assertEquals(DoorAutoCloseBook.Arrival.LEAVE, book.decideArrival(DOOR, true));
		assertFalse(book.isArmed(DOOR));
	}

	@Test
	void aDoorShutByHandInsideTheWindowIsNoLongerTheServersToSlam()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);

		book.observe(DOOR, false);

		assertFalse(book.isArmed(DOOR));

		// the player swings their door open again well before the armed timer fires
		book.observe(DOOR, true);

		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(DOOR, token, true, false, 0));
		assertEquals(DoorAutoCloseBook.Arrival.LEAVE, book.decideArrival(DOOR, true));
	}

	@Test
	void anUninterruptedOpenWindowKeepsItsArmedToken()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);

		book.observe(DOOR, true);

		assertTrue(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(DOOR, token, true, false, 0));
	}

	@Test
	void forgettingAndClearingDropEveryPendingClose()
	{
		DoorAutoCloseBook book = new DoorAutoCloseBook();
		long token = book.arm(DOOR);
		book.arm(OTHER_DOOR);

		book.forget(DOOR);

		assertFalse(book.isArmed(DOOR));
		assertEquals(DoorAutoCloseBook.Decision.SUPERSEDED, book.decide(DOOR, token, true, false, 0));

		book.clear();

		assertEquals(0, book.size());
		assertFalse(book.isArmed(OTHER_DOOR));
	}
}
