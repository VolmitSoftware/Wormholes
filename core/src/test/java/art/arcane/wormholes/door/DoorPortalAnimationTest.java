package art.arcane.wormholes.door;

import art.arcane.wormholes.util.Direction;

import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorPortalAnimationTest
{
	private static final float EPSILON = 0.0001F;
	private static final Direction[] CARDINALS =
		{Direction.N, Direction.S, Direction.E, Direction.W};

	@Test
	void frameNeverTouchesTheNormalAxis()
	{
		for(Direction facing : CARDINALS)
		{
			PortalPlaneGeometry base = overlay(facing);
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				PortalPlaneGeometry frame =
					DoorPortalAnimation.frame(base, facing, tick);
				if(facing == Direction.N || facing == Direction.S)
				{
					assertEquals(base.translationZ(), frame.translationZ(), EPSILON);
					assertEquals(base.scaleZ(), frame.scaleZ(), EPSILON);
				}
				else
				{
					assertEquals(base.translationX(), frame.translationX(), EPSILON);
					assertEquals(base.scaleX(), frame.scaleX(), EPSILON);
				}
			}
		}
	}

	@Test
	void framePulsesInsideTheBaseEnvelopeAndStaysCentered()
	{
		for(Direction facing : CARDINALS)
		{
			PortalPlaneGeometry base = overlay(facing);
			float baseLateralScale = lateralScale(base, facing);
			float baseLateralCenter = lateralTranslation(base, facing) + (baseLateralScale / 2.0F);
			float baseVerticalCenter = base.translationY() + (base.scaleY() / 2.0F);
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				PortalPlaneGeometry frame =
					DoorPortalAnimation.frame(base, facing, tick);
				float lateralScale = lateralScale(frame, facing);
				float verticalScale = frame.scaleY();

				assertTrue(lateralScale <= baseLateralScale + EPSILON);
				assertTrue(lateralScale >= (baseLateralScale * (1.0F - DoorPortalAnimation.PULSE_DEPTH)) - EPSILON);
				assertTrue(verticalScale <= base.scaleY() + EPSILON);
				assertTrue(verticalScale >= (base.scaleY() * (1.0F - DoorPortalAnimation.PULSE_DEPTH)) - EPSILON);
				assertEquals(
					baseLateralCenter,
					lateralTranslation(frame, facing) + (lateralScale / 2.0F),
					DoorPortalAnimation.SWAY_AMPLITUDE + EPSILON);
				assertEquals(baseVerticalCenter, frame.translationY() + (verticalScale / 2.0F), EPSILON);
			}
		}
	}

	@Test
	void frameActuallyAnimatesBetweenTicks()
	{
		for(Direction facing : CARDINALS)
		{
			PortalPlaneGeometry base = overlay(facing);
			PortalPlaneGeometry first =
				DoorPortalAnimation.frame(base, facing, 0);
			boolean moved = false;
			for(int tick = DoorPortalAnimation.FRAME_PERIOD_TICKS; tick <= 48; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				if(!DoorPortalAnimation.frame(base, facing, tick).equals(first))
				{
					moved = true;
					break;
				}
			}
			assertTrue(moved, facing + " frame never changed");
		}
	}

	@Test
	void orbitPointsStayOnTheVisiblePanel()
	{
		for(Direction facing : CARDINALS)
		{
			PortalPlaneGeometry base = overlay(facing);
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				for(int arm = 0; arm < DoorPortalAnimation.ORBIT_ARMS; arm++)
				{
					assertOnPanel(base, facing, DoorPortalAnimation.orbitPoint(base, facing, tick, arm));
				}
			}
		}
	}

	@Test
	void counterRotatingArmsDiverge()
	{
		PortalPlaneGeometry base = overlay(Direction.N);
		boolean diverged = false;
		for(int tick = 0; tick <= 44; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
		{
			double[] first = DoorPortalAnimation.orbitPoint(base, Direction.N, tick, 0);
			double[] second = DoorPortalAnimation.orbitPoint(base, Direction.N, tick, 1);
			if(Math.abs(first[0] - second[0]) > 0.05D || Math.abs(first[1] - second[1]) > 0.05D)
			{
				diverged = true;
				break;
			}
		}
		assertTrue(diverged);
	}

	@Test
	void scatterPointsCoverThePanelWithoutLeavingIt()
	{
		for(Direction facing : CARDINALS)
		{
			PortalPlaneGeometry base = overlay(facing);
			assertOnPanel(base, facing, DoorPortalAnimation.scatterPoint(base, facing, 0.0D, 0.0D));
			assertOnPanel(base, facing, DoorPortalAnimation.scatterPoint(base, facing, 0.999D, 0.999D));
			double[] low = DoorPortalAnimation.scatterPoint(base, facing, 0.05D, 0.05D);
			double[] high = DoorPortalAnimation.scatterPoint(base, facing, 0.95D, 0.95D);
			assertNotEquals(low[1], high[1]);
		}
	}

	@Test
	void aFlatPanelPulsesAcrossBothHorizontalAxesAndHoldsItsThickness()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			0,
			64,
			0,
			Direction.S,
			DoorHalf.BOTTOM,
			DoorOpenState.OPEN);
		PortalPlaneGeometry base = DoorPortalGeometry.overlayGeometry(
			DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT), Direction.U);
		float baseCenterX = base.translationX() + (base.scaleX() / 2.0F);
		float baseCenterZ = base.translationZ() + (base.scaleZ() / 2.0F);
		for(Direction face : new Direction[] {Direction.U, Direction.D})
		{
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				PortalPlaneGeometry frame = DoorPortalAnimation.frame(base, face, tick);
				assertEquals(base.translationY(), frame.translationY(), EPSILON);
				assertEquals(base.scaleY(), frame.scaleY(), EPSILON);
				assertTrue(frame.scaleX() <= base.scaleX() + EPSILON);
				assertTrue(frame.scaleZ() <= base.scaleZ() + EPSILON);
				assertTrue(frame.scaleX() >= (base.scaleX() * (1.0F - DoorPortalAnimation.PULSE_DEPTH)) - EPSILON);
				assertTrue(frame.scaleZ() >= (base.scaleZ() * (1.0F - DoorPortalAnimation.PULSE_DEPTH)) - EPSILON);
				assertEquals(
					baseCenterX,
					frame.translationX() + (frame.scaleX() / 2.0F),
					DoorPortalAnimation.SWAY_AMPLITUDE + EPSILON);
				assertEquals(baseCenterZ, frame.translationZ() + (frame.scaleZ() / 2.0F), EPSILON);
			}

			for(int arm = 0; arm < DoorPortalAnimation.ORBIT_ARMS; arm++)
			{
				double[] orbit = DoorPortalAnimation.orbitPoint(base, face, 7, arm);
				assertEquals(base.translationY() + (base.scaleY() / 2.0D), orbit[1], EPSILON);
			}
			double[] scatter = DoorPortalAnimation.scatterPoint(base, face, 0.25D, 0.75D);
			assertEquals(base.translationY() + (base.scaleY() / 2.0D), scatter[1], EPSILON);
			assertEquals(base.translationX() + (0.25D * base.scaleX()), scatter[0], EPSILON);
			assertEquals(base.translationZ() + (0.75D * base.scaleZ()), scatter[2], EPSILON);
		}
	}

	@Test
	void invalidInputsAreRejected()
	{
		PortalPlaneGeometry base = overlay(Direction.N);
		assertThrows(NullPointerException.class,
			() -> DoorPortalAnimation.frame(null, Direction.N, 0));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.orbitPoint(base, Direction.N, 0, DoorPortalAnimation.ORBIT_ARMS));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.orbitPoint(base, Direction.N, 0, -1));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.scatterPoint(base, Direction.N, 1.0D, 0.5D));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.scatterPoint(base, Direction.N, 0.5D, -0.1D));
	}

	private static PortalPlaneGeometry overlay(Direction facing)
	{
		return DoorPortalGeometry.overlayGeometry(
			DoorPortalGeometry.geometry(facing, DoorHinge.LEFT), facing);
	}

	private static void assertOnPanel(
		PortalPlaneGeometry base,
		Direction facing,
		double[] point)
	{
		assertTrue(point[1] >= base.translationY() - EPSILON);
		assertTrue(point[1] <= base.translationY() + base.scaleY() + EPSILON);
		if(facing == Direction.N || facing == Direction.S)
		{
			assertTrue(point[0] >= base.translationX() - EPSILON);
			assertTrue(point[0] <= base.translationX() + base.scaleX() + EPSILON);
			assertEquals(base.translationZ() + (base.scaleZ() / 2.0D), point[2], EPSILON);
		}
		else
		{
			assertTrue(point[2] >= base.translationZ() - EPSILON);
			assertTrue(point[2] <= base.translationZ() + base.scaleZ() + EPSILON);
			assertEquals(base.translationX() + (base.scaleX() / 2.0D), point[0], EPSILON);
		}
	}

	private static float lateralScale(
		PortalPlaneGeometry geometry,
		Direction facing)
	{
		return facing == Direction.N || facing == Direction.S
			? geometry.scaleX()
			: geometry.scaleZ();
	}

	private static float lateralTranslation(
		PortalPlaneGeometry geometry,
		Direction facing)
	{
		return facing == Direction.N || facing == Direction.S
			? geometry.translationX()
			: geometry.translationZ();
	}
}
