package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.ProjectorFrameTransform;
import art.arcane.optics.volume.PlaneWindow;

public final class PortalProjectorPlaneTest {
	@Test
	public void onlyOppositeSideCellsPastThePortalSlabAreProjected() {
		double clearance = 0.5001D;

		assertFalse(ProjectorFrameTransform.projectsBehindPortalPlane(2.0D, true, clearance));
		assertFalse(ProjectorFrameTransform.projectsBehindPortalPlane(0.25D, true, clearance));
		assertFalse(ProjectorFrameTransform.projectsBehindPortalPlane(-0.25D, true, clearance));
		assertTrue(ProjectorFrameTransform.projectsBehindPortalPlane(-1.0D, true, clearance));

		assertFalse(ProjectorFrameTransform.projectsBehindPortalPlane(-2.0D, false, clearance));
		assertFalse(ProjectorFrameTransform.projectsBehindPortalPlane(-0.25D, false, clearance));
		assertFalse(ProjectorFrameTransform.projectsBehindPortalPlane(0.25D, false, clearance));
		assertTrue(ProjectorFrameTransform.projectsBehindPortalPlane(1.0D, false, clearance));
	}

	@Test
	public void planeClearanceTracksPortalNormalThickness() {
		Box northPortal = new Box(0.0D, 4.999D, 64.0D, 68.999D, 10.0D, 10.999D);
		double northClearance = ProjectorFrameTransform.portalPlaneClearance(northPortal, Frame.canonical(Face.N));
		assertTrue(northClearance > 0.5D);
		assertTrue(northClearance < 0.502D);

		Box thickDownPortal = new Box(0.0D, 4.999D, 63.0D, 64.999D, 10.0D, 14.999D);
		double downClearance = ProjectorFrameTransform.portalPlaneClearance(thickDownPortal, Frame.canonical(Face.D));
		assertTrue(downClearance > 0.999D);
		assertTrue(downClearance < 1.002D);
	}

	@Test
	public void scanBoundsIncludeBlockCentersAtTheProjectionEdges() {
		assertEquals(4, ProjectorFrameTransform.minBlockForCenter(4.5D));
		assertEquals(8, ProjectorFrameTransform.maxBlockForCenter(8.5D));
		assertEquals(4, ProjectorFrameTransform.minBlockForCenter(4.5000003D));
		assertEquals(8, ProjectorFrameTransform.maxBlockForCenter(8.4999997D));
		assertEquals(5, ProjectorFrameTransform.minBlockForCenter(4.500002D));
		assertEquals(7, ProjectorFrameTransform.maxBlockForCenter(8.499998D));
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

	@Test
	public void reuseAllowsSmallEyeDriftForAnyNumberOfPasses() {
		assertTrue(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, false, false,
			12.5D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));

		assertTrue(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, false, false,
			12.7D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, false, false,
			12.5D + 0.3D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));
	}

	@Test
	public void reuseAllowsUnchangedRemoteProjection() {
		assertTrue(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, false, false,
			12.5D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));
	}

	@Test
	public void viewOcclusionResamplesCellVisibilityAfterMeaningfulEyeMovement() {
		assertFalse(PortalProjector.requiresViewCellResample(ProjectionRenderMode.PANOPTIC, true,
			0.25D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D));
		assertFalse(PortalProjector.requiresViewCellResample(ProjectionRenderMode.VENTICULAR, true,
			0.249D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D));
		assertTrue(PortalProjector.requiresViewCellResample(ProjectionRenderMode.VENTICULAR, true,
			0.25D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D));
		assertFalse(PortalProjector.requiresViewCellResample(ProjectionRenderMode.VENTICULAR, false,
			4.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D));
	}

	@Test
	public void reuseDefeatedBySideFlip() {
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, true, false,
			12.5D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));
	}

	@Test
	public void reuseDefeatedByLocalWorldChange() {
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, false, true,
			12.5D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, false, false, false, false, true,
			12.7D, 64.62D, -3.25D, 12.5D, 64.62D, -3.25D));
	}

	@Test
	public void reuseDefeatedByResampleTriggers() {
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, true, false, false, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, false, true, false, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 1, false, false, false, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
		assertFalse(PortalProjector.canReuseProjection(true, true, true, 0, false, false, true, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
		assertFalse(PortalProjector.canReuseProjection(false, true, true, 0, false, false, false, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
		assertFalse(PortalProjector.canReuseProjection(true, false, true, 0, false, false, false, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
		assertFalse(PortalProjector.canReuseProjection(true, true, false, 0, false, false, false, false, false,
			0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D));
	}
}
