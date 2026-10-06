package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;

import art.arcane.optics.math.Vec3d;
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
		Vec3d fromOrigin = new Vec3d(7.25D, -3.5D, 4.75D);
		Vec3d toOrigin = new Vec3d(-11.25D, 9.5D, 22.75D);
		Vec3d point = new Vec3d(8.25D, -1.5D, 10.75D);
		Vec3d vector = new Vec3d(2.0D, -3.0D, 5.0D);

		for (Face fromNormal : Face.values()) {
			Frame fromFrame = Frame.canonical(fromNormal);
			for (Face toNormal : Face.values()) {
				Frame toFrame = Frame.canonical(toNormal);
				Vec3d projectedPoint = OpticTransform.between(fromFrame, fromOrigin, toFrame, toOrigin).point(point);
				Vec3d restoredPoint = OpticTransform.between(toFrame, toOrigin, fromFrame, fromOrigin).point(projectedPoint);
				assertVector(point, restoredPoint);

				Vec3d projectedVector = OpticTransform.of(AxisPermutation.between(fromFrame, toFrame), 0.0D, 0.0D, 0.0D).vector(vector);
				Vec3d restoredVector = OpticTransform.of(AxisPermutation.between(toFrame, fromFrame), 0.0D, 0.0D, 0.0D).vector(projectedVector);
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
			assertEquals(downFrame.getRight(), AxisPermutation.between(uprightFrame, downFrame).face(uprightFrame.getRight()));
			assertEquals(downFrame.getUp(), AxisPermutation.between(uprightFrame, downFrame).face(uprightFrame.getUp()));
			assertEquals(downFrame.getNormal(), AxisPermutation.between(uprightFrame, downFrame).face(uprightFrame.getNormal()));
		}
	}

	@Test
	public void downToUprightReverseTransformRestoresOriginalPointAndVector() {
		Frame downFrame = Frame.fromDirectionAndLook(Face.D, new Vec3d(0.0D, -1.0D, -1.0D));
		Frame uprightFrame = Frame.canonical(Face.N);
		Vec3d downOrigin = new Vec3d(20.0D, 64.0D, -10.0D);
		Vec3d uprightOrigin = new Vec3d(-5.0D, 80.0D, 40.0D);
		Vec3d point = new Vec3d(23.0D, 61.0D, -14.0D);
		Vec3d vector = new Vec3d(1.0D, -2.0D, -3.0D);

		Vec3d throughPortal = OpticTransform.between(downFrame, downOrigin, uprightFrame, uprightOrigin).point(point);
		Vec3d backThroughPortal = OpticTransform.between(uprightFrame, uprightOrigin, downFrame, downOrigin).point(throughPortal);
		assertVector(point, backThroughPortal);

		Vec3d throughVector = OpticTransform.of(AxisPermutation.between(downFrame, uprightFrame), 0.0D, 0.0D, 0.0D).vector(vector);
		Vec3d backVector = OpticTransform.of(AxisPermutation.between(uprightFrame, downFrame), 0.0D, 0.0D, 0.0D).vector(throughVector);
		assertVector(vector, backVector);
	}

	@Test
	public void verticalFrameUsesLookYawForScreenUp() {
		Frame downNorth = Frame.fromDirectionAndLook(Face.D, new Vec3d(0.0D, -1.0D, -1.0D));
		assertEquals(Face.D, downNorth.getNormal());
		assertEquals(Face.N, downNorth.getUp());
		assertEquals(Face.E, downNorth.getRight());

		Frame upSouth = Frame.fromDirectionAndLook(Face.U, new Vec3d(0.0D, 1.0D, 1.0D));
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
		Frame remoteDown = Frame.fromDirectionAndLook(Face.D, new Vec3d(0.0D, -1.0D, -1.0D));
		Frame localNorth = Frame.canonical(Face.N);
		double[] scratch = new double[3];

		assertEquals(Face.E, AxisPermutation.between(remoteDown, localNorth).face(Face.E));
		assertEquals(Face.U, AxisPermutation.between(remoteDown, localNorth).face(Face.N));
		assertEquals(Face.N, AxisPermutation.between(remoteDown, localNorth).face(Face.D));
		assertEquals(Face.D, AxisPermutation.between(remoteDown, localNorth).face(Face.S));
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

	private static void assertVector(Vec3d expected, Vec3d actual) {
		assertEquals(expected.getX(), actual.getX(), EPSILON);
		assertEquals(expected.getY(), actual.getY(), EPSILON);
		assertEquals(expected.getZ(), actual.getZ(), EPSILON);
	}
}
