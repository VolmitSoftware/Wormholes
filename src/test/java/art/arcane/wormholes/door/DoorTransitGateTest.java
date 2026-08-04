package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DoorTransitGateTest
{
	@Test
	public void newlyPlacedPairNeedsOnlySourceLiveOpenState()
	{
		DoorOpenCycle source = new DoorOpenCycle();
		DoorOpenCycle mate = new DoorOpenCycle();
		source.observe(false);
		mate.observe(false);

		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(0.5D, 65.0D, 1.0D),
			new DoorVec3(0.5D, 65.0D, 0.0D));

		assertTrue(crossing.isPresent());
		assertTrue(DoorTransitGate.claim(source, true, true));
		assertEquals(DoorOpenCycle.Phase.IN_TRANSIT, source.phase());
		assertEquals(DoorOpenCycle.Phase.CLOSED, mate.phase());
	}

	@Test
	public void closedSourceCannotClaimDetectedCrossing()
	{
		DoorOpenCycle source = new DoorOpenCycle();
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(0.5D, 65.0D, 1.0D),
			new DoorVec3(0.5D, 65.0D, 0.0D));

		assertTrue(crossing.isPresent());
		assertFalse(DoorTransitGate.claim(source, false, false));
		assertEquals(DoorOpenCycle.Phase.CLOSED, source.phase());
	}

	@Test
	public void closedAtCrossingCannotClaimAfterDoorOpens()
	{
		DoorOpenCycle source = new DoorOpenCycle();

		assertFalse(DoorTransitGate.claim(source, false, true));
		assertEquals(DoorOpenCycle.Phase.ARMED, source.phase());
	}

	@Test
	public void openAtCrossingCannotClaimAfterDoorCloses()
	{
		DoorOpenCycle source = new DoorOpenCycle();

		assertFalse(DoorTransitGate.claim(source, true, false));
		assertEquals(DoorOpenCycle.Phase.CLOSED, source.phase());
	}

	@Test
	public void movementFarFromDoorIsNotAdmitted()
	{
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(10.5D, 65.0D, 1.0D),
			new DoorVec3(10.5D, 65.0D, 0.0D));

		assertTrue(crossing.isEmpty());
	}

	@Test
	public void slowMovementJustOutsideTheFixedBandIsStillRejected()
	{
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(3.5D, 65.0D, 0.2D),
			new DoorVec3(3.5D, 65.0D, -0.2D));

		assertTrue(crossing.isEmpty());
	}

	@Test
	public void fastSegmentReachingAcrossThePlaneIsAdmitted()
	{
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(0.5D, 65.0D, 3.0D),
			new DoorVec3(0.5D, 65.0D, -0.1D));

		assertTrue(crossing.isPresent());
		assertEquals(DoorwayCrossing.Direction.BACK_TO_FRONT, crossing.get().direction());
	}

	@Test
	public void fastSegmentWideOfThePlaneStillFindsNoCrossing()
	{
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(3.5D, 65.0D, 3.0D),
			new DoorVec3(3.5D, 65.0D, -0.1D));

		assertTrue(crossing.isEmpty());
	}

	@Test
	public void objectsPassThroughAnOpenDoorWithoutClaimingItsCycle()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(false);

		assertTrue(DoorTransitGate.passThrough(cycle, true, true));
		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
		assertTrue(DoorTransitGate.passThrough(cycle, true, true));
		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
	}

	@Test
	public void objectsCannotPassThroughAClosedDoor()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();

		assertFalse(DoorTransitGate.passThrough(cycle, true, false));
		assertFalse(DoorTransitGate.passThrough(cycle, false, true));
		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
	}

	@Test
	public void objectsNeverStealAClaimedPlayerTransit()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(false);
		assertTrue(DoorTransitGate.claim(cycle, true, true));
		assertEquals(DoorOpenCycle.Phase.IN_TRANSIT, cycle.phase());

		assertTrue(DoorTransitGate.passThrough(cycle, true, true));

		assertEquals(DoorOpenCycle.Phase.IN_TRANSIT, cycle.phase());
	}

	@Test
	public void anObjectCompletionLeavesTheDoorStandingOpenForTheRestOfTheVolley()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(true);

		DoorTransitGate.complete(cycle, objectTransit(), true, false);

		// the object sweep reads exactly this to decide whether arrows 2..n are fed
		assertTrue(cycle.portalActive());
		assertTrue(DoorEntitySweep.shouldSweep(new DoorwayPlane(0, 64, 0, BlockFace.NORTH), cycle, true));
		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
	}

	@Test
	public void anObjectCompletionCannotRearmARedstoneHeldDoor()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(false);
		assertTrue(DoorTransitGate.claim(cycle, true, true));
		assertEquals(DoorOpenCycle.Phase.CONSUMED, cycle.complete(true, true));

		DoorTransitGate.complete(cycle, objectTransit(), true, false);

		assertEquals(DoorOpenCycle.Phase.CONSUMED, cycle.phase());
		assertTrue(cycle.portalActive());
	}

	@Test
	public void aClosedContactSurfaceCompletionLeavesItsPermanentlyLivePlateAlone()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(true);
		DoorTransit contact = new DoorTransit(
			DoorwayPlane.trapdoor(
				0, 64, 0, BlockFace.NORTH, Bisected.Half.BOTTOM, DoorOpenState.CLOSED),
			DoorwayCrossing.Direction.FRONT_TO_BACK,
			0.0F,
			0.0F);

		DoorTransitGate.complete(cycle, contact, true, false);

		assertTrue(cycle.portalActive());
		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
	}

	@Test
	public void aClaimedTransitStillConsumesItsCycleOnTheClosedDoorItLeavesBehind()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(false);
		assertTrue(DoorTransitGate.claim(cycle, true, true));

		DoorTransitGate.complete(cycle, livingTransit(), true, false);

		assertEquals(DoorOpenCycle.Phase.CLOSED, cycle.phase());
		assertFalse(cycle.portalActive());
	}

	@Test
	public void completingATransitTheCycleNeverAdmittedIsHarmless()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(true);

		DoorTransitGate.complete(cycle, livingTransit(), true, true);

		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
	}

	private static DoorTransit objectTransit()
	{
		return new DoorTransit(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			DoorwayCrossing.Direction.FRONT_TO_BACK,
			0.0F,
			0.0F,
			0.25D,
			0.25D,
			DoorTravelerClass.OBJECT,
			new DoorVec3(0.0D, 0.0D, -3.0D));
	}

	private static DoorTransit livingTransit()
	{
		return new DoorTransit(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			DoorwayCrossing.Direction.FRONT_TO_BACK,
			0.0F,
			0.0F);
	}
}
