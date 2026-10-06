package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;

import art.arcane.optics.math.Vec3;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class PortalFrameTest {
	private static final double EPSILON = 1e-9D;

	@Test
	public void equivalentAxesShareAValueKeyAndDifferentRollsRemainDistinct() {
		Set<Frame> frames = new HashSet<>();
		for (Face normal : Face.values()) {
			for (Face up : Face.values()) {
				if (normal.getAxis() == up.getAxis()) {
					continue;
				}
				Frame first = Frame.fromNormalUp(normal, up);
				Frame second = Frame.fromNormalUp(normal, up);
				assertEquals(first, second);
				assertEquals(first.hashCode(), second.hashCode());
				frames.add(first);
				frames.add(second);
			}
		}
		assertEquals(24, frames.size());
		assertNotEquals(Frame.fromNormalUp(Face.S, Face.U), Frame.fromNormalUp(Face.S, Face.E));
	}

	@Test
	public void canonicalDirectionPairsAreInvertible() {
		Vec3 fromOrigin = new Vec3(7.25D, -3.5D, 4.75D);
		Vec3 toOrigin = new Vec3(-11.25D, 9.5D, 22.75D);
		Vec3 point = new Vec3(8.25D, -1.5D, 10.75D);
		Vec3 vector = new Vec3(2.0D, -3.0D, 5.0D);

		for (Face fromNormal : Face.values()) {
			Frame fromFrame = Frame.canonical(fromNormal);
			for (Face toNormal : Face.values()) {
				Frame toFrame = Frame.canonical(toNormal);
				Vec3 projectedPoint = fromFrame.transformPoint(point, fromOrigin, toOrigin, toFrame);
				Vec3 restoredPoint = toFrame.transformPoint(projectedPoint, toOrigin, fromOrigin, fromFrame);
				assertVector(point, restoredPoint);

				Vec3 projectedVector = fromFrame.transformVector(vector, toFrame);
				Vec3 restoredVector = toFrame.transformVector(projectedVector, fromFrame);
				assertVector(vector, restoredVector);
			}
		}
	}

	@Test
	public void uprightToDownMapsScreenAxesIntoDownFrame() {
		Frame downFrame = Frame.canonical(Face.D);
		double[] scratch = new double[3];

		for (Face normal : new Face[] { Face.N, Face.E, Face.S, Face.W }) {
			Frame uprightFrame = Frame.canonical(normal);
			assertEquals(downFrame.getRight(), uprightFrame.transformDirection(uprightFrame.getRight(), downFrame, scratch));
			assertEquals(downFrame.getUp(), uprightFrame.transformDirection(uprightFrame.getUp(), downFrame, scratch));
			assertEquals(downFrame.getNormal(), uprightFrame.transformDirection(uprightFrame.getNormal(), downFrame, scratch));
		}
	}

	@Test
	public void downToUprightReverseTransformRestoresOriginalPointAndVector() {
		Frame downFrame = Frame.fromDirectionAndLook(Face.D, new Vec3(0.0D, -1.0D, -1.0D));
		Frame uprightFrame = Frame.canonical(Face.N);
		Vec3 downOrigin = new Vec3(20.0D, 64.0D, -10.0D);
		Vec3 uprightOrigin = new Vec3(-5.0D, 80.0D, 40.0D);
		Vec3 point = new Vec3(23.0D, 61.0D, -14.0D);
		Vec3 vector = new Vec3(1.0D, -2.0D, -3.0D);

		Vec3 throughPortal = downFrame.transformPoint(point, downOrigin, uprightOrigin, uprightFrame);
		Vec3 backThroughPortal = uprightFrame.transformPoint(throughPortal, uprightOrigin, downOrigin, downFrame);
		assertVector(point, backThroughPortal);

		Vec3 throughVector = downFrame.transformVector(vector, uprightFrame);
		Vec3 backVector = uprightFrame.transformVector(throughVector, downFrame);
		assertVector(vector, backVector);
	}

	@Test
	public void verticalFrameUsesLookYawForScreenUp() {
		Frame downNorth = Frame.fromDirectionAndLook(Face.D, new Vec3(0.0D, -1.0D, -1.0D));
		assertEquals(Face.D, downNorth.getNormal());
		assertEquals(Face.N, downNorth.getUp());
		assertEquals(Face.E, downNorth.getRight());

		Frame upSouth = Frame.fromDirectionAndLook(Face.U, new Vec3(0.0D, 1.0D, 1.0D));
		assertEquals(Face.U, upSouth.getNormal());
		assertEquals(Face.S, upSouth.getUp());
		assertEquals(Face.E, upSouth.getRight());
	}

	@Test
	public void verticalFrameDerivationUsesShapeBeforeFallback() {
		Frame eastWideDown = Frame.derive(new Box(0.0D, 5.0D, 64.0D, 64.0D, 0.0D, 2.0D), Face.D);
		assertEquals(Face.E, eastWideDown.getUp());

		Frame northWideDown = Frame.derive(new Box(0.0D, 2.0D, 64.0D, 64.0D, 0.0D, 5.0D), Face.D);
		assertEquals(Face.N, northWideDown.getUp());
	}

	@Test
	public void basisDirectionsRotateLikeProjectedBlockFacesAndAxes() {
		Frame remoteDown = Frame.fromDirectionAndLook(Face.D, new Vec3(0.0D, -1.0D, -1.0D));
		Frame localNorth = Frame.canonical(Face.N);
		double[] scratch = new double[3];

		assertEquals(Face.E, remoteDown.transformDirection(Face.E, localNorth, scratch));
		assertEquals(Face.U, remoteDown.transformDirection(Face.N, localNorth, scratch));
		assertEquals(Face.N, remoteDown.transformDirection(Face.D, localNorth, scratch));
		assertEquals(Face.D, remoteDown.transformDirection(Face.S, localNorth, scratch));
	}

	@Test
	public void flipNormalPreservesScreenUpAndReversesScreenRight() {
		Frame northFrame = Frame.canonical(Face.N);
		Frame flipped = northFrame.flipNormal();

		assertEquals(Face.S, flipped.getNormal());
		assertEquals(Face.U, flipped.getUp());
		assertEquals(Face.W, flipped.getRight());
	}

	@Test
	public void rollRotatesScreenAxesAroundNormal() {
		Frame northFrame = Frame.canonical(Face.N);
		Frame clockwise = northFrame.rotateClockwise();
		Frame counterClockwise = northFrame.rotateCounterClockwise();

		assertEquals(Face.N, clockwise.getNormal());
		assertEquals(Face.E, clockwise.getUp());
		assertEquals(Face.D, clockwise.getRight());

		assertEquals(Face.N, counterClockwise.getNormal());
		assertEquals(Face.W, counterClockwise.getUp());
		assertEquals(Face.U, counterClockwise.getRight());
	}

	@Test
	public void changingNormalPreservesRollWhenPossible() {
		Frame rolledNorth = Frame.canonical(Face.N).rotateClockwise();
		Frame flipped = rolledNorth.withNormal(Face.S);
		Frame upFacing = rolledNorth.withNormal(Face.U);

		assertEquals(Face.S, flipped.getNormal());
		assertEquals(Face.E, flipped.getUp());
		assertEquals(Face.U, upFacing.getNormal());
		assertEquals(Face.E, upFacing.getUp());
	}

	private static void assertVector(Vec3 expected, Vec3 actual) {
		assertEquals(expected.getX(), actual.getX(), EPSILON);
		assertEquals(expected.getY(), actual.getY(), EPSILON);
		assertEquals(expected.getZ(), actual.getZ(), EPSILON);
	}
}
