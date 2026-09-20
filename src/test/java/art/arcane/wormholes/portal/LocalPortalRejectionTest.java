package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.Direction;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPortalRejectionTest {
    @Test
    void frontSideRejectionReturnsTravelerAlongViewedNormal() {
        Traversive traversive = traversive(true);

        assertVector(new Vector(2.0D, 65.0D, 1.75D), LocalPortalTraversal.sourceRejectionPoint(traversive));
        assertVector(new Vector(0.0D, 0.0D, -3.0D), LocalPortalTraversal.sourceRejectionVelocity(traversive));
    }

    @Test
    void backSideRejectionReturnsTravelerToOppositeSourceSide() {
        Traversive traversive = traversive(false);

        assertVector(new Vector(2.0D, 65.0D, 4.25D), LocalPortalTraversal.sourceRejectionPoint(traversive));
        assertVector(new Vector(0.0D, 0.0D, 3.0D), LocalPortalTraversal.sourceRejectionVelocity(traversive));
    }

    @Test
    void departureCommitmentAcceptsWithinRadiusAndRejectsBeyond() {
        assertTrue(LocalPortalTraversal.withinDepartureCommitmentRadius(0.0D));
        assertTrue(LocalPortalTraversal.withinDepartureCommitmentRadius(255.9D));
        assertTrue(LocalPortalTraversal.withinDepartureCommitmentRadius(256.0D));
        assertFalse(LocalPortalTraversal.withinDepartureCommitmentRadius(256.1D));
    }

    @Test
    void rejectedFastCrossingReturnsBeyondTheSourcePlane() {
        for (boolean frontSide : new boolean[] {true, false}) {
            Traversive surface = traversive(frontSide);
            Vector normal = surface.getInFrame().getNormal().toVector();
            Vector endpoint = surface.getInOrigin().clone().subtract(normal.clone().multiply(8.0D))
                .add(new Vector(0.4D, 0.2D, 0.0D));
            Traversive crossing = surface.forMember(new Object(), endpoint);
            Vector rejection = LocalPortalTraversal.sourceRejectionPoint(crossing);

            assertEquals(1.25D, rejection.clone().subtract(surface.getInOrigin()).dot(normal), 1.0E-9D);
            assertEquals(endpoint.getX(), rejection.getX(), 1.0E-9D);
            assertEquals(endpoint.getY(), rejection.getY(), 1.0E-9D);
        }
    }

    private static Traversive traversive(boolean frontSide) {
        PortalFrame frame = PortalFrame.canonical(Direction.N).view(frontSide);
        return new Traversive(
            new Object(),
            TraversableType.ENTITY,
            frame,
            new Vector(2.0D, 65.0D, 3.0D),
            new Vector(2.0D, 65.0D, 3.0D),
            new Vector(0.0D, 0.0D, frontSide ? -0.2D : 0.2D),
            new Vector(0.0D, 0.0D, frontSide ? -1.0D : 1.0D),
            frontSide
        );
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1.0E-9D);
        assertEquals(expected.getY(), actual.getY(), 1.0E-9D);
        assertEquals(expected.getZ(), actual.getZ(), 1.0E-9D);
    }
}
