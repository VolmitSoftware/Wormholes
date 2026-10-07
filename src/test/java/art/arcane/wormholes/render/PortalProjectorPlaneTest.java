package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.volume.ProjectionVolume;

public final class PortalProjectorPlaneTest {
	@Test
	public void onlyOppositeSideCellsPastThePortalSlabAreProjected() {
		double clearance = 0.5001D;

		assertFalse(ProjectionVolume.projectsBehindPortalPlane(2.0D, true, clearance));
		assertFalse(ProjectionVolume.projectsBehindPortalPlane(0.25D, true, clearance));
		assertFalse(ProjectionVolume.projectsBehindPortalPlane(-0.25D, true, clearance));
		assertTrue(ProjectionVolume.projectsBehindPortalPlane(-1.0D, true, clearance));

		assertFalse(ProjectionVolume.projectsBehindPortalPlane(-2.0D, false, clearance));
		assertFalse(ProjectionVolume.projectsBehindPortalPlane(-0.25D, false, clearance));
		assertFalse(ProjectionVolume.projectsBehindPortalPlane(0.25D, false, clearance));
		assertTrue(ProjectionVolume.projectsBehindPortalPlane(1.0D, false, clearance));
	}

	@Test
	public void planeClearanceTracksPortalNormalThickness() {
		Box northPortal = new Box(0.0D, 4.999D, 64.0D, 68.999D, 10.0D, 10.999D);
		double northClearance = ProjectionVolume.portalPlaneClearance(northPortal, Frame.canonical(Face.N));
		assertTrue(northClearance > 0.5D);
		assertTrue(northClearance < 0.502D);

		Box thickDownPortal = new Box(0.0D, 4.999D, 63.0D, 64.999D, 10.0D, 14.999D);
		double downClearance = ProjectionVolume.portalPlaneClearance(thickDownPortal, Frame.canonical(Face.D));
		assertTrue(downClearance > 0.999D);
		assertTrue(downClearance < 1.002D);
	}

	@Test
	public void scanBoundsIncludeBlockCentersAtTheProjectionEdges() {
		assertEquals(4, ProjectionVolume.minBlockForCenter(4.5D));
		assertEquals(8, ProjectionVolume.maxBlockForCenter(8.5D));
		assertEquals(4, ProjectionVolume.minBlockForCenter(4.5000003D));
		assertEquals(8, ProjectionVolume.maxBlockForCenter(8.4999997D));
		assertEquals(5, ProjectionVolume.minBlockForCenter(4.500002D));
		assertEquals(7, ProjectionVolume.maxBlockForCenter(8.499998D));
	}

	@Test
	public void portalPlaneWindowRejectsRaysThatMissTheApertureBounds() {
		Box area = new Box(0.0D, 2.999D, 64.0D, 66.999D, 10.0D, 10.999D);
		Frame frame = Frame.canonical(Face.N);
		double originX = 1.5D;
		double originY = 65.5D;
		double originZ = 10.5D;
		double eyeX = 1.5D;
		double eyeY = 65.5D;
		double eyeZ = 6.5D;
		double eyeSignedDistance = 4.0D;
		PlaneWindow window = PlaneWindow.create(null, area, frame,
			originX, originY, originZ, 0.0D, eyeSignedDistance);

		assertTrue(window.containsRayIntersection(eyeX, eyeY, eyeZ, 1.5D, 65.5D, 15.5D, -5.0D));
		assertFalse(window.containsRayIntersection(eyeX, eyeY, eyeZ, 20.5D, 65.5D, 15.5D, -5.0D));
		assertFalse(window.containsRayIntersection(eyeX, eyeY, eyeZ, 1.5D, 90.5D, 15.5D, -5.0D));
	}

	@Test
	public void backSideViewFrameKeepsUpAndFlipsRight() {
		Frame front = Frame.canonical(Face.N);
		Frame back = PortalProjector.viewFrame(front, false);

		assertEquals(Face.S, back.getNormal());
		assertEquals(Face.U, back.getUp());
		assertEquals(Face.W, back.getRight());
	}
}
