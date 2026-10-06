package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;

public final class PortalCoordMapFlipTest {
	@Test
	public void mirrorOnVerticalNormalFlipsWorldUp() {
		assertTrue(PortalCoordMap.reflectionFlipsWorldUp(Frame.canonical(Face.U)));
		assertTrue(PortalCoordMap.reflectionFlipsWorldUp(Frame.canonical(Face.D)));
	}

	@Test
	public void mirrorOnWallNormalKeepsWorldUp() {
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(Frame.canonical(Face.N)));
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(Frame.canonical(Face.S)));
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(Frame.canonical(Face.E)));
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(Frame.canonical(Face.W)));
	}

	@Test
	public void floorToCeilingTunnelFlipsWorldUp() {
		Frame floor = Frame.canonical(Face.U);
		Frame ceiling = Frame.canonical(Face.D);
		assertTrue(PortalCoordMap.transformFlipsWorldUp(floor, ceiling));
		assertTrue(PortalCoordMap.transformFlipsWorldUp(ceiling, floor));
	}

	@Test
	public void matchingVerticalTunnelKeepsWorldUp() {
		Frame floorA = Frame.canonical(Face.U);
		Frame floorB = Frame.canonical(Face.U);
		assertFalse(PortalCoordMap.transformFlipsWorldUp(floorA, floorB));
	}

	@Test
	public void wallTunnelsKeepWorldUp() {
		for (Face fromNormal : new Face[] { Face.N, Face.S, Face.E, Face.W }) {
			for (Face toNormal : new Face[] { Face.N, Face.S, Face.E, Face.W }) {
				assertFalse(PortalCoordMap.transformFlipsWorldUp(Frame.canonical(fromNormal), Frame.canonical(toNormal)));
			}
		}
	}

	@Test
	public void wallToFloorTunnelDoesNotFlip() {
		Frame wall = Frame.canonical(Face.N);
		Frame floor = Frame.canonical(Face.U);
		assertFalse(PortalCoordMap.transformFlipsWorldUp(wall, floor));
		assertFalse(PortalCoordMap.transformFlipsWorldUp(floor, wall));
	}

	@Test
	public void eyeSideFlipOfVerticalFrameRestoresWorldUp() {
		Frame floorBackside = Frame.canonical(Face.U).flipNormal();
		Frame ceiling = Frame.canonical(Face.D);
		assertFalse(PortalCoordMap.transformFlipsWorldUp(floorBackside, ceiling));
	}
}
