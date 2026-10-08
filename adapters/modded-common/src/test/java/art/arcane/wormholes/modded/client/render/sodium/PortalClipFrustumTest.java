package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector4d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalClipFrustumTest {
    private static final Vector4d EAST_OF_X_105 = new Vector4d(1.0D, 0.0D, 0.0D, -105.0D);

    @Test
    public void theOpenFrustumAcceptsEveryBox() {
        Frustum open = PortalClipFrustum.OPEN;
        assertTrue(open.testAab(-1.0E6F, -1.0E6F, -1.0E6F, -1.0E6F + 1.0F, -1.0E6F + 1.0F, -1.0E6F + 1.0F));
        assertEquals(FrustumIntersection.INSIDE, open.intersectAab(-5.0F, -5.0F, -5.0F, 5.0F, 5.0F, 5.0F));
        assertTrue(open.testSection(900.0F, -900.0F, 900.0F));
        assertTrue(open.testSectionExpanded(900.0F, -900.0F, 900.0F, 1.0F));
    }

    @Test
    public void boxesBehindTheDestinationPlaneAreCulledInCameraRelativeSpace() {
        Frustum clipped = PortalClipFrustum.clipped(PortalClipFrustum.OPEN, EAST_OF_X_105, 100.0D, 64.0D, 0.0D);
        assertTrue(clipped.testAab(6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F));
        assertFalse(clipped.testAab(0.0F, 0.0F, 0.0F, 4.0F, 1.0F, 1.0F));
        assertTrue(clipped.testAab(4.0F, 0.0F, 0.0F, 6.0F, 1.0F, 1.0F));
    }

    @Test
    public void sectionTestsUseThePaddedSectionRadius() {
        Frustum clipped = PortalClipFrustum.clipped(PortalClipFrustum.OPEN, EAST_OF_X_105, 100.0D, 64.0D, 0.0D);
        assertTrue(clipped.testSection(0.0F, 0.0F, 0.0F));
        assertTrue(clipped.testSection(-4.0F, 0.0F, 0.0F));
        assertFalse(clipped.testSection(-4.2F, 0.0F, 0.0F));
        assertTrue(clipped.testSectionExpanded(-5.0F, 0.0F, 0.0F, 1.0F));
        assertFalse(clipped.testSectionExpanded(-5.2F, 0.0F, 0.0F, 1.0F));
    }

    @Test
    public void intersectionReportsInsideOnlyWhenTheBaseAndThePlaneBothContainTheBox() {
        Frustum clipped = PortalClipFrustum.clipped(PortalClipFrustum.OPEN, EAST_OF_X_105, 100.0D, 64.0D, 0.0D);
        assertEquals(FrustumIntersection.INSIDE, clipped.intersectAab(6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F));
        assertEquals(FrustumIntersection.INTERSECT, clipped.intersectAab(4.0F, 0.0F, 0.0F, 6.0F, 1.0F, 1.0F));
        assertEquals(FrustumIntersection.OUTSIDE, clipped.intersectAab(0.0F, 0.0F, 0.0F, 4.0F, 1.0F, 1.0F));
    }

    @Test
    public void theBaseFrustumStillCulls() {
        Frustum clipped = PortalClipFrustum.clipped(new Rejecting(), EAST_OF_X_105, 100.0D, 64.0D, 0.0D);
        assertFalse(clipped.testAab(6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F));
        assertFalse(clipped.testSection(20.0F, 0.0F, 0.0F));
        assertFalse(clipped.testSectionExpanded(20.0F, 0.0F, 0.0F, 1.0F));
        assertEquals(FrustumIntersection.PLANE_NY, clipped.intersectAab(6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F));
    }

    @Test
    public void aTiltedPlaneUsesTheNearestCornerAlongItsNormal() {
        double inverse = 1.0D / Math.sqrt(2.0D);
        Frustum clipped = PortalClipFrustum.clipped(PortalClipFrustum.OPEN, new Vector4d(inverse, inverse, 0.0D, 0.0D), 0.0D, 0.0D, 0.0D);
        assertTrue(clipped.testAab(-2.0F, -2.0F, 0.0F, 0.5F, 0.5F, 1.0F));
        assertFalse(clipped.testAab(-2.0F, -2.0F, 0.0F, -0.5F, 0.4F, 1.0F));
        assertEquals(FrustumIntersection.INTERSECT, clipped.intersectAab(-2.0F, -2.0F, 0.0F, 0.5F, 0.5F, 1.0F));
    }

    private static final class Rejecting implements Frustum {
        @Override
        public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return false;
        }

        @Override
        public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return FrustumIntersection.PLANE_NY;
        }

        @Override
        public boolean testSection(float x, float y, float z) {
            return false;
        }

        @Override
        public boolean testSectionExpanded(float x, float y, float z, float extend) {
            return false;
        }
    }
}
