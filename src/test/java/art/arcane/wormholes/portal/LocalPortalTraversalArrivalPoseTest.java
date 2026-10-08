package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.util.BukkitGeometry;

final class LocalPortalTraversalArrivalPoseTest {
    @Test
    void lookingStraightDownIntoAFloorThatExitsUpwardArrivesLookingStraightUp() {
        LocalPortal source = portal(Face.U);
        LocalPortal destination = portal(Face.D);
        Traversive crossing = crossing(source, new Angles.Look(30.0F, 90.0F), new Vector(0.0D, -0.4D, 0.0D));

        Location target = destination.computeExitTarget(crossing);
        LookTransfer predicted = ArrivalOrientation.transfer(crossing.crossing(), LookTransfer.cameraUp(30.0F, 90.0F), destination.getFrame(),
            OrientationRule.FRAME, false);

        assertEquals(-90.0F, target.getPitch(), 0.0F);
        assertEquals(predicted.yaw(), Angles.unwrap(target.getYaw(), predicted.yaw()), 1.0E-3F);
        assertEquals(0.0F, predicted.roll(), 1.0E-3F);
    }

    @Test
    void lookingStraightDownIntoAFloorThatExitsAWallArrivesLevelAlongTheExit() {
        LocalPortal source = portal(Face.U);
        for (Face wall : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            LocalPortal destination = portal(wall);
            Traversive crossing = crossing(source, new Angles.Look(0.0F, 90.0F), new Vector(0.0D, -0.4D, 0.0D));

            Location target = destination.computeExitTarget(crossing);
            Vec3d exitDirection = destination.getFrame().getNormal().toVector().multiply(-1.0D);
            Vec3d look = Angles.direction(target.getYaw(), target.getPitch());

            assertEquals(0.0F, target.getPitch(), 1.0E-3F, wall.name());
            assertEquals(exitDirection.x(), look.x(), 1.0E-5D, wall.name());
            assertEquals(exitDirection.z(), look.z(), 1.0E-5D, wall.name());
        }
    }

    @Test
    void theExactRotationSurvivesWhereTheLookVectorLosesTheYaw() {
        LocalPortal source = portal(Face.U);
        LocalPortal destination = portal(Face.D);
        Location first = destination.computeExitTarget(crossing(source, new Angles.Look(30.0F, 90.0F), new Vector(0.0D, -0.4D, 0.0D)));
        Location second = destination.computeExitTarget(crossing(source, new Angles.Look(120.0F, 90.0F), new Vector(0.0D, -0.4D, 0.0D)));

        assertEquals(90.0F, Math.abs(Angles.unwrap(second.getYaw() - first.getYaw(), 0.0F)), 1.0E-3F);
    }

    @Test
    void uprightWallPairsKeepTheBodyBesideTheHead() {
        LocalPortal source = portal(Face.N);
        LocalPortal destination = portal(Face.E);
        Traversive crossing = crossing(source, new Angles.Look(10.0F, 20.0F), new Vector(0.0D, 0.0D, -0.4D));

        LocalPortalTraversal.ExitPlacement placement = destination.traversal().exitPlacement(crossing);

        assertEquals(20.0F, placement.target().getPitch(), 1.0E-3F);
        assertEquals(placement.target().getYaw(), placement.bodyYaw(), 1.0E-3F);
    }

    private static LocalPortal portal(Face normal) {
        World world = AccessTestPortals.world("arrival-pose");
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setFrame(Frame.canonical(normal));
        return portal;
    }

    private static Traversive crossing(LocalPortal portal, Angles.Look rotation, Vector velocity) {
        Vector point = BukkitGeometry.bukkit(portal.getOrigin());
        Vec3d look = Angles.direction(rotation.yaw(), rotation.pitch());
        return new Traversive(null, TraversableType.PLAYER, portal.getFrame().view(true), point, point, velocity,
            new Vector(look.x(), look.y(), look.z()), true, UUID.randomUUID(), rotation);
    }
}
