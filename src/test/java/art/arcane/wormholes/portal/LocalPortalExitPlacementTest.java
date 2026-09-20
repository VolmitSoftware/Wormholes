package art.arcane.wormholes.portal;

import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalPortalExitPlacementTest {
    @Test
    void fallingJumpPreservesHeightInsteadOfPlacingFeetInsideTheFloor() {
        LocalPortal portal = portal(Direction.S);
        Vector point = portal.getOrigin().clone();
        point.setY(101.42D);
        Traversive traversive = crossing(portal, point, new Vector(0.0D, -0.8D, -0.2D), true);

        Location target = portal.computeExitTarget(traversive);

        assertEquals(101.42D, target.getY(), 1.0E-9D);
        assertEquals(point.getX(), target.getX(), 1.0E-9D);
        assertEquals(point.getZ(), target.getZ(), 1.0E-9D);
        assertEquals(new Vector(0.0D, -0.8D, -0.2D), traversive.getOutVelocity(portal.getFrame()));
    }

    @Test
    void jumpingAndStrafingPreserveTheTransformedEndpointOnEveryFrame() {
        for (Direction direction : List.of(Direction.N, Direction.S, Direction.E, Direction.W, Direction.U, Direction.D)) {
            LocalPortal portal = portal(direction);
            PortalFrame frame = portal.getFrame();
            for (boolean frontSide : List.of(Boolean.TRUE, Boolean.FALSE)) {
                double sign = frontSide ? -1.0D : 1.0D;
                for (double verticalSpeed : List.of(-3.0D, 3.0D)) {
                    Vector point = portal.getOrigin().clone();
                    Vector velocity = frame.getNormal().toVector().multiply(sign * 0.2D)
                        .add(frame.getRight().toVector().multiply(4.0D))
                        .add(frame.getUp().toVector().multiply(verticalSpeed));
                    Traversive traversive = crossing(portal, point, velocity, frontSide);

                    Location target = portal.computeExitTarget(traversive);
                    Vector expected = point;

                    assertEquals(expected.getX(), target.getX(), 1.0E-9D);
                    assertEquals(expected.getY(), target.getY(), 1.0E-9D);
                    assertEquals(expected.getZ(), target.getZ(), 1.0E-9D);
                    assertEquals(velocity, traversive.getOutVelocity(frame));
                }
            }
        }
    }

    @Test
    void zeroNormalVelocityDoesNotDisplaceTheArrival() {
        LocalPortal portal = portal(Direction.S);
        Vector point = portal.getOrigin().clone();
        for (boolean frontSide : List.of(Boolean.TRUE, Boolean.FALSE)) {
            Traversive traversive = crossing(portal, point, new Vector(0.0D, -0.8D, 0.0D), frontSide);
            Location target = portal.computeExitTarget(traversive);

            assertEquals(point.getY(), target.getY(), 1.0E-9D);
            assertEquals(point.getZ(), target.getZ(), 1.0E-9D);
        }
    }

    private static LocalPortal portal(Direction normal) {
        World world = AccessTestPortals.world("exit-placement");
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setFrame(PortalFrame.canonical(normal));
        return portal;
    }

    private static Traversive crossing(LocalPortal portal, Vector point, Vector velocity, boolean frontSide) {
        return new Traversive(null, TraversableType.PLAYER, portal.getFrame().view(frontSide),
            portal.getOrigin(), point, velocity, velocity.clone().normalize(), frontSide);
    }
}
